# Workspace trust

## Current behavior

`PermissionReviewRetainedInstructionsReader.read` calls
`WorkspaceRuleFileReader.readWorkspaceRootRuleFile` on every review, so the rule file of the active
workspace is whatever is on disk at that moment. Nothing records whether the owner ever vouched for
that workspace, and nothing pins the file the owner would have vouched for.

Two consequences follow. A workspace the owner has just obtained speaks with the owner's authority
from the first review. And a rule file written during the same turn, which the workspace branch of
automatic review writes without a review of its own, is read back as the owner's authorization.

## Reference behavior

The reference implementation answers both with a trust decision per directory, and keeps the project
document out of the session until that decision exists.

- A directory with no decision is asked about on entry: "Do you trust the contents of this
  directory? Working with untrusted contents comes with higher risk of prompt injection. Trusting
  the directory allows project-local config, hooks, and exec policies to load." The answer is stored
  per project path.
- `load_project_instructions` returns before reading any `AGENTS.md` when the project is explicitly
  untrusted, so that project contributes no document at all: neither as agent instructions nor as
  authorization evidence.
- A directory with no decision yet also defaults to the read-only permission profile, so a fresh
  copy runs in the most restrictive posture until the owner decides.
- `AgentsMdManager` loads the project documents once and caches them, keyed by the environment
  selection and the project trust level, and reloads only when that key changes.

## Approach

Give a workspace the decision it currently lacks, and let the decision govern the rule file.

- Record one decision per workspace path: vouched for, or not the owner's. Ask for it the first time
  a workspace would contribute its rule file, and let the owner change it later.
- Vouched for: the rule file stays trusted evidence, exactly as today.
- Not the owner's: do not read the rule file as evidence at all. Leaving it out entirely is the
  reference behavior and is simpler to reason about than keeping it as untrusted context.
- No decision yet: the rule file is context, not authorization, until the owner answers.
- Pin the decision to the rule file's content hash as well as the path, so a rewritten rule file
  needs a fresh answer. The reference gets this from never re-reading; we read on every review, so
  the hash is what preserves the property.
- Read the rule file once per workspace session, like the reference cache, so a file written during
  the turn cannot become authorization inside that turn.

The decision belongs to the owner and is visible, in the same spirit as the reference question. Do
not derive it from a path pattern, a remote URL, or a modification time; none of them separates the
owner's own workspace from one that merely arrived.

## Validation

- Unit tests: a workspace with no decision contributes no rule file, a vouched-for workspace
  contributes it, and a rule file whose content changed loses the decision.
- Unit tests: a rule file written during the turn does not appear in that turn's evidence.
- On the device: open a workspace that was never used before and confirm the rule file is not read
  as the owner's authorization until the owner answers.
