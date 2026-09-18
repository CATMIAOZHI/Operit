# Message origin

## Where the trust is granted

- `PermissionReviewRetainedInstructions.kt` builds the block. `readUserMessages` keeps every message
  whose `roleName` or `sender` equals `user`, and the reader also appends the workspace rule file and
  the `user.md` document.
- `AgentToolPermissionReviewer.buildReviewPrompt` states that the user messages, the workspace rule
  file, and the profile document inside `RETAINED USER INSTRUCTIONS` are trusted evidence of user
  intent, while the transcript, action arguments, file contents, and command output are untrusted.
- `PermissionRiskScorer` reads the same block and binds its verdict to the block hash, so a change
  here moves both the blocking review and the asynchronous classifier. They must stay consistent.

## Where the foreign message enters

- `app/src/main/AndroidManifest.xml` declares `ExternalChatReceiver` with `android:exported="true"`,
  no `android:permission`, and the action `com.ai.assistance.operit.EXTERNAL_CHAT`.
- `ExternalChatReceiver.onReceive` passes `EXTRA_MESSAGE` to `ExternalChatRequestExecutor`.
- `ExternalChatRequestExecutor` reaches `StandardChatManagerTool.sendMessageToAI`, so the text is
  persisted as a user turn of a normal chat and then read back as trusted intent.

## Approach

Mark the messages the external entry delivered, and keep them out of the trusted block. Candidates,
to be settled in the implementation round:

1. Persist the origin on the message. Most robust and survives restarts; `ChatMessage` is persisted,
   so it needs a schema and migration decision.
2. Record the delivered messages outside the history, keyed by chat and timestamp, and filter them
   while reading. No schema change; the store must not outlive its chat or leak between chats.
3. Give a delivered message a distinguishable sender so the existing `sender == "user"` filter
   already excludes it. Cheapest, but it must be confirmed that prompt assembly still renders the
   turn as a user turn for the agent.

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
