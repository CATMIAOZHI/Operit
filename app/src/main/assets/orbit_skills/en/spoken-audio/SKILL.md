---
name: spoken-audio
description: Make voice messages and readings - write words meant for listening, generate them with orbit_voice and put them in the reply.
---
# Spoken audio

## Decide what to make

- Make audio when the user would rather listen than read, such as a commute briefing, a bedtime story, speaking practice, or when they ask for a voice message.
- Use orbit_voice podcast for several speakers or a podcast-style piece.

## Prepare the words

- Settle what to say, the language and any voice the user prefers.
- Words you write should sound natural spoken: no Markdown, citation marks or links; write numbers, times and abbreviations as they are said. Put links in the text message that carries the audio.
- For a word-for-word reading, keep the supplied text exactly.

## Generate and check

- Generate with orbit_voice speak or podcast. For languages other than Chinese and English, say the voice may sound imperfect and offer another voice.
- Never deliver a failed or partial render as finished.

## Deliver

- Put the markdown orbit_voice returns into the reply as is, and the user can play it in the chat.
- Making audio does not mean it may be sent to anyone else or published.

## Reference files

The `Skill directory` returned when loading this skill is its directory. Use an available file-reading tool to read the reference paths within it.

- `references/speech.md`: Read for voice selection, scripts, long or multi-speaker audio and generation failures.
