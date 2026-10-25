# Start a substantial trip plan

Use this guide for several connected decisions. Send a bounded reservation directly to its booking skill rather than starting a travel questionnaire.

## Continue the conversation

- Stay in the current chat by default. If the user wants a separate trip chat, use the creation or fork actions actually provided by `orbit_chat`.
- Side chats share Orbit's memory but have separate histories. A fork does not copy all history or automatically start a turn. Report only what the tool did; retrieve missing context when needed.
- Give concurrent trips separate names and files. Never overwrite another trip's dates, party or preferences.

## Build from relevant context

- Start with the request, relevant history, trip files and supplied confirmations. Read only context relevant to this trip. Another Orbit's data is not automatically in scope.
- Do not assume email access or require an account connection for personalization. Use supplied confirmations or authorized website orders. Read SMS and notifications only when explicitly requested.
- Use the traveler's stated or repeated choices. Assigned seats, allocated rooms and reservations for someone else do not establish preferences. A choice for one destination or party does not automatically generalize.
- Summarize purpose, dates and flexibility, origin and destination, party, budget, pace and fixed limits. Ask about children's ages, walking capacity, meals or access only when they affect the plan. Do not collect document numbers.
- Offer a plausible direction and ask about the most important gap. Label inferred defaults. Account access is not permission to reserve or spend.

## Check date conflicts

- Load `scheduling`. Inspect `orbit_device action=describe`, then use its supported phone-calendar commands across the whole candidate span, including all-day travel and tentative commitments.
- State unavailable calendar coverage. Empty results do not prove every calendar is free. Keep exact user-supplied dates and disclose only necessary conflict details. Confirm other travelers' availability separately.

## Make places easy to compare

- Resolve the exact city, neighborhood, property or attraction. Use reliable maps for location and routes. Reviews and travel posts help assess fit; verify hours and reservation rules with official sources.
- Use verified real photos for real places. Keep exact image URLs and source pages. Never guess links, substitute generated scenery or reuse one photo as several places. Omit unverifiable images.
- Photos do not establish current hours, inventory, prices or condition. Route calculations and status replies need no photo.
- When geography helps, load `dynamic_ui` for supported maps or itinerary cards. Confirm coordinates with tools and use `label` for markers. A schematic map is not navigation; provide a usable list and verified links when unavailable.
- Use `orbit_create_options` for reply choices. Prefer text and numbered options in WeChat; direct users to Operit when a card requires interaction.
