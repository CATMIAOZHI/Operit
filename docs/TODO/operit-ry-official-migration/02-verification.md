# Verification

## Static review

- Confirmed normal restore still requires the current package name.
- Confirmed migration accepts only the official package name.
- Confirmed both published format version 1 manifest layouts are accepted.
- Confirmed unknown, empty, and path-escaping payloads are rejected before stores close.
- Confirmed the safety snapshot and migration share one process mutex.
- Confirmed cancellation cannot interrupt the destructive phase.
- Confirmed success and destructive-phase failure both terminate the stale process.
- Confirmed clone application variants do not display the migration action.

## Automated coverage

`RawSnapshotPackagePolicyTest` covers official and Ry package isolation, empty payload rejection, changed manifest rejection, and the legacy manifest layout.

Gradle tests and builds were not run locally, in accordance with the repository execution policy. The current Android workflow is manual and does not run automatically for this pull request.

[DONE]
