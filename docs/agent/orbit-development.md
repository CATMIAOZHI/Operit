# Orbit development

## Product boundaries

Orbit is a persistent personal assistant built on Operit's existing chat, model, tool,
subagent and storage systems. A role card seeds `Soul.md` once; editing or deleting that
card does not change the companion. Multiple companions may exist; only one is active.
The permission mode remains fast automatic review.

Each Orbit defaults to its own user profile, memory graph, notes, conversation recall
and learned skills. Ordinary memory selectors do not switch the active Orbit's scope.
Prompt snapshots also carry this ownership so migrating an existing companion does not
reuse a frozen global user profile or skill catalog.

This is default memory routing, **not an operating-system sandbox**. The assistant is
instructed to use its own memory. When the user explicitly requests another memory source,
existing file or command tools may read it under normal permission review. Shell, Root,
uploads and device automation are not claimed to be confined to an Orbit directory.
Reading another source does not automatically authorize importing it into Orbit memory.

## P1 migration and lifecycle

- New and legacy companions receive a deterministic private memory space. An existing
  binding whose space is missing reports an error; it never falls back to global memory.
- Existing global memory is not copied into private storage. Existing chat content is
  retained, including content previously discussed before this migration.
- Branches and task chats inherit their parent's ownership. Recall filters apply before
  database pagination. Ownership records outlive companion removal so remaining archives
  do not become ordinary recall sources.
- Permission review reads the user profile belonging to the source chat.
- Memory settings open the companion's own profile, notes, graph, history and skills.
- Deletion is marked durably before stopping work. Foreground turns, subagents, automatic
  learning and graph extraction must finish cancellation before their storage is removed.
  Interrupted deletion stays unavailable for resumption and offers retry deletion.

## P2 durable read-only tasks

- `orbit_task` creates, lists, updates, runs, pauses and cancels webpage monitors in the
  current companion. The task UI exposes purpose, source, reporting policy, next check
  and latest result. `CHANGES` stays quiet when there is no useful change; `ALWAYS`
  requires a report for every check.
- Room v36 adds task, event, run and delivery records without replacing existing chat
  data. A stable event key and a renewable execution lease prevent competing workers
  from committing the same run. A reboot can reclaim a lease while retaining its
  source checkpoint; a former owner cannot submit after takeover, edit or cancellation.
- A small WorkManager wake-up is separate from the executing worker, so scheduling a
  different task does not stop a running model turn. Android may delay either worker.
  An HTTP GET reads at most 1 MB, fingerprints normalized visible text and sends at most
  20,000 characters to the model. Content requiring login or JavaScript is outside
  this first monitor's scope. There is no authenticated connector or general script runner.
- The background model only has a host-bound result submission tool. Its ordinary
  output is never added to the main chat. A result and its delivery record commit
  together; the chat message and message receipt also commit together. Retrying delivery
  cannot recreate a user-deleted message. System notifications use stable IDs, but are
  not part of the database transaction.
- Daily check-ins migrate to the same task pipeline. Focus, time, enabled state and
  last-check status belong to each companion. The former global settings are inherited
  by the previously active companion only; changing companions does not copy them.
- Pause/edit/cancel invalidates old execution and pending delivery. Failed checks are
  distinct from unchanged content; repeated identical failures stay quiet under CHANGES.
  Tasks and their latest outcomes remain inspectable through the tool after a report.

Local verification is recorded per implementation commit. Device behavior, OS scheduling
and live model completion still require device validation; they are not inferred from
Room tests or compilation.

## P3 first controlled execution slice

- Room v37 stores action receipts, host-owned task/chat bindings and user-input checkpoints.
  `ASSIST` tasks can read webpages, delegate through v2, and atomically save UTF-8 artifacts
  beneath their own task directory. These tools still pass the fixed FAST permission route.
  Background tasks do not receive arbitrary shell, package, browser script or external-write tools.
- A write starts with a durable receipt. An interrupted operation with an unknown outcome
  stops automatic recovery and requires explicit verification in task details. Pausing
  cannot retract a write that already succeeded; its late verified receipt is still recorded.
- Child agents inherit the task/run/generation and the restricted tool set. Actual execution
  rechecks the live lease; root cleanup stops and joins the tree before removing temporary chats.
  Permission review resolves the original companion chat for actual user instructions.
- `orbit_wait_for_user` persists the request and exits the model turn. The user can add a
  note and continue immediately from task details. An optional browser URL opens only after
  the user clicks. Existing browser UI is reused, with a fresh human-controlled document;
  AI browser calls are blocked until the user finishes or cancels. Resume requires a fresh
  snapshot of that same controlled document. A lost tab requires reopening.
- Password and sensitive autocomplete input values are omitted from page snapshots.
  This does not promise that arbitrary website content contains no secrets. Protected
  credential injection, general external side effects and their adapters remain outstanding.

