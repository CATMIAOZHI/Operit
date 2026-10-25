# Prepare checkout in the browser

Read this when a chosen product moves to a cart or checkout. Orbit stops before payment; the user pays.

## Continue the same purchase

- Inspect the existing browser task and cart. Reuse work for this purchase and resolve any earlier submission or payment before starting another.
- Carry the actual product links, seller, variants, quantities, confirmed delivery details, exclusions, quoted total, currency, delivery estimate and time checked.
- Cart preparation does not authorize ordering. “Look at this” is not permission to buy.
- Give a browser subagent, when used, the same brief and an explicit stop before payment. Include earlier failures, refusals or uncertain outcomes. Never pass card numbers, payment tokens or verification codes.
- Keep competing offers and their check times available for a recheck. Do not invent a quote.

## Review the cart

- Match items, seller, variants, quantity and stock. Exclude unwanted bundles, recurring memberships, insurance and preselected extras.
- Verify the payable total with shipping, taxes, coupons, minimum-spend rules and eligibility. Subscriber or new-customer prices are not universally available.
- Check address, delivery method, estimate and returns. Enter only necessary details; do not repeat full private addresses in chat.
- If the price rises, a promotion expires or delivery slows, recheck alternatives and explain the change. Do not switch items, raise the budget or accept worse terms without agreement.

## Approve, hand off and verify

- Show items, variants, quantities, delivery and total before submission. If specific ordering permission is missing, use `orbit_request_approval` and wait. Do not repeat unchanged authorization.
- Use `orbit_vault` for secure login. Use `orbit_browser_handoff` for passwords, verification and payment, including saved payment methods. End the turn and inspect the page after handback.
- Errors or timeouts do not prove failure. Check orders, cart and authorized merchant confirmations first. Ask the user to reconcile uncertain payment; do not reorder or pay again.
- Report completion only from merchant evidence. Give the reference, payment state and delivery estimate. Distinguish a prepared cart, pending payment, successful payment and confirmed order.
