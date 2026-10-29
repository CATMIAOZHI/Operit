# Reading together

When the user reads a book in Legado (阅读), you can read along: read the same book and leave comments beside its paragraphs.
They show up in Legado's paragraph-comment bubbles, under your name.

## What you can use

`orbit_reading` does two things only: it gives you the reading context, and it writes comments.

- `where`: which book and chapter the user has open, roughly which paragraphs the page shows, and how many chapters ahead already have your comments.
- `books`, `catalog`: the bookshelf and the chapter list; `has_comments` marks chapters you have commented on.
- `read`: the text of a chapter. Each paragraph starts with `[n]`, the number you comment on; `[n]*` means you already commented there.
- `write_comments`: comments on a chapter, `[{"paragraph":3,"text":"..."}]`. `read` the chapter first so the numbers match.
- `list_comments`, `delete_comments`: see and delete the comments you wrote.

When Legado is not installed, never opened, or cannot be reached, the tool says why; tell the user that.

## No spoiler fence

You are reading with the user, not guarding what comes later.
You may read any chapter, also ones the user has not reached.
A comment is still read by someone at that paragraph: respond to the paragraph itself, and do not give away in an early comment what the book reveals later.

## Write ahead

Comments should be ready before the user gets there, so reading never waits on you.
When the user wants to read a book together, check where they are with `where`, then write comments for a few chapters from the current one onward.
How far ahead, and whether to keep going in the background each day, is for you and the user to agree on.
For background top-ups, make an ordinary task with `orbit_task` (read `docs/scheduling-and-watching.md` first): each run checks `where` first, writes further only when too few chapters ahead are done, and otherwise ends without doing anything.

## How to write a comment

- At most 300 characters; one or two sentences is usually right.
- Respond to the paragraph itself: a detail, a fine line, a foreshadowing, what a character is thinking, how it strikes you. Do not retell the plot.
- Not every paragraph needs one. Pick the few places in a chapter where you really have something to say.
- Write in your own voice, like someone reading beside them.
- When the user wants more, fewer, or a different style, adjust and note it.

## Your own reading notes

`orbit_reading` does not keep notes for you.
Where you got to, what each chapter covered, the characters, what the user said about the book, the style you agreed on: keep these yourself with `orbit_file`.
A good place is `files/reading/<book name>/`, for example `files/reading/The Three-Body Problem/notes.md`.
How you split and what you write is up to you; when you pick the book up again, read your notes first.
These files show in the Library, so the user can see them too.
