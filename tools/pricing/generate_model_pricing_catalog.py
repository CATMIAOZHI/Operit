#!/usr/bin/env python3
"""Generate Operit's pricing catalog from models.dev plus curated overrides."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/pricing/model_pricing_v1.json"
DEFAULT_OVERRIDES = REPO_ROOT / "tools/pricing/model_pricing_overrides_v1.json"
DEFAULT_MODELS_DEV_SOURCE = "https://models.dev/api.json"

# Bootstrap defaults only. The checked-in override document is the maintained
# source of truth after it has been created.
DEFAULT_PROVIDER_MAPPINGS = {
    "aliyun": "alibaba-cn",
    "anthropic": "anthropic",
    "anthropic_generic": "anthropic",
    "deepseek": "deepseek",
    "gemini_generic": "google",
    "google": "google",
    "iflow": "iflowcn",
    "mimo": "xiaomi",
    "mistral": "mistral",
    "moonshot": "moonshotai-cn",
    "novita": "novita-ai",
    "nvidia": "nvidia",
    "openai": "openai",
    "openai_generic": "openai",
    "openai_responses": "openai",
    "openai_responses_generic": "openai",
    "openrouter": "openrouter",
    "siliconflow": "siliconflow-cn",
    "zhipu": "zhipuai",
}

ENTRY_FIELDS = {
    "provider",
    "model",
    "billingMode",
    "currency",
    "input",
    "cacheRead",
    "cacheWrite",
    "output",
    "perRequest",
    "aliases",
    "sourceUrl",
    "verifiedAt",
}


class CatalogError(ValueError):
    pass


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_source(source: str) -> tuple[dict[str, Any], bytes]:
    if source.startswith(("https://", "http://")):
        request = urllib.request.Request(source, headers={"User-Agent": "Operit-pricing-catalog-generator/1"})
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read()
    else:
        raw = Path(source).read_bytes()
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise CatalogError("models.dev source must be a JSON object")
    return parsed, raw


def is_price(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0


def normalize(value: str) -> str:
    return " ".join(value.strip().lower().split())


def validate_entry(entry: dict[str, Any], label: str) -> None:
    if set(entry) != ENTRY_FIELDS:
        raise CatalogError(f"{label} has unexpected or missing fields")
    provider = normalize(str(entry["provider"]))
    model = normalize(str(entry["model"]))
    if not provider or not model:
        raise CatalogError(f"{label} has a blank provider or model")
    if entry["billingMode"] not in {"TOKEN", "COUNT"}:
        raise CatalogError(f"{label} has an invalid billingMode")
    if entry["currency"] not in {"USD", "CNY"}:
        raise CatalogError(f"{label} has an invalid currency")
    for field in ("input", "cacheRead", "cacheWrite", "output", "perRequest"):
        value = entry[field]
        if value is not None and not is_price(value):
            raise CatalogError(f"{label}.{field} must be null or a non-negative number")
    aliases = entry["aliases"]
    if not isinstance(aliases, list) or any(not normalize(str(alias)) for alias in aliases):
        raise CatalogError(f"{label}.aliases must contain non-blank strings")
    if entry["billingMode"] == "COUNT":
        if any(entry[field] is not None for field in ("input", "cacheRead", "cacheWrite", "output")):
            raise CatalogError(f"{label} COUNT pricing cannot contain token prices")
        if entry["perRequest"] is None:
            raise CatalogError(f"{label} COUNT pricing requires perRequest")
    else:
        if entry["input"] is None or entry["cacheRead"] is None or entry["output"] is None:
            raise CatalogError(f"{label} TOKEN pricing requires input, cacheRead, and output")
        if entry["perRequest"] is not None:
            raise CatalogError(f"{label} TOKEN pricing cannot contain perRequest")


def load_overrides(path: Path) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if set(document) != {"schemaVersion", "providerMappings", "entries", "fallbackEntries"}:
        raise CatalogError("override document has unexpected or missing fields")
    if document["schemaVersion"] != 1:
        raise CatalogError("unsupported override schemaVersion")
    mappings = document["providerMappings"]
    if not isinstance(mappings, dict) or not mappings:
        raise CatalogError("providerMappings must be a non-empty object")
    normalized_mappings: dict[str, str] = {}
    for target, source in mappings.items():
        normalized_target = normalize(str(target))
        normalized_source = normalize(str(source))
        if not normalized_target or not normalized_source:
            raise CatalogError("providerMappings cannot contain blank IDs")
        if normalized_target in normalized_mappings:
            raise CatalogError(f"duplicate provider mapping: {normalized_target}")
        normalized_mappings[normalized_target] = normalized_source
    seen_keys: set[str] = set()
    for field in ("entries", "fallbackEntries"):
        entries = document[field]
        if not isinstance(entries, list):
            raise CatalogError(f"override {field} must be an array")
        for index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                raise CatalogError(f"override {field}[{index}] must be an object")
            validate_entry(entry, f"override {field}[{index}]")
            key = f"{normalize(entry['provider'])}\0{normalize(entry['model'])}"
            if key in seen_keys:
                raise CatalogError(f"duplicate override pricing key: {key.replace(chr(0), ':')}")
            seen_keys.add(key)
    return {
        "schemaVersion": 1,
        "providerMappings": normalized_mappings,
        "entries": document["entries"],
        "fallbackEntries": document["fallbackEntries"],
    }


def models_dev_entries(
    source: dict[str, Any],
    mappings: dict[str, str],
    source_url: str,
) -> list[dict[str, Any]]:
    if not source_url.startswith("https://"):
        raise CatalogError("generated sourceUrl must use HTTPS")
    result: list[dict[str, Any]] = []
    for target_provider, source_provider in sorted(mappings.items()):
        provider = source.get(source_provider)
        if not isinstance(provider, dict):
            raise CatalogError(f"models.dev provider is missing: {source_provider}")
        models = provider.get("models")
        if not isinstance(models, dict):
            raise CatalogError(f"models.dev provider has no models object: {source_provider}")

        pending: list[tuple[str, dict[str, Any]]] = []
        exact_ids: set[str] = set()
        for source_key, model in models.items():
            if not isinstance(model, dict):
                continue
            cost = model.get("cost")
            if not isinstance(cost, dict) or not is_price(cost.get("input")) or not is_price(cost.get("output")):
                continue
            model_id = normalize(str(model.get("id") or source_key))
            if not model_id:
                continue
            exact_ids.add(model_id)
            pending.append((str(source_key), model))

        for source_key, model in pending:
            cost = model["cost"]
            model_id = normalize(str(model.get("id") or source_key))
            source_key_normalized = normalize(source_key)
            aliases = []
            if source_key_normalized != model_id and source_key_normalized not in exact_ids:
                aliases.append(source_key_normalized)
            cache_read = cost.get("cache_read")
            entry = {
                "provider": target_provider,
                "model": model_id,
                "billingMode": "TOKEN",
                "currency": "USD",
                "input": float(cost["input"]),
                # Operit's existing accounting contract treats an unpublished
                # cache-read rate conservatively as ordinary input pricing.
                "cacheRead": float(cache_read if is_price(cache_read) else cost["input"]),
                # Missing cache-write pricing stays unknown; it must not become
                # a silent zero when providers report cache-write tokens.
                "cacheWrite": float(cost["cache_write"]) if is_price(cost.get("cache_write")) else None,
                "output": float(cost["output"]),
                "perRequest": None,
                "aliases": aliases,
                "sourceUrl": source_url,
                "verifiedAt": None,
            }
            validate_entry(entry, f"models.dev {source_provider}/{model_id}")
            result.append(entry)
    return result


def merge_entries(
    generated: list[dict[str, Any]],
    fallbacks: list[dict[str, Any]],
    overrides: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for entry in generated:
        key = f"{normalize(entry['provider'])}\0{normalize(entry['model'])}"
        if key in merged:
            raise CatalogError(f"generated pricing key is duplicated: {key.replace(chr(0), ':')}")
        merged[key] = entry
    for entry in fallbacks:
        key = f"{normalize(entry['provider'])}\0{normalize(entry['model'])}"
        merged.setdefault(key, entry)
    for entry in overrides:
        key = f"{normalize(entry['provider'])}\0{normalize(entry['model'])}"
        merged[key] = entry

    exact = set(merged)
    aliases: set[str] = set()
    for key, entry in merged.items():
        provider = normalize(entry["provider"])
        filtered: list[str] = []
        for alias in entry["aliases"]:
            alias_key = f"{provider}\0{normalize(alias)}"
            if alias_key in exact or alias_key in aliases:
                continue
            aliases.add(alias_key)
            filtered.append(normalize(alias))
        entry["aliases"] = sorted(filtered)

    return sorted(
        merged.values(),
        key=lambda entry: (normalize(entry["provider"]), normalize(entry["model"])),
    )


def generated_at_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_document(
    models_dev: dict[str, Any],
    models_dev_raw: bytes,
    overrides: dict[str, Any],
    source_url: str,
    existing: dict[str, Any] | None,
    generated_at: str | None,
) -> dict[str, Any]:
    generated = models_dev_entries(models_dev, overrides["providerMappings"], source_url)
    entries = merge_entries(generated, overrides["fallbackEntries"], overrides["entries"])
    source_hash = sha256_hex(models_dev_raw)[:12]
    override_hash = sha256_hex(canonical_json(overrides))[:12]
    revision = f"models-dev-{source_hash}-operit-{override_hash}"
    timestamp = generated_at
    if timestamp is None and existing and existing.get("revision") == revision:
        timestamp = existing.get("generatedAt")
    if timestamp is None:
        timestamp = generated_at_now()
    return {
        "schemaVersion": 1,
        "revision": revision,
        "generatedAt": timestamp,
        "entries": entries,
    }


def write_json(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_seed_override_document(entries: Any) -> dict[str, Any]:
    if not isinstance(entries, list):
        raise CatalogError("seed catalog has no entries array")
    selected = [
        entry
        for entry in entries
        if isinstance(entry, dict) and (entry.get("billingMode") == "COUNT" or entry.get("currency") == "CNY")
    ]
    fallbacks = [
        entry
        for entry in entries
        if isinstance(entry, dict) and entry.get("billingMode") != "COUNT" and entry.get("currency") != "CNY"
    ]
    for index, entry in enumerate(selected):
        validate_entry(entry, f"seed entry[{index}]")
    for index, entry in enumerate(fallbacks):
        validate_entry(entry, f"seed fallbackEntry[{index}]")
    return {
        "schemaVersion": 1,
        "providerMappings": DEFAULT_PROVIDER_MAPPINGS,
        "entries": sorted(
            selected,
            key=lambda entry: (normalize(entry["provider"]), normalize(entry["model"])),
        ),
        "fallbackEntries": sorted(
            fallbacks,
            key=lambda entry: (normalize(entry["provider"]), normalize(entry["model"])),
        ),
    }


def seed_overrides(source_location: str, target_path: Path) -> None:
    source, _ = read_source(source_location)
    document = build_seed_override_document(source.get("entries"))
    write_json(target_path, document)
    print(
        f"Seeded {len(document['entries'])} overrides and "
        f"{len(document['fallbackEntries'])} fallbacks at {target_path}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models-dev", default=DEFAULT_MODELS_DEV_SOURCE, help="models.dev api.json URL or path")
    parser.add_argument("--source-url", default=DEFAULT_MODELS_DEV_SOURCE, help="sourceUrl recorded in generated rows")
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--generated-at", help="explicit ISO-8601 generatedAt value")
    parser.add_argument("--check", action="store_true", help="fail when the checked-in output differs")
    parser.add_argument(
        "--seed-overrides-from",
        help="create overrides/fallbacks from an existing catalog URL or path",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.seed_overrides_from:
            seed_overrides(args.seed_overrides_from, args.overrides)
        overrides = load_overrides(args.overrides)
        models_dev, raw = read_source(args.models_dev)
        existing = None
        if args.output.is_file():
            existing = json.loads(args.output.read_text(encoding="utf-8"))
        document = build_document(
            models_dev=models_dev,
            models_dev_raw=raw,
            overrides=overrides,
            source_url=args.source_url,
            existing=existing,
            generated_at=args.generated_at,
        )
        rendered = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        if args.check:
            current = args.output.read_text(encoding="utf-8") if args.output.is_file() else ""
            if current != rendered:
                print(f"Pricing catalog is stale: {args.output}", file=sys.stderr)
                return 1
            print(f"Pricing catalog is current: {len(document['entries'])} entries")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(
            f"Generated {len(document['entries'])} entries at {args.output} "
            f"({document['revision']})"
        )
        return 0
    except (CatalogError, OSError, json.JSONDecodeError) as error:
        print(f"pricing catalog generation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
