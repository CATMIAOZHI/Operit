---
name: trip-planning
description: Build a feasible trip across routes, stays, transport and daily plans; verify key facts and keep suggestions, choices and bookings distinct. The user handles payment.
---
# Trip Planning

## When to use this skill

- Use it when dates, routes, overnight bases or several days need planning. Keep entry, transit and airport questions about an active trip here.
- For one bounded flight, stay or transfer, load its booking skill directly. One missing detail does not require a new planning intake.
- Respect an instruction to finish the plan first. Checking prices does not authorize booking.

## Read the relevant guide

The **Skill directory** returned when this skill loads is its directory. Use an available file-reading tool to read these files beneath it. Do not guess a device path or treat a link as an already-read guide.

| Read before | File |
|---|---|
| Starting a substantial plan or gathering context | `references/planning-kickoff.md` |
| Exploring dates, airports, connections or city order | `references/flight-itinerary-discovery.md` |
| Building days, revising, tracking work or delivering a file | `references/operational-itinerary.md` |
| Advising on entry, transit, airports or transfers | `references/travel-fact-verification.md` |
| Passing an item to a booking skill for live offers or reservations | `references/booking-handoff.md` |

## Build a workable trip

- Start with the request, relevant trip files and confirmations. Establish purpose, dates and flexibility, travelers, budget and pace. Ask about the gap that most affects the plan.
- When choosing dates, load `scheduling` and check the whole candidate span on the phone calendar, including all-day travel. Use commands actually supported by `orbit_device`. Keep exact dates the user supplied and mention conflicts once. Missing calendar access is not proof of availability; one person's calendar says nothing about another traveler's.
- Maintain one current plan per trip. Treat confirmed bookings as fixed, including user-reported bookings labeled as not independently verified. Never silently replace, duplicate or downgrade them.
- Use local times and explicit arrival dates. Check exact locations and door-to-door routes using reliable map sites or local operators. Do not invent journey or connection times.
- Compare the whole trip's cost and duration. A cheaper flight can require another hotel night. Within allowed flexibility, consider rail, different arrival and departure cities, fewer hotel moves, and weather or closure backups.
- Verify opening days, reservation rules, last services and border requirements with the authority responsible. Keep links and check dates. Reviews and social posts provide ideas, not current operating rules.

## Coordinate the bookings

Find skills with `orbit_skills`, then load their returned `package_name` with `use_package`.

- Load `flight-booking` for flights, `hotel-booking` for stays and `trip-transport` for trains, cars, ferries, buses or transfers.
- Load `scheduling` for calendar work and reminders.
- For attractions or activities, check the operator first and compare a suitable reseller. Submit a reservation only when requested.

Carry choices, dates, party, limits, budget and sources forward. Write results back to the same plan. A listing with availability is not a confirmed order.

Use `orbit_browser_handoff` for passwords, verification challenges and payment, then end the turn. Reinspect the page when control returns. Check uncertain order outcomes before retrying. Orbit cannot pay.

## Save and deliver

Keep a trip file under `files/` with `orbit_file`; read it before revising. Keep trips separate and temporary travel facts out of lasting personal preferences. Use `orbit_tracking` for concrete commitments; it does not monitor automatically.

For requested reminders or checks, read `docs/scheduling-and-watching.md`, create an `orbit_task`, and verify its timezone and next run. Use photos or maps only when useful and verified. Deliver files with `orbit_file action=send`; state any important work still open.
