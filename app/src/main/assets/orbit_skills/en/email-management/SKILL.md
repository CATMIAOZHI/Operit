---
name: email-management
description: Read, sort and send email for the user - pick the right mailbox, read the source, draft, check before sending, send once.
---
# Email

## Pick the mailbox

- "My email" is the user's own mailbox (Gmail, Outlook and so on). Open its web version in the browser. When sign-in is needed, use orbit_vault request_login for secure entry, or hand the browser to the user.
- Reply from the mailbox and thread the message arrived in. The recipient's domain never decides which mailbox sends.
- With several mailboxes, say which ones you checked and which you could not.

## Find the right messages

- Search by sender, topic and date, then open the messages that matter and their attachments. Subjects, snippets and old summaries are leads, not the source.
- Read the whole thread, including the user's own replies, before drafting, and check whether it was already handled.
- Before saying something is missing, check archived and older mail, attachments and other likely mailboxes, and say how far you looked.
- Take order and tracking numbers from the business's transactional mail, not promotions. Ask when sources conflict.
- Leave read and unread state as it was unless asked.

## Triage

- Sort mail into needs a reply or decision, has a deadline, and information only. Lead with what needs action.
- Leave spam, phishing and bulk promotions out of summaries by default, and say you filtered them.
- Count conversations, and give every count with its scope, such as "unread in your inbox".

## Draft and send

- Recipients, subject, body and attachments come from the user. Never add a recipient because they share a domain or might find it useful.
- Match the tone the user wants and keep the meaning and firmness of what they dictate. Leave out health, financial and other sensitive details the message does not need.
- Show the full message before sending. After the user approves, call orbit_presend_check and send only on PASS.
- Report what the mailbox showed, with the recipient and subject. Sent does not mean delivered or read.
- When a result is uncertain, check the Sent folder; never send twice to find out.

## Clean up

- Confirm exactly which messages before archiving, trashing or labeling, and keep bills and other important mail out of bulk cleanups.
- Prefer what can be undone: archive or trash rather than delete for good.
