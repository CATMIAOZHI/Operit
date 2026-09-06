# Official model pricing catalog

Pricing management downloads official identities from `https://models.dev/models.json`
and official API prices from `https://models.dev/api.json`. Both requests must succeed
before a candidate is published. If either times out, the app downloads the complete
`official_model_pricing_v1.json` from the matching Dev/stable branch. Other failures
preserve the previous directory and report the error.

Downloads require a click. **Replace built-in prices** applies the downloaded candidate
and persists it across restarts; later downloads require another explicit application.
API-config, provider/model and legacy manual prices retain priority. No startup network
refresh or directory restore button is provided.

Matching ignores the configured API provider. Exact official IDs, unique short IDs,
recognized routing prefixes and unique official display names are supported. Ambiguous
or missing identities stay unknown; versions are not guessed.
`official_price_sources_v1.json` explicitly maps each model owner to its official API
source, including the chosen regional API. Reseller and subscription-plan prices are
not substituted. The same policy is used by the app and generator.

Context tiers apply to the whole request when its total input tokens strictly exceed
a threshold. The highest matching threshold wins. Cache reads and independently billed
cache writes count toward context, but output does not. Manual prices override the
selected tier. Historical events keep their frozen scalar prices and costs; current-price
revaluation selects the tier per event. Tiered requests with unknown context or aggregated
retries remain unknown unless complete manual pricing is supplied. Audio-specific pricing
is not represented.

Generate the repository fallback from the repository root:

```powershell
python tools/pricing/generate_official_model_pricing_catalog.py
```

For reproducible offline updates, pass downloaded source files:

```powershell
python tools/pricing/generate_official_model_pricing_catalog.py --models models.json --prices api.json
python tools/pricing/generate_official_model_pricing_catalog.py --models models.json --prices api.json --check
```

Commit the resulting asset when publishing so timeout fallback can download it.
The old `generate_model_pricing_catalog.py` and `model_pricing_v1.json` belong to the
previous provider-specific contract; their caches are not interpreted as official prices.
