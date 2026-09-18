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

Two sources the device owner never vouched for can enter that block.

1. A message another application delivered. `ExternalChatReceiver` is exported with no permission and
   no caller check, and it hands the intent's `message` to `ExternalChatRequestExecutor`, which sends
   it through `StandardChatManagerTool.sendMessageToAI`. The text is stored as an ordinary user turn
   and read back as trusted user intent.
2. The root rule file of the active workspace, which is read again on every review. A workspace the
   owner has just obtained therefore contributes its `AGENTS.md` as trusted user intent, and a file
   the agent itself wrote during the turn is picked up the same way.

Before this round the reviewer had no retained block at all: the same text reached it only through
the transcript, which the prompt labels untrusted evidence. The automatic review stop therefore
lost a reason to doubt both sources. No permission level changed for the `ALLOW` stop, which never
reaches a reviewer.

A third source needs no retained block to be trusted: the fast stop answers a call from a verdict
taken for an earlier batch, and that verdict names neither the tool nor the arguments it scored.

## Intent

Only the device owner's own words count as the owner's authorization, so the automatic review stop
keeps meaning "the reviewer decides", not "whoever spoke last decides". The stop the owner chose
stays authoritative and is not capped, narrowed, or overridden.

## Expected result

- The reviewer's trusted block carries only the messages the device owner actually wrote.
- The workspace rule file counts as authorization only for a workspace the owner vouched for, and
  only while that file still matches what the owner vouched for.
- A message delivered by another application, and the rule file of a workspace with no decision,
  reach the reviewer as untrusted content instead.
- Under `ALLOW` nothing changes.
- A reused verdict answers only the action it was taken for, or the stop waits for the batch's own
  verdict, depending on how strict the owner wants the fast stop to be.

## Scope

1. [Message origin](1_MessageOrigin.md)
2. [Workspace trust](2_WorkspaceTrust.md)
3. [Score reuse](3_ScoreReuse.md)
