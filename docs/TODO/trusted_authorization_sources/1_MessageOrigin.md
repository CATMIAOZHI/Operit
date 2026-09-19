# Message origin

## Where the trust is granted

- `PermissionReviewRetainedInstructions.kt` builds the block. `readUserMessages` keeps the messages
  the device owner actually wrote, and the reader also appends the workspace rule file and the
  `user.md` document.
- `AgentToolPermissionReviewer.buildReviewPrompt` states that the user messages, the workspace rule
  file, and the profile document inside `RETAINED USER INSTRUCTIONS` are trusted evidence of user
  intent, while the transcript, action arguments, file contents, and command output are untrusted.
- `PermissionRiskScorer` reads the same block and binds its verdict to the block hash, so a change
  here moves both the blocking review and the asynchronous classifier. They must stay consistent.

## What the delivered-turn marking settled

A turn the app delivers into a chat is stored as a user turn, so the shape of a row never proved who
spoke. The row does record where it came from, in its display mode, and that is what the block now
reads:

- `ChatTurnOptions.deliveredByTool` marks a turn a tool or the agent host hands to a chat, decided at
  the write side by `ChatTurnOptions.userTurnDisplayMode`.
- `StandardChatManagerTool` sets it for every `send_message_to_ai` dispatch, and
  `SubagentCoordinator.toChatTurnOptions` does the same for v1 and v2 subagent turns, as does the
  explore slice's own dispatch in `SubagentSliceCoordinator`, so a task prompt no longer reads as the
  owner's authorization.
- `ChatMessageDisplayMode.TOOL_DELIVERED` carries it in storage, which needs no schema change.
- `isOwnerStatedUserMessage` drops the delivered and collaboration modes from the block, and
  `PermissionReviewTranscript` shows a delivered row as a delivery instead of under the owner's
  label.

The external broadcast path of this item is therefore closed on the read side: the text
`ExternalChatReceiver` delivers still starts a turn, but it reaches a reviewer as untrusted content.
The exported receiver itself is unchanged and stays out of scope, as below.

## Where the foreign message enters

- `app/src/main/AndroidManifest.xml` declares `ExternalChatReceiver` with `android:exported="true"`,
  no `android:permission`, and the action `com.ai.assistance.operit.EXTERNAL_CHAT`.
- `ExternalChatReceiver.onReceive` passes `EXTRA_MESSAGE` to `ExternalChatRequestExecutor`.
- `ExternalChatRequestExecutor` reaches `StandardChatManagerTool.sendMessageToAI`, so the text is
  persisted as a delivered user turn of a normal chat, which the block no longer reads as intent.

## Approach (settled)

The origin is persisted on the message, as candidate 1 wanted, through the display mode column that
already existed: no schema change, and it survives restarts. The turn still renders and is answered
as a user turn, which candidate 3 could not promise.

Do not cap the effective permission level. The origin decides what counts as the owner's
authorization, not which stop the owner may use.

The exported receiver itself is out of scope. Any application that can send the broadcast can
already start a turn today, exactly as it could before this round.

## Validation

- Unit tests on the reader: a message delivered by another application is absent from the trusted
  block, an owner message in the same chat is present, and `complete`/`available` behave as before.
- Unit tests on the reviewer and the scorer stay green on the shared block hash.
- On the device, with the automatic review stop, an external broadcast no longer reads as owner
  intent in a review.

The reader tests now cover the delivered mode, the collaboration modes and a legacy row whose display
mode is missing or unknown (`PermissionReviewRetainedInstructionsTest`), and the transcript tests
cover the label a delivered row renders under (`PermissionReviewTranscriptTest`). The device check
still needs a run with the automatic review stop.

Imported conversations are the other writer of user turns, and they stay authorization on purpose:
the import exists so the owner can carry their own history over, which is why this item stops at the
deliveries the app itself makes.
