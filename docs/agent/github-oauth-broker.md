# GitHub OAuth broker contract

This document defines the personal-service boundary used by the Android GitHub login flow. The
broker is intentionally not tied to a cloud vendor. Deploy it under an operator-controlled HTTPS
origin and set that origin as `GITHUB_OAUTH_BROKER_BASE_URL` in the build's ignored
`local.properties`.

## Trust boundary

- The APK contains the OAuth app's public `client_id`, but never its `client_secret`.
- The broker reads `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` from its secret store.
- The broker accepts only the exact registered redirect URI
  `operitry://github-oauth-callback`; it must not forward an arbitrary request value.
- The broker does not persist authorization codes, PKCE verifiers, access tokens, or refresh
  tokens and does not place them in logs.
- Apply request size limits, rate limiting, a short request timeout, and generic external error
  responses. Browser origins do not need access to this endpoint, so CORS should remain disabled.

## Exchange endpoint

`POST /oauth/github/exchange`

Request body:

```json
{
  "code": "github-authorization-code",
  "codeVerifier": "pkce-verifier-generated-by-the-app",
  "redirectUri": "operitry://github-oauth-callback"
}
```

After verifying the redirect URI, the broker posts the following form fields to
`https://github.com/login/oauth/access_token`:

```text
client_id=<server-side GITHUB_CLIENT_ID>
client_secret=<server-side GITHUB_CLIENT_SECRET>
code=<request code>
code_verifier=<request codeVerifier>
redirect_uri=operitry://github-oauth-callback
```

It requests `Accept: application/json`, converts GitHub's snake-case response to the following
camel-case response, and returns no cacheable content:

```json
{
  "accessToken": "token",
  "tokenType": "bearer",
  "scope": "notifications,public_repo,user:email,read:user",
  "expiresIn": null,
  "refreshToken": null
}
```

Return `200` only for a complete, structurally valid token response. For all other cases return a
generic non-2xx response without including GitHub's code, token, secret, or upstream response body.

## Android flow

1. Generate `state` and `code_verifier` with `SecureRandom` and derive an S256
   `code_challenge`.
2. Persist the state and verifier together before opening GitHub authorization.
3. On the app-owned callback, consume the pending request once and verify `state`.
4. Send the authorization code and verifier to the broker.
5. Validate the returned token against GitHub `/user`; persist it only after identity validation.

Changing this protocol requires coordinated app and broker deployment. A build with a blank or
non-HTTPS broker URL fails before opening the authorization browser.
