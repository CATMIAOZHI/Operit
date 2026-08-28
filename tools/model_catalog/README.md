# Model capability catalog

`model_capabilities_v1.json` is the repository-hosted snapshot used by the
manual multimodal capability sync in Operit.

Regenerate it from the official models.dev catalog:

```powershell
python tools/model_catalog/generate_model_capabilities_catalog.py
python tools/model_catalog/generate_model_capabilities_catalog.py --check
```

The Android app always reads the bundled or previously cached local copy.
Only the explicit “refresh local catalog” action downloads this file from the
matching `personal/dev` or `personal/main` branch.