## P4 goals, results and delivery

- Room v38 links goals to tasks and text artifact versions. Goals show actual completed
  task counts; pausing fences execution and resuming leaves individual tasks for the user
  to continue. Event subscriptions run after a predecessor succeeds, including a one-shot
  that completed just before subscription. Human handoff preserves this source result.
- ASSIST defaults to one-shot execution. Failure is resumable; success is terminal.
  Interval and completion-event schedules are explicit options rather than inferred from kind.
- Saved artifacts use immutable version directories and verified hashes. Revision tasks
  read a selected version and preserve its artifact identity when keeping its filename.
  Unknown writes require host verification of the saved bytes before their version is indexed.
- The workspace exposes tasks, goals, a result Feed, artifact history/reading/revision and
  notification preferences. Feed results remain individually inspectable when delivery is grouped.
- Quiet hours (22:00–08:00), minimum delivery spacing (0/15/60 minutes) and per-task daily
  digests delay reports without delaying execution. Due reports are batched into one chat
  message with per-result receipts; notification completion updates the whole batch atomically.
  The scheduler waits for the next delivery time instead of polling through the quiet period.

## P5 identity versions

- Each Orbit keeps an atomic Soul version journal, seeded once from its role card.
  Existing Soul.md files import without consulting the source card. Soul.md remains a
  readable mirror; the journal is authoritative and repairs an interrupted mirror write.
- `orbit_soul` reads the current version or proposes an edit. Only the companion's main
  chat can propose; user approval in **Soul and history** is required to apply it.
  Concurrent stale proposals cannot overwrite a newer persona.
- The current version, up to 19 prior reviewed versions and up to five pending proposals
  are retained. Restoring creates a new version. An accepted identity revision clears that
  companion's frozen prompt on the next request; existing chat messages are not rewritten.
  Soul text cannot change the host's FAST permission policy.

## Remaining full product roadmap

These are acceptance targets, not claims of implemented or tested behavior.

| Stage | Deliverable | Acceptance focus |
| --- | --- | --- |
| P3 remaining | External-operation adapters and protected credentials | Extend the verified narrow receipt/handoff path without exposing untracked side effects |
| P5 | Memory quality and skill growth | Evidence, correction/deletion, suppression of relearning forgotten facts, skill and Soul versions |
| P6 | Optional self-hosted always-on execution | Exclusive execution authority, offline reconciliation, credentials, budgets and results |

Task execution and user-facing delivery must be separate. Intermediate model/tool/subagent
output must not automatically flood the main chat. Android background execution remains
subject to OS limits; local-only operation must not promise cloud availability.

Reference implementations are architectural evidence, not verified deployments. Check
licenses before using source; do not import another agent framework to replace Operit's
chat system. Each implementation stage requires relevant local validation and a fresh
independent read-only audit before its local commit.
### P5 skill discovery and file recovery

Orbit prompts show at most twelve skill names and short descriptions. `orbit_skills` searches
the live, AI-visible directory with the caller's existing tool access; results are capped at
twenty entries per page. Skill bodies continue to load through `use_package`. Learning's
management directory also supports query/offset/limit, including drafts, without injecting
all skill bodies. Skill content never grants additional tool permissions.

The private skill detail page opens its review history. Approved file changes can prepare
a new restoration proposal using the current file version for concurrency checks; the user
reviews current/target text before approval. Empty files and absent files are distinct.
This restores one file of an existing skill, not an entire package. Deleted packages and
versions already pruned from the bounded review history cannot be restored through this UI.

### P5 memory corrections and learning boundaries

Orbit-owned memory corrections and deletions advance a durable learning generation before writing.
Background proposals and graph commits carry the generation captured with their sources; a late
result cannot overwrite a correction. Pending proposals from older generations become superseded
and no longer consume the review quota. A retry of the last interrupted correction preserves its
original watermark.

Notes/skills batches, automatic graph extraction, manual history rebuilding, and chat-triggered
memory updates all filter prior evidence. The watermark uses both message IDs and correction time:
fork/import paths preserve timestamps even when they allocate new message IDs. Learning-only
history search, context, session and direct reads enforce the same restriction. Ordinary recall
and explicit file/command reads remain available.

This is deliberately conservative: after correcting existing memory, new learning starts with
subsequent messages. It does not erase historical conversations or claim to discover every unknown
semantic derivative. Corrections also expire the frozen Orbit prompt prefix on the next request.
Global memory behavior is unchanged.

Learning proposals retain host-collected batch message IDs, revisions and excerpt byte ranges.
The review page identifies AI interpretations and can display current source excerpts; these are
not asserted to be immutable historical copies or per-sentence proof. Graph-level provenance and
semantic dependency cleanup remain separate work.
