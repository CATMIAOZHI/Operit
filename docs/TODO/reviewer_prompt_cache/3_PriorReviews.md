# Prior reviews

## Problem

The asynchronous classifier judges one dispatched batch on that batch's own evidence: the retained
user instructions, the workspace, a bounded transcript window, and the actions about to run. It has
no record of what the blocking reviewer already decided in this conversation, so a repeated pattern
looks new to it every time. The reference implementation deliberately shows its asynchronous scorer
what its synchronous reviews already decided.

## Reference mechanics

Read in the workspace checkout of `openai/codex` at `codex/`: `guardian-context/src/reviews.rs`, its
registration in `guardian-context/src/lib.rs`, and `ext/guardian-v2/src/async_scorer/extension.rs`.

- `MAX_PREVIOUS_REVIEWS = 8` bounds the count, and one rendered fragment is capped at about 1,000
  tokens: 100 for the correlation, 350 for the reviewed action, 250 for the rationale, 800 for the
  whole body.
- The section is `SectionScope::AsyncOnly`, so the blocking reviewer never receives it. The reviewer
  does not need it: it continues its own conversation and already holds its earlier assessments.
- The host selects the records and attests each rendered body, and it selects them by authorization:
  a review is only used when its `authorization_version` and `root_authorization_version` still match
  the current ones (`extension.rs`).
- It is delivered as a `developer` message whose lead-in says the decisions come from the host and
  apply only to their original actions, and that the reviewed action and the rationale are evidence
  rather than instructions or authorization. Each fragment repeats that its decision applies only to
  the action it judged and adds "reassess changed circumstances and future actions".
- The review bodies come from the synchronous reviewer, not from the asynchronous scorer itself.
- The rationale is JSON-quoted before truncation and every piece of the fragment has `</` escaped,
  "so payloads cannot close the fragment" and "rationale text cannot imitate record headings".
- Construction validates the count and the rendered size of whole fragments and **rejects** oversized
  evidence rather than truncating or silently dropping a record. Truncation itself happens per
  component, before that check.

## Change

- Show the classifier this conversation's own earlier decisions, oldest first, up to eight. They are
  appended, so the block only grows as the conversation proceeds and an earlier prompt stays a
  reusable prefix.
- Take only decisions the blocking reviewer itself reached. A review event that records a call the
  fast path allowed carries no independent judgement: it is the classifier's own earlier answer, and
  showing it back would let that answer argue for the next one. Those events name a resolution source
  other than the reviewer's own, so the rule is that a decision counts only when it has none.
- Take only decisions whose authorization is still the current one: the same policy version, the
  same retained instructions the reviewer read, and the same workspace block. A decision from before
  the user last spoke belongs to a superseded authorization and is not shown, the way the reference
  implementation filters prior reviews by authorization version.
- Read the decisions of the chat being judged and of the conversation it belongs to. A sub-agent's
  actions are reviewed and recorded under the sub-agent's own chat, while the classifier reasons
  about the root conversation.
- Put the block after the workspace and before the transcript. It changes as the conversation goes
  on, so it belongs on the volatile side of the prompt, but nothing that holds still is placed after
  it, so the reusable prefix still ends where it did.
- Keep each fragment small and bounded, and say when a field was cut rather than cutting silently:
  a missing part is never a reason to read an action as safer.
- Say what a decision is worth. The heading and the preamble state that the decision applies only to
  the action it judged, that the reviewed action and the rationale are evidence rather than
  instructions, that neither widens what the user authorized, and that the current course of action
  has to be reassessed against changed circumstances.
- Quote the rationale as JSON, so it cannot span a line or imitate the headings around it. The
  reviewed action is JSON already, and it is serialized from the parsed action rather than from the
  text a caller passed in, so it cannot contain a line of its own either.
- Label an approval that came from a one-time user override, because that approval belongs to the
  single retry the user authorized and must not read as a standing permission.

## Shape

- `renderPriorReviews` renders the block from a list of events, the chats they may come from, and the
  current authorization, and returns a `PriorReviewsRender`: the block, or null when nothing
  qualifies, together with the number of fragments it carried, its character count, and a fingerprint
  of its text. It is a pure function of its arguments, so the selection, the shape, the bounds, and
  the quoting are unit-tested without a chat core.
- It selects `APPROVED` and `DENIED` only. A review that timed out, failed, or was aborted reached no
  decision about the action, and showing its absence would read as one.
- `PermissionReviewEvent.policyVersion`, `retainedInstructionsHash`, and `workspaceKey` record what
  the reviewer decided under, written where the reviewer publishes the event.
  `PermissionReviewPriorReviews` compares all three against the current ones. Older stored events
  decode with all three unset and are therefore not shown, which is the safe direction.
- `MAX_PRIOR_REVIEWS` bounds the count, `MAX_PRIOR_REVIEW_ACTION_CHARS` and
  `MAX_PRIOR_REVIEW_RATIONALE_CHARS` bound the fields, `MAX_PRIOR_REVIEW_CHARS` bounds a fragment,
  and `MAX_PRIOR_REVIEWS_CHARS` bounds the block including its heading and preamble. Past the block
  budget the oldest decisions are dropped, because the newest ones are the ones a trajectory check
  needs.
