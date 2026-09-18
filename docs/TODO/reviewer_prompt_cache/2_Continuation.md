# Continuation

## Problem

Every review builds its prompt from scratch and starts a new reviewer run, so there is no earlier
material to reuse and the reusable prefix stops at the retained instructions. The reference
implementation instead keeps one reviewer conversation per thread and appends to it, which is what
makes its prefix stable.

## Reference mechanics

Read in the workspace checkout of `openai/codex` at `codex/`: `codex-rs/guardian-context`,
`codex-rs/ext/guardian-v2`, `codex-rs/core/src/guardian`, and `codex-rs/core/src/agents_md.rs`.

- `core/src/guardian/prompt.rs` chooses between a full and a delta prompt from
  `GuardianTranscriptCursor { parent_history_version, transcript_entry_count }`. The delta is used
  only when the parent history version is unchanged and the entry count did not shrink; otherwise
  the full prompt is rebuilt.
- The delta intro reads "added since your last approval assessment. Continue the same review
  conversation", so the reviewer keeps its own earlier assessments in view.
- The cursor counts the whole collected history, not the rendered window, so retention can bound
  what is rendered without moving what was already sent.
- `SectionScope` (Shared / SyncOnly / AsyncOnly) gives the blocking reviewer and the asynchronous
  scorer different section sets out of one registry, so the two consumers see different evidence and
  different limits.
- Before it sends a delta, the reference implementation confirms the review session still holds the
  whole transcript it was given, and only then continues it. A delta is only as good as the earlier
  prompt still being in the conversation.

## Serialization

Our reviews used to run at the same time as each other. `ToolExecutionManager` dispatched the
permission checks of one tool batch through `parallelMapPreservingOrder`, and its comment was explicit
that individual reviewers finish out of order. A batch therefore reached the reviewer in whatever
order its coroutines won the race for it, which decides more than the reading order: the review that
arrives first builds the full prompt, and the ones behind it continue that conversation. The race was
what decided which action paid for the prompt, and a review that lost it still ran beside the first
one with a prompt of its own after `CONTINUATION_WAIT_MS`.

The batch is now handed over one action at a time, in the model's own call order, which is the order
the actions are executed in and read in. The reviewer's conversation therefore reads in the order the
actions happened, and every review after the first continues the run the one before it left behind.
A level that never reaches the reviewer keeps the parallel dispatch, where an order could only decide
which reply comes back first.

The reviewer still refuses to share a run with a simultaneous review: `SubagentTaskResult.
AlreadyRunning` clears `latestReviewerTaskId` and retries as a fresh task, with the comment "A
simultaneous review never shares another action's transcript".

The per-chat lock is still what makes a review wait, but only for a review that started ahead of its
batch. A batch that arrives while an earlier review is in flight waits up to `CONTINUATION_WAIT_MS`
and then runs concurrently with the full prompt, which is what a review with no continuation does
anyway — and what it must do, because a review that has not finished holds no run to continue. The
wait applies whether or not there is anything to continue: the lock is taken before the store is
read, because which reviews may continue is only known from the store. The reference implementation
serializes for the same reason, with a semaphore it does not wait on — it forks the last committed
reviewer history and runs the concurrent review there instead.

What this costs is stated where it is paid: the reviews of one batch now run one after another rather
than at the same time, so a batch of five reviewed actions waits for the four reviews in front of its
last one. The owner chose that over the race, for the order it gives and for the prompt no longer
being rebuilt beside the one that was already warm.

## Change

- Keep one reviewer run per parent chat and reuse its task id for the next review, the way the
  exact-override retry already does.
- Record a cursor with that run: how much of the whole collected history had settled and a hash of
  exactly those entries. Measured over the collected history rather than the rendered window, so a
  bounded window can still be continued. The answer still being written is not settled, so the cursor
  stops before it and a delta always resends it.
- On the next review, send only the entries after the cursor while the history still starts with
  exactly the entries the cursor covers and the policy, the retained instructions, the workspace, and
  the model are unchanged. Otherwise rebuild the full prompt.
- Fall back to the full prompt and a fresh task whenever the continuation cannot be proven, which
  keeps the current behaviour whenever two reviews overlap and for a reviewer whose run disappeared.
- Confirm the remembered run still holds the whole prompt before continuing it: the run has to exist,
  its child chat has to exist, that chat has to still hold a message carrying
  `REVIEW_PROMPT_MARKER`, and it must not have been summarized. The marker is written at the top of
  the full prompt and never into a delta, so it is the reviewer-side equivalent of the reference
  implementation's check that its transcript is still in the session.
