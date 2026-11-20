# Model catalogs

Operit ships two snapshots of models.dev under `app/src/main/assets/model_catalog/`:

- `model_capabilities_v1.json`: each official model's inputs (picture, audio,
  video) and its context window and longest reply, matched by model name.
- `model_protocols_v1.json`: each provider's models with the SDK, reasoning
  fields and thinking levels, matched by the account's API address.

Regenerate both from models.dev:

```powershell
python tools/model_catalog/generate_model_capabilities_catalog.py
python tools/model_catalog/generate_model_capabilities_catalog.py --check
python tools/model_catalog/generate_model_protocols_catalog.py
python tools/model_catalog/generate_model_protocols_catalog.py --check
```

Matching only reads the bundled or previously cached local copy. The app
downloads new copies once a week in the background and when the user taps
“Re-match” in the model settings. The capability catalog comes from
`https://models.dev/models.json`, falling back on timeout to this repository's
copy on the matching `dev` or `main` branch; the protocol catalog comes from
`https://models.dev/api.json`. A failed download keeps the previous local copy.

The protocol generator keeps providers listed without an `api` address only for
the official providers the app gives a default address to
(`ModelProtocolCatalog.OFFICIAL_ENDPOINTS`); change both together.
