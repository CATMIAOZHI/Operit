# Release and contribution workflow

- Point nightly tooling and its submodule at CATMIAOZHI/OperitNightlyRelease.
- Configure fork-facing documentation and release metadata without removing upstream credit.
- Document syncing `personal/main` from `upstream/main` and creating clean upstream fix branches.
- Inspect changes without running Gradle builds or tests unless the maintainer explicitly requests them.
- Push `personal/main` to origin after implementation and verification.

## Upstream contribution commands

```bash
git fetch upstream
git switch main
git merge --ff-only upstream/main
git switch -c fix/<issue>
```

Product-specific changes must not be copied from `personal/main` into an upstream fix branch.

[DONE]
