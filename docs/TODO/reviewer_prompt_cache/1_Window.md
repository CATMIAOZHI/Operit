# Window

## Problem

A provider that caches prompt prefixes charges a shared prefix far below the full input price, so a
transcript window that holds still is nearly free. The review prompt could not use that. Its window
kept the newest messages inside a message-count cap of twelve as well as a character budget, so it
slid by one position every time one more message arrived, and the text an earlier review of the same
turn had already sent could not be matched.

The prompt order was already the cache-friendly one — policy, evidence rules, submission
instructions, retained instructions, workspace, transcript, lifecycle, action — with the volatile
action last, so the window was the part worth fixing.

## Change

- The reviewer's message-count cap is gone, so its window has no count ceiling of its own: it grows
  by appending until the character budget is full instead of sliding once a turn passes twelve
  entries. The candidate tail of twenty-four still bounds which entries can be chosen, which is a
  property the window already had. The entries this restores are the ones the cap used to cut, so
  every rendering is a superset of what the capped window showed. The asynchronous classifier keeps
  its own smaller ceiling of eight entries; that ceiling is its own cost decision and does not change
  here, though its rendering now also carries the omission notice whenever the ceiling or its budget
  cuts something.
- Entries the budget leaves out are reported with a host notice, in the same shape the retained
  instructions use, so a partial view is never read as a complete one. So is the history the
  candidate tail cuts before the window: the window cannot see what it was never offered, and the
  reviewer prompt reads a missing notice as "nothing was left out", so the caller reports that cut
  and the notice is emitted whenever the tail was shortened. The check is made on the offered
  messages rather than on the rendered entries, which errs towards saying the view is partial.
- The last user entry stays in view when the window had to drop the user turn, so an action is never
  read without the request it answers.
- Entries are still drawn from the newest candidate tail, which keeps the work of one review
  bounded, and a history longer than that tail reaches the reviewer as an announced omission.

What this does not do: once the character budget is full, the oldest entries still fall out and the
rendering moves. The guarantee is "stable while the budget holds", not "stable for a whole turn".
Holding it for a whole turn is the continuation, which is built in [Continuation](2_Continuation.md):
it appends to the run instead of re-rendering the window, so it does not depend on this stability.

## Rejected: anchoring the window on the turn

An earlier draft anchored the window on the user message that opened the turn under review, and
bounded an anchored window by the character budget alone. Independent audit rejected it: for the
first review of a new turn the window then held only that turn's messages, which is strictly less
evidence than the capped window showed before, and nothing told the reviewer that anything was
missing. A reviewer that reads less than before decides differently, and the rule here is that this
change may never lower the evidence.

## Shape

- `permissionReviewTranscriptEntries` renders the sanitized entries, dropping blank content and the
  entry a live assistant message replaces.
- `renderPermissionReviewTranscriptWindow` selects the newest entries the budget holds, reports what
  it left out, and re-adds the last user entry when the window lost its user turn. A message-count
  ceiling is still accepted and both call sites pass one: the reviewer passes
  `REVIEWER_MAX_TRANSCRIPT_MESSAGES`, which is pinned to "no ceiling", and the classifier passes its
  own eight.
- `buildPermissionReviewTranscript` gathers the history, takes the newest candidate tail, reports a
  history longer than that tail as an omission, and adds the live assistant entry.
- `PermissionReviewTranscriptTest.kt` fixes the contract: the reviewer's ceiling letting forty entries
  through, the prefix holding still as a turn grows, the omission notice, the user-anchor fallback,
  the cut candidate tail, the empty window, and the entry rendering.

## Validation

- `:app:testDebugUnitTest` — the cases in `PermissionReviewTranscriptTest`, plus the full unit suite.
- Independent read-only audit re-ran on the corrected change and returned `AUDIT: PASS`.
- Neither reader sees less than it did before the cap came off: the reviewer's window is a superset of
  the capped one, and the classifier keeps its own ceiling. The two no longer read the same window —
  the reviewer has no count ceiling of its own — and in a sub-agent review the reviewer reads the
  child chat while the classifier reads the root chat, which was already true before this change.
- Residues: the window moves once the character budget is full; `buildPermissionReviewTranscript`
  itself is not unit-tested, since it needs a chat core; the live assistant entry is now subject to
  the budget, so a caller passing `maxChars` below that entry's own length would drop it, which
  neither of the two call sites does.
