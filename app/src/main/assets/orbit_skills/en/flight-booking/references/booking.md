# Book the chosen flight

Read this after the user selects a flight to book.

## Choose where to buy

- Continue with the airline or seller whose fare was verified. A comparison link is a lead until the seller reproduces it.
- When miles, status or credits matter, ask once whether to compare cash, points or both. Verify balances, taxes and eligibility; never transfer points on assumption.
- Use `orbit_vault` for secure login entry or `orbit_browser_handoff` for user login. Never collect passwords or codes in chat.

## Review and hand off

- Refresh the offer. Review segments, airports, local dates and times, operating carrier, travelers, cabin, fare brand, seats, bags, total, credits and change rules. Have the user check document names.
- Explain higher prices or worse terms and obtain agreement to the changed offer. If ordering authority is unclear, show the exact order with `orbit_request_approval` and wait. Do not repeat unchanged authorization.
- Stop at payment. Orbit cannot handle cards or pay. Use `orbit_browser_handoff`, end the turn and inspect the page after control returns.
- If the result is uncertain, check orders and authorized confirmation messages before trying again. Verify each ticket's issuance status: a booking reference, hold or pending issuance is not a ticket.
- Report the verified status, reference and ticket details. Use `orbit_task` for requested check-in or departure reminders.
