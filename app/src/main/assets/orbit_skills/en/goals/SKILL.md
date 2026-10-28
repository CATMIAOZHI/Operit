---
name: goals
description: Help users create and pursue personal goals; load the relevant creation guide or ongoing scaffold before helping.
---
# Goals

Help the user define a meaningful outcome and make realistic progress. Follow their preferences for support, privacy, and check-ins. Use evidence, ask only what changes the next step, and respect rest or a change of direction.

## Read the right guide

The **Skill directory** returned when loading this skill is its actual directory. Use an available file-reading tool to read the paths below relative to that directory. The category guides are not automatically injected.

- Before creating a goal, choose its category and read `creation/<category>.md`.
- For an existing goal, read `guides/<category>/scaffold.md`. Continue from its current state instead of repeating intake.
- Categories: `career`, `health`, `interests`, `money`, `productivity`, `relationships`, `something_else`. If none fits, use `something_else`.

Read the existing goal with `orbit_goal action=detail`. Find its category in the plan, or infer it from the user's stated outcome. When uncertain, use `something_else`. For mixed goals, choose the main outcome and apply every relevant safety boundary.

## Save and act truthfully

- Check `orbit_goal action=list` for an existing equivalent before creating. Create in the user's chat with `title` and `plan`; verify using the returned `goal_id` and `detail`.
- Put the category, outcome, completion criteria, time horizon, check-in cadence, constraints, and next steps in `plan`. An unknown date or undecided cadence can stay undecided. These are plan text, not extra tool fields. Titles allow 80 characters; plans allow 8000.
- Create executable steps with `orbit_task`, passing `goal_id`. Goal creation alone starts no work. Check existing tasks first. Schedule reminders or recurring checks only when requested or agreed.
- Record real progress, commitments, blockers, and changed boundaries with `orbit_goal_note action=log`. Read notes with `list`/`read`. Only the background briefing process writes `brief`; never promise an immediate briefing.
- Progress comes from linked task states. Distinguish scheduled, running, done, waiting, and failed. Read full details when previews are insufficient.

Read the full plan before replacing it with `update`. Only the user's chat can change goals; background runs read them. Check conflicting commitments. At the time horizon, discuss achievement, extension, or reduced scope. Log achievement; goals have no `complete`. Never cancel to mark completion: cancellation cannot be resumed. Pause as requested; inspect linked tasks when resuming.

Use `orbit_tracking` for concrete matters you are following, such as an awaited reply. It also needs a separate task for periodic checks.

Use only relevant, authorized sources. Never assume account connections or health integrations. External actions follow Orbit's approval and sending rules; payments stay with the user. Background execution depends on Android, permissions, and connectivity.
