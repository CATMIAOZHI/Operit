# Verification

## Static review

- Confirmed normal restore still requires the current package name.
- Confirmed migration accepts only the official package name.
- Confirmed both published format version 1 manifest layouts are accepted.
- Confirmed unknown, empty, and path-escaping payloads are rejected before stores close.
- Confirmed the safety snapshot and migration share one process mutex.
- Confirmed cancellation cannot interrupt the destructive phase.
- Confirmed success and destructive-phase failure both terminate the stale process.
- Confirmed clone application variants do not display the migration action.
- Confirmed the safety snapshot uses `SnapshotOptions(includeTerminalData = true)` so legacy
  v1 snapshots that default to `includeTerminalData=true` can be rolled back from the safety
  snapshot.
- Confirmed the UI no longer wraps the pre-validation phase in `NonCancellable`; the settings
  screen only persists a pending request and exits, and the migration itself runs on the next
  cold start.
- Confirmed `MainActivity.onCreate` checks the pending flag before
  `OperitApplication.initializeMainApplication`, so WorkManager, foreground services,
  schedulers, repositories and DataStore writers are not started during the migration.
- Confirmed the pending flag is cleared on both success and failure to avoid a restart loop.
- Confirmed the pending flag is cleared in `prepareForReplacement` BEFORE the safety snapshot
  is taken, so restoring the safety snapshot for rollback does not re-enter the migration path
  and overwrite rolled-back data.
- Confirmed `isOfficialOperitMigrationPending` detects the `completed=true + pending=true`
  combination (process killed between the completion commit and any remaining state update),
  clears the stale pending flag, and returns false, so `MainActivity` enters normal mode
  instead of looping on the completion check.
- Confirmed the migration coroutine uses `GlobalScope + Dispatchers.IO + NonCancellable` so the
  migration completes even if the activity is destroyed (config change, user backing out),
  and the pending-flag clearing paths cannot be interrupted by cancellation.

## Automated coverage

`RawSnapshotPackagePolicyTest` covers official and Ry package isolation, empty payload rejection, changed manifest rejection, and the legacy manifest layout.

Gradle tests and builds were not run locally, in accordance with the repository execution policy. The current Android workflow is manual and does not run automatically for this pull request.

[DONE]
