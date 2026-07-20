# Release signing

- Keep the Operit Ry release keystore and its recovery information outside the repository.
- Store the CI copy in GitHub Actions repository secrets.
- Require CI to compare the decoded keystore with the pinned certificate fingerprint before building.
- Verify every generated APK or AAB against the same certificate before uploading it.

## Certificate

- Alias: `operit-ry-release`
- Subject: `CN=Rainy, OU=Operit Ry, O=Rainy, C=CN`
- Type: JKS
- Key: RSA 4096 / SHA256withRSA
- Validity: 36500 days
- SHA-256: `40:F8:7A:4D:66:EB:70:D0:E2:D1:37:9C:6A:97:DD:DC:0C:ED:D3:BA:87:2E:02:74:50:CA:77:AF:42:EC:5E:74`

## GitHub Actions secrets

- `OPERIT_RELEASE_KEYSTORE_BASE64`
- `OPERIT_RELEASE_STORE_PASSWORD`
- `OPERIT_RELEASE_KEY_ALIAS`
- `OPERIT_RELEASE_KEY_PASSWORD`

The secret values and keystore bytes must never be committed, printed in CI logs, or stored in project documentation.

## Verification

- Workflow: `Android Build`
- Run: https://github.com/CATMIAOZHI/Operit/actions/runs/29783010513
- Commit: `225fc9d65592cea792a3e298e0d95ca89dec7126`
- Task: `:app:bundleRelease`
- Result: release bundle build, certificate verification, and artifact upload succeeded.
- Artifact: `operit-android-3`
- Artifact archive SHA-256: `109f4b0e3992d08c636d5e087f7be59513a5dd8eb599b3a5a77e4e9063192420`

[DONE]
