---
fork: https://github.com/CATMIAOZHI/Operit
branch: personal/main
status: active
---

# Operit Ry personal distribution

## Current state

The upstream application owns the Android identity, branding, release channels, remote announcements, online market, documentation links, and several runtime download URLs. A personal build would therefore still contact or install artifacts maintained by the upstream project.

## Goal

Maintain Operit Ry as an independently installable distribution while keeping generic bug fixes easy to submit from clean branches based on `upstream/main`.

## Scope

- Use `com.rainy.operitry` and the launcher name `Operit Ry` while retaining the in-app Operit AI product wording.
- Recolor the existing Operit icon with the Rainytoken pink palette.
- Use `CATMIAOZHI/Operit` for project links, stable releases, help, and fork-owned runtime scripts.
- Use `CATMIAOZHI/OperitNightlyRelease` for nightly and patch releases.
- Check stable updates only after an explicit user action.
- Disable remote announcements and remove the online market while retaining local package management.
- Retain upstream attribution and contribution documentation.
- Keep third-party GitHub mirror probing unchanged by explicit maintainer choice.

## Branch policy

Product-specific changes live only on `personal/main`. Upstreamable fixes start from the latest `upstream/main` and must not contain Operit Ry branding, service routing, or release configuration.
