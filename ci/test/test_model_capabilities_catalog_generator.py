import importlib.util
import unittest
from pathlib import Path


SCRIPT = (
    Path(__file__).resolve().parents[2]
    / "tools/model_catalog/generate_model_capabilities_catalog.py"
)
SPEC = importlib.util.spec_from_file_location(
    "model_capabilities_catalog_generator",
    SCRIPT,
)
assert SPEC and SPEC.loader
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class ModelCapabilitiesCatalogGeneratorTest(unittest.TestCase):
    def test_builds_a_compact_deterministic_official_catalog(self):
        source = {
            "vendor/model-b": {
                "name": "Model B",
                "family": "family-b",
                "modalities": {
                    "input": ["video", "text", "image", "image", "unknown"],
                    "output": ["text"],
                },
                "cost": {"input": 1, "output": 2},
            },
            "vendor/model-a": {
                "name": "Model A",
                "family": "family-a",
                "modalities": {"input": ["text"]},
            },
            "vendor/embedding": {
                "name": "Embedding",
                "modalities": {"input": ["embedding"]},
            },
        }

        document = generator.build_document(source)

        self.assertEqual(
            ["vendor/model-a", "vendor/model-b"],
            list(document),
        )
        self.assertEqual(
            ["image", "text", "video"],
            document["vendor/model-b"]["modalities"]["input"],
        )
        self.assertNotIn("cost", document["vendor/model-b"])

    def test_rejects_noncanonical_model_ids(self):
        with self.assertRaises(generator.CatalogError):
            generator.build_document(
                {
                    "model-without-owner": {
                        "modalities": {"input": ["text"]},
                    }
                }
            )


if __name__ == "__main__":
    unittest.main()
