# Subagent v2 implementation and validation

Reference: local `codex`, OpenAI `main` commit
`c55db1b8d9b876230f9fb22be78d77fc0b526d0c` (2026-09-09).

## Required behavior

- Retain the current synchronous `task` tool as v1, including reading companion's
  internal callers. The settings selector defaults to v2.
- Implement Codex's separate v2 collaboration surface: `spawn_agent`,
  `send_message`, `followup_task`, `interrupt_agent`, `list_agents`, `wait_agent`.
- Canonical paths start at `/root`; relative names resolve beneath the caller.
  Cross-branch addressing uses canonical paths, scoped to one root conversation.
- Spawn is asynchronous. Agents retain identity and conversation across turns.
  Messages steer active turns at safe boundaries; queue-only messages do not wake
  idle agents. Follow-up tasks wake idle agents and cannot target root.
- Interrupt ends the active turn without deleting the agent. Wait wakes on inbox
  activity or user steering and respects a bounded timeout. Completion messages
  are delivered to the parent, with sender and recipient identity.
- Context forking supports all, none and a positive turn count. Preserve parent
  model settings when inherited; expose explicit model selection consistently
  with Operit's configured models.
- Persist agent identity and mailbox state and isolate separate root chats.
  Enforce concurrency and nesting limits without blocking parent tool calls.
- UI must distinguish v1 task runs from v2 agents and retain usable conversation
  navigation, progress and cancellation.

## Execution plan

1. Update and inspect the reference; map Codex handlers, agent control,
   resolver, mailbox, lifecycle and context-fork semantics to Operit's runtime.
2. Implement v2 state/control and tools, then integrate prompt exposure,
   settings and management UI. Preserve v1 runtime and reading callers.
3. Compile and run focused lifecycle, mailbox, isolation, fork and exposure tests.
4. Independent read-only audit of final changes; fix findings and re-audit.
5. Build a debug APK and install with data preserved. An independent agent runs
   real-device validation using isolated test conversations and reports evidence
   for v2 collaboration, settings switching and v1 compatibility.

## Validation status

- Reference update: complete.
- Implementation: complete.
- Compilation and focused tests: final debug APK build passed; 100 focused JVM
  tests passed with zero failures or skips.
- Independent audit: CLEAN for runtime changes and opt-in live validation test.
- Independent real-device verification: PASS on the final debug candidate,
  SHA-256 `82086ACD7C5FB4637FF4EF51A640BA0B6C855B3E285522D97279C3DC65B490ED`.
  Verified fork 1/all without recursive delegation, asynchronous lifecycle,
  queue/followup/wait, interruption, process recovery with retained identity,
  result cards, real v1 task execution and settings restoration to v2.
- Live compaction instrumentation: PASS (`OK (1 test)`, 45.113 seconds).
  The checkpoint excluded the model-generated token; a later turn recalled it
  from the post-checkpoint answer with the same chat and run IDs. The disposable
  model configuration was removed and app data was preserved.
- Full Lint was not run; no commit, push or publication was performed.

Device tests must not uninstall or clear the user's app. Do not use Gradle
connected-test tasks that can uninstall it. Test APKs, if needed, are installed
by replacement and invoked with `am instrument`.

## Using the two versions

The Subagent settings screen selects the version for new conversations. v2 is
the default. A conversation keeps the version assigned when its tool surface is
first prepared; changing the setting does not change an existing agent tree.
Reading Companion's internal tasks always keep the v1 coordinator contract.

v1 exposes `task` and waits for one child task result. v2 exposes the six separate
collaboration tools. `send_message` queues input without waking an idle agent;
`followup_task` starts an idle agent and reuses its identity and transcript.
`interrupt_agent` stops the current turn without deleting that identity.
`wait_agent` reports mailbox activity, user steering or timeout; messages are
delivered through the model's actual turn inbox.

`fork_turns` accepts `all` (default), `none`, or a positive turn count. A full fork
inherits the running parent's prepared context and model selection unless an
explicit profile/model overrides it. `model` selects a configured model ID;
`reasoning_effort` overrides that agent's request parameter without editing the
user's model configuration. Model-provider support for the selected effort
remains required.

There are at most ten active child turns per root and eight child path levels.
Model-specific concurrency limits also apply. v2 rejects saturated model
admission instead of waiting while a parent may hold the needed model slot.

Agent identity, pending input, model selection and context checkpoints persist
in private application storage. Process restart marks formerly active agents
interrupted; follow-up can continue them. Successful mailbox handoff is
recoverable by its persisted message ID. Context compaction stays in the same
agent turn, preserving the inbox and cancellation ownership.

Management rows carry v1/v2 labels. v2 messages use dedicated event cards.
Parent-facing final results omit child thinking and internal tool markup;
the child conversation retains its complete execution transcript.

## Opt-in live compaction validation

`CollaborationCompactionLiveAndroidTest` is skipped unless `v2LiveValidation=true`.
It requires `v2ValidationChatId` to identify a newly created empty test conversation
and `v2ValidationModelId` to identify an existing usable model. It clones that
model into a disposable configuration to force a checkpoint, then removes only
that temporary configuration in `finally`. Existing model settings are unchanged.

The first response generates a token absent from the initial task and checkpoint.
The next turn must recall that token from the post-checkpoint transcript while
retaining the same chat and run identity. This verifies the cutoff boundary in
the real request path rather than only checking stored timestamps.

Build `:app:assembleDebugAndroidTest`, install the main and test APKs using
`adb install -r`, and invoke the single class with `am instrument`. Leave the
test package installed. Do not use `connectedAndroidTest` or clear app data.
