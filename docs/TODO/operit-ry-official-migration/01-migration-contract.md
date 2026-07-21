# Migration contract

## Source

The migration action accepts format version 1 raw snapshots created by the official Android package `com.ai.assistance.operit`. Both published format version 1 manifests are supported: the original four-directory layout and the current layout with `external_files`. A normal restore continues to require the current package name and does not accept official snapshots.

## Destination

Payload files are merged into the corresponding Operit Ry private directories using the existing raw snapshot rules. Files represented in the archive overwrite matching destination files. Other destination files remain in place.

## Safety

The source archive is extracted and its manifest is validated before any live data is changed. Operit Ry then checkpoints Room, closes Room and ObjectBox, and creates a current-package raw snapshot (including terminal data) before writing migrated data. A successful migration records a private completion marker and exits the process so the next cold start enters normal mode.

The migration runs in a dedicated cold-start phase, before `OperitApplication.initializeMainApplication` initializes WorkManager, foreground services, schedulers, repositories and DataStore writers. This guarantees no background writer races with the safety snapshot or file replacement.

Once live stores close, the operation cannot be cancelled. Other snapshot operations are rejected until the mandatory restart. If writing migrated data fails, Operit Ry clears the pending flag (to avoid a restart loop), retains the pre-migration safety snapshot, and exits the process so the next cold start enters normal mode; the user can recover manually from the safety snapshot. The pending flag is also cleared before the safety snapshot is taken, so restoring the safety snapshot for rollback does not re-trigger the migration.

Android Keystore keys do not migrate between package identities. Data encrypted with installation-bound keys can require reconfiguration after restart.

Historical raw snapshots are unsigned. Package validation provides format isolation, not proof of origin, so users must select a snapshot they created in official Operit.

## Pending state

The settings screen persists a pending migration request (`pending=true`, `pending_uri=<SAF URI>`) to a private `SharedPreferences` and exits the process. On the next cold start, `MainActivity.onCreate` checks the pending flag before calling `initializeMainApplication`. If pending, it runs the migration in a background coroutine (showing a minimal progress surface), then exits the process regardless of outcome.

The manager clears the pending flag in two places:

1. **Before the safety snapshot** (`prepareForReplacement`): so the safety snapshot does not capture `pending=true` / `pending_uri`. A later restore of the safety snapshot for rollback will not re-enter the migration path and overwrite the rolled-back data.
2. **On failure before `prepareForReplacement`**: so a failure during zip reading, manifest validation or package mismatch (before the safety snapshot is taken) still clears the pending flag, avoiding a restart loop.

On success, the completion flag is committed inside the replacement phase (single `SharedPreferences.commit`). The pending flag has already been cleared in step 1, so there is no two-commit window between "completed" and "pending clear".

If the process is killed between the completion commit and any remaining state update, a stale `pending=true` may coexist with `completed=true`. `isOfficialOperitMigrationPending` detects this combination, clears the stale pending flag, and reports "not pending", so `MainActivity` enters normal mode instead of looping on the completion check.

[DONE]
