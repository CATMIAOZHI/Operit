#!/usr/bin/env python3
"""Generate Operit's bundled model protocol catalog from models.dev's api.json."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = "https://models.dev/api.json"
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/model_catalog/model_protocols_v1.json"

# Providers whose SDK has a built-in address, so models.dev lists no "api" for them. The app
# supplies these addresses itself (ModelProtocolCatalog.OFFICIAL_ENDPOINTS); change both together.
OFFICIAL_PROVIDERS = {"openai", "anthropic", "google", "xai"}


class CatalogError(ValueError):
    pass


def read_source(source: str) -> dict[str, Any]:
    if source.startswith(("https://", "http://")):
        request = urllib.request.Request(
            source,
            headers={"User-Agent": "Operit-model-protocols-generator/1"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
    else:
        raw = Path(source).read_bytes()
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise CatalogError("models.dev source must be a JSON object")
    return parsed


def text(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def build_model(provider_id: str, value: dict[str, Any]) -> dict[str, Any]:
    model: dict[str, Any] = {}
    # The Codex provider lists OpenAI's models by name when no account is signed in.
    if provider_id == "openai" and text(value.get("name")):
        model["name"] = value["name"].strip()
    if "interleaved" in value:
        model["interleaved"] = value["interleaved"]
    if isinstance(value.get("reasoning_options"), list):
        model["reasoning_options"] = value["reasoning_options"]
    override = value.get("provider")
    if isinstance(override, dict):
        kept = {key: override[key] for key in ("npm", "api", "shape") if text(override.get(key))}
        if kept:
            model["provider"] = kept
    modes = (value.get("experimental") or {}).get("modes") if isinstance(value.get("experimental"), dict) else None
    if provider_id == "openai" and isinstance(modes, dict):
        kept_modes: dict[str, Any] = {}
        for mode, config in modes.items():
            reasoning = (((config or {}).get("provider") or {}).get("body") or {}).get("reasoning") \
                if isinstance(config, dict) else None
            kept_modes[mode] = (
                {"provider": {"body": {"reasoning": reasoning}}} if isinstance(reasoning, dict) else {}
            )
        if kept_modes:
            model["experimental"] = {"modes": kept_modes}
    return model


def build_document(source: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for provider_id, value in source.items():
        if not isinstance(value, dict):
            continue
        api = text(value.get("api"))
        if api is None and provider_id not in OFFICIAL_PROVIDERS:
            continue
        models = value.get("models")
        if not isinstance(models, dict) or not models:
            continue
        provider: dict[str, Any] = {}
        if api is not None:
            provider["api"] = api
        if text(value.get("npm")):
            provider["npm"] = value["npm"].strip()
        provider["models"] = {
            str(model_id): build_model(provider_id, model)
            for model_id, model in models.items()
            if isinstance(model, dict)
        }
        result[provider_id] = provider
    if not result:
        raise CatalogError("source contains no usable providers")
    return result


def render(document: dict[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=False, separators=(",", ":"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=DEFAULT_SOURCE, help="models.dev api.json URL or local path")
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
            current = args.output.read_text(encoding="utf-8") if args.output.is_file() else ""
            if current != rendered:
                print(f"Model protocol catalog is stale: {args.output}", file=sys.stderr)
                return 1
            print(f"Model protocol catalog is current: {len(document)} providers")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8", newline="\n")
        print(f"Generated {len(document)} providers at {args.output}")
        return 0
    except (CatalogError, OSError, json.JSONDecodeError) as error:
        print(f"model protocol catalog generation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
