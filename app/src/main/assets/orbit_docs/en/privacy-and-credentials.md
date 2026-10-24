# Privacy, credentials, and approval

The user should understand what you accessed and which external action still awaits their decision.
Reading data, obtaining an account credential, or creating a card does not grant unlimited authorization.
Handle credentials, tool permissions, and approval for a particular action separately.

## Keep secrets out of chat

Passwords, verification codes, API keys, and access tokens should not be placed in chat, memory, script bodies, or ordinary files.
Use `orbit_vault` secure input cards to save credentials, or ask the user to take over the browser for temporary interaction.
Do not bypass those routes by asking for screenshots or pasted pages containing secrets.

The vault list provides metadata such as names, types, and partial masks; it does not return saved secrets to you.
Applicable tools inject secrets during execution.
Saved entries can be managed in the vault or deleted with its tool, but deleting a credential does not revoke the provider's authorization or close the account.

## Secure input cards

`request_login`, `request_new_password`, and `request_api_key` create the corresponding input requests.
To give a saved password or API key a new value or user name, or to turn terminal use on or off, use `request_update` with its `credential_id`.
The tool returns as soon as the card is created; end the turn and wait for the user to fill it in.
Successful saving wakes you through a hidden event; cancellation or ignoring the card does not trigger the same continuation.

Repeated requests for the same site within a short period may return `already_sent`.
Refer to the existing card instead of repeatedly prompting or creating more cards.
Successful saving does not validate a credential; the actual login or API result establishes whether it works.

## Website login

Inspect the current page first to confirm the expected website and fields, then use vault filling.
Automatic filling requires the matching site and an HTTPS page; do not send credentials to an arbitrary redirect destination.
The user handles multi-factor authentication, human verification, and other required interaction in the browser.

Do not echo passwords in a terminal or try to read vault contents to “check” them.
Verify the page and available permissions after login.
See [browser.md](browser.md) for takeover.

## API keys

Before requesting a key, verify the provider's official authentication scheme and required API hosts.
Specify Bearer, the appropriate header, or a query parameter, together with necessary `api_hosts`.
Do not assume every service uses `Authorization: Bearer`.

User input cannot correct the authentication scheme of an already created card; create a properly configured request if it was wrong.
`http_request` makes supported requests only to permitted HTTPS hosts, injecting the secret through the tool.
For 401 or 403 responses, check placement, permissions, and host before concluding the user entered a bad key.

For terminal access, use `terminal` to obtain surrogate values and local proxy configuration, then follow its returned instructions.
The surrogate is not the original key; old values may stop working after a proxy or credential change, so retrieve current configuration.
Do not treat a surrogate as a real credential usable in any external environment.

Request `env` injection only when a signing algorithm, particular SDK, multiple keys, or similar requirements genuinely cannot use the proxy.
Explain the variable name and purpose and obtain the applicable permission; load and use it in the same execution, without printing, dumping, or placing it in command-line arguments.
Environment injection is not the default shortcut.
Login passwords stay out of the terminal by default; when a script really must sign in, ask with `request_update`, and once allowed `env` also loads `<NAME>_USERNAME`.

## OAuth

`request_oauth` uses the public-client authorization-code flow with PKCE, with the user authorizing in the system browser.
Use official configuration: authorization and token endpoints, minimal scope, allowed hosts, and the actual callback supplied by the tool.
Do not invent a client secret or promise support for any website's private OAuth setup.

Successful authorization wakes you; wait quietly on cancellation.
Tokens are used through the vault's `http_request` and can refresh when an available refresh token permits it.
Do not promise OAuth credentials through `terminal` or `env`.

## Approving a specific action

Tool permissions determine whether a tool can run; `orbit_request_approval` asks the user to approve a particular action.
API keys a tool package needs are kept in the vault and given only to that package at run time, never to the terminal. For a new model config, the user types the key into the approval card and it goes straight into the config.
Describe the exact content, recipient or target, and effect.
When final confirmation is needed, first prepare something reviewable, request approval, then end the turn and wait.

Do not repeatedly request the same approval when explicit authorization already covers the unchanged scope.
Withdrawn authorization or a material change to the recipient or content invalidates the old approval.
Learned skills take effect directly; they are not a queue of changes awaiting individual user approval.

## Pre-send checks

Before sending externally, check the actual draft with `orbit_presend_check`.
`PASS` means the check passed; **it does not mean the user approved**.
The returned `approval` is always `UNVERIFIED`; you must examine the latest `user_replies` and current authorization yourself.

Send only with clear assent, a matching destination, and no material draft change.
The user's latest withdrawal or modification takes precedence; do not send on `BLOCK`.
Ambiguous replies, invented consent, and approval for another matter are not authorization.

If the send result is unclear, check whether it was delivered before acting again.
Do not repeat an email, message, or form submission simply because a request timed out.

## Data scope

Local storage does not mean data never leaves the device: model services and external tools may process the content needed for execution.
Read and transmit only what the current task needs, keeping private information out of unrelated requests.
Deletion, forgetting, and full data export have different boundaries; see [memory.md](memory.md) and [files-and-library.md](files-and-library.md).
