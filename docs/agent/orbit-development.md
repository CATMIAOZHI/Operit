# Orbit development

## Product contract

Orbit is a long-term personal assistant on Operit's existing chat, model, tool, subagent,
browser and storage systems. Foreground and background turns use the ordinary tool discovery
and package-loading routes, with the same fixed FAST automatic review. There is no Orbit-only
tool whitelist, dedicated outbound connector, remote executor or alternative agent runtime.
The existing CLI/FULL provider modes and user tool decisions still apply.

Multiple companions may exist, but only one runs at a time. A role card seeds Soul.md once;
later editing or deleting that card does not change Orbit. Each companion has a persistent
main conversation. Naming remains an Orbit tool action.

Each companion stores model choices in `orbit_models` DataStore: main, assistant, optional
image fallback and advanced function overrides. Shared model configurations supply endpoints,
credentials and model lists; references bind by config ID and exact model name (an empty name
explicitly means a plugin's default model). Initialization
copies the current CHAT/MEMORY choices once, then never follows the main app’s functional choices.
An unavailable selection blocks that call with a settings hint. Image capability comes from the
existing per-model capability setting; no automatic paid probe or model-name guessing is used.
`MultiServiceManager` routes Orbit functions while `OrbitUsageTag` retains accounting identity.
Function-map refreshes affect only main-app caches; companion choice changes and deletion retire
that companion's caches, while shared account edits still refresh all owners. Active leases finish
before their retired provider is released. Orbit send preparation suspends without blocking the UI
and reserves the chat until dispatch, preserving the submitted draft and its send order.
Preparation blocks duplicate queue/steer submissions and remains cancellable without clearing the
draft. Summary generation and configuration use the target chat's service; last-send model and memory
overrides are stored per chat. Deleted/deleting owners, including stale usage tags, are rejected
before resolving a provider, and model-setting initialization/removal share the same lock.

Room migration 44→45 adds `orbit_tasks.modelRole` (`DEFAULT`, `MAIN`, `ASSISTANT`).
Default chat wakes use main; background tasks use assistant; alignment defaults to assistant.
Explicit task choices override those defaults for that execution only. `orbit_task` create/update
accept `model=default/main/assistant`, and its result includes `effective_model`. Protected routines
allow model changes. Task-details choices fill a chat draft rather than directly editing the task.

Each Orbit defaults to its own profile, notes, memory graph, conversation recall and learned
skills. Branches and background/child chats inherit ownership. This is default memory routing,
not an OS sandbox: explicit user requests may use existing command/file tools to read other
sources under normal review. Reading does not automatically authorize importing those memories.

## Background work and user experience

Users delegate in chat. Orbit can arrange one-shot, periodic and predecessor-completion tasks
through orbit_task. ASSIST is the default; it can discover existing tools, load packages and
delegate through v2. Daily proactive turns have the same tool capabilities. URL_CHANGE can
skip a model call when a bounded public-page text fingerprint is unchanged; it is an optional
optimization, not the assistant's capability boundary.
WATCH does the same for phone sources (notifications still in the tray, SMS inbox, calendar
instances) and for a read-only script in `files/hooks/`, pinned by hash at create/update and run
as base64 through the current shell executor. A source that cannot be read (permission off,
script changed or failed) fails the run with a user-facing reason; the same kind of failure
alerts once. A notification listener that is still binding skips the check quietly once
(COMPLETED, `@skipped`, fingerprint kept, no EVENT relay, hidden from activity); a one-time
task or a second skip in a row fails with a "turn notification access off and on" reason, and
every later check keeps failing that way until a real read, alerting once (dedupe ignores
`@skipped` runs via `lastRealRun`).
Every run stores a `decision` (`@unchanged` for skipped unchanged checks).

Task controls are appended to ordinary tools, including in textual-tool mode. Only root runs
can submit the final result or wait for the user. Children retain host-owned task/Orbit identity
and cancellation checks, but use normal tools rather than a separate small catalog.

Room stores tasks, events, runs and deliveries. WorkManager wakes the app; a renewable lease
fences cancelled or superseded runs. Android may delay background work, and a powered-off phone
does not execute Orbit tasks. No server component is required or included.

Model output and tool results stay in hidden execution chats. Only the submitted useful result
is delivered to the main conversation. Task details and orbit_task.detail expose a bounded
execution record. Waiting tasks can receive the user's actual answer through orbit_task.resume
with user_note, as well as through task details. Browser login/manual handoff still uses the
existing browser and requires a fresh observation before continuing.

Once a root binding has been stored, the model may have used ordinary tools with external
effects. A lost lease/reboot therefore pauses that run as INTERRUPTED instead of replaying it.
Exceptions and timeouts also pause. Transcripts and bindings are retained for inspection;
explicit continuation starts a new task version and includes the preceding execution record.
This does not guarantee exactly-once external effects. Unknown results must be checked before
repeating an operation. Source retrieval before model start can still resume automatically.

FAST errors and denials remain actual tool results, as in foreground chat. The assistant is
instructed to report the reason and wait when blocked, rather than bypass review. An unfinished
or failed model turn is surfaced as interrupted even if it did not submit a final report.

Chat messages and delivery receipts commit atomically, so delivery retries do not duplicate
messages. Notification IDs are stable, but system notifications are not a database transaction.
Quiet hours, delivery spacing and per-task digests delay messages rather than task execution.

## Goals, artifacts and identity

Goals link existing tasks and show their actual states. Feed exposes formal results; text
artifacts have immutable versions, hashes, preview and revision tasks. Optional artifact-save
helpers retain narrow receipts; they are not a restriction on ordinary file tools.

Soul updates use orbit_soul.read/history/update. An update with the current version applies
directly, without a second user-approval workflow. The main conversation and its live root
background turns can update it; children cannot rewrite their parent's identity. Atomic
version checks reject stale concurrent edits. The current and up to nineteen prior applied
versions remain available for user-initiated restoration. Soul text cannot change FAST policy.

## Learning and skills

Orbit skill prompts show a small directory preview. orbit_skills searches the visible catalog;
use_package loads bodies on demand. File-version restoration uses the existing review history
and does not claim to restore deleted whole packages.

Explicit memory corrections advance a durable learning generation. Late background learning
cannot overwrite a newer correction; stale pending proposals are superseded. The current
conservative policy excludes pre-correction history from subsequent automatic learning, using
both source IDs and timestamps. Ordinary recall and explicit reads remain available. This is
not precise per-fact forgetting; refining that behavior is a separate product decision.

Learning proposals retain host-collected batch source references and current excerpt access,
not immutable per-sentence evidence. Graph provenance and semantic dependency cleanup remain
future work, not implemented claims.

## Storage and verification

Removing an Orbit stops its root and child work before deleting private data. Ownership metadata
prevents surviving archives from becoming ordinary global recall sources.

Browser download records formerly containing plaintext cookies migrate to Android Keystore
AES-GCM under noBackupFilesDir. Ciphertext commits before old plaintext is removed. Storage
failure preserves records and pauses downloads. This storage protection is independent of
Orbit's tool permissions; it is not an outbound connector.

Local validation and independent read-only audit are recorded per change. Compilation and
database tests do not prove real-device scheduling or live model behavior. Optional remote
execution and dedicated connectors are explicitly deferred by the product decision.
