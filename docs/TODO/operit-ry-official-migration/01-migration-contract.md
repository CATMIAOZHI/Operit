# Migration contract

## Source

The migration action accepts format version 1 raw snapshots created by the official Android package `com.ai.assistance.operit`. Both published format version 1 manifests are supported: the original four-directory layout and the current layout with `external_files`. A normal restore continues to require the current package name and does not accept official snapshots.

## Destination

Payload files are merged into the corresponding Operit Ry private directories using the existing raw snapshot rules. Files represented in the archive overwrite matching destination files. Other destination files remain in place.

## Safety

The source archive is extracted and its manifest is validated before any live data is changed. Operit Ry then checkpoints Room, closes Room and ObjectBox, and creates a current-package raw snapshot (including terminal data) before writing migrated data. A successful migration records the COMPLETED state and exits the process so the next cold start enters normal mode.

The migration runs in a dedicated cold-start phase, before `OperitApplication.initializeMainApplication` initializes WorkManager, foreground services, schedulers, repositories and DataStore writers. The migration gate is enforced inside `initializeMainApplication` itself. Services use that initialization gate; exported data-writing receivers and widget entry points use a lightweight state gate before doing work, avoiding full application initialization on the broadcast main thread. Main-process DocumentsProviders start before `Application.onCreate`, so they use the same state policy as a pure access gate and reject every query or mutation while migration blocks data access. Writable provider descriptors stage changes in cache and re-check the gate when committing. Descriptor commits, synchronous provider mutations and IDLE -> PENDING share a process lock, so the transition cannot race a provider write. The dedicated `:repair` recovery provider remains available. This prevents Android or an external caller from starting a background writer before the migration completes.

Once live stores close, the operation cannot be cancelled. Other snapshot operations are rejected until the mandatory restart. PREPARING failures before stores close atomically return to IDLE and can display the error. Failures after stores close keep PREPARING persisted and immediately exit, so no entry point can reopen data in the invalid process; the next cold process safely resets PREPARING to IDLE. Once REPLACING is persisted, failures record FAILED, retain the pre-migration safety snapshot, and exit the process; the next cold start observes FAILED and routes to the recovery surface instead of initializing normally. The recovery surface lists the recorded safety snapshot as the recommended restore target and offers Restore / Exit actions; a successful restore atomically records IDLE. REPLACING / FAILED / NEEDS_RECOVERY cannot be cleared without restoring data because doing so could initialize a partially replaced data directory.

Pure validation failures (corrupt zip, wrong source package, URI permission failure), preparation-state write failures and a PENDING state without a URI atomically return to IDLE because no data has been touched. The progress surface displays the error and offers to exit; the next launch reaches Settings so the user can select another archive instead of automatically retrying the same invalid request. If persisting IDLE fails, the process does not exit: the error surface lets the user retry the PENDING -> IDLE transition.

Android Keystore keys do not migrate between package identities. Data encrypted with installation-bound keys can require reconfiguration after restart.

Historical raw snapshots are unsigned. Package validation provides format isolation, not proof of origin, so users must select a snapshot they created in official Operit.

## Migration state machine

The migration state is persisted in `Context.noBackupFilesDir` (`<dataDir>/no_backup`), which raw snapshots do NOT capture (they only include `files/`, `external_files/`, `shared_prefs/`, `datastore/` and `databases/`). This guarantees the pre-migration safety snapshot never contains migration state, so restoring it for rollback enters normal mode (IDLE) instead of re-entering the migration path.

State transitions are:

```
IDLE -> PENDING -> PREPARING -> REPLACING -> COMPLETED
                                         -> FAILED
         |                                  |
         +-> IDLE (safe pre-replacement failure)
                                            +-> IDLE (successful recovery)
NEEDS_RECOVERY (virtual, never persisted)
```

- **IDLE**: no migration requested. The settings screen shows the migration action (in Operit Ry only).
- **PENDING**: the settings screen has persisted the source URI and exited the process. The next cold start observes PENDING and runs the migration. A missing URI or validation failure atomically transitions to IDLE before exit so the next launch can reach Settings.
- **PREPARING**: validation has succeeded and preparation has started (Room checkpoint, database close, safety snapshot). No payload directory has been replaced. The running process owns this state, so Activity recreation keeps showing progress instead of clearing it. A failure before stores close can record IDLE and display the error. A failure after stores close retains PREPARING until immediate process exit. A cold-process observation safely records IDLE before normal initialization; if that write fails, the startup error surface retries it.
- **REPLACING**: the safety snapshot is on disk (its absolute path is recorded in the state file) and directory replacement is in progress. If the process crashes here, the data directory may be partially replaced and the next cold start MUST NOT initialize normally; it routes to the recovery surface, which recommends the recorded safety snapshot.
- **COMPLETED**: all directories replaced successfully. The next cold start enters normal mode and the migration action is hidden.
- **FAILED**: the migration threw after REPLACING was persisted, so payload replacement may have started. The state file records the safety snapshot path and the next cold start routes to the recovery surface.
- **NEEDS_RECOVERY**: virtual state returned by `MigrationStateStore.read` when an existing state file is unreadable or contains an unknown state name. It is never written to disk. It forces the caller to treat the situation as recoverable (show the recovery surface) instead of fail-open-ing into normal mode with potentially-partially-replaced data.

State writes use Android `AtomicFile`, so a crash mid-write leaves either the previous or the new state, never a truncated hybrid.

The state file is a three-line text file: `STATE`, `URI`, `SAFETY_BACKUP_PATH`. `URI` is meaningful while a request is active; `SAFETY_BACKUP_PATH` is meaningful in REPLACING / FAILED (the absolute path to the snapshot created immediately before replacement). The recovery surface uses the recorded path as the recommended restore target.

The settings screen only transitions IDLE -> PENDING after both `takePersistableUriPermission` and the state write succeed. If either fails, the error is surfaced to the user and the process is not exited.

`MigrationStateStore.read` is fail-closed: if the state file exists but cannot be read or parsed, or contains an unknown state name, it returns NEEDS_RECOVERY so the global gate routes to the recovery surface instead of normal-starting into potentially-partially-replaced data. A missing state file is normal on first install and returns IDLE.

The recovery surface offers two actions:
- **Restore**: lists `OperitBackupDirs.rawSnapshotDir()` files, pre-selects the safety snapshot recorded in the state file, and calls `RawSnapshotBackupManager.restoreFromBackupFile`. A successful restore atomically records IDLE only for REPLACING / FAILED / NEEDS_RECOVERY and exits the process. Normal restores preserve IDLE, PENDING, PREPARING and especially the permanent COMPLETED marker, so the one-time migration action cannot reappear after a later Ry snapshot restore.
- **Exit**: exits the process without changing state (used when the user recovers manually from external storage).

[DONE]
