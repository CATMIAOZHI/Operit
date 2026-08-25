# Model pricing catalog

Operit's checked-in pricing catalog is generated from the public
[`models.dev/api.json`](https://models.dev/api.json) snapshot and then overlaid
with `model_pricing_overrides_v1.json`.

The override file contains:

- mappings from Operit runtime provider IDs to models.dev provider IDs;
- provider/model rows that Operit intentionally owns, currently count-based
  products and CNY-denominated prices.
- legacy USD token rows used only when models.dev no longer carries the model,
  so historical model pricing remains available without overriding fresh data.

Generate or refresh the catalog from the repository root:

```powershell
python tools/pricing/generate_model_pricing_catalog.py
```

Check that the checked-in catalog matches the current source and overrides:

```powershell
python tools/pricing/generate_model_pricing_catalog.py --check
```

For an offline or reviewable update, download `api.json` first and pass its
path through `--models-dev`. The generated revision contains hashes of both
the models.dev snapshot and the Operit override document.

`--seed-overrides-from` exists only for explicitly rebuilding the layers from
a legacy catalog. It makes COUNT/CNY rows authoritative and keeps other rows as
missing-model fallbacks; review its output before replacing the maintained
override file.
