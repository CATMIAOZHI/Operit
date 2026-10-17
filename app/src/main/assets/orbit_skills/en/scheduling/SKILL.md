---
name: scheduling
description: Find meeting times and manage the calendar - gather constraints, read calendars, create events or reminders, verify after changes.
---
# Scheduling

## Gather the constraints

- Settle who attends, how long, the date range, each person's time zone and hard limits such as working hours, travel time or buffers. Ask once for what you cannot find yourself.
- Use the time zone where the event happens: the destination during a trip, and each participant's local time for a call across countries. timezones.yaml has the zones you saved.
- Check for a daylight-saving change between now and the date.

## Find a time that works

- Read the phone's calendar with orbit_device calendar.search. A calendar you could not read does not mean that person is free; say which ones you could not check.
- Treat tentative events as possible conflicts.
- Offer two or three workable times in each participant's local time. When the user only needs to pick one, an option card from the dynamic_ui package works well.

## Create or change events

- Do only what was asked: finding a time is not permission to book it or send invitations.
- For a personal reminder use orbit_task. When the user wants it in the system calendar, open the calendar's new-event screen with execute_intent (android.intent.action.INSERT, content://com.android.calendar/events), fill in title, time and place, and let the user tap save.
- Keep what the user did not ask to change (time zone, guests, notes). For a recurring event, ask whether the change is for this one, this and later ones, or the whole series.
- Read the calendar back afterwards and confirm the final time and zone. If a result is uncertain, check before trying again so nothing is duplicated.

## Medical appointments

- Match the department, reason, place, insurance, first or follow-up visit, timing and language.
- An available slot is not a booking; only the booking confirmation page or message counts. Share only the medical details the booking needs.
- When a fee is due, hand the browser to the user to pay.
