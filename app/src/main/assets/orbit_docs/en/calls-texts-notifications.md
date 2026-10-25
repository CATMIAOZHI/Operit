# Calls, Texts, and Notifications

At the user's request, you can read SMS and notifications on this Android phone, arrange change monitoring, and notify the user through the app.
Reading private messages requires an explicit user request and the relevant permission. Report only task-related content; do not save numbers or messages in memory unless asked.

## Calls and sending texts

You cannot answer calls for the user, take part in phone conversations, or send SMS directly.
The SMS commands in `orbit_device` read messages; they offer no dialing or sending command.
With the `daily_life` package imported and enabled, `make_phone_call` opens the dialer and `send_message` opens the SMS editor with the number and text filled in; the user still taps call or send.
Without that package, you can draft a text, look up a contact's number with permission, and ask the user to take the action themselves.
Opening one of those screens, finding a number, drafting content, or saving a reminder does not mean a call was placed or a text was sent.

When the user says “message me a reminder later,” normally create an `orbit_task`. Its result reaches chat and may notify the user according to app settings; this is not an actual SMS.
The WeChat channel also connects only the user and their dedicated bot. It cannot message other contacts; see [weixin.md](weixin.md).

## Read SMS

First use `orbit_device action=describe` to check available commands, parameters, and authorization. Then use `action=invoke`, with the command in `command` and a JSON object in `params`.

- `sms.search`: search message text or sender/recipient numbers. Optional `query` allows up to 80 characters; `limit` is 1–30, default 10.
- When `has_more=true`, pass `next_before` unchanged as the next call's `params.before` to read older messages.
- Search shows up to 1000 characters of each body. If `cut=true`, use `sms.read` with the message `id` to read its full content.
- `sms.read` returns up to 20000 characters per call. If `next_start_char` is present, pass it as the next call's `params.start_char` to continue.

Results come from the phone's current SMS database, with times in the phone's local time zone.
Without permission, a foreground invocation may open a system permission prompt. You cannot approve for a user who is outside the app.
After a denial, do not retry or find a workaround. If the user later needs access, you can place a `permission=sms` card.

## Read other apps' notifications

When available in the current tools, use `get_notifications` to inspect phone notifications. `limit` controls the number returned; `include_ongoing` controls whether ongoing notifications are included, defaulting to false.
This needs notification reading access. If missing, use `orbit_permission_card permission=notification_read`.
Reading notifications does not provide other apps' chat history, accounts, or every private message.
An app may hide content, the user may clear a notification, or only part of a message may be visible. An empty result does not prove no message arrived.

## Watch phone messages and events

When the user asks to hear about a kind of message, use `orbit_task action=create` with `kind=WATCH`, `schedule_mode=INTERVAL`, and `notify_policy=CHANGES`.
Set `title` to the task name, explain the relevant changes and reporting behavior in `instruction`, and use at least 15 for `interval_minutes`.
Check existing tasks first to avoid duplicates.

`watch` accepts:

- `notifications`, or `notifications:package,package` to check only named apps.
- `sms`, or `sms:sender` to inspect recent inbox messages, optionally filtering by number or sender text.
- `calendar`, or `calendar:days` to inspect the next 1–30 days of calendar events, default 7. Calendar permission is required.

These are periodic reads and change checks, not real-time triggers for every incoming message.
Notification watches inspect notifications still present at check time, excluding this app and ongoing notifications. SMS and notification watches each take at most 50 items, with up to 500 characters per item.
Fleeting notifications and messages outside these bounds can be missed. Do not promise a complete record.
After a change wakes you, still decide whether it matches the user's request. Do not copy unrelated private content.
See [scheduling-and-watching.md](scheduling-and-watching.md) for location arrival/departure and script conditions. Do not assume arbitrary phone event subscriptions exist.

## Notify the user and describe delivery

Task reminders and background results first return to their source chat for you to tell the user. If that chat was deleted, they return to the main chat.
After you speak proactively, the app marks the chat unread. When the app is not open and system notifications are allowed, it attempts to show a notification that opens the chat.
A saved task, a completed task, a result in chat, a displayed notification, and the user having seen it are different states.
Tool success alone does not prove the user received or read a notification.

“Notifications” in Orbit settings offers a night quiet-hours switch (22:00–08:00) and reporting intervals of immediate, 15 minutes, or 60 minutes.
Task wakes and result delivery also follow quiet hours, intervals, and daily digest policy. A task wake explicitly requested with “run now” does not wait for these arrangements.
Use `notify_policy=ALWAYS` for every-run reports, `CHANGES` for useful changes, and `DIGEST` for daily roundups.
These policies do not guarantee bypassing system Do Not Disturb, notification channel settings, battery restrictions, force-stop, lost connectivity, or a powered-off phone.

If the user explicitly needs an immediate system notification and the tool is available, use `send_notification`, with required `message` and optional `title`.
It does not pass through Orbit's task reporting queue or quiet hours. Do not use it to bypass the user's notification preferences or replace a saved future reminder.
A successful result confirms the call completed, not that the user saw the notification.

## Permission cards

Relevant `permission` values for `orbit_permission_card` are `sms` (read texts), `notification_read` (read other apps' notifications), `notify` (post system notifications), `calendar` (read calendar), and `keep_alive` (background keep-alive and battery-optimization exemption).
Reading and posting notifications need different access. Permission to run a task does not mean permission to display system notifications.
Offer a card only when the user's task needs it, explain why, place at most one per turn, and end that turn with one short line.
Already enabled permissions produce no card. A placed card does not mean permission was granted.
After the user opens settings through a card, only a verified off-to-on change sends a hidden note to the original chat. Denial or no change is not consent.
Do not repeatedly offer cards the user ignored or declined. Continue according to actual authorization results.
