<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/readme/orbit-banner-dark.svg">
    <img src="docs/assets/readme/orbit-banner-light.svg" width="100%" alt="Orbit">
  </picture>
</p>

<p align="center">
  <b>A personal AI that lives on your Android phone, remembers you, and grows with you.</b>
</p>

<p align="center">
  <a href="https://github.com/CATMIAOZHI/orbit/releases/latest"><img src="https://img.shields.io/github/v/release/CATMIAOZHI/orbit?label=release&color=3D6FD1" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3D6FD1" alt="Android 8.0+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-LGPL--3.0-D9531F" alt="LGPL-3.0"></a>
  <a href="https://github.com/CATMIAOZHI/orbit/stargazers"><img src="https://img.shields.io/github/stars/CATMIAOZHI/orbit?color=D9531F" alt="Stars"></a>
</p>

<p align="center">
  <a href="https://github.com/CATMIAOZHI/orbit/releases/latest">Download</a> ·
  <a href="#what-orbit-does">Features</a> ·
  <a href="#getting-started">Getting started</a> ·
  <a href="README.zh-CN.md">中文</a>
</p>

---

In a vast universe, you are the sun at the center, and Orbit is the planet that circles you. The logo says exactly that: the core is you; the ring and its two moons are Orbit.

Orbit is a long-term personal agent. It talks with you, remembers what matters, works quietly in the background, and speaks up only when something is worth your attention. Its home is your phone: its memory, files and the traces of its growth are stored on the device, so changing models or providers does not make it forget you.

