import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "tools/pricing/generate_official_model_pricing_catalog.py"
SPEC = importlib.util.spec_from_file_location("official_pricing", SCRIPT)
pricing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pricing)


class OfficialPricingGeneratorTest(unittest.TestCase):
    def test_official_source_alias_and_tiers(self):
        result = pricing.build_entries(
            {"vendor/model": {"name": "Official Model"}},
            {
                "official": {"models": {"model": {"cost": {
                    "input": 1, "output": 2,
                    "tiers": [{"tier": {"type": "context", "size": 200000},
                               "input": 3, "output": 4, "cache_read": 0}],
                }}}},
                "reseller": {"models": {"model": {"cost": {"input": 0, "output": 0}}}},
            },
            {"vendor": "official"},
        )
        self.assertEqual(result[0]["input"], 1)
        self.assertEqual(result[0]["aliases"], ["Official Model"])
        self.assertIsNone(result[0]["cacheWrite"])
        self.assertEqual(result[0]["contextTiers"][0]["cacheRead"], 0)

    def test_ambiguous_names_omitted_and_legacy_context_converted(self):
        entries = pricing.build_entries(
            {f"vendor/{name}": {"name": "Shared"} for name in ["a", "b"]},
            {"official": {"models": {name: {"cost": {
                "input": 1, "output": 2, "context_over_200k": {"input": 3, "output": 4}
            }} for name in ["a", "b"]}}},
            {"vendor": "official"},
        )
        self.assertTrue(all(not entry["aliases"] for entry in entries))
        self.assertEqual(entries[0]["contextTiers"][0]["minInputTokens"], 200000)

    def test_does_not_guess_version_or_reseller(self):
        with self.assertRaisesRegex(ValueError, "no matching"):
            pricing.build_entries(
                {"vendor/model": {}},
                {"official": {"models": {"model-20260906": {"cost": {"input": 1, "output": 2}}}}},
                {"vendor": "official"},
            )


if __name__ == "__main__":
    unittest.main()
