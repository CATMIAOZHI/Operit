# Voice

The user can dictate text, listen to your messages, and ask you to make playable audio files.
Dictation, message read-aloud, and audio generation are separate features. Do not describe them as live voice calls.

## Voice input

When the user taps the microphone in the chat composer, the app records speech and transcribes it into the editable message box.
The user can check and edit it before sending. Starting a recording does not mean a message was sent.
Recording needs microphone permission. Recognition quality and availability depend on the selected speech recognition service and its configuration.
When permission is needed, use `orbit_permission_card permission=microphone` to place a permission card.

The WeChat channel also accepts the user's voice messages, but WeChat's servers transcribe them before passing text to you.
If transcription fails, you receive only an explanatory note. Do not pretend you heard the original recording.
See [weixin.md](weixin.md) for connection steps and channel limits.

## Read-aloud and voice settings

Chat offers message read-aloud and automatic read-aloud options, using the app's current speech service configuration.
Voice profiles come in two kinds: read-aloud (tts) and voice input (stt). Each kind can have several profiles, one in use at a time.
Use `orbit_voice action=profiles` to see them and which is in use (no keys, only has_key).
To switch, add, change a key or delete, use `request_switch`, `request_create`, `request_key` or `request_delete`. Each places an approval card; when a key is needed the user types it on the card and you never see it. End the turn with one short line; after approval you get a hidden note with the result.
For details such as speed, pitch or headers, or when the user wants to adjust things themselves, use `orbit_voice action=request_settings` to place a voice settings card they open and edit.

Voice messages and podcasts use cloud voices by default: `speak` uses your read-aloud voice (your own, else the app's), the same voice that reads messages aloud.
When your read-aloud voice cannot make audio files (the phone's system voice, an on-device model, …), `speak` and `podcast` fail with an error that says how to set one up: recommend a cloud voice, most easily Xiaomi MiMo, and follow the error's hints and the steps below. Do not switch to the phone's system voice yourself.

### First voice setup (Xiaomi MiMo)

When you get your voice from MiMo, always let the user pick it first; do not take one of MiMo's preset voices:
1. Ask what kind of voice they want (gender, age, tone, someone it should sound like). If they have no idea, suggest one that fits your persona.
2. Use `design` to make 2–3 different samples from descriptions, each with its own `title` (such as "gentle" or "lively"). In the reply, write a line with each sample's number and name first (such as "1. Gentle"), and its markdown on the next line, so the user can tell them apart; then end the turn while they listen. Each sample costs the user a little, so do not make many at once.
3. If they want changes, design again with a new description. Once they pick one, use `request_own_voice` with that sample's `path` as `sample` and a `name`. After approval the voice is cloned from that recording and becomes yours.
If the app has no MiMo key yet, `design` fails: first place a `request_create` card (`kind=tts`, `service=MIMO_TTS`, `switch=false`) so the user types the key; it is only saved, not switched to. Then start again from step 1.
If the user wants the phone's own voice, place a `request_settings` card so they turn on "Use the phone's own voice for voice messages" in Orbit Settings > Voice themselves. With it on, `speak` and `podcast` use the phone's system voice.
If a cloud voice fails, the tool reports the error and does not fall back to the phone's voice; tell the user the reason in one line.

## Make a voice message

Use `orbit_voice action=speak` when the user wants a voice message, briefing, story, or other replayable audio.
Put the spoken words in `text`. Optional `title` supplies the filename without an extension (the chat does not show it); a few words on the topic are enough, such as "Morning briefing".
`voice` (a phone system voice name) is only accepted when the user chose the phone's own voice.

When your voice is Xiaomi MiMo, you can make it sound more human: `style` says in words how to read it (tone, emotion, pace, or a director's role / scene / direction), and `text` may start with a style tag like `(gentle)` and carry cues like `(sighs)`. Each podcast line can have its own `style`. MiMo does not read short bracketed text aloud, so keep spoken words out of brackets; performance cues do not show under "to text". See [voice-style.md](voice-style.md) for how to write them and the tag lists. Other voices drop the style and cues, and the result says `styled=false`.

Each file allows at most 6000 characters, counting all lines together for multi-speaker audio; requests to the voice service are also capped, so do not cut lines too small.
Split longer content into several files. If the audio is too large or synthesis fails, follow the returned error to shorten or split it. Do not report failed generation as success.

## Multi-speaker audio

Use `orbit_voice action=podcast` to combine several speakers' lines into one audio file.
`script` is a JSON array, for example `[{"speaker":"A","text":"What shall we discuss?"},{"speaker":"B","text":"Your plans."}]`.
Each item should have `speaker` and nonempty `text`. Use a consistent name for each speaker; a speaker's lines in a row are made in one go.

With cloud voices, the first speaker uses your read-aloud voice (named `orbit`) and the others get cloud voices whose audio joins with yours into one file: the user's other read-aloud profiles of the same service (`profile:<name>`) and, when your voice is MiMo and a MiMo key is saved in the app, MiMo's preset voices (such as `MiMo·Milo`).
To choose, list them with `action=voices` and map them in `voices`, for example `{"A":"orbit","B":"MiMo·Milo"}`. With too few voices some speakers share one, and the result's `note` says so.
When the user chose the phone's own voice, `voices` lists phone system voices and `podcast` uses the phone's system voice.
Write short, natural exchanges. Do not describe this as real people participating, a live connection, or a conference call.

## Deliver the audio

On success, the tool returns `path`, `engine` (`cloud` or `phone`) and `markdown`; a voice message also has `service` (the profile name), a podcast the `voices` each speaker got.
Put the returned `markdown` unchanged on a line of its own; the chat shows it as a voice bar the user taps to play.
Send it the way people send a voice note: at most one short sentence around it, and do not write out what the audio says; the user can long-press the voice bar and tap "Show text" to see its words. Give a text version when the user asks for one.
The file is saved under the current Orbit's `files/voice/` (MP3 or WAV for cloud voices, WAV for the phone's voice) and is also available among the files on the Builds page.To resend existing audio, use `orbit_file action=send`.
A device path alone is not a delivered file. Report completion only from the actual tool result.
