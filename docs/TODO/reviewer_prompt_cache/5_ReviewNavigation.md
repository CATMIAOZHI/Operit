# Review navigation

## Problem

[Continuation](2_Continuation.md) gave one conversation to the reviews of one chat, because that is
what makes the prompt prefix reusable. The review panel still treated a review as if it had a chat of
its own: every entry point opened `run.childChatId`, which is now the chat several reviews share, and
nothing said which of them the reader had asked for.

Measured on the device on 2026-09-17 (chat `51c45e3a`, from the review events and the sub-agent runs):
18 recorded reviews, 6 of which reached the model, and those 6 ran in 3 reviewer chats — three reviews
shared child chat `d76d8f61`, two more shared `5b40f0c9`. Tapping any of the three jumped into the same
conversation and landed where that conversation ended, which is the last review of the three rather
than the one tapped.

## Change

- `TranscriptJumpHost` carries one pending "open that chat and land on the message holding this text"
  request, filed by whoever is about to switch chats and read by the transcript of that chat.
- `permissionReviewJumpMarker(reviewId)` is the text that names a review inside its own conversation:
  the message that asks for a review states `review_id=<id>`, and no other message of that transcript
  carries that same id.
- `VirtualTranscript` takes the request once it holds the chat's messages, finds the message carrying
  the marker, and lands on the answer to it.
- A review the loaded window does not reach is found the way the locator finds a message: the whole
  conversation is searched for the marker, and revealing the message that carries it loads the page it
  is on, so the request is taken up again once that page is there.
- The four entry points file it before they switch: the review panel's own "open sub-agent details",
  the sub-agent manager's rows, the sibling switcher's rows, and a row of the recent denials list.

## Shape

- The request is keyed by the chat it belongs to and carries a token, so a transcript only takes its
  own chat's request and only once; a request whose chat never opens stays filed rather than leaking
  into another conversation.
- The landing spot is the message after the one that names the review, which is the reviewer's answer:
  the reader already knows which review they asked for, and the verdict is what they opened the panel
  to read. A review still being written has no answer yet, so the request itself is the landing spot.
- The jump reuses the locator's existing path — its search to find a message outside the loaded
  window, its reveal to load the page that holds it, and the pending jump to scroll to the row —
  instead of introducing a second way to move the transcript.
- The search runs at most once each time the chat is composed and what it found is remembered with it,
  so a transcript that keeps recomposing does not repeat it. A search cancelled while it was still
  reading is simply run again, rather than taken for one that found nothing, while one cancelled after
  it had returned goes straight on to what it found. A request whose marker the conversation does not
  carry at all is dropped rather than left in the slot.
- The bookmark restore is stopped for that chat before the jump: a reader who asked for one review
  must not be returned to wherever they last stopped reading a conversation they only glimpsed.

## Constraints

- The marker must identify exactly one message in the transcript it is used in. A review id is unique
  per review and is written into the message that asks for that review, so the match is exact rather
  than a guess about ordering.
- A jump must never move a transcript that the reader did not ask to move: nothing is filed unless a
  review record and its reviewer run are both known.
- Nothing here may change what a review reads or how it is dispatched; this is navigation over records
  that already exist.

## Validation

- `:app:testDebugUnitTest` and `:app:lintDebug` pass with the change.
- On device: tapping a review that shares its reviewer chat with other reviews should land on that
  review's own exchange, and tapping a review that has a chat of its own should be unchanged.
- The jump cannot be unit-tested: it needs a composed transcript. What is verified by reading is that
  the request is filed on every review entry point and that the marker is written into every review
  message, both the full prompt and a [continuation](2_Continuation.md) delta.

## Residues

- The fallback is today's behaviour: if the conversation does not carry the marker at all — the review
  was deleted, or this is not the chat it ran in — the request is dropped and the transcript opens
  where it would have opened before.
- A marker the conversation does carry can still be unreachable — the page holding it could not be
  loaded — and then the request is dropped as well and the reader is left where they were, so the click
  can simply be repeated.
- The sub-agent manager lists runs, not reviews, so a row for a reviewer chat that several reviews
  share lands on the newest review of that chat rather than on a review the row cannot name. Its text
  is the same newest review, so the row and the landing spot agree.
- The request is in memory and single-slot: two jumps filed back to back keep only the newer one,
  which is the one the reader asked for last.

## Out of scope

- Bounding the prompt prefix, which is [Window](1_Window.md), [Continuation](2_Continuation.md),
  [Prior reviews](3_PriorReviews.md) and [Session identity](4_SessionIdentity.md).
- Making one review per reviewer chat again. That was the behaviour before
  [Continuation](2_Continuation.md), and it is what made every review pay for its whole prompt.