- Leave the retained instructions block where it is. It changes only when the owner speaks or the
  workspace changes, which is the behaviour the prefix wants.
- Keep the action last, and keep the lifecycle block after the transcript; both are volatile, so
  they must never sit inside the reused prefix.
- Hand a batch's actions to the checks one at a time, in the model's own call order, so the reviewer
  receives them in the order they will be executed and every review after the first continues the
  run the one before it left behind.
- Keep the reviewer's child chat out of the automatic history summary: a continuation depends on the
  earlier prompt still being in that conversation, and a summary would replace the policy and the
  retained instructions with a paraphrase of them.

## Shape

- `PermissionReviewContinuationStore` keys the last successful run per parent chat, bounded to
  `MAX_TRACKED_CHATS`, and hands out the per-chat lock that keeps a review from running beside
  another one that is still building the same conversation.
- `ToolExecutionManager` hands a batch's permission checks to `mapBatchInReviewOrder`, which walks
  them one at a time when `batchNeedsReviewOrder` sees an action the reviewer serves and through
  `parallelMapPreservingOrder` otherwise. Order is the reviewer's, and the checks of one batch are
  what hands it the actions.
- `permissionReviewStableHistorySize` and `permissionReviewCursor` record how far a review read: how
  much of the collected history had settled and a hash of exactly those entries, not the rendered
  window. The hash is what makes the proof exact: a prefix that was rebuilt, reordered, trimmed, or
  edited in place no longer matches, while a prefix that only grew still does.
- `permissionReviewDelta` decides whether the remembered run may be continued. It refuses when the
  policy version, the retained instructions, the workspace, or the model changed, when the history no
  longer starts with the entries that run read, when the earlier run is not confirmed to still hold
  its prompt, and when the added entries would not fit one character budget. The workspace check
  matters because the workspace block in the reused prefix belongs to the action that was reviewed,
  not to the chat: an action in another workspace has to be judged against its own block.
- `reviewerConversationIsIntact` asks the reviewer's own side of that question, the way the reference
  implementation asks it of its review session: it reads the run by task id, takes its child chat, and
  refuses the continuation unless that chat exists and still holds the marker. The summary check
  stays alongside it, because a summary can leave the original rows in storage while the reviewer
  stops reading them, so the marker alone would not notice. A failed check forgets the remembered
  run, so the chat starts clean on the next review.
- `buildReviewContinuationPrompt` builds the delta message: the added entries, the lifecycle, the
  submission reminder, and the action last. The policy, the evidence rules, the retained
  instructions, and the workspace block stay in the conversation the run already holds. The submission
  reminder is repeated because the reminder and the review id are the one part of the earlier message
  that goes stale.
- `AgentToolPermissionReviewer.review` reads the history once, uses it for both the window and the
  cursor, and falls back to the full prompt and a fresh run whenever the continuation cannot be
  proven: no lock in time, no remembered run, a refused delta, an unreachable run, or an
  `AlreadyRunning` answer. A review that failed forgets the run, so the next one starts clean.
- `ChatTurnOptions.disableSummary` reaches the turn from `SubagentTaskRequest.disableSummary`, which
  the reviewer sets. It joins the existing `forceDisableSummary` gate, so it also stops the summary a
  turn can trigger on its own when it runs out of context.

## Constraints

- A delta must be refused whenever the cursor cannot be proven to describe the same history, and
  whenever another review is running.
- A delta must also be refused whenever the run it would continue is not confirmed to still hold the
  prompt, so the reviewer is never sent an increment to a conversation that lost its prefix.
- The delta carries every entry after the cursor, so the reviewer's conversation holds the earlier
  prompt plus everything newer: no entry that the earlier review had not finished reading is ever
  left out. The one exception is an entry older than the cursor that the earlier render dropped for
  budget and a later render selects again; it is not re-sent, and the conversation carries the
  omission notice either way. Cache savings must not buy a quieter warning than that.
- The reviewer's submission tool contract does not change.

## Validation

- Tests on the cursor: a history that grew yields a delta; a rewritten or edited prefix, a changed
  policy, retained-instructions block, workspace, or model, a shrunken history, or a missing cursor
  yields the full prompt; and a live answer that grows between two reviews still continues.
- A delta prompt contains exactly the entries after the cursor and no others, apart from the live
  assistant entry of the running turn, which may be a longer copy of an entry already read (that is
  more evidence, never less).
- Two reviews of the same turn render a byte-identical prefix up to the retained instructions, which
  [Window](1_Window.md) covers for the full prompt. The continuation does not need it: it appends
  instead of re-rendering.
