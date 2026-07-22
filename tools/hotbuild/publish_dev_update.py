from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

from build_patch import get_token, gh_api_json, publish_release, sha256_hex


CHANNEL = "personal-dev"


def _release_metadata(release: dict) -> dict | None:
    try:
        metadata = json.loads(release.get("body") or "")
    except (TypeError, json.JSONDecodeError):
        return None
    return metadata if isinstance(metadata, dict) else None


def _download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "operit-dev-publisher"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build-number", required=True, type=int)
    parser.add_argument("--series", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--repo", default="CATMIAOZHI/OperitNightlyRelease")
    parser.add_argument("--application-id", default="com.rainy.operitry.dev")
    args = parser.parse_args()

    apk = Path(args.apk).resolve()
    if not apk.is_file():
        raise RuntimeError(f"APK not found: {apk}")

    token = get_token(None)
    if not token:
        raise RuntimeError("GITHUB_TOKEN, GH_TOKEN or GITHUB_PAT is required")

    releases = gh_api_json(
        "GET",
        f"https://api.github.com/repos/{args.repo}/releases?per_page=100&page=1",
        token,
    )
    tag = f"v{args.version}"
    candidates = []
    for release in releases:
        if release.get("draft"):
            continue
        if release.get("tag_name") == tag:
            continue
        metadata = _release_metadata(release)
        if not metadata:
            continue
        if metadata.get("channel") != CHANNEL or metadata.get("applicationId") != args.application_id:
            continue
        asset = next(
            (item for item in release.get("assets", []) if str(item.get("name", "")).endswith(".apk")),
            None,
        )
        if asset is None:
            continue
        try:
            dev_build = int(metadata.get("devBuild", 0))
        except (TypeError, ValueError):
            continue
        if dev_build <= 0 or dev_build >= args.build_number:
            continue
        candidates.append((dev_build, metadata, asset))

    with tempfile.TemporaryDirectory(prefix="operit-dev-update-") as temp_dir_value:
        temp_dir = Path(temp_dir_value)
        published_apk = temp_dir / "operit-ry-dev.apk"
        shutil.copy2(apk, published_apk)

        previous_build = 0
        previous_metadata = None
        previous_apk = temp_dir / "previous.apk"
        for candidate_build, candidate_metadata, candidate_asset in sorted(
            candidates,
            key=lambda item: item[0],
            reverse=True,
        ):
            if candidate_metadata.get("series") != args.series:
                continue
            try:
                _download(str(candidate_asset["browser_download_url"]), previous_apk)
            except (OSError, urllib.error.URLError):
                continue
            expected_sha = str(candidate_metadata.get("targetSha256", "")).lower()
            if expected_sha and sha256_hex(previous_apk) == expected_sha:
                previous_build = candidate_build
                previous_metadata = candidate_metadata
                break

        if previous_metadata is None:
            notes = {
                "format": "full-apk",
                "channel": CHANNEL,
                "applicationId": args.application_id,
                "version": args.version,
                "devBuild": args.build_number,
                "series": args.series,
                "sourceSha": args.source_sha,
                "targetSha256": sha256_hex(published_apk),
            }
            publish_release(
                args.repo,
                tag,
                tag,
                json.dumps(notes, indent=2, sort_keys=True),
                [published_apk],
                token,
            )
            print(f"published bootstrap dev APK: {tag}")
            return 0

        build_patch = Path(__file__).resolve().with_name("build_patch.py")
        command = [
            sys.executable,
            str(build_patch),
            "--from",
            str(previous_apk),
            "--to",
            str(published_apk),
            "--format",
            "apkraw",
            "--from-version",
            args.series,
            "--to-version",
            args.series,
            "--from-patch-index",
            str(previous_build),
            "--to-patch-index",
            str(args.build_number),
            "--repo",
            args.repo,
            "--tag",
            tag,
            "--channel",
            CHANNEL,
            "--application-id",
            args.application_id,
            "--dev-build",
            str(args.build_number),
            "--version-label",
            args.version,
            "--series",
            args.series,
            "--source-sha",
            args.source_sha,
            "--extra-asset",
            str(published_apk),
        ]
        subprocess.run(command, cwd=temp_dir, check=True)
        print(f"published dev patch: {tag}")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
