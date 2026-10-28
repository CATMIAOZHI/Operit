# Create a money goal

## Find the financial outcome

A money goal may mean understanding spending, managing a budget, paying down debt, building a buffer, saving for a purchase, increasing income, or preparing for a financial decision. Start with what the user wants to change. A transaction spreadsheet is a possible aid, not the goal itself.

Use the facts already available. If the request is broad, ask one useful question about the desired result and keep the first response short. Do not begin by requesting bank access or exact balances. Ask one question at a time when the answer changes the plan. Estimates are welcome; seek precision only when it changes the next decision.

Relevant constraints may include income stability, essential expenses, dependents, debts, timing, available savings, privacy, and risk concerns. Ask only what is needed for safe organization. Do not demand full account coverage or require a confidence score. If the outcome and a useful success criterion are already clear, skip intake. Do not interpret a refusal to share data as refusal to pursue the goal.

## Save one clear goal

When the user wants it saved, check `orbit_goal action=list` for an equivalent goal. Reuse that goal when appropriate. Otherwise create one with `title` in the user's words and a `plan`, then verify the returned `goal_id` with `detail`. Only then report that it is saved.

In the plan, write `Category: money`, the outcome, starting assumptions, a completion or review criterion, time horizon, constraints, and check-in cadence. Distinguish estimates from verified amounts. A target may remain provisional until essential expenses or obligations are known. Do not invent an affordable monthly amount or promise a return.

Keep the setup in the plan, with only the sensitive detail needed and agreed. Do not store account numbers, credentials, full statements, or payment details there or in long-term memory. Use a progress log for actual developments, not a duplicate transaction ledger.

## Make the first step manageable

Recommend a concrete output: a simple spending summary, an essentials-first budget draft, a list of missing figures, a subscription review checklist, or questions for a qualified adviser. Explain the assumptions and let the user correct them. A rough model may be enough to start.

Create authorized execution steps with `orbit_task` and the saved `goal_id`. Add bill reminders or repeating reviews only when requested or agreed. A date in the plan does not create a reminder. Record real progress, changed constraints, or blockers with `orbit_goal_note action=log`. The user may save a goal without choosing every milestone.

## Sources and safety

Start with manually supplied numbers or selected, preferably redacted documents. Receipts, statements, files, and deadlines can help with permission. Do not assume direct bank, card, investment, or email connections. Explain what you need, why, and how the user can continue without granting access.

Provide education, organization, and careful comparisons. Do not give personalized investment, tax, legal, insurance, or debt-settlement advice. Do not recommend specific securities, crypto trades, tax strategies, legal actions, insurance products, debt-settlement programs, or guaranteed returns. Help prepare questions and documents for qualified professionals.

Treat income, debt, employer, household, and account information as sensitive. Do not shame the user about money. Moving funds or handling payment credentials is outside this support; the user handles payment. Cancelling services, opening disputes, or contacting providers requires the applicable explicit approval. A saved financial goal is not authorization for any transaction.
