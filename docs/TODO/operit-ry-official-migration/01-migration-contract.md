# Migration contract

## Source

The migration action accepts format version 1 raw snapshots created by the official Android package `com.ai.assistance.operit`. Both published format version 1 manifests are supported: the original four-directory layout and the current layout with `external_files`. A normal restore continues to require the current package name and does not accept official snapshots.

## Destination

Payload files are merged into the corresponding Operit Ry private directories using the existing raw snapshot rules. Files represented in the archive overwrite matching destination files. Other destination files remain in place.

## Safety

The source archive is extracted and its manifest is validated before any live data is changed. Operit Ry then checkpoints Room, closes Room and ObjectBox, and creates a current-package raw snapshot (including terminal data) before writing migrated data. A successful migration records the COMPLETED state and exits the process so the next cold start enters normal mode.

The migration runs in a dedicated cold-start phase, before `OperitApplication.initializeMainApplication` initializes WorkManager, foreground services, schedulers, repositories and DataStore writers. The migration gate is enforced inside `initializeMainApplication` itself, so every process entry point (Activity, Service, Receiver, Worker) respects it, not only `MainActivity`. This prevents a sticky service restored by Android from starting background writers before the migration completes.

Once live stores close, the operation cannot be cancelled. Other snapshot operations are rejected until the mandatory restart. If writing migrated data fails, Operit Ry records the FAILED state, retains the pre-migration safety snapshot, and exits the process; the next cold start observes FAILED and routes to the recovery surface instead of initializing normally. The user can recover manually from the safety snapshot.

Android Keystore keys do not migrate between package identities. Data encrypted with installation-bound keys can require reconfiguration after restart.

Historical raw snapshots are unsigned. Package validation provides format isolation, not proof of origin, so users must select a snapshot they created in official Operit.

## Migration state machine

The migration state is persisted in `Context.noBackupFilesDir` (`<dataDir>/no_backup`), which raw snapshots do NOT capture (they only include `files/`, `external_files/`, `shared_prefs/`, `datastore/` and `databases/`). This guarantees the pre-migration safety snapshot never contains migration state, so restoring it for rollback enters normal mode (IDLE) instead of re-entering the migration path.

States transition strictly forward:

```
IDLE -> PENDING -> PREPARING -> REPLACING -> COMPLETED
                                         -> FAILED
```

- **IDLE**: no migration requested. The settings screen shows the migration action (in Operit Ry only).
- **PENDING**: the settings screen has persisted the source URI and exited the process. The next cold start observes PENDING and runs the migration.
- **PREPARING**: the migration has started and is reading/extracting/validating the zip. If the process crashes here, the data directory is untouched and the next cold start routes to the recovery surface (the user can retry by clearing state to IDLE).
- **REPLACING**: databases are closed, the safety snapshot has been taken, and directory replacement is in progress. If the process crashes here, the data directory may be partially replaced and the next cold start MUST NOT initialize normally; it routes to the recovery surface so the user can restore from the safety snapshot.
- **COMPLETED**: all directories replaced successfully. The next cold start enters normal mode and the migration action is hidden.
- **FAILED**: the migration threw an exception (after PREPARING or REPLACING). The next cold start routes to the recovery surface.

State writes are atomic (temp file + rename), so a crash mid-write leaves either the previous or the new state, never a truncated hybrid.

The settings screen only transitions IDLE -> PENDING after both `takePersistableUriPermission` and the state write succeed. If either fails, the error is surfaced to the user and the process is not exited.

[DONE]
