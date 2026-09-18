---
title: Reviewer prompt cache
fork: https://github.com/CATMIAOZHI/Operit
branch: codex/reviewer-prompt-cache
status: active
---

# Reviewer prompt cache

## Current state

A provider that caches prompt prefixes charges a shared prefix far below the full input price, so
the part of a review prompt that holds still is nearly free. Three things kept the review prompt from
using that.

1. The reviewer's transcript window kept the newest messages inside a message-count cap as well as a
   character budget, so every entry shifted by one position whenever one more message arrived and
   nothing an earlier review sent could be matched. Done in [Window](1_Window.md) by dropping the cap
   for the reviewer; the asynchronous classifier keeps its own smaller ceiling.
2. Every review builds its prompt from scratch and starts a new reviewer run, so there is no earlier
   material to reuse beyond the retained instructions. Done in [Continuation](2_Continuation.md): the
   reviews of a batch are serialized in the order the model asked for them, so a review continues the
   run the previous one left behind.
3. The asynchronous classifier judges each batch with no record of what the blocking reviewer already
   decided. Done in [Prior reviews](3_PriorReviews.md): it is now shown this conversation's own
   earlier reviewer decisions, bounded, limited to the current authorization, and framed as evidence
   rather than authorization.
4. Every review asked its provider under the reviewer chat it ran in, so a review that could not
   continue the earlier reviewer conversation looked like a brand new conversation to the provider and
   re-read the whole prefix at full price. Done in [Session identity](4_SessionIdentity.md): all
   reviews of one conversation ask under one identity, derived from the conversation and not from the
   run, the way the reference implementation keys its reviewer by `guardian:<parent thread>`.

The prompt order was already the cache-friendly one: policy, evidence rules, submission
instructions, retained instructions, workspace, transcript, lifecycle, action, with the volatile
action last. Nothing here changes it.

## Intent

Raise how much of a review prompt a provider can reuse across reviews of the same turn, without
letting the reviewer see less evidence than it sees today. Cache savings must not buy a weaker
review.

## Watching it

The two calls this work is about are counted apart from everything else in the token statistics:
`TokenStatCategory.PERMISSION_REVIEWER` for the blocking reviewer, which used to share the sub-agent
bucket, and `TokenStatCategory.PERMISSION_RISK_SCORER` for the asynchronous classifier, which used to
be `OTHER`. The ledger records the cached-input token count of every request it accepts, and the
statistics page already reports a cache-read figure, so those two categories now say what prefix
reuse is actually happening instead of leaving it inferred from the prompt shape.

The classifier's own input is one call rather than a conversation, so there is no reviewer session to
read it back from. What its record keeps instead is the size of the earlier decisions it was handed:
`priorReviewCount`, `priorReviewChars` and `priorReviewHash` on `PermissionRiskScoreRecord`, shown on
the pre-classification page as "earlier reviews carried". The block is rebuilt from the review events,
so a rebuild that ends with the same fingerprint points at the fragments the batch was shown, as far
as the events it was given still exist: the review history is a bounded, sanitized window and the
block drops its own oldest fragments at its budget. The ledger stores counts, not content, so the
structured record cannot show the prompt text. The request body is reachable only through the app's
ordinary logging, where `AppLogger.enableFileLogging` and `AppLogger.logRequestBodies` are both on by
default and the providers that go through them log the messages they send, so a classifier prompt
appears in the file the in-app log viewer reads. That is a debugging channel rather than a per-batch
record: the file is cleared on every cold start and kept only when the app was launched to send a
crash report, and no id links a logged body back to a batch - only the progress lines written beside
it, which a reader has to match up by hand.

The same page states what each call cost: `PermissionRiskScoreUsage` on the record carries the call's
own input, its cache read, and its output, filled from the same provider reports the ledger counts
through the same merging. The cache read and the output are therefore the statistics page's numbers;
the input is the total the provider stated, or the sum of the parts it stated, which is a derivation
rather than one of the ledger's own figures: the ledger charges for the parts it can split and needs
the cache-write count as well, while the row is not pricing anything. The row also carries the cache
share, which is the number that says whether the prefix work is paying off, and it says when a
provider reported nothing rather than showing a zero. What it still cannot say is which part of the
prompt the provider served from its cache; only the total is reported.

Measured on the device before the categories existed (2026-09-13 to 2026-09-16, split by hand from
the sub-agent model and run titles, because the reviewer and the classifier shared a bucket):

| Calls | Cached input | Uncached input | Cache read |
| --- | --- | --- | --- |
| Blocking reviewer, 80 over 72 runs | 81,664 | 580,993 | 12% |
| Asynchronous classifier, 73 | 206,336 | 170,092 | 55% |
| Main conversation, 125 | 4,527,487 | 272,959 | 94% |

The reviewer's 12% is the number this work is meant to move: each of those reviews started a fresh
run, so only the part of the prompt that did not depend on the conversation could be matched.

Reading the same ledger after [Window](1_Window.md) and [Continuation](2_Continuation.md) shipped on
2026-09-17 showed the remaining loss is not in the prompt shape: a continued review reads 81-92% from
cache, while a review that has to start a new reviewer run reads 0% — 10,628, 11,101 and 12,741
uncached input tokens on the three fresh runs of one chat. That is
[Session identity](4_SessionIdentity.md)'s subject.

## Expected result

- A window that keeps the entries already sent in place for as long as the budget holds, reports what
  it left out, and never reads an action without the request it answers.
- Where a review can continue an earlier reviewer conversation, only the transcript entries added
  since the last assessment are sent.
- The reviews of one batch run in the model's own call order, so the reviewer reads them in the order
  the actions are executed and only the first of a batch pays for the prompt.
- Any review whose continuity cannot be proven falls back to the full prompt.
- The classifier knows what this conversation already decided, and knows that those decisions are
  evidence rather than a standing permission.
- Every review of one conversation asks its provider under that conversation's identity, so a review
  that could not continue an earlier reviewer conversation still reuses the prefix that one warmed.

## Scope

1. [Window](1_Window.md) — done; the independent audit re-ran on the corrected change and passed
2. [Continuation](2_Continuation.md) — done; serialization accepted, independent audit passed
3. [Prior reviews](3_PriorReviews.md) — done; bounded, framed, and appended so the prefix still holds
4. [Session identity](4_SessionIdentity.md) — done; the identity is derived from the reviewed
   conversation, and the reviewer pins it for every review it dispatches
5. [Review navigation](5_ReviewNavigation.md) — done; the review panel opens the exchange that
   judged the review the reader asked for, inside the conversation several reviews now share

The observability under "Watching it" has no document of its own: it ships with the cache work to make
it measurable, not to change what the reviewer does.
