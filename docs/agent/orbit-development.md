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

## Remaining full product roadmap

These are acceptance targets, not claims of implemented or tested behavior.

| Stage | Deliverable | Acceptance focus |
| --- | --- | --- |
| P3 | Controlled side effects, background delegation, browser handoff | Action receipts, unknown outcomes never blindly retried, revocation and user takeover |
| P4 | Goals, trackers, feed and artifacts | Task/result links, event subscriptions, quiet hours, frequency limits, continued conversation |
| P5 | Memory quality and skill growth | Evidence, correction/deletion, suppression of relearning forgotten facts, skill and Soul versions |
| P6 | Optional self-hosted always-on execution | Exclusive execution authority, offline reconciliation, credentials, budgets and results |

Task execution and user-facing delivery must be separate. Intermediate model/tool/subagent
output must not automatically flood the main chat. Android background execution remains
subject to OS limits; local-only operation must not promise cloud availability.

Reference implementations are architectural evidence, not verified deployments. Check
licenses before using source; do not import another agent framework to replace Operit's
chat system. Each implementation stage requires relevant local validation and a fresh
independent read-only audit before its local commit.
