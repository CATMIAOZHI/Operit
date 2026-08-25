import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "tools/pricing/generate_model_pricing_catalog.py"
SPEC = importlib.util.spec_from_file_location("pricing_catalog_generator", SCRIPT)
assert SPEC and SPEC.loader
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


def entry(
    provider: str,
    model: str,
    *,
    billing_mode: str = "TOKEN",
    currency: str = "USD",
    input_price: float | None = 1.0,
    cache_read: float | None = 1.0,
    cache_write: float | None = None,
    output_price: float | None = 2.0,
    per_request: float | None = None,
) -> dict:
    return {
        "provider": provider,
        "model": model,
        "billingMode": billing_mode,
        "currency": currency,
        "input": input_price,
        "cacheRead": cache_read,
        "cacheWrite": cache_write,
        "output": output_price,
        "perRequest": per_request,
        "aliases": [],
        "sourceUrl": None,
        "verifiedAt": None,
    }


class PricingCatalogGeneratorTest(unittest.TestCase):
    def test_models_dev_base_is_mapped_and_operit_override_wins(self):
        models_dev = {
            "source-provider": {
                "models": {
                    "model-a": {
                        "id": "model-a",
                        "cost": {
                            "input": 1,
                            "output": 2,
                            "cache_read": 0.1,
                            "cache_write": 0.2,
                        },
                    },
                    "model-b": {
                        "id": "model-b",
                        "cost": {"input": 3, "output": 4},
                    },
                    "no-public-price": {"id": "no-public-price"},
                }
            }
        }
        overrides = {
            "schemaVersion": 1,
            "providerMappings": {"operit-provider": "source-provider"},
            "entries": [
                entry(
                    "operit-provider",
                    "model-a",
                    currency="CNY",
                    input_price=9,
                    cache_read=8,
                    output_price=10,
                )
            ],
            "fallbackEntries": [
                entry("operit-provider", "model-b", input_price=30, cache_read=30, output_price=40),
                entry("operit-provider", "legacy-only", input_price=5, cache_read=5, output_price=6),
            ],
        }

        document = generator.build_document(
            models_dev=models_dev,
            models_dev_raw=generator.canonical_json(models_dev),
            overrides=overrides,
            source_url="https://models.dev/api.json",
            existing=None,
            generated_at="2026-08-24T00:00:00Z",
        )
        rows = {row["model"]: row for row in document["entries"]}

        self.assertEqual({"model-a", "model-b", "legacy-only"}, set(rows))
        self.assertEqual("CNY", rows["model-a"]["currency"])
        self.assertEqual(9, rows["model-a"]["input"])
        # A fallback cannot replace fresh models.dev data.
        self.assertEqual(3.0, rows["model-b"]["cacheRead"])
        self.assertIsNone(rows["model-b"]["cacheWrite"])
        self.assertEqual("https://models.dev/api.json", rows["model-b"]["sourceUrl"])
        self.assertEqual(5, rows["legacy-only"]["input"])

    def test_seed_keeps_only_count_or_cny_owned_rows(self):
        source = {
            "entries": [
                entry("openai", "usd-token"),
                entry("aliyun", "cny-token", currency="CNY"),
                entry(
                    "google",
                    "count-product",
                    billing_mode="COUNT",
                    input_price=None,
                    cache_read=None,
                    output_price=None,
                    per_request=0.01,
                ),
            ]
        }
        seeded = generator.build_seed_override_document(source["entries"])

        self.assertEqual(
            {("aliyun", "cny-token"), ("google", "count-product")},
            {(row["provider"], row["model"]) for row in seeded["entries"]},
        )
        self.assertEqual(
            {("openai", "usd-token")},
            {(row["provider"], row["model"]) for row in seeded["fallbackEntries"]},
        )
        self.assertEqual(generator.DEFAULT_PROVIDER_MAPPINGS, seeded["providerMappings"])


if __name__ == "__main__":
    unittest.main()