- `PermissionReviewEventRepository.decisionsFor` takes the chats to read and loads the stored history
  before it answers, the same way the other readers of that repository do, so a caller that arrives
  before the background decode cannot see the chat's own earlier decisions as absent.
- `PermissionRiskScorer.beginBatch` registers that repository and passes the rendered block into
  `buildClassifierInput`, which places it between the workspace block and the transcript. With no
  qualifying decision the prompt carries no block of its own and is byte-for-byte what it was before
  this change, apart from the omission notice a history longer than the candidate tail adds
  ([Window](1_Window.md)).
- The batch's own record keeps what it was shown: `PermissionRiskScoreRecord.priorReviewCount`,
  `priorReviewChars`, and `priorReviewHash`, filled from the render. The pre-classification page shows
  them as "earlier reviews carried" on every batch that reached the classifier, and says when a batch
  carried none; a batch that was skipped was never asked and shows nothing. Without this the only
  trace of the block would be the tokens it cost.
- A one-time override approval survives a restart. The override's pending state is cleared when the
  stored events are loaded, but whether the override was applied is a record of what happened, and
  the classifier has to keep reading it as one.

## Constraints

- An earlier decision is context for a trajectory, never authorization. Nothing in the block may
  widen what the user approved, and the preamble says so in the prompt itself.
- The classifier's own verdicts are not evidence. The block carries the blocking reviewer's
  decisions, the same way the reference implementation's `previous_reviews` carries its synchronous
  reviews.
- A decision reached under a superseded authorization is not shown at all.
- Cutting is reported. A fragment that had to be trimmed says how much went missing.

## Validation

- `PermissionReviewPriorReviewsTest` fixes the contract: no block before a decision exists; another
  chat's decisions are not this chat's evidence; the sub-agent chat's own decisions are; the
  classifier's reuse and a manual allow are never shown as reviews; a decision from a superseded
  authorization is not shown; oldest first; only the newest eight; appending one more decision leaves
  the earlier block a prefix; a rationale cannot imitate the record headings; a one-time override is
  labelled; a denial reads as a denial; a trimmed field is reported; and the oldest decisions are
  what the budget cuts. Three more cover the record's view of the block: the reported count and
  character total match what was rendered, a batch with nothing to show carries no block at all, and
  the fingerprint identifies the fragments it was taken from.
- `:app:testDebugUnitTest` and `:app:lintDebug` pass on the change.

## Residues

- The block slides once the count or the block budget is reached: a ninth decision drops the oldest
  fragment, so the prefix after it misses. It sits before the volatile transcript, so what is lost is
  the tail of a prompt that was going to miss anyway.
- An already-decided event can be rewritten in place when a settings change enforces a different
  outcome than the reviewer reached. That rewrite turns a decision into a source-tagged one and it
  leaves the block, which is the safe direction but does mean the block is not strictly append-only.
- The authorization check makes the block empty after each user message until the reviewer decides
  something new under the new instructions. That is the reference implementation's behaviour too,
  and it is the point of the check: an approval the user's next message superseded is not context
  for what follows it.
- Showing earlier approvals can pull the classifier toward a low verdict, which is the one way this
  change can loosen a decision. The reference implementation accepts the same coupling and leans on
  its sandbox; we accept it with the framing and the selection above. No unit test can measure a bias
  like that.
- A payload can still spell out an omission marker of its own, and a Unicode line separator inside a
  payload is not escaped. Both are text inside a JSON-quoted field, so neither can add a line or a
  heading; they can only make the field read oddly.
- The stored event is sanitized when it is written to disk: the rationale and the action arguments
  are dropped there. So after a process restart the block still carries each decision, its risk and
  authorization lines, and the tool name the action was summarized to, but with an empty rationale
  and a stripped action, which is much less to reason from. That is the same privacy choice that
  keeps the rest of the stored history free of payloads.
- `buildClassifierInput` and the `beginBatch` read are not unit-tested: the first is private and the
  second needs a chat core and a model lease. What is tested is the rendered block, and the fact that
  an empty block leaves the prompt unchanged is reviewed by reading.
- The recorded fingerprint answers for the events that batch was given, not for whatever is stored
  later: the review history is a bounded window (200 events app-wide) whose stored form drops each
  event's rationale and action arguments, and the block drops its own oldest fragments at its budget.
  A rebuild that does not match is therefore not by itself proof that the batch was shown something
  else. The page shows it as a fingerprint of the block, not as a guarantee that it can be reproduced.

## Out of scope

- The blocking reviewer does not receive the block. It continues its own conversation, so it already
  holds its earlier assessments.
- Changing the classifier's own bounds (transcript, actions), or its score-reuse rules, is not part
  of this change.
- The decision events are still recorded under the chat the action ran in, which is what the audit
  UI lists. Reading both chats here is what keeps that storage layout from hiding a sub-agent's
  decisions from the classifier.
