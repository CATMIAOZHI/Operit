---
title: Trusted authorization sources
fork: https://github.com/CATMIAOZHI/Operit
branch: personal/dev
status: planned
---

# Trusted authorization sources

## Current state

The blocking permission reviewer decides `user_authorization` from an evidence block that the review
prompt declares trusted: the retained user messages of the reviewed chat, the workspace rule file,
and the `user.md` profile document.

Two sources can enter that block without the owner vouching for their content.

1. A message another application delivered. `ExternalChatReceiver` is exported with no permission and
   no caller check, and it hands the intent's `message` to `ExternalChatRequestExecutor`, which sends
   it through `StandardChatManagerTool.sendMessageToAI`. Closed on the read side: that dispatch, every
   other tool delivery, and every subagent task prompt are stored as a delivered turn and dropped from
   the block. The exported receiver itself is unchanged.
2. The root rule file of the active workspace, which is read again on every review. A workspace the
   owner has just obtained therefore contributes its `AGENTS.md` as trusted user intent, and a file
   the agent itself wrote during the turn is picked up the same way.

An imported conversation is deliberately not counted here. Importing exists so the owner can carry
their own history over, so the user turns a file carries are the owner's own words and stay
authorization. A file the owner never wrote is the owner's decision to import, not a delivery the
app made.

Before this round the reviewer had no retained block at all: the same text reached it only through
the transcript, which the prompt labels untrusted evidence. The automatic review stop therefore
lost a reason to doubt these sources. No permission level changed for the `ALLOW` stop, which never
reaches a reviewer.

One more source needs no retained block to be trusted: the fast stop answers a call from a verdict
taken for an earlier batch, and that verdict names neither the tool nor the arguments it scored.

## Intent

Only the device owner's own words count as the owner's authorization, so the automatic review stop
keeps meaning "the reviewer decides", not "whoever spoke last decides". The stop the owner chose
stays authoritative and is not capped, narrowed, or overridden.

## Expected result

- The reviewer's trusted block carries only the messages the device owner actually wrote.
- The workspace rule file counts as authorization only for a workspace the owner vouched for, and
  only while that file still matches what the owner vouched for.
- A message delivered by another application, a tool, or the host reaches the reviewer as untrusted
  content instead. Done for every delivery that goes through the chat tool or a subagent turn.
- The rule file of a workspace with no decision reaches the reviewer as untrusted content instead.
- Under `ALLOW` nothing changes.
- A reused verdict answers only the action it was taken for, or the stop waits for the batch's own
  verdict, depending on how strict the owner wants the fast stop to be.

## Scope

1. [Message origin](1_MessageOrigin.md)
2. [Workspace trust](2_WorkspaceTrust.md)
3. [Score reuse](3_ScoreReuse.md)
