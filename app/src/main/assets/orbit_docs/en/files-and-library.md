# Files and library

The user can send you files, and you can save and deliver work products.
Chat attachments, generated files, artifact versions, and memory files have different purposes and deletion rules.
Identify which one the user means rather than describing all of them as the same kind of attachment.

## Viewing and delivering files

The Builds page has two tabs: "Builds" holds content you generated, and "Library" holds files the user uploaded and saved.
Images, video, audio, and plain text support in-app previews, sharing, and “Open in another app.”
Actual preview support still depends on the format, content, and device decoding support; not every file extension guarantees a preview.
Other formats can open in an appropriate external app; do not promise an internal editor that does not exist.

Use `orbit_file action=send` to deliver a file to the current chat.
A raw device path is not a clickable deliverable; saying “saved at this path” is usually insufficient for the user to receive it.
Use `import` to bring a file from an external path into Orbit's managed file area first.

## Managing your files

`orbit_file` provides `list`, `read`, `write`, `append`, `delete`, `trash`, `restore`, `daily`, `prompt_copy`, `import`, `export`, and `send`.
Use accepted relative paths rather than assuming any device directory belongs to Orbit.
This tool does not manage the main `memory.md`; use the dedicated memory tools for that file.

Listing and reading have pagination or length limits.
Follow a returned `next_offset`, next line, or next character position; truncation is not the end of a file.
`write` replaces the whole file, so read the existing content and preserve unrelated parts before a partial change.

`docs/` is read-only reference content refreshed on every app launch; edits will be overwritten.
Do not put user files, learned content, or temporary task state there.

## User uploads

Uploaded files are saved within the current Orbit and are not cleared merely because the app closes.
Each Orbit's upload area has a limit of approximately 5 GiB; adding files beyond the limit trims the oldest uploads.
A single file larger than the total limit cannot be saved.

The upload area is not a permanent backup.
Deleting an item from the upload list removes it directly; it does not enter the trash described below.
Do not promise that `restore` can recover deleted uploads or uploads removed by the storage limit.
Deleting the whole Orbit also removes its files, so important originals should be kept separately by the user.

## Deletion and recovery

Managed files deleted with `orbit_file delete` normally enter trash and are retained for 30 days.
Use `trash` to inspect entries and `restore` to recover ones still retained.
Permanent cleanup, forgetting, and upload deletion have different rules; do not promise a 30-day recovery window for all of them.

When asked to delete a file, establish its name, location, and impact so you do not remove another copy with the same name.
If recovery fails or the original entry is gone, say so; do not create a new file and present it as a successful recovery.

## Artifact versions

Artifact version records in Builds preserve previously saved work products for inspection.
Use `orbit_artifact` actions `list` and `read` to inspect artifacts and versions.
To continue work from a version, pass its identifier as a new task's `source_artifact_version_id`.

This does not mean every file has automatic history, and it is not skill-file rollback.
The source file, artifact version, and new task's output may be separate objects; check before describing them.

## Export

`orbit_file export` can convert Markdown to HTML, DOCX, or PDF.
It supports basic headings, paragraphs, lists, quotations, code, and text styling; tables may become fixed-width text, and embedded images are not guaranteed.
Inspect the actual output when layout fidelity matters; a successful export does not prove identical formatting.

`prompt_copy` copies Orbit's own prompt content, not every system instruction from Operit or the model service.
The user can also export and share an Orbit data archive under “Identity” in the details page.
Data export is for saving and inspection; do not promise one-click reimport, restored account sessions, or complete credential coverage.
