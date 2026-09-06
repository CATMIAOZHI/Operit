# Model capability catalog

`model_capabilities_v1.json` is the repository-hosted snapshot used by the
manual multimodal capability sync in Operit.

Regenerate it from the official models.dev catalog:

```powershell
python tools/model_catalog/generate_model_capabilities_catalog.py
python tools/model_catalog/generate_model_capabilities_catalog.py --check
```

Normal reads use the bundled or previously cached local copy. The explicit
“refresh local catalog” action first downloads `https://models.dev/models.json`.
On timeout it falls back to this file from the matching `personal/dev` or
`personal/main` branch. Other failures preserve the previous local catalog.
The settings page displays the last successful local update time (also restored
after restart), or indicates that it is using the bundled copy. Refreshing the
directory does not itself overwrite the user's configured model capabilities;
the separate sync action applies matching entries.
