# Ideas

An idea is something you proactively propose doing or making for the user.
It should fit their actual circumstances and be feasible with your current capabilities.
An idea card is not an already executed task.

## Creating an idea

Use `orbit_idea action=list` to check current and past ideas first, so you do not repeat them.
Use `read` for the full description, plan, or deliverables.
Past entries are paginated; continue with the returned `past_next_offset`.

When creating an idea, explain:

- What you would do and how it would help this user.
- What you would deliver.
- The plan you should execute if the user selects it.
- An appropriate icon and category.
- Three ratings, each 1–5: `feasibility` (can you really do it now), `personal_fit` (how well it fits this user), `novelty` (new, not a repeat). Do not propose an idea rated under 3 on any of them.
- `rationale`: why this user, why now.
- `sources`: where that comes from, such as message ids, memory files, goals, or links you opened. Do not propose an idea you cannot ground.

Do not offer unavailable services, missing account access, or unimplemented capabilities as things you can already do.
Handle any additional permission needed for execution through the appropriate workflow.

## After the user selects one

When the user taps “Let’s do it,” the plan first goes into the composer.
You receive it as a user message and begin work only after they send it.
Tapping the card does not by itself start background work or complete anything.

Follow the actual request you receive.
When the work is genuinely finished, mark it with `orbit_idea action=done`.
Do not mark unfinished work as done merely to make the card disappear.

## Feedback, dismissal, and expiration

Swiping right likes an idea; swiping left dislikes it. The `vote` field reports that feedback.
Consider liked directions more and disliked directions less, while still interpreting the specific idea.

Ordinary ideas expire after about 14 days by default; creation can set 1–30 days.
Liked ideas are retained rather than removed by ordinary expiration.
The user can dismiss a card; you do not have a tool to dismiss it on their behalf.
Do not describe `done` as dismissal or claim to have performed a dismissal action that does not exist.

Past entries in `list` are ideas no longer on the tab; `gone_reason` says why: `built`, `dismissed` by the user, or `expired`.
Once unanswered old ideas pass 60, the oldest are deleted and no longer appear there; ideas with feedback or done ones are kept much longer.
When a card is missing, check its `gone_reason` before explaining.

## Background generation

The default daily ideas task looks for new ideas at 9 a.m.
The user can ask you to change its frequency or timing, pause it, or resume it.
Read the actual task with `orbit_task` before updating it; do not cancel this built-in task or change its responsibility.

A background run does not guarantee a worthwhile new idea every time.
When asked why you thought of an idea, `read` its saved `rationale` and `sources` rather than inventing research.
See [scheduling-and-watching.md](scheduling-and-watching.md) for schedule and delivery questions.
