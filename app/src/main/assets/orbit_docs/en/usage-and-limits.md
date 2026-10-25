# Usage and allowances

Orbit's allowance is a token limit tracked locally by the app to keep you from continuously spending the user's model usage.
You do not sell a service or offer Orbit plans, subscriptions or paid top-ups.
The model provider's balance, prices and actual bill are separate. Changing a local allowance neither buys tokens nor changes the provider's charges.

## Two shared allowances

All Orbits share a weekly allowance and an extra allowance. Ordinary chats in the main app do not count against this Orbit allowance.
The weekly allowance is spent first, followed by the extra allowance.

| Item | Default and range |
|---|---|
| Weekly allowance | Defaults to 1,000,000,000 tokens; minimum 100,000,000. |
| Extra allowance | Defaults to 1,000,000,000 tokens; may be set to 0. |
| Maximum for either allowance | 1,000,000,000,000 tokens each. |

The weekly allowance resets on Monday at 00:00 in the phone's current time zone.
By default, extra usage does not reset with the week; what has been spent stays counted until the user refills it.
The user can instead enable weekly reset for the extra allowance, refilling it with the new week.
Switching to weekly reset does not immediately erase extra usage already counted.

Usage counts model input and output, including cached input. When the service does not supply complete usage, the app estimates missing parts.
It is neither a message count nor an exact monetary bill.
A request is counted after it finishes, so do not describe the local limit as precisely cutting off a request already in progress.

## When an allowance runs out

When the weekly allowance runs out and extra usage is still available, the app sends you a hidden reminder once the main chat is quiet, once per week.
Check the user's preferences and chat history before deciding whether to tell them briefly. If they do not want allowance messages or the timing is wrong, stay quiet as instructed by the reminder.
If you tell them, explain how much extra remains, when the week resets, and that they can adjust it at the top of your settings.
Do not keep reminding them that week; defer work that can wait.

When both allowances run out, subsequent Orbit model requests are blocked before sending, and background work waits.
The user can wait for the weekly reset, raise an allowance or refill the extra allowance in settings. Ordinary use of the main app is not subject to this Orbit limit.
While paused, do not promise you can still make model requests to ask for a higher allowance.

If you received the weekly exhaustion reminder, the app schedules a hidden recovery message to that same Orbit after the new week's allowance becomes available.
Do not treat it as something the user just said or mention internal hidden messages to them.
If you previously told the user about exhaustion or pausing, you may briefly explain that normal operation has resumed at a suitable time. Otherwise follow their preferences, stay quiet, and resume work deferred to save tokens.
Background delivery depends on the phone's running state; do not promise an immediate notification at midnight on Monday.

## Adjusting allowances

At the top of Orbit settings, the user can view allowances, change limits and the extra reset mode, or manually refill the extra allowance.
You can first use `orbit_settings action=get` for current values, ranges and shared scope, then request changes with a `changes` JSON object in `request_change`:

| Parameter | Meaning |
|---|---|
| `weekly_limit` | Weekly limit as a raw integer token count. |
| `extra_limit` | Extra limit as a raw integer token count. |
| `extra_weekly_reset` | `true` resets with the week; `false` keeps extra usage until manually refilled. |
| `refill_extra` | Accepts only `true`; clears extra usage to restore the current extra limit. |

Do not pass millions, money or display units as parameter values.
You may request several changes together. The card lists old and new values; end the turn after placing it and wait for approval.
After approval, the app checks and applies each item, then sends a hidden result showing successes and skipped items. Follow the actual result.
Do not edit preference files to bypass approval.

Refilling extra usage does not clear usage history or reset the weekly usage already counted.
There is no setting for you to reset weekly usage at will.

## Usage

The user can open Usage from the allowance card to see input, output, call counts, usage by purpose, daily trends and activity.
Model work such as chats, subagents, background tasks, memory review and context compaction can all consume the allowance.
Work performed by waking the main chat is counted under Chat; do not infer a category solely from a task's name.
Use current app pages and tool results for figures, rather than guessing remaining usage from an old conversation or deriving a provider balance or refund from local statistics.
