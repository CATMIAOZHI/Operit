# Migration contract

## Source

The migration action accepts format version 1 raw snapshots created by the official Android package `com.ai.assistance.operit`. Both published format version 1 manifests are supported: the original four-directory layout and the current layout with `external_files`. A normal restore continues to require the current package name and does not accept official snapshots.

## Destination

Payload files are merged into the corresponding Operit Ry private directories using the existing raw snapshot rules. Files represented in the archive overwrite matching destination files. Other destination files remain in place.

## Safety

The source archive is extracted and its manifest is validated before any live data is changed. Operit Ry then checkpoints Room, closes Room and ObjectBox, and creates a current-package raw snapshot (including terminal data) before writing migrated data. A successful migration records a private completion marker and exits the process so the next cold start enters normal mode.

The migration runs in a dedicated cold-start phase, before `OperitApplication.initializeMainApplication` initializes WorkManager, foreground services, schedulers, repositories and DataStore writers. This guarantees no background writer races with the safety snapshot or file replacement.

Once live stores close, the operation cannot be cancelled. Other snapshot operations are rejected until the mandatory restart. If writing migrated data fails, Operit Ry clears the pending flag (to avoid a restart loop), retains the pre-migration safety snapshot, and exits the process so the next cold start enters normal mode; the user can recover manually from the safety snapshot.

Android Keystore keys do not migrate between package identities. Data encrypted with installation-bound keys can require reconfiguration after restart.

Historical raw snapshots are unsigned. Package validation provides format isolation, not proof of origin, so users must select a snapshot they created in official Operit.

## Pending state

The settings screen persists a pending migration request (`pending=true`, `pending_uri=<SAF URI>`) to a private `SharedPreferences` and exits the process. On the next cold start, `MainActivity.onCreate` checks the pending flag before calling `initializeMainApplication`. If pending, it runs the migration in a background coroutine (showing a minimal progress surface), then exits the process regardless of outcome. The manager clears the pending flag on both success and failure, so the next cold start enters normal mode.

[DONE]
