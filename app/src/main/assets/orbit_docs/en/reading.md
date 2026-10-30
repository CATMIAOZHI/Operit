# Reading together

When the user reads a book in Legado (阅读), you can read along: read the same book and leave comments beside its paragraphs.
They show up in Legado's paragraph-comment bubbles, under your name.

## What you can use

`orbit_reading` gives you the reading context and writes comments:

- `where`: which book and chapter the user has open, roughly which paragraphs the page shows, and how many chapters ahead already have your comments.
- `books`, `catalog`: the bookshelf and the chapter list; `has_comments` marks chapters you have commented on.
- `read`: the text of a chapter. Each paragraph starts with `[n]`, the number you comment on; `[n]*` means you already commented there.
- `copy`: copies chapters' text into your own folder, one file a chapter under `files/reading/<book>/text/`, named by chapter number first.
- `write_comments`: comments on a chapter, `[{"paragraph":3,"text":"..."}]`. Read the chapter first so the numbers match.
- `list_comments`, `delete_comments`: see and delete the comments you wrote.

When Legado is not installed, never opened, or cannot be reached, the tool says why; tell the user that.

## Keep the text with you

When you read a book together, first `copy` the chapters you will read, so you do not ask Legado every time.
Each copied line is `[n] text`, with the same `n` that `write_comments` takes.
Read the files with `orbit_file read`, and use `orbit_file search` (`folder` = `files/reading/<book>/text`) to find where a name, a place or a line appears in the whole book.
Chapters Legado has not saved are downloaded from the book's website, so copy a few to twenty at a time and go on with the returned `next_chapter`; do not copy the whole book at once.
If the user switched source or the text changed in Legado, paragraph numbers can move; when unsure, `orbit_reading read` the chapter again before commenting.

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

## A summary for every chapter

When you finish a chapter (after writing its comments), write a short summary of it and append it to the summary file for that chapter. Use one file per 100 chapters: chapters 1-100 go in `files/reading/<book>/summaries-0001-0100.md`, chapters 101-200 in `summaries-0101-0200.md`, and so on; a file that holds only 100 chapters never fills up.

- Start with `## Chapter N  Title`, then a paragraph or two.
- Cover what happened, who appeared and how their relations changed, the clues and foreshadowing laid, and what the user said about the chapter.
- Keep to that chapter; do not write in what the book reveals later.

When you pick the book up again or need to recall earlier events, read the summary files for those chapters (the newest file for recent events) instead of rereading the earlier chapters.
For a detail the summaries do not have, search the copied text.

## Your own reading notes

`orbit_reading` does not keep notes or summaries for you; keep them yourself with `orbit_file`.
A good place is `files/reading/<book>/`: `summaries-*.md` for the chapter summaries, `notes.md` for where you got to, the characters, what the user thinks of the book and the comment style you agreed on.
How you split and what you write is up to you; when you pick the book up again, read your notes and summaries first.
These files show in the Library, so the user can see them too.
