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
- Confirmed a missing pending URI or pure validation failure atomically returns to IDLE before
  process exit, so the next launch reaches Settings instead of retrying the same request forever.
- Confirmed a failed PENDING -> IDLE write does not exit and restart the process. The cold-start
  surface displays the persistence error and offers a retry; it exits only after IDLE is saved.
- Confirmed validation and preparation failures that successfully return to IDLE are surfaced
  before exit; the next user-initiated launch returns to Settings.
- Confirmed the migration state machine lives in `noBackupFilesDir`, which raw snapshots do NOT
  capture, so the safety snapshot never contains migration state and a rollback enters normal
  mode (IDLE) instead of re-entering the migration path.
- Confirmed state transitions use Android `AtomicFile`, so a crash mid-write leaves either the
  previous or the new state rather than a truncated or partially copied file.
- Confirmed PREPARING is always before payload replacement. Activity recreation recognizes the
  process-owned run and keeps its progress surface; only a failure or cold-process observation
  before stores close atomically returns to IDLE. A failure after stores close retains PREPARING
  until immediate process exit. REPLACING remains fail-closed and routes to recovery.
- Confirmed the migration gate is enforced inside `OperitApplication.initializeMainApplication`,
  so every process entry point (MainActivity, AIForegroundService, FloatingChatService) respects
  it, not only MainActivity. Sticky services restored by Android before the activity is created
  will stop and exit instead of starting background writers.
- Confirmed exported data-writing receivers (external chat, script execution, ToolPkg debug and
  Tasker/boot) plus both widget entry points check the lightweight state gate before work without
  running full application initialization synchronously on the broadcast main thread.
- Confirmed `WorkspaceDocumentsProvider` and `MemoryDocumentsProvider`, which can start before
  `Application.onCreate`, perform no initialization writes while blocked and check the pure state
  gate before every query or mutation. The dedicated `:repair` provider remains available.
- Confirmed writable provider descriptors stage data outside snapshot payload directories and
  re-check the state at commit. Descriptor commits, synchronous provider mutations and IDLE ->
  PENDING share a process lock, so a pending transition cannot race a Workspace or Memory write.
- Confirmed `MainActivity.attachBaseContext` uses the system locale while migration blocks access,
  so it does not instantiate the main DataStore before the startup gate.
- Confirmed MainActivity performs no AppLogger or intent/repository work before consuming the
  migration startup action.
- Confirmed `takePersistableUriPermission` failures are surfaced to the user and the process is
  not exited, so the cold start would not observe a pending state it cannot read.
- Confirmed `setPendingOfficialOperitMigration` returns false on write failure and the caller
  surfaces an error instead of exiting the process.
- Confirmed the migration coroutine uses `GlobalScope + Dispatchers.IO + NonCancellable` so the
  migration completes even if the activity is destroyed (config change, user backing out),
  and claims process ownership before dispatch. Recreated activities display the same progress
  surface instead of launching a duplicate run or clearing active PREPARING state. Each visible
  Activity observes the process-owned outcome, so a recreated instance receives success/failure.
- Confirmed early-return migration and recovery screens do not initialize `anrMonitor`, and
  `onDestroy` only stops it when initialized, so finishing those activities cannot throw
  `UninitializedPropertyAccessException`.
- Confirmed `MigrationStateStore.read` is fail-closed: a missing state file returns IDLE (normal
  first install), but an unreadable or unparseable state file returns NEEDS_RECOVERY, which the
  global gate treats the same as REPLACING/FAILED and routes to the recovery surface
  instead of normal-starting into potentially-partially-replaced data.
- Confirmed the state file records the safety snapshot absolute path during PREPARING->REPLACING
  and FAILED transitions, so the recovery surface can recommend the correct snapshot.
- Confirmed the PREPARING transition happens inside `prepareForReplacement` (after the zip is
  read, extracted and validated), so a corrupt zip, wrong source package, or URI permission
  failure returns the state to IDLE and the next launch lets the user pick a different archive.
- Confirmed FAILED is only written after REPLACING is persisted. Validation/checkpoint failures
  before stores close return to IDLE; failures after stores close remain blocked as PREPARING
  until process exit and do not require a recovery snapshot.
- Confirmed the recovery surface lists `OperitBackupDirs.rawSnapshotDir()` files, pre-selects
  the safety snapshot recorded in the state file, and offers only Restore / Exit. It cannot
  clear REPLACING / FAILED without restoring, which prevents bypassing the data
  consistency barrier.
- Confirmed a successful restore changes only REPLACING / FAILED / NEEDS_RECOVERY
  to IDLE. A normal restore preserves COMPLETED, so the one-time migration action does not
  reappear after restoring an ordinary Operit Ry snapshot.

## Automated coverage

`RawSnapshotPackagePolicyTest` covers official and Ry package isolation, empty payload rejection, changed manifest rejection, and the legacy manifest layout. `MigrationStatePolicyTest` covers data-access states, safe cancellation, failure transitions, cold-start actions and Activity recreation while a process-owned migration is active.

Local verification completed with a temporary Android SDK and the repository dependency-preparation flow:

- `./gradlew :app:compileDebugKotlin` — passed.
- `./gradlew :app:testDebugUnitTest --tests com.ai.assistance.operit.data.backup.MigrationStatePolicyTest --tests com.ai.assistance.operit.data.backup.RawSnapshotPackagePolicyTest` — passed.

[DONE]
