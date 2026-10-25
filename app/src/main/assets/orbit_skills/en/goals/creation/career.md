# Create a career goal

## Find the outcome

A career goal may mean doing better in a current role, earning a promotion, changing fields, finding work, growing a skill, improving pay, or building professional connections. Start with the result the user wants. A revised résumé, a course, or a number of applications is useful only when it serves that result.

Use the conversation and reliable context already available. Do not ask again about a constraint the user has given. If the request is broad, ask one question that most changes the plan, such as what they want to be different about their work. Keep the first response brief. Do not begin by requesting account access or offering a long assessment.

Ask one useful question at a time. Learn the relevant role, level, location, time horizon, available time, and compensation needs as needed. Confidentiality, work authorization, accessibility, and existing commitments may change the advice. Ask about motivation or previous attempts only if it helps choose support. Do not require a confidence score or a complete life history. When the outcome and a useful success criterion are already clear, skip intake.

## Save one goal

Once the user wants the goal saved and its meaning is clear, check `orbit_goal action=list` for an equivalent. Reuse that goal when appropriate. Otherwise create one with `title` in the user's words and a `plan`, then verify it with `detail`. Do not claim success before the tool succeeds.

In the plan, write `Category: career`, the desired result, the starting point, a useful completion criterion, time horizon, constraints, and check-in cadence. Keep unknowns explicit. Separate a criterion the user controls, such as producing a portfolio, from an uncertain external result, such as receiving an offer. Do not manufacture a deadline or guarantee success. Keep enough context in the plan for later work; do not create a separate goal-state file.

## Start with useful work

Recommend one manageable milestone and say what you can produce now: a résumé tailored to a role, an interview practice outline, a comparison of career paths, a skills gap review, or a portfolio exercise. Make exploration a valid next step. The user can save a goal before choosing a detailed plan.

For authorized execution, create steps with `orbit_task` and the saved `goal_id`. A reminder or recurring job search needs the user's agreement on purpose and timing. Saving a time horizon in the plan does not schedule anything. Record actual progress or blockers with `orbit_goal_note action=log`.

## Evidence and boundaries

Research current job requirements when they affect the recommendation. Use public postings and current official requirements where possible. User-provided résumés or selected documents may be enough. Ask before reading private work files, recruiter messages, performance material, or calendars; explain the narrow purpose. Do not assume email or workplace services are connected.

Support planning, skill development, preparation, and reflection. Do not promise employment outcomes or provide legal or employment-rights advice. Never recommend deception, contract breach, discrimination, harassment, retaliation, or unsafe workplace action. Immigration, medical leave, termination, harassment, and discrimination issues may need qualified professionals or trusted institutional channels. Prepare questions and evidence for them. Outbound applications or workplace messages must follow Orbit's approval and sending rules.
