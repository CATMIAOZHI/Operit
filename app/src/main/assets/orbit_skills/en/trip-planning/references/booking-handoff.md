# Move from planning to booking

Use this guide when a plan item needs live offers or booking. It is working guidance, not a form for the user.

## Carry the existing decision

- Preserve whether the user is planning or ready to book, including instructions to finish planning first. Identify the item and its proposed, selected or rejected state. Never revive a rejected option automatically.
- Carry only relevant facts: exact route or place, local dates and times, party and relevant children's ages, cabin or room, budget, bags, access needs, allowed flexibility and dependencies.
- Include source links, check times and clearly labeled estimates. For flights, retain each segment's airports, dates, times, carriers and flight numbers, plus limits on connections or separate tickets.
- Do not invent missing constraints. Exploration needs no names, document numbers or payment details. Obtain necessary private input only at the appropriate stage.

## Match the current offer

- Match the exact property, operator or service, location, date, party, variant, and age or access conditions.
- Match flights segment by segment: airports, local dates and times, carriers, flight numbers, order and cabin. Compare operating carriers when both sources name them. A flight number alone is insufficient.
- The same itinerary can have different fares. Verify current baggage, total price, ticket structure, expiry and refund or change rules. An earlier estimate or a cabin name does not establish these.
- Separate an exact match, an allowed difference, a material change and an option this seller does not carry. Ask about material changes rather than replacing a selection. One seller's absence does not prove universal unavailability.
- Where flexibility matters, compare verified standard and flexible offers. Distinguish cash refunds, credits, changes, penalties, fare differences and deadlines.

## Preserve planning intent

- During planning, prices and inventory inform the plan. Do not start checkout, collect identity details or end with a booking prompt. “Show flights” does not override “finish planning first.”
- Before booking several parts, show the current shape and unresolved dependencies. If dates depend on both transport and a stay, compare both first.
- For a needed choice, `orbit_create_options` can offer “Keep planning and book later” and “Start booking this flight.” Use its new token once. Use `dynamic_ui` for itinerary displays, not reply buttons.
- Load `flight-booking`, `hotel-booking` or `trip-transport` for current terms, submission and confirmation. Explicitly preserve planning-only scope for price research.
- Write evidence and seller results back to the same item without changing the user's choice. A different product starts as a new proposal.
- Hand passwords, challenges and payment to the user, then end the turn. Reinspect returned pages; check unknown outcomes before retrying. Record bookings only from real confirmation or clearly attributed user reports.
