# Compare flights

Read this for flight searches. Expand into a wider trip only when the user wants that help.

## Establish the search

- Establish origin, destination, dates, travelers, cabin and allowed flexibility. When unstated, search with a stated assumption such as one adult flying one way. Never invent children's ages, accessibility needs or permission to exceed a budget.
- Use exact dates as given. Resolve “next Friday” in the origin's timezone and write the actual date. Check an authorized calendar only when relevant; an unread calendar does not establish that the dates are free.
- Price children by age on the travel dates. If a birthday crosses a fare boundary during the trip, verify each affected leg. Ask whether an infant under two needs a seat. Ask once for missing ages rather than searching children as adults.
- Apply stated preferences for airlines, airports, seats, bags and connections. One past trip is a clue, not a permanent preference. Do not widen dates, airports or cabin without permission.
- When loyalty status, miles or credits could change the choice, verify eligibility, expiry and the actual balance. A membership or a held credit card does not establish every fare benefit. Keep membership numbers out of the reply.

## Search websites

- Use web search to find the airline and suitable sellers, then search matching conditions in the browser. Compare airline sites with regional sellers and sources such as Google Flights or Skyscanner. Orbit has no dedicated flight connector.
- Cover the airports, dates and cabin the user allows. Start with promising sources and avoid repeating identical searches. An airline's own site may have member fares, low-cost inventory or award availability absent elsewhere.
- For an exact flight number, include only offers carrying that number. A timetable proves a scheduled service, not available seats. For a codeshare, check both the marketed number and operating airline; do not substitute a different service on the same route.
- If a requested or expected airline is missing, check its site or make a targeted search. An empty result does not prove it does not fly. Explain coverage gaps and continue with other authorized public sources when appropriate.
- A browser subagent, when available, needs the complete brief: airports and allowed alternatives, dates, travelers and ages, cabin, bags, connection limits, and “research only; do not order.” Request complete itineraries, total price, fare terms, seller links and the time checked.
- An advertisement or “Buy now” button is not authorization. Comparing fares does not authorize creating orders or using unrelated accounts. Use `orbit_browser_handoff` when the user must enter a password, complete verification or pass a CAPTCHA.

## Verify with the seller

- Search snippets and comparison prices are leads. Follow each promising result to its airline or seller and confirm every segment, all passengers and the full payable total in one currency before calling it bookable.
- Label a price that cannot be reproduced as unverified. Report errors, sold-out fares or redirects to different dates. Do not replace the quoted service with another and claim to have verified the original.
- Compare complete trips. Keep one-way and round-trip totals distinct. A return quote includes both directions; never label the outbound half as the whole price. Open-jaw and multi-city offers must include every segment.
- Check cabin on every leg. A business-class offer can contain an economy segment; label mixed cabins and identify the affected leg.
- Add the bags and necessary seat selection the user needs before naming the cheapest fare. Check refunds, change charges, ticket issuer and who handles disruptions. Unknown terms are neither restrictive nor flexible.
- Label separate tickets and self-transfers. Allow for collecting and rechecking bags, terminal or airport changes, security and entry requirements. Verify transit rules where relevant and explain whether missed connections are protected. A published minimum connection is not a comfortable buffer.
- Show each airport's local dates and times, including next-day arrivals. Check elapsed time, overnight connections and ground transfers. Only introduce alternate dates or airports with permission; include any extra hotel or transport cost.

## Rank a useful shortlist

- Rank verified total price and practical travel time together. When the cheapest worthwhile trip and shortest practical trip differ, show both. Explain what the added money saves in time or what the cheaper trip adds in connections.
- Drop options that are both dearer and slower with equivalent terms. Then weigh stops, ground transfers, arrival time, connection reliability and flexibility. Do not invent a fixed hourly value for the user's time.
- Include a flexible fare when it is a meaningful alternative and state the price difference and rules. Leave out near-duplicates.
- Say “lowest found in this search,” with sources and the time checked. Do not claim the lowest price anywhere or promise that a fare or seat will remain available.
- Stop when leading offers are verified and more searching is unlikely to change the decision. Read [sources.md](sources.md) for gaps or wider coverage; every website need not be searched for every request.

## Present the options

- Use stable labels and a short list or table for two or three offers. Include flight numbers, airports, local dates and times, elapsed time, stops, cabin, bags, total, decisive terms and the actual seller link.
- Present round trips as complete combinations. If only an outbound has been selected, explain that the return and full price still need verification.
- Add a short recommendation and the decisive tradeoff. If the user has not chosen, clearly replace an outdated list when better offers emerge. After selection, explain the alternative and change only with permission.
- Use `orbit_browser_show` when showing the page helps. A page, card or selection is not a reservation. After the user chooses, follow [booking.md](booking.md) for final review and handoff.