- The reviewer still parses its decision from the submission tool on both prompt shapes.
- `BatchCheckOrderTest` pins the dispatch shape the order rests on — a batch the reviewer serves runs
  one check at a time in the order it was given, a batch at any other level keeps that order in its
  results while its checks stay free to overlap — and the rule that decides which one applies: only a
  batch holding an action at the level the reviewer serves is walked in order.
- `PermissionReviewContinuationTest` covers all of that, and the full unit suite passes.

## Residues

- The continuation lives in memory. A process restart rebuilds the full prompt, which is the safe
  direction and costs one review's cache rather than one review's evidence.
- The delta message itself is not bounded by the transcript window: it carries every entry added since
  the last review, up to one character budget. Past that it falls back to the full prompt rather than
  trimming, because a trimmed delta would show less than a full prompt.
- The reviewer's run is continued across turns as well as within one, the same way the reference
  implementation continues its guardian conversation. A run that was abandoned mid-turn still holds
  its own earlier assessment, and the delta intro says the new action is judged on its own evidence.
- The delta refuses whenever the action's workspace differs from the previous review's, which is
  common when one turn works in several workspaces. Those actions rebuild the full prompt.
- A batch at the reviewer's level hands over its checks one at a time, so an action the workspace
  policy or the stored score would have answered without a review also waits for the reviews in front
  of it. Nothing it is waiting for could have been executed earlier anyway: the batch executes only
  after every one of its permission results is in.
- A continuation wins over the run the exact-override retry remembers, because the cursor belongs to
  the continuation's run. The reviewer then reads its own newer assessment rather than the one that
  denied the action, and the exact-override marker in the lifecycle block is what carries the user's
  approval either way.
- The cursor proves what the reviewer will be shown, so it hashes the entries themselves. Two costs
  come with that: the hash is recomputed over the whole covered prefix on every review, which grows
  with the session but stays a millisecond-scale read; and only a settled prefix is covered, so an
  action that arrives after the user queued a message behind the running answer falls back to the full
  prompt.
- The summary opt-out covers the automatic paths. The group-round summary and the manual summary in
  the chat UI do not consult it. The group-round summary cannot reach a reviewer's child chat, since
  that turn is a sub-task and the group path needs a turn that is not; the manual summary has no such
  gate, and summarizing that chat would leave the cursor describing a parent history it says nothing
  about. Either way, a summary that lands there ends the continuation: a summary inserts a row rather
  than rewriting the prompt, so it is the summary half of the liveness check that refuses it, while
  the marker half is what catches a prompt that was removed or replaced outright.
- The liveness check is a proof that the full prompt is still in the chat, not that the reviewer run
  is still reading it, and it is a substring test rather than a comparison with the prompt that was
  actually sent. A child chat is reachable from the sub-agent list, so a user can delete or edit its
  messages: removing or replacing the first user message is caught, and so is rewriting it without the
  marker, but a rewrite that keeps that line still reads as intact. The window between the check and
  the dispatch of the delta is not closed either.
- An entry older than the cursor that the earlier render dropped for budget and a later render
  selects again is not re-sent. The reviewer's conversation still carries the omission notice, so the
  gap is announced rather than silent, but the guarantee is "nothing that the earlier review had left
  unread is dropped", not "the window is always reproduced exactly".
- The per-chat lock map is a `ConcurrentHashMap` behind Kotlin's non-atomic `getOrPut` and a
  `firstOrNull { !isLocked }` eviction, so two reviews can briefly hold different locks for one chat.
  The failure mode is a redundant run and an `AlreadyRunning` answer, which falls back to the full
  prompt; it never widens a review. A lock can also be left behind if the wait is cancelled at the
  moment the lock changes hands, which costs latency rather than correctness.
- `PermissionReviewExactOverrideStore.reserve` is released in the `finally` that the new wait now sits
  inside, so a cancellation during that wait leaves the reservation behind for longer than it used
  to. The reservation was already between those two points; only its window grew.
- `AgentToolPermissionReviewer.review` itself is still not unit-tested: its orchestration needs a chat
  core and a coordinator. Only the cursor, the delta decision, the prompt shape, the store, and the
  window are. Four pieces of the wiring share that gap: the review orchestration, the branch that
  throws the oldest lock away, the coordinator honouring `disableSummary`, and
  `reviewerConversationIsIntact`, which needs a run repository and a chat core. What is tested is the
  refusal itself, and that a delta prompt never carries `REVIEW_PROMPT_MARKER`.

## Out of scope

- The asynchronous scorer stays a single call per batch; it has no conversation to continue. It does
  learn what the blocking reviewer already decided, in [Prior reviews](3_PriorReviews.md).
- Reordering the prompt is not part of this change; the order is already the cache-friendly one.
