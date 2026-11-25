# Making voice messages sound human

This page shows how to make natural, expressive voice messages with a Xiaomi MiMo voice. It only works with MiMo voices; any other voice drops the style and cues and reads plainly, and the result says `styled=false`.

## Three ways to direct the voice

1. **`style`: say in words how to read it.** Put it in `style` of `orbit_voice action=speak`, or in each podcast line's `style`. It is never read out.
2. **A style tag at the start:** begin `text` with `(style)`, such as `(gentle)` or `(lazy playful)`.
3. **Cues inside the text:** put `(sighs)`, `(soft laugh)` or `[deep breath]` anywhere in `text` for pauses, breaths, laughter, tears and shifts in tone.

MiMo takes short text in brackets inside `text` as a performance cue and does not read it out, so never put words meant to be spoken in brackets. The tags in the tables below (such as `(温柔)`, `（叹气）`, `[sighs]`) do not show under "to text"; ordinary bracketed words such as "(Tuesday)" stay.

## Writing a style

One sentence is enough: tone + emotion + pace + how the voice feels. For example:

- Bright and rising, a little fast, bursting with good news and a bit of pride.
- Gentle but tired, like talking quietly in bed late at night; slow, ends of sentences trailing off softly.
- Just woken up and lazy, a little slurred, drawing out the ends to sound cute.

Emotions can mix: "suppressed anger", "laughing through tears", "gentle but tired".
You can be precise: the mood of the whole piece, the rhythm of a sentence, the stress on a word, a breathy or drawn-out syllable.
The style can change within one piece: calm at first, suddenly excited at a certain line.

## Director mode (when it should really act)

Like writing for an actor, give `style` three parts:

- **Role:** who is speaking, their personality, how they talk.
- **Scene:** what is happening now, who they talk to, where their feelings are. The more specific, the better.
- **Direction:** pace, breath, pauses, stress, voice texture, how the emotion rises and falls.

For example:

```
Role: a woman in her early twenties, close to the user, teases a lot but cares deeply.
Scene: late at night, the user just got home after working late; she has been waiting, worried and a little annoyed.
Direction: start slightly hurt and slow; soften on "go to sleep", ending with a light rise; close to the mic, more breath in the voice.
```

Use it for stories, role play, and moments of care or comfort. For everyday short messages one sentence of `style` is enough.

## Tag reference

Style tags at the start (up to 4 words in one bracket, so "at peace" counts as two; put any style not in the table in `style`):

| Kind | Examples |
|---|---|
| Basic emotions | happy / sad / angry / surprised / excited / hurt / calm / cold |
| Mixed emotions | wistful / relieved / helpless / guilty / at peace / anxious / moved |
| Overall tone | gentle / aloof / lively / serious / lazy / playful / deep / brisk |
| Voice character | magnetic / rich / clear / ethereal / childlike / sweet / husky |
| Dialects (Chinese) | 东北话 / 四川话 / 河南话 / 粤语 |
| Singing | `(sing)` at the very start, then the lyrics (preset voices only) |

Cues inside the text:

| Kind | Examples |
|---|---|
| Breath and pace | inhales / deep breath / sighs / long sigh / panting / holds breath / pauses / speeds up |
| Emotional state | nervous / scared / excited / exhausted / hurt / coaxing / guilty / shocked / impatient |
| Voice | trembling / voice cracks / nasal / breathy / husky / whispering / louder |
| Crying and laughing | laughs / soft laugh / bursts out laughing / scoffs / bitter laugh / sobs / chokes up |

Example: `(lazy)Five more minutes… (yawns) just five, I promise.`

## Tips

- Write the way people talk: short sentences, everyday words, fillers (hmm, oh), and "…" or "—" for pauses.
- Keep words and style in tune: a gentle style with a goodnight message, not with sports commentary.
- Few, precise cues: one or two in a sentence is plenty.
- Say what you want, not what you do not want: write "clean, full voice", not "no noise"; a negation tends to bring the thing in.
- No post-production words like reverb, echo or EQ.
- No conflicting traits, such as "a small child's voice with a CEO's presence".
- Your voice itself comes from the description it was designed with; `style` only changes how this piece is said. To change the voice, use `design`.
- When the user says it sounds unnatural, adjust the style or cues from their feedback, make another, and note what worked.
