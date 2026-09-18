# Session identity

## Problem

Every review asked its provider under the reviewer chat the turn ran in. All of the cache work in
[Window](1_Window.md), [Continuation](2_Continuation.md) and [Prior reviews](3_PriorReviews.md) shapes
the prompt so its stable part comes first, and a provider still only reuses a cached prefix while the
conversation identity it was asked under stays the same. A review that continues the earlier reviewer
conversation keeps that identity for free, because the continuation runs in the same chat. A review
that cannot continue one starts a new reviewer chat, and a new chat is a new conversation as far as
the provider is concerned, so the whole prefix — policy, evidence rules, submission contract, retained
instructions, workspace block, transcript — was re-read at full price.

Measured on the device on 2026-09-17, from the token ledger's `PERMISSION_REVIEWER` rows (the category
[Window](1_Window.md) introduced, which is what made this visible):

| Reviewer request | Cached input | Cache read |
| --- | --- | --- |
| 05:46:03, first review of a chat | 0 of 10,628 | 0% |
| 05:46:24, continued | 10,752 of 13,345 | 81% |
| 05:46:32, continued | 13,568 of 15,862 | 86% |
| 05:47:26, fresh run | 0 of 11,101 | 0% |
| 05:50:04, fresh run | 0 of 12,741 | 0% |
| 05:50:06, continued | 12,928 of 14,096 | 92% |

The pattern is not a coincidence of prompt content: every fresh run reads its whole prompt, every
continued review reads a fifth of it. The three fresh runs are the same three runs the sub-agent
records show as separate reviewer chats.

## Reference mechanics

Read in the workspace checkout of `openai/codex` at `codex/`.

- `core/src/client.rs` computes the key a request is cached under:
  `fn prompt_cache_key(&self, responses_metadata)` returns the explicit override when there is one,
  otherwise `format!("{source}:{parent_thread_id}")` when the client's session source is internal and
  the request carries a parent thread, and `responses_metadata.session_id` in every other case.
- `core/src/client_tests.rs`, `internal_session_prompt_cache_key_is_scoped_to_parent_thread`, pins
  exactly that rule for the guardian source.
- `app-server/tests/suite/v2/guardian_sync_session_tests.rs` is the case this document is about: one
  seed review, one reused reviewer thread, and one forked reviewer thread all assert
  `prompt_cache_key == format!("guardian:{}", parent.id)`, while the same test asserts that the
  concurrent reviewer threads have *different* `client_metadata.thread_id`s.
- `app-server/tests/suite/v2/guardian_v2.rs` does the same for the asynchronous scorer with
  `guardian-v2:{thread_id}`; `ext/guardian-v2/src/async_scorer/sampler.rs` builds it beside its
  request.

So the reference implementation separates the two ideas that our reviewer chat id had merged: the
reviewer's own session may be reused or forked per review, while the identity its prompt is cached
under is derived from the conversation being reviewed and never moves. The second is what decides
prefix reuse, and it is independent of whether a review continued a conversation.

## Change

- `providerSessionIdForScope(scope)` in `api/chat/llmprovider/OpenCodeGoHeaders.kt` turns a stable
  scope name into the conversation identity its requests ask under, hashed into a UUID so it has the
  same shape as every other identity the providers have been handed.
- `ChatTurnOptions.providerSessionId`, `SubagentTaskRequest.providerSessionId` and
  `EnhancedAIService.SendMessageOptions.providerSessionId` carry it: a turn may belong to a
  conversation that is not the chat it runs in.
- `EnhancedAIService.sendMessage(options)` resolves it once — the pinned identity, else the turn's own
  chat, else the service instance's fallback — and uses it both for the turn's own requests and for
  the tool continuations the turn starts, through `MessageExecutionContext.providerSessionId`.
- `AgentToolPermissionReviewer` pins `permission_reviewer:<parent chat>` for every review it
  dispatches, continued or fresh.

## Shape

- The scope is the reviewed conversation, not the reviewer chat: `parentChatId` is the chat whose
  transcript the prompt carries, which is the same key the continuation store and the review events
  already use. Two reviews of one conversation therefore always ask under one identity, however many
  reviewer chats they ran in.
- The role is part of the scope name, so a future internal reader of the same conversation (the
  asynchronous classifier is the obvious candidate, which the reference implementation also keys
  separately) gets a namespace of its own rather than a second prompt shape inside the reviewer's.
- Nothing else changes: a fresh run still rebuilds the full prompt, still records a new run, and still
  keeps its own child chat, so the review list, the event records and the reviewer's own conversation
  are exactly what they were.

## Constraints

- The identity must not be derived from anything that changes between two reviews of one conversation,
  or the fix would not hold across the run boundary it exists for.
- It must not be the turn's own chat id, because that is exactly the value that changes.
- It must stay opaque to providers and stable across processes: a restart must not move it, or the
  first review after a restart would pay full price again.
- Pinning an identity must not widen what the reviewer may read. This change only decides which cache
  a request is filed under; the prompt, the evidence rules and the submission contract are untouched.

## Validation

- `ProviderSessionScopeTest` pins the four properties the identity needs: one scope always resolves to
  the same value, two conversations do not share it, two roles over one conversation do not share it,
  and the value is shaped like every other identity.
- `SubagentTurnOptionsTest` pins the trip from `SubagentTaskRequest` to the turn: a pinned run keeps
  its identity, an ordinary run keeps null and therefore its own chat.
- The full unit suite passes.
- On device, the check that matters: the `PERMISSION_REVIEWER` cache read of a *first* review in a new
  run should stop being 0%, because the prefix the previous run warmed is now filed under the same
  identity.

## Residues

- The reviewer's own child chat still changes with every fresh run, which is what the review list and
  the per-review jump show. Only the provider-side identity is pinned.
- The classifier still asks under whatever identity its own call site implies (today the provider
  instance's fallback), so its cache is stable for as long as that provider instance lives but is not
  derived from the conversation. It is a single call per batch with no reviewer conversation to
  continue, so the reference implementation's separate `guardian-v2:{thread_id}` key would buy it
  mostly a namespace of its own. Not part of this change.
- The identity is derived from the chat id, not stored on the event, so a review whose conversation
  later moves (a chat copy or a branch) asks under a different identity from the reviews before it.

## Out of scope

- Which reviewer chat a review can be read back from. That is the reviewer conversation's own
  behaviour, not its provider identity.
- Reordering or resizing the prompt; the order was already the cache-friendly one.
