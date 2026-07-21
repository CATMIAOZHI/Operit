---
fork: https://github.com/CATMIAOZHI/Operit
branch: feat/official-operit-migration
base: personal/main
status: review
---

# Official Operit migration

## Current state

Operit Ry uses `com.rainy.operitry`, while official Operit snapshots record `com.ai.assistance.operit`. Raw snapshot restore requires an exact package match, so users cannot move their existing private application data into the independently installed Operit Ry application.

The restore path also closes Room and ObjectBox before validating the selected archive. Selecting an invalid archive can therefore disrupt the current process even though no files are restored.

## Goal

Provide a one-time, explicit migration path from an official Operit raw snapshot into Operit Ry without weakening normal snapshot package validation.

## Scope

- Show a dedicated migration action until one migration succeeds.
- Accept only raw snapshots whose source package is `com.ai.assistance.operit`.
- Create an Operit Ry safety snapshot (with terminal data) before writing migrated data.
- Validate the selected archive before closing databases.
- Preserve the existing merge-based raw snapshot semantics and restart requirement.
- Record successful migration after imported shared preferences have been written.
- Add focused package policy tests and user-facing migration documentation.
- Run the migration in a dedicated cold-start phase, before WorkManager, foreground services,
  schedulers, repositories and DataStore writers start, so no background writer races with the
  safety snapshot or file replacement.

## Pull request

Target `personal/main` because the application ID transition and Operit Ry entry point are distribution-specific.

[DONE]
