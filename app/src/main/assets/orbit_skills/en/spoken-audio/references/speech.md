# Generate spoken audio

Read this for voice messages, narration, verbatim readings and dialogue. Use `orbit_voice` with the phone's speech engine; no separate speech account is required. Voices and languages depend on the device. Generated audio is not a live call or a published podcast.

## Choose language and voice

- Call `action=voices`. Copy the returned `name` exactly; `locale` identifies language and `needs_network` identifies a network requirement. Do not invent voice IDs, use another product's catalog or promise voice cloning.
- Match a requested voice against current results. Ask about ambiguous names. If unavailable, explain and offer actual alternatives; never silently substitute.
- Omit voice selection when the user has no preference. Chinese text favors a Chinese voice. Check support for other languages and mixed-language text and select a suitable listed voice when needed.
- Consider connectivity for network voices. Missing voice data or a disabled engine needs the remedy indicated by the error; not every language works offline.
- To change app speech settings, call `action=request_settings` and end the turn. The user changes settings; sending the card neither applies changes nor returns their new values.

## Prepare and render

- For composed speech, include only spoken words. Remove Markdown, citation markers and raw links. Write numbers, times and abbreviations as they should sound; put source links in the accompanying message.
- Preserve supplied text for a verbatim reading. Do not polish, omit sentences or rewrite numbers. Splitting preserves order and content.
- Use `action=speak` with `text`, optional `voice` from the current list and a short optional `title`.
- For dialogue, use `action=podcast`. Supply `script` as a JSON array of `speaker` and `text` objects. Optional `voices` maps speaker labels to listed voice names; automatic assignment may distinguish speakers only by pitch when voices are limited.
- Example: `script=[{"speaker":"A","text":"Good morning."},{"speaker":"B","text":"What shall we read?"}]`. Labels belong in `speaker`, not spoken text unless intended.
- Each file allows at most 6000 characters across all lines. Split longer work at sentence or paragraph boundaries and number parts. An approximate duration is not a guarantee; size or device limits may require shorter parts.

## Check and deliver

- Check success and returned `path`, `chars`, `lines`, `bytes`, `voices` and `markdown`. Report a mismatch with the requested voice.
- A missing, empty or partial render is unfinished. Tool success does not establish natural pronunciation. Inspect audio when possible, and never claim to have listened without doing so.
- Include the returned `markdown` unchanged. Do not invent player links. Files are saved in `files/voice/`; a path alone is insufficient delivery.
- Deliver all parts in order with brief labels. Avoid repeating a long script when only audio was requested.
- Generation does not authorize external sending, uploading or publication.

## Handle failure

- Correct the actual error: split excessive text, repair script JSON or refresh the voice list. Preserve the text and requested voice; do not hide failure by dropping content or changing engines.
- Explain missing engine or voice data and unfinished work. Respect the user's choice before changing voices, and offer settings when needed.
- Use bounded retries for transient failures. Do not repeatedly send an unchanged failing request or infer an account failure from an opaque error.
- Schedule later retries with `orbit_task` only when requested. Save timing, text source, voice and attempt limit, and verify the saved task.
- Cancel obsolete retries after success, cancellation or the limit. Report the actual outcome and avoid later duplicate delivery.
