# Tool packages and account connections

Tool packages determine what you can call; account connections determine whether a service is usable.
Importing a package, enabling it, saving a key, signing in and authorizing a specific action are separate states.
Use current tool documentation and actual results instead of inferring permissions from earlier chats or service names.

## Default core packages

Every Orbit keeps these core packages imported and enabled. They cannot be switched off or removed:

| Package | Purpose |
|---|---|
| `super_admin` | Terminal |
| `various_search` | Web search |
| `memory_review` | Memory tools |
| `dynamic_ui` | Dynamic interfaces and cards |
| `browser` | Browser |

Always enabled does not guarantee permission for every operation or that required components are installed.
Import other packages as needed. Orbit's package list and switches are independent of the main app and do not follow later changes to its switches.
`extended_chat` is unavailable to Orbit; use `orbit_chat` for your own chats.

## Inspect and import

Use `orbit_packages action=list` for `imported` and `available`; optionally filter names or descriptions with `query`.
In `imported`, `on` is the current switch and `always_on` marks packages that cannot be disabled. `env` lists declared variables, whether they are required and whether they are set.
A set variable does not establish a valid key or a connected account.

Import with `orbit_packages action=request_import`:

- `packages` contains comma-separated names from `available`, at most 20 per request.
- `paths` contains device package files or folders, separated by commas or newlines. Supported files are `.js`, `.ts`, `.hjson` and `.toolpkg`; folders expand only package files directly inside them, at most 20 files in total.
- Both sources can appear in one request. Optional `title` and `reason` briefly explain the purpose. Split requests whose card text is too long.

Request only packages needed for the task; read an unfamiliar file package first.
After the tool creates an approval card, end the turn with one short line and wait for approval.
The app then imports and enables packages and sends a hidden result with successes, failures and missing keys or logins.
Follow that result and read successfully imported packages with `use_package`. Creating a card is not a completed import.

## Switch packages

For imported packages, request switches with `orbit_packages action=request_toggle`.
Put comma-separated names in `on` and `off`; never put a package in both or request its existing state.
At most 20 packages can change at once, including dependencies that must also be enabled. Import missing dependencies first; dependencies needing activation appear on the card.
Core packages cannot be disabled, nor can a package still needed by another enabled package.

End the turn after the card. The app executes approved changes and notifies you of the result.
Use `use_package` as needed for newly enabled packages and stop using disabled packages.
Do not bypass approval by editing configuration files or using other settings tools.

## API keys

When a package lacks a key, create a secure entry card with `orbit_vault action=request_api_key`.
Set `provider` to the service name, `package_name` to the package, and `env_name` to the exact uppercase variable it requires.
For package keys, omit `api_hosts`; `env_reason` is unnecessary.
After the user saves it, the key is supplied only while that package runs. Other packages and the terminal cannot access it; there is no need to put it in the main app's environment variables.

Never request secrets in chat or read or echo saved values.
To update a saved key, use `request_update` with `credential_id`; see [privacy-and-credentials.md](privacy-and-credentials.md) for details.

## Account login and OAuth

For ChatGPT/Codex, use `orbit_vault action=request_chatgpt_login`; this login also serves `codex_draw`.
For Claude, Grok, Antigravity and Command Code subscription accounts, use `request_account_login` with `provider` set to `claude`, `grok`, `antigravity` or `command_code`, respectively.
End the turn after sending a new login card. Success sends a hidden notice; skipping does not. Reuse the existing card on `already_sent`; `already_logged_in` means the app holds a saved login.
Using these accounts as chat models also requires a matching model configuration. The user can choose “Add model” on the login card, after which you can request a switch with `orbit_models action=request_use`.
Account login does not import tool packages or grant arbitrary website or service actions.

For other services, `orbit_vault action=request_oauth` supports public-client authorization code with PKCE when the actual interface permits it.
It requires official authorization settings and the registered actual callback address, and does not support flows requiring a client secret. OAuth tokens are used through the vault's `http_request`; do not promise automatic access from packages.
Use browser handoff when website login or verification requires the user; see [browser.md](browser.md).

## Verify a connection

Check package switches, missing variables and current login-card results or hidden notices first.
Read the actual tools with `use_package`. Prefer a documented status check when provided; otherwise verify with the smallest necessary call within the authorized task.
There is no universal account-status command for all packages. Do not invent `status` commands or login entry points.
Saved keys and login state do not guarantee continued server acceptance. Explain authentication, permission, network and rate-limit errors according to their actual results.
Do not send messages, publish content or repeat potentially successful writes just to test a connection.
