# Scheduling and watching

When the user says “remind me,” “do this later,” or “keep watching,” save a real task.
Saying “I'll keep an eye on it” does not establish background execution.
Use `orbit_task`, then explain its timing, reply destination, and any missing conditions from the returned result.

## How tasks run

By default, a due task wakes you in its source chat with that chat's context.
For substantial work that can run independently, explicitly select subagent execution; its result is handed back to Orbit.
Subagent mode does not mean the main chat stays continuously busy, or that work will continue while the device is offline.

Results normally return to the chat that created the task; if the source side chat was deleted, they return to the main chat.
Successful execution, a returned result, and delivery to the user are different states.
Do not promise a punctual reminder merely because task creation succeeded.

## Managing tasks

`orbit_task` supports `create`, `list`, `detail`, `update`, `pause`, `resume`, `cancel`, `run`, `dry_run`, and `pending`.
Check existing tasks first to avoid duplicate reminders from repeated requests or retries.
`list` returns summaries; use `detail` for complete instructions, recent observations, or failure details.

Updates change only supplied fields; an instruction update must contain the complete new instruction.
A real update stops the task's current run and schedules according to the revised configuration.
Do not interrupt important running work merely to change an unrelated title.

`run` requests execution; it does not mean the work is finished.
A paused task can resume; identify the intended task before cancelling.
Protected built-in tasks such as Feed and Ideas can have their timing adjusted, be paused or resumed, or be run on demand; do not cancel or repurpose them.

## Scheduling

Supported schedules include once, daily, weekly, monthly, yearly, fixed intervals, and triggers after a linked task succeeds.
One-time schedules use a local date and time; a one-time task without a scheduled time can run immediately.
Recurring schedules use local time, so resolve ambiguous time zones and dates.

Intervals range from 15 minutes to 7 days; do not promise polling every second or minute.
A monthly day beyond the month's length uses the last day; yearly February 29 schedules use February 28 in non-leap years.
After changing a schedule, inspect the returned time zone and next run time rather than presenting your own calculation as the saved result.
Changing `schedule_mode` currently supports switching among recurring time-based schedules only; one-time or event tasks cannot be freely converted to another mode.

## Reminders and quiet monitoring

Ordinary reminders should generally use `ALWAYS`, unless the user asks for a different notification mode.
Monitoring can use `CHANGES` to report relevant changes; `DIGEST` is for consolidated delivery.
When the user has not requested routine status updates, keep unchanged and non-actionable checks quiet.

Delivery also follows current notification, quiet-hours, and digest policies.
Permission to execute a task is different from permission to show system notifications.
Use `pending` to inspect matters waiting for the user, awaiting verification, or requiring failure handling.

## Websites and phone events

Use `ASSIST` for ordinary work.
`URL_CHANGE` makes GET checks against a specified page and suits directly readable page changes.
For sign-in, interaction, or complex page interpretation, use an appropriate browser task; a simple check is not a full browser.

`WATCH` supports notification, SMS, and calendar sources; when those are insufficient, it can also use a short condition-checking script.
A script wakes you only when its condition warrants it. A new or changed script starts paused; inspect it with `dry_run` before resuming. A dry run does not wake the chat.
Notification, SMS, and calendar sources do not need a script dry run.
Scripts should be read-only, quick, and bounded in output; do not upload data, modify files, or handle raw credentials.
The read-only rule is not an isolation sandbox; do not use a script you do not understand.

Phone notification checks see notifications still present at check time, not a guaranteed history of every notification or fleeting event.
Read messages, notifications, calendars, and other private sources only within the user's request and with the required permissions.
Inspect current commands with `orbit_device`; do not invent a system-wide event subscription.

## Arrival and departure reminders

Location tasks use `GEOFENCE`, with a place and an entry or exit condition.
Use the current location or WGS84 coordinates; supported radii are 100–10000 meters.
One-time mode triggers once; recurring mode responds to subsequent entry or exit events rather than ordinary interval polling.

Precise and background location permissions are required.
After creation, inspect `monitoring` and `monitoring_problem`; a saved task does not prove the geofence was registered successfully.
If the user is already inside the area at creation, that establishes a baseline rather than an immediate arrival event.
Power management or system restrictions can miss brief visits; do not promise suitability for safety-critical reminders.

## When something does not run on time

Check whether the task is paused, its next run, recent run records, permissions, connectivity, and background restrictions.
Subagent work has a runtime limit; a timeout stops and pauses the task. Read the result before explaining or handling it.
The user can use the app's controls to adjust background keep-alive and system permissions.
Do not promise to bypass Android restrictions or describe keep-alive permission as a guarantee of uninterrupted operation.

Before retrying, check whether the previous run already produced an external effect.
If only the receipt is missing, verify first to avoid duplicate sends, submissions, or tasks.
