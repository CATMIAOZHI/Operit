#!/usr/bin/env python3
"""Build the repository fallback for live official model prices."""
import argparse
import hashlib
import json
import math
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
MODELS_URL = "https://models.dev/models.json"
PRICES_URL = "https://models.dev/api.json"
SOURCES = ROOT / "app/src/main/assets/pricing/official_price_sources_v1.json"
OUTPUT = ROOT / "app/src/main/assets/pricing/official_model_pricing_v1.json"


def read(source):
    if str(source).startswith("https://"):
        request = urllib.request.Request(str(source), headers={"User-Agent": "Operit-official-pricing/1"})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    return json.loads(Path(source).read_text(encoding="utf-8"))


def normalize(value):
    return value.strip().lower().replace("_", "-")


def price(row, key):
    value = row.get(key)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0 else None


def context_tier(size, row):
    if not isinstance(size, int) or size < 0:
        raise ValueError("invalid context threshold")
    input_price, output_price = price(row, "input"), price(row, "output")
    if input_price is None or output_price is None:
        raise ValueError("context tier missing input/output price")
    cached = price(row, "cache_read")
    return dict(minInputTokens=size, input=input_price, output=output_price,
                cacheRead=input_price if cached is None else cached, cacheWrite=price(row, "cache_write"))


def build_entries(models, providers, sources):
    entries = []
    names = {key: row["name"] for key, row in models.items() if row.get("name", "").strip()}
    name_counts = Counter(normalize(name) for name in names.values())
    model_ids = {normalize(key) for key in models}
    for official_id in sorted(models):
        owner, short = official_id.split("/", 1)
        source = sources.get(owner)
        if source is None:
            continue
        candidates = [
            row for key, row in providers.get(source, {}).get("models", {}).items()
            if normalize(short) in {
                normalize(key), normalize(row.get("id", key)),
                normalize(row.get("id", key).rsplit("/", 1)[-1]),
            }
        ]
        if len(candidates) != 1:
            continue
        cost = candidates[0].get("cost", {})
        input_price, output_price = price(cost, "input"), price(cost, "output")
        if input_price is None or output_price is None:
            continue
        cached = price(cost, "cache_read")
        tiers = [
            context_tier(row["tier"]["size"], row)
            for row in cost.get("tiers", []) if row.get("tier", {}).get("type") == "context"
        ]
        if not tiers and isinstance(cost.get("context_over_200k"), dict):
            tiers = [context_tier(200_000, cost["context_over_200k"])]
        if len({tier["minInputTokens"] for tier in tiers}) != len(tiers):
            raise ValueError("duplicate context thresholds")
        entries.append(dict(
            provider=owner, model=official_id, billingMode="TOKEN", currency="USD",
            input=input_price, cacheRead=input_price if cached is None else cached,
            cacheWrite=price(cost, "cache_write"), output=output_price, perRequest=None,
            aliases=[names[official_id]] if official_id in names
                and name_counts[normalize(names[official_id])] == 1
                and normalize(names[official_id]) not in model_ids
                and normalize(names[official_id]) != normalize(short) else [],
            sourceUrl=PRICES_URL, verifiedAt=None,
            contextTiers=sorted(tiers, key=lambda tier: tier["minInputTokens"]),
        ))
    if not entries:
        raise ValueError("no matching official prices")
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", default=MODELS_URL)
    parser.add_argument("--prices", default=PRICES_URL)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    entries = build_entries(read(args.models), read(args.prices), read(SOURCES))
    canonical = json.dumps(entries, ensure_ascii=False, separators=(",", ":"))
    revision = "official-" + hashlib.sha256(canonical.encode()).hexdigest()[:16]
    existing = read(args.output) if args.output.is_file() else {}
    generated = (existing.get("generatedAt") if existing.get("revision") == revision else None)
    document = dict(schemaVersion=1, revision=revision,
                    generatedAt=generated or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                    entries=entries)
    rendered = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not args.output.is_file() or args.output.read_text(encoding="utf-8") != rendered:
            raise SystemExit("Official pricing fallback is stale")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(f"Official prices: {len(entries)}, tiered: {sum(bool(e['contextTiers']) for e in entries)}, revision: {revision}")


if __name__ == "__main__":
    main()
