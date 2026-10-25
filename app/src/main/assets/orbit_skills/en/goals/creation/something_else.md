# Create a custom goal

## Keep the user's meaning

Use this guide when no other category fits. The goal might involve a home project, travel, a life event, community work, a personal decision, or something hard to name. Do not force it into a category that changes the user's intent. Separate the desired result from a possible activity or tool.

Use the context already available. If the opening is broad, ask one useful question about what the user wants to be different or what they hope to make happen. Keep the first response short. Do not start a long assessment, request data access, or demand a measurable target for exploration.

Ask one question at a time when its answer changes support. Relevant constraints may include timing, budget, location, access, available help, privacy, safety, or energy. Do not re-ask known facts. Ask why it matters or what they have tried only when it would help choose the next step. If the outcome and a useful criterion are already clear, skip intake.

## Save one useful record

When the user wants the goal saved, use `orbit_goal action=list` to check for an equivalent. Reuse an existing goal where appropriate. Otherwise create one with a `title` in the user's language and a `plan`. Verify it through `detail` using the returned `goal_id` before saying it is saved.

The plan should say `Category: something_else` and include the desired outcome, starting point, completion or review criterion, time horizon, check-in cadence, constraints, and initial steps. Mark unknown dates and decisions as undecided. A useful exploration or maintenance goal does not require a numerical target.

Keep enough context in the plan for later sessions. Do not create a separate goal-state file or store secrets. If new information points to a specialist category, read its guide and apply its safety boundaries before making stronger recommendations. Read the full plan before updating it, preserving valid constraints.

## Offer concrete help

Recommend one small milestone and explain what you can produce now: a comparison, checklist, short research note, project outline, draft, or preparation plan. Choose something that reduces uncertainty or makes the next action easier. Do not require agreement on a detailed plan before the goal can be saved.

For authorized execution, use `orbit_task` with `goal_id`. Reminders or recurring checks need a real purpose and the user's agreement on timing. A date in the plan alone schedules nothing. Use `orbit_goal_note action=log` for actual progress, commitments, blockers, and changes in direction.

## Sources and boundaries

Use only information relevant to this goal and within the user's privacy limits. User-provided facts are a complete starting point. Check that a tool or service really exists before offering it, and ask before accessing private sources.

This fallback does not weaken specialist safeguards. Health, financial, legal, employment, or relationship risks require the relevant guidance and, when appropriate, qualified support. Do not make strong domain claims from a vague goal. External messages, purchases, bookings, or changes follow Orbit's approval rules; the user handles payment.