Orbit ships inside **Operit Ry**, a personal distribution of [Operit](https://github.com/AAswordman/Operit) maintained by [WaterRainCat (水晴喵)](https://github.com/CATMIAOZHI).

> Orbit is developed and released in [CATMIAOZHI/orbit](https://github.com/CATMIAOZHI/orbit); [CATMIAOZHI/Operit](https://github.com/CATMIAOZHI/Operit), the fork of official Operit, is its synced mirror. Both repositories carry the same Releases, so you can download the APK from either; issues and pull requests go to orbit.

## What Orbit does

<p align="center">
  <img src="docs/assets/readme/screens/en/chat.png" width="30%" alt="Chatting with Orbit">
  &nbsp;
  <img src="docs/assets/readme/screens/en/feed.png" width="30%" alt="Orbit's feed">
  &nbsp;
  <img src="docs/assets/readme/screens/en/ideas.png" width="30%" alt="Orbit's ideas">
</p>

<table>
<tr>
<td width="50%" valign="top">

**Remembers you**<br>
Facts, preferences, people and promises are kept in local memory files. Orbit learns from your corrections and turns methods that worked into reusable skills.

</td>
<td width="50%" valign="top">

**Works while you are away**<br>
Reminders, recurring tasks, and watches on notifications, texts, your calendar, web pages or places. A check that finds nothing new stays quiet and costs no tokens.

</td>
</tr>
<tr>
<td valign="top">

**Feed, Ideas and Goals**<br>
Short posts written around your interests, ideas for things to do together, and long-term goals it keeps track of with you.

</td>
<td valign="top">

**Gets things done on the phone**<br>
A browser it can sign in with (you can take over at any time), apps operated on a background screen, a Linux terminal, files, and helper agents for longer jobs.

</td>
</tr>
<tr>
<td valign="top">

**Reads with you**<br>
Read a book in [Legado](https://github.com/CATMIAOZHI/legado) and Orbit writes paragraph comments ahead of you, so its notes are already there when you arrive.

</td>
<td valign="top">

**Reaches you where you are**<br>
Chat and side chats, option buttons and reactions, voice messages, and a WeChat channel for talking without opening the app.

</td>
</tr>
</table>

### A closer look

<table>
<tr>
<td width="33%" align="center"><img src="docs/assets/readme/screens/en/history.png" alt="Task history"><br><sub><b>Everything it did</b>, day by day</sub></td>
<td width="33%" align="center"><img src="docs/assets/readme/screens/en/approvals.png" alt="Approvals"><br><sub><b>Asks before it acts</b>, and keeps a record</sub></td>
<td width="33%" align="center"><img src="docs/assets/readme/screens/en/browser.png" alt="Browser and background apps"><br><sub><b>Browser and background apps</b> you can take over</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/assets/readme/screens/en/schedules.png" alt="Schedules"><br><sub><b>Schedules</b> and watches</sub></td>
<td align="center"><img src="docs/assets/readme/screens/en/goals.png" alt="Goals"><br><sub><b>Goals</b> it follows with you</sub></td>
<td align="center"><img src="docs/assets/readme/screens/en/identity.png" alt="Soul and memory"><br><sub><b>Soul and memory</b> it keeps on your phone</sub></td>
</tr>
</table>

### A few things people use it for

- *"Keep an eye on my messages and tell me about the important ones."*
- *"A parcel code just came in. Is it a new one?"*
- *"Remind me to work out every morning, and ask me again if I skip a week."*
- *"Remind me to take my keys when I leave home."*
- *"Tell me when this page changes."*
- *"Watch my GitHub issues, and when a bug comes in, send a helper to fix it and open a PR."*
- *"Let's read this novel together."*

## Bring your own model

Orbit works with OpenAI, Claude, Gemini, DeepSeek, OpenRouter and Ollama Cloud. Select **OpenAI (Generic)** to enter an OpenAI-compatible endpoint, including one on your local network. You add your own API key where required, and requests go straight from your phone to the service you configure. Operit does not run a model service or relay your chats.

Weekly token allowances and usage statistics keep background work within a budget you set.

## Your data

- Memory, chats, tasks and files are stored on your device. You can export them at any time.
- When Orbit thinks, the context it needs (your messages, relevant memory, tool results) is sent to the model provider you configured. Read that provider's terms to know how it handles data.
- Websites and services Orbit uses on your behalf receive what is sent to them. Passwords and API keys go into a secure vault instead of the chat, and payment is always left to you.

## Getting started

1. Download the latest APK from [Releases](https://github.com/CATMIAOZHI/orbit/releases/latest). It needs Android 8.0 or later on an arm64 phone; 6 GB of RAM or more is recommended.
2. Install it, open the app and agree to the terms.
3. Add a model and its API key.
4. Open Orbit and say hello.

> Only download Operit Ry from the Releases of [CATMIAOZHI/orbit](https://github.com/CATMIAOZHI/orbit/releases) or its mirror [CATMIAOZHI/Operit](https://github.com/CATMIAOZHI/Operit/releases). Packages from other places may have been modified.

Orbit is part of a full Android AI assistant. Beyond Orbit, Operit offers a built-in Ubuntu environment, 40+ tools, MCP servers and Skills, tool packages and workflows, character cards and a floating window. See the [documentation](docs) for more.

## Builds and branches

| | Branch | Package | Updates |
|---|---|---|---|
| **Stable** | [`main`](https://github.com/CATMIAOZHI/orbit/tree/main) | `com.rainy.operitry` | [Releases](https://github.com/CATMIAOZHI/orbit/releases), patch updates between versions |
| **Dev** | [`dev`](https://github.com/CATMIAOZHI/orbit/tree/dev) | `com.rainy.operitry.dev` | [Nightly](https://github.com/CATMIAOZHI/OperitNightlyRelease) |

New features land in `dev` first and move to `main` after testing. The dev build installs alongside the stable one and official Operit.

## Contributing

Issues and pull requests are welcome on [CATMIAOZHI/orbit](https://github.com/CATMIAOZHI/orbit/issues). Please search for existing ones first, and keep API keys, tokens and cookies out of logs and screenshots.

- Build the app: [BUILDING.md](docs/doc-src/dev-core/BUILDING.md)
- Write scripts, Skills and tool packages: [SCRIPT_DEV_GUIDE.md](docs/SCRIPT_DEV_GUIDE.md)

<a href="https://github.com/CATMIAOZHI/orbit/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=CATMIAOZHI/orbit" alt="Contributors">
</a>

## License and credits

Operit Ry is an unofficial distribution based on [AAswordman/Operit](https://github.com/AAswordman/Operit) and keeps the original copyright and license notices. Thanks to the upstream authors and every contributor.

Licensed under [LGPL-3.0](LICENSE).
