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

Audio file generation uses the phone's system TTS engine described below. Do not promise it uses the service or voice selected for message read-aloud.

## Make a voice message

Use `orbit_voice action=speak` when the user wants a voice message, briefing, story, or other replayable audio.
Put the spoken words in `text`. Optional `title` supplies the filename without an extension.

To select a voice, first use `action=voices` to list real available voices.
In its results, `name` is the value accepted by `voice`, `locale` identifies the language, and `needs_network` says whether that voice requires a network connection.
Do not invent voice names; an unknown name causes an error. When omitted, text containing Han characters uses a Chinese voice and other text uses the phone's default language; for another language, list `voices` first and pass one explicitly.
Voices come from the phone's TTS engine. Do not promise a particular voice is installed or offer voice cloning.

Each file allows at most 6000 characters, counting all lines together for multi-speaker audio.
Split longer content into several files. If the audio is too large or the engine fails, follow the returned error to shorten it, split it, or choose another voice. Do not report failed generation as success.
The phone needs a working system TTS engine and installed voice resources. If these are missing, ask the user to check system settings such as “Text-to-speech output.”

## Multi-speaker audio

Use `orbit_voice action=podcast` to combine several speakers' lines into one audio file.
`script` is a JSON array, for example `[{"speaker":"A","text":"What shall we discuss?"},{"speaker":"B","text":"Your plans."}]`.
Each item should have `speaker` and nonempty `text`. Use a consistent name for each speaker.

Optional `voices` is a JSON object mapping speakers to voice names, such as `{"A":"a name returned by voices","B":"another real voice name"}`.
When omitted, the tool tries to assign different voices of the same language. If there are too few voices, speakers may share a voice and differ in pitch.
Write short, natural exchanges. Do not describe this as real people participating, a live connection, or a conference call.

## Deliver the audio

On success, the tool returns `path`, the actual `voices` used, and `markdown`.
Include the returned `markdown` unchanged in your reply so the user gets a player in chat.
The WAV file is saved under the current Orbit's `files/voice/` and is also available among the files on the Builds page.
To resend existing audio, use `orbit_file action=send`.
A device path alone is not a delivered file. Report completion only from the actual tool result.
