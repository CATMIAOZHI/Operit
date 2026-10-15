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

## Remaining full product roadmap

These are acceptance targets, not claims of implemented or tested behavior.

| Stage | Deliverable | Acceptance focus |
| --- | --- | --- |
| P2 | Durable read-only tasks, events, runs and delivery; migrate daily check-in | Lease ownership, checkpoints, pause/cancel, restart recovery, deduplicated delivery, per-task reporting |
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
