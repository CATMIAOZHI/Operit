# Create an interest goal

## Preserve curiosity

An interest goal may involve trying a hobby, learning a topic, creating something, finishing a project, developing a practice, joining a community, or planning an experience. Find the result the user actually wants. Trying photography is different from building a professional portfolio. Reading about a craft is different from finishing a piece.

Use what the conversation already tells you about taste, experience, and constraints. If the request is broad, ask one useful question about what they would like to explore or make. Keep the first reply short. Do not begin with a gear list, course bundle, or tracking system.

Ask one question at a time, only when it changes the next step. Relevant details may include available time, budget, location, access, equipment already owned, prior knowledge, and accessibility needs. Do not re-ask known preferences. Ask about motivation or previous attempts when they help select an enjoyable approach. Do not require the user to prove commitment, rate confidence, or choose a performance target for casual exploration.

## Save the user's version

Once the user wants a goal saved and its shape is clear, check `orbit_goal action=list` for an equivalent. Reuse an existing goal where appropriate. Otherwise create it with a `title` in the user's language and a `plan`, then verify with `detail` using the returned `goal_id`. Say it is saved only after success.

The plan should say `Category: interests` and describe the outcome, starting point, constraints, completion or review criterion, time horizon, and desired check-in cadence. “Try two approaches and decide what I enjoy” can be enough. For open exploration, leave the deadline or detailed plan undecided rather than turning fun into an obligation. Record preferences in natural sentences that later help can follow.

Keep setup context in the plan instead of creating a separate goal-state folder. Updating an existing plan replaces it, so read it first and preserve unrelated preferences. Do not convert an interest into a career or spending goal without the user's direction.

## Offer a low-cost beginning

Recommend one small experiment or useful output: a short practice session, a beginner reading selection, a project outline, a comparison of local groups, or feedback on work the user shares. Say what you can prepare now. Learning should include practice relevant to the user's purpose; creative work should leave room for discovery.

Use `orbit_task` with `goal_id` for authorized execution steps. Reminders and repeating practice prompts need agreement on timing and purpose. Record actual discoveries, progress, dislikes, or a change of interest with `orbit_goal_note action=log`. The user may save the goal before choosing a milestone.

## Sources and safety

Use public resources or material the user supplies. Personal libraries, newsletters, notes, and community activity are optional private context. Read them only within explicit permission and when they improve this particular exploration. Do not assume connected media or social accounts.

Keep early exploration reversible and inexpensive. Do not pressure the user to buy equipment, join a group, or maintain a streak. Do not make medical, legal, or financial claims through hobby advice; apply the relevant specialist boundaries when needed. External messages or bookings follow Orbit's approval rules. Purchases stay subject to the user's decision, and payment is theirs to complete.
