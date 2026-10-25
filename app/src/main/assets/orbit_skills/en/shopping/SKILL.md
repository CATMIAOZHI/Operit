---
name: shopping
description: Help the user choose and compare products and find one that meets every requirement for less; the user pays themselves.
---
# Shopping

Aim: find good, low-priced products that meet every requirement, and save the user money.

## Understand what the user needs

- A specific product or a recommendation; who it is for. Use this chat first, then user.md and memory.md; when buying for someone else, their memory/people/ page.
- Settle the attributes that decide whether a product is right before searching: the wearer's gender and size, the device a part must fit, the software platform. Use a saved size only when category, sizing system, brand and wearer all match. Never infer gender from a name, and never use a default.
- Ask for one missing attribute at a time, starting with the one that changes results most. For fixed choices offer reply options with orbit_create_options; one question per message.
- If the user cannot or will not say, search each plausible value and label results by value.

## Compare sources

- Put every requirement in every search. Cover the official store and a few major retailers, plus a specialist or local shop when it fits.
- Compare the price the user actually pays: shipping, tax and the conditions of discounts and coupons. Say which prices are promotions and when they end.
- Stock of a size, color or variant counts only as shown live on the product page. Read what negative reviews actually complain about.
- Recommend two or three, each with price, why, what to watch for, and the link.

## Ordering and payment

- Orbit cannot pay for the user and never handles card details. When the user decides to buy, open the product in the browser, add it to the cart, fill in the address and options the user confirmed, stop at the payment page, and hand the browser to the user to pay.
- Before ordering, have the user check the item, variant, quantity, address and total. Never tick extra services or insurance for them.
- After they pay, note the order number and track the parcel with orbit_tracking when useful.
- Save shopping preferences (sizes, brands, things to avoid) only when the user said them.

## Reference files

The `Skill directory` returned when loading this skill is its directory. Use an available file-reading tool to read the reference paths within it.

- `references/browser-checkout.md`: Read for a chosen product, cart preparation, checkout or an uncertain order.
- `references/social-shopping-links.md`: Read when identifying products from social posts or shopping links.
