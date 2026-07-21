# Migration contract

## Source

The migration action accepts format version 1 raw snapshots created by the official Android package `com.ai.assistance.operit`. Both published format version 1 manifests are supported: the original four-directory layout and the current layout with `external_files`. A normal restore continues to require the current package name and does not accept official snapshots.

## Destination

Payload files are merged into the corresponding Operit Ry private directories using the existing raw snapshot rules. Files represented in the archive overwrite matching destination files. Other destination files remain in place.

## Safety

The source archive is extracted and its manifest is validated before any live data is changed. Operit Ry then checkpoints Room, closes Room and ObjectBox, and creates a current-package raw snapshot (including terminal data) before writing migrated data. A successful migration records the COMPLETED state and exits the process so the next cold start enters normal mode.

The migration runs in a dedicated cold-start phase, before `OperitApplication.initializeMainApplication` initializes WorkManager, foreground services, schedulers, repositories and DataStore writers. The migration gate is enforced inside `initializeMainApplication` itself, so every process entry point (Activity, Service, Receiver, Worker) respects it, not only `MainActivity`. This prevents a sticky service restored by Android from starting background writers before the migration completes.

Once live stores close, the operation cannot be cancelled. Other snapshot operations are rejected until the mandatory restart. If writing migrated data fails, Operit Ry records the FAILED state (only after PREPARING or REPLACING was entered), retains the pre-migration safety snapshot, and exits the process; the next cold start observes FAILED and routes to the recovery surface instead of initializing normally. The recovery surface lists the safety snapshot recorded in the state file as the recommended restore target and offers Restore / Clear-state / Exit actions; a successful restore clears the migration state so the next cold start enters normal mode.

Pure validation failures (corrupt zip, wrong source package, URI permission failure) leave the state at PENDING because no data has been touched and no safety snapshot has been created; the user can pick a different archive without being locked into the recovery surface.

Android Keystore keys do not migrate between package identities. Data encrypted with installation-bound keys can require reconfiguration after restart.

Historical raw snapshots are unsigned. Package validation provides format isolation, not proof of origin, so users must select a snapshot they created in official Operit.

## Migration state machine

The migration state is persisted in `Context.noBackupFilesDir` (`<dataDir>/no_backup`), which raw snapshots do NOT capture (they only include `files/`, `external_files/`, `shared_prefs/`, `datastore/` and `databases/`). This guarantees the pre-migration safety snapshot never contains migration state, so restoring it for rollback enters normal mode (IDLE) instead of re-entering the migration path.

States transition strictly forward:

```
IDLE -> PENDING -> PREPARING -> REPLACING -> COMPLETED
                                         -> FAILED
NEEDS_RECOVERY (virtual, never persisted)
```

- **IDLE**: no migration requested. The settings screen shows the migration action (in Operit Ry only).
- **PENDING**: the settings screen has persisted the source URI and exited the process. The next cold start observes PENDING and runs the migration. Validation failures stay at PENDING so the user can pick a different archive.
- **PREPARING**: validation has succeeded and the destructive phase has started (Room checkpoint, database close, safety snapshot). The data directory is still untouched. If the process crashes here, the next cold start routes to the recovery surface (the user can retry or restore).
- **REPLACING**: the safety snapshot is on disk (its absolute path is recorded in the state file) and directory replacement is in progress. If the process crashes here, the data directory may be partially replaced and the next cold start MUST NOT initialize normally; it routes to the recovery surface, which recommends the recorded safety snapshot.
- **COMPLETED**: all directories replaced successfully. The next cold start enters normal mode and the migration action is hidden.
- **FAILED**: the migration threw an exception after PREPARING or REPLACING. The state file records the safety snapshot path when one was created. The next cold start routes to the recovery surface.
- **NEEDS_RECOVERY**: virtual state returned by `MigrationStateStore.read` when the state file is missing-but-expected, unreadable, or contains an unknown state name. It is never written to disk. It forces the caller to treat the situation as recoverable (show the recovery surface) instead of fail-open-ing into normal mode with potentially-partially-replaced data.

State writes are atomic (temp file + rename), so a crash mid-write leaves either the previous or the new state, never a truncated hybrid.

The state file is a three-line text file: `STATE`, `URI`, `SAFETY_BACKUP_PATH`. `URI` is only meaningful in PENDING; `SAFETY_BACKUP_PATH` is only meaningful in PREPARING / REPLACING / FAILED (the absolute path to the safety snapshot created right before directory replacement). The recovery surface uses the recorded path as the recommended restore target.

The settings screen only transitions IDLE -> PENDING after both `takePersistableUriPermission` and the state write succeed. If either fails, the error is surfaced to the user and the process is not exited.

`MigrationStateStore.read` is fail-closed: if the state file exists but cannot be read or parsed, or contains an unknown state name, it returns NEEDS_RECOVERY so the global gate routes to the recovery surface instead of normal-starting into potentially-partially-replaced data. A missing state file is normal on first install and returns IDLE.

The recovery surface offers three actions:
- **Restore**: lists `OperitBackupDirs.rawSnapshotDir()` files, pre-selects the safety snapshot recorded in the state file, and calls `RawSnapshotBackupManager.restoreFromBackupFile`. A successful restore clears the migration state (because raw snapshots exclude `noBackupFilesDir`) and exits the process so the next cold start enters normal mode.
- **Clear migration state**: deletes the state file and exits; the next cold start enters normal mode. Useful when the user has already restored via external means or wants to dismiss a stuck state.
- **Exit**: exits the process without changing state (used when the user recovers manually from external storage).

[DONE]
