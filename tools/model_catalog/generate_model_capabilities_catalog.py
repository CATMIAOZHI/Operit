#!/usr/bin/env python3
"""Generate Operit's repository-hosted official model capability catalog."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = "https://models.dev/models.json"
DEFAULT_OUTPUT = (
    REPO_ROOT
    / "app/src/main/assets/model_catalog/model_capabilities_v1.json"
)
SUPPORTED_MODALITIES = {"text", "image", "audio", "video", "pdf"}


class CatalogError(ValueError):
    pass


def read_source(source: str) -> dict[str, Any]:
    if source.startswith(("https://", "http://")):
        request = urllib.request.Request(
            source,
            headers={"User-Agent": "Operit-model-capabilities-generator/1"},
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read()
    else:
        raw = Path(source).read_bytes()
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise CatalogError("models.dev source must be a JSON object")
    return parsed


def build_document(source: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for official_model_id, value in sorted(source.items()):
        if not isinstance(value, dict):
            continue
        modalities = value.get("modalities")
        if not isinstance(modalities, dict):
            continue
        inputs = modalities.get("input")
        if not isinstance(inputs, list):
            continue
        normalized_inputs = sorted(
            {
                str(modality).strip().lower()
                for modality in inputs
                if str(modality).strip().lower() in SUPPORTED_MODALITIES
            }
        )
        if not normalized_inputs:
            continue
        model_id = str(official_model_id).strip()
        if not model_id or "/" not in model_id:
            raise CatalogError(f"invalid official model ID: {official_model_id!r}")
        result[model_id] = {
            "name": str(value.get("name") or model_id),
            "family": str(value.get("family") or ""),
            "modalities": {"input": normalized_inputs},
        }
    if not result:
        raise CatalogError("source contains no usable official models")
    return result


def render(document: dict[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        default=DEFAULT_SOURCE,
        help="models.dev models.json URL or local path",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when the checked-in catalog differs from the source",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        document = build_document(read_source(args.source))
        rendered = render(document)
        if args.check:
            current = (
                args.output.read_text(encoding="utf-8")
                if args.output.is_file()
                else ""
            )
            if current != rendered:
                print(f"Model capability catalog is stale: {args.output}", file=sys.stderr)
                return 1
            print(f"Model capability catalog is current: {len(document)} models")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(f"Generated {len(document)} models at {args.output}")
        return 0
    except (CatalogError, OSError, json.JSONDecodeError) as error:
        print(f"model capability catalog generation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
