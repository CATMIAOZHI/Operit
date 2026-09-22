# Score reuse

## Current behavior

`resolvePermissionFastPath` in `PermissionRiskScorer.kt` decides whether a stored verdict may answer
the call under review without running the blocking reviewer. It requires the verdict to belong to
this scored session, to be at most `MAX_LAG_STEPS` (two) batches old, to have no later scoring
failure, to be `LOW`, and to have been taken under the same authorization.

Nothing in that check names the action being reviewed. The verdict carries no tool name and no
argument fingerprint, so a verdict taken for one action answers a different action from another tool
family, and it does so up to two batches later.

## Why it is like that

The fast stop mirrors the adaptive scorer of OpenAI Codex: one step per dispatched batch, the newest
verdict reusable, reuse limited to a lag of two, and reuse across tool families. The owner chose that
shape deliberately, because the fast stop exists to catch an accidental action, not to enforce a
sandbox: the level answers "may the agent do this" and the reviewer is the thing that decides.

## What the promotion review found

`PermissionRiskScorer.kt:151` was flagged P1 by the security review of the ry.8 promotion. The
sequence it describes: a batch of benign calls earns a `LOW` verdict; the next batch starts its
classification asynchronously and checks permissions immediately, so a sensitive file, shell or
network call issued in that batch can be answered by the previous batch's `LOW` verdict before the
current batch's own verdict lands.

The window is bounded by batches rather than by time, and every discard condition the resolver keeps
still applies. The gap is that "the last thing the agent did was low risk" is treated as "this thing
is low risk".

## Approach

Candidates, to be settled in the implementation round; the owner's decision on how strict the fast
stop should be decides which one, and a stricter option costs the reuse the stop exists for.

1. Fingerprint the scored action and keep the verdict only for the action it scored. Reuse then needs
   an exact match, which removes the cross-family reuse the reference implementation has.
2. Require the verdict's step to be the current step, so a batch never borrows an earlier verdict.
   This is the strictest reading and turns reuse into "answer from this batch's own classification".
3. Keep the lag but wait for the current batch's classification to land before reusing a verdict, so
   the verdict that answers a call is never older than the call's own batch.

The level the owner chose stays authoritative: none of these may cap, narrow or override the stop.

## Validation

- Unit tests on `resolvePermissionFastPath`: a verdict taken for another action falls back to the
  blocking reviewer, and one taken for the reviewed action still answers it.
- Unit tests: a classification still in flight for the current batch defers instead of reusing an
  earlier verdict.
- On the device, with the fast stop, the pre-classification page shows which verdict answered a call
  and whether it was taken for that call.
