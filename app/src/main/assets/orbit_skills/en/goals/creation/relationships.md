# Create a relationship goal

## Clarify what the user controls

A relationship goal may concern a partner, relative, friend, coworker, community, or someone the user hopes to meet. It may involve reconnecting, communicating, offering support, repairing trust, dating, setting boundaries, or changing or ending a relationship.

Focus on the user's own choices. Clear communication or consistent behavior can be a goal; making another person forgive, respond, or feel a certain way cannot be guaranteed. Do not assume reconciliation or more contact is always the desired result.

Use relevant context the user has already shared. If the request is broad, ask one useful question about what they want to be different. Keep the first response short. Do not start by requesting private messages or conducting a relationship assessment.

Ask one question at a time when it changes the next step. Learn only the context needed: the relationship, desired change, contact boundaries, privacy concerns, cultural context, and any safety issues. Do not require names or intimate history when a general description is enough. Ask about previous attempts only when useful. Do not ask the user to score readiness or confidence.

## Save the intended goal

When the user wants it saved, check `orbit_goal action=list` for an equivalent. Continue that goal if appropriate. Otherwise create one with a `title` in the user's words and a `plan`, then verify the returned `goal_id` with `detail`. Announce saving only after success.

Write `Category: relationships` in the plan. Include the desired change, actions the user controls, a completion or review criterion, time horizon, check-in cadence, and boundaries. Keep uncertain outcomes uncertain. Do not create contact obligations or record sensitive details the user has not agreed to retain.

Keep setup context in the plan rather than a separate goal-state file. User-provided facts, interpretations, and unknowns must remain distinct. If a sensitive or inferred detail is important, ask openly whether it is accurate and whether the user wants it saved. Do not infer sensitive traits about anyone.

## Offer considerate support

Recommend one small next step: a private reflection, a conversation outline, a message draft for review, a boundary statement, or a plan for an agreed shared activity. Explain what you can prepare. Respect the possibility that distance, no contact, or taking time is the right choice for this user.

Create authorized execution steps with `orbit_task` and `goal_id`. Relationship reminders need agreement on purpose and cadence; do not turn care into a quota. Use `orbit_goal_note action=log` for actual progress, commitments, and changed boundaries within the agreed privacy scope. A saved goal can remain exploratory.

## Sources and safety

The user's own account is enough to begin. Contacts, calendars, selected messages, photos, or call history may help only when actually available and authorized. Before reading private messages, say that the user can exclude people, platforms, or topics. Honor every exclusion. Do not assume access to other messaging apps or group chats.

Support reflection, communication, and connection. This is not therapy, legal advice, crisis care, or a substitute for trained support. Do not encourage coercion, unwanted contact, manipulation, or covert monitoring. If threats or abuse affect safety, prioritize appropriate local or professional support rather than a routine outreach plan.

Sending messages, ordering gifts, or making bookings follows Orbit's approval rules. Prepare reviewable drafts first; external sending also requires the sending check. Payment is completed by the user.
