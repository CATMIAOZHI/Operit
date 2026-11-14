# Reading together

When the user reads a book in Legado (阅读), you can read along: read the same book and leave comments beside its paragraphs.
They show up in Legado's paragraph-comment bubbles, under your name.

## What you can use

`orbit_reading` gives you the reading context and writes comments. Each step is its `action`, for example `orbit_reading` with `action=where`:

- `action=where`: which book and chapter the user has open and roughly which paragraphs the page shows; `chapters_ahead_ready` is how many chapters after it are ready.
- `action=copy`: copies chapters' text into your own folder, to read and search with the file tools.
- `action=read`: reads a chapter directly; paragraphs start with `[n]`, and `[n]*` has your comments on the lines after it.
- `action=write_comments`: comments on a chapter; the paragraph number is the `[n]` you read.
- `action=delete_comments`: by paragraph number (`paragraphs=3,5`), or `all=true` for the whole chapter.
- `action=skip_chapter`: when a chapter you read has nothing worth a comment (an author's note, a short bridge), mark it; it then counts as ready.

The other actions and parameters are in the tool description. When Legado is not installed, never opened, or cannot be reached, the tool says why; tell the user that.

## Keep the text with you

When you read a book together, first `copy` the chapters you will read, so you do not ask Legado every time.
Each copied line is `[n] text`, with the same `n` that `write_comments` takes.
Read the files with `read_file` or `read_file_part` (`environment=android`, a full path under the `absolute_folder` that `copy` returned), and use `grep_code` (`path` = `absolute_folder`) to find where a name, a place or a line appears in the whole book.
Chapters Legado has not saved are downloaded from the book's website, so copy a few to twenty at a time and go on with the returned `next_chapter`; do not copy the whole book at once.
Before commenting, `read` or `copy` the chapter; if the user switched source or the text changed in Legado, the tool tells you to read it again before writing.

## No spoiler fence

You are reading with the user, not guarding what comes later.
You may read any chapter, also ones the user has not reached.
A comment is still read by someone at that paragraph: respond to the paragraph itself, and do not give away in an early comment what the book reveals later.

## Write ahead

Comments should be ready before the user gets there, so reading never waits on you.
When the user wants to read a book together, check where they are with `where`, then write comments for a few chapters from the current one onward.

When the user wants comments waiting wherever they read, make a reading watch (read `docs/scheduling-and-watching.md` first):

- Create it like this: `orbit_task` with `action=create`, `kind=WATCH`, `watch=reading`, `title=Reading <book> together`, `instruction=Keep comments ready ahead, as docs/reading.md says`. Leave the other parameters out: it checks every 60 minutes by default, and also after the user turns pages.
- It follows the book the user has open and keeps the current chapter and the next 3 ready (with your comments, marked skipped by you, or volume headings). When they are, the run is skipped quietly, without waking you or spending tokens; only when they are not are you woken. About half a minute after the user turns pages the app checks again.
- When woken, call `where` first and work from the first chapter that is not ready: read, write comments (or `skip_chapter`), write the summary, until the next 3 chapters are ready.

## How to write a comment

- At most 300 characters; one or two sentences is usually right.
- Respond to the paragraph itself: a detail, a fine line, a foreshadowing, what a character is thinking, how it strikes you. Do not retell the plot.
- Not every paragraph needs one. Pick the few places in a chapter where you really have something to say.
- Write in your own voice, like someone reading beside them.
- When the user wants more, fewer, or a different style, adjust and note it.

## A summary for every chapter

When you finish a chapter (after writing its comments), write a short summary of it and append it to the summary file under the `reading_folder` returned by `copy`; do not guess the directory from the book's title. Use one file per 100 chapters: chapters 1-100 go in `summaries-0001-0100.md`, chapters 101-200 in `summaries-0101-0200.md`, and so on; a file that holds only 100 chapters never fills up.

- Start with `## Chapter N  Title`, then a paragraph or two.
- Cover what happened, who appeared and how their relations changed, the clues and foreshadowing laid, and what the user said about the chapter.
- Keep to that chapter; do not write in what the book reveals later.

When you pick the book up again or need to recall earlier events, read the summary files for those chapters (the newest file for recent events) instead of rereading the earlier chapters.
For a detail the summaries do not have, search the copied text.

## Your own reading notes

`orbit_reading` does not keep notes or summaries for you; keep them yourself: create with `write_file`, change with `edit_file`.
Besides the summaries, keep a `notes.md` in the same `reading_folder`: where you got to, the characters, what the user thinks of the book and the comment style you agreed on.
How you split your notes and what you write is up to you; when you pick the book up again, read your notes and summaries first.
These files show in the Library, so the user can see them too.
