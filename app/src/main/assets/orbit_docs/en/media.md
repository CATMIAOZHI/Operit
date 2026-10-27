# Images, video and audio

You can generate images or videos through imported, enabled tool packages.
Orbit has no separate built-in image generation interface; available capabilities depend on the current packages, accounts, permissions and actual service results.
Read the tools first instead of assuming a model can generate images or videos from its name.

## Prepare a package

Use `orbit_packages action=list` to check imported packages, their switches and required environment variables.
If a needed package is still in `available`, request it with `request_import` and `packages`; if it is imported but off, request `request_toggle` with `on`.
These actions create approval cards. End the turn with one short line, wait for user approval and the app's hidden result, then read the tools with `use_package`.
See [connectors.md](connectors.md) for batch imports and switching rules.

`codex_draw` uses the app's ChatGPT login and needs no separate image API key.
If signed out, put a login card in chat with `orbit_vault action=request_chatgpt_login`, then end the turn and wait. A successful login sends you a hidden notice.

`grok_draw` uses the app's Grok account login (Grok Imagine) and also needs no API key, but the account must include image generation (a paid plan such as SuperGrok; not Free or X Basic).
If signed out, put a login card in chat with `orbit_vault action=request_account_login provider=grok`, then end the turn and wait. HTTP 403 means the account has no image access; tell the user plainly and do not keep retrying.

`siliconflow_draw` requires `SILICONFLOW_API_KEY`.
Use `orbit_vault action=request_api_key` with `provider=SiliconFlow`, `package_name=siliconflow_draw` and `env_name=SILICONFLOW_API_KEY`.
The user enters it on the vault card. The key is supplied only while that package runs and is not available to the terminal; never ask for it in chat or write it into a script.
Saving confirms configuration was saved; an actual generation result establishes whether the service works.

## Images

With `codex_draw`, first call `codex_draw:read_skill` for image creation guidance.
For a new image, use `codex_draw:draw_image` with required `prompt`; put up to 5 reference images in `referenced_image_paths`.
To edit an existing image, use `codex_draw:edit_image` with required `prompt` and `image_path`; extra references also go in `referenced_image_paths`, with at most 5 images including the edit target.
`grok_draw` works the same way: first `grok_draw:read_skill`, then `grok_draw:draw_image` for new images and `grok_draw:edit_image` for edits. It sets the shape with `aspect_ratio` (such as 1:1, 16:9, 9:16, 2:3; default auto) and has no size, quality or background parameters.
When both are available and the user has no preference, pick the one that is signed in and has quota.
These inputs must be real, readable local PNG, JPEG or WebP files. A website URL is not a local path.

Distinguish references from edit targets: the new-image tool rejects `image_path`, while editing saves a new file.
Preserve the user's purpose, subject, style, text and constraints in the prompt. For edits, specify what to change and preserve without promising pixel-perfect preservation.
With `codex_draw`, `background=transparent` requests transparency; use the tool documentation for size, quality and other options.

`siliconflow_draw:draw_image` also generates images, with required `prompt` and optional parameters such as `model`, `image_size` and `batch_size`.
Do not copy parameters between packages or treat an interface parameter as a guarantee that every service model supports it.

## Video

`siliconflow_draw:draw_video` supports text-to-video with required `prompt`.
For image-to-video, supply `image_path` or `image_url`; local images are converted and uploaded to the service.
Optional parameters include `model`, `image_size`, `poll_interval_ms` and `max_wait_time_ms`. Use values supported by the tool and service.
The tool submits a task, polls its status and downloads a successful result. Submission, polling or a timeout does not mean a video has been delivered.
This tool has no audio-track or duration parameter. Do not promise specified music, narration or arbitrary durations.

## Delivery

After success, check the returned local files and displayable results. Reading guidance or completing login is not successful generation.
You can display the returned Markdown for images. To save a result in Orbit's file area and deliver a chat attachment, use `orbit_file action=import` with the generated file's absolute path in `source`; optionally set `path` to a location under `files/`.
Then use `orbit_file action=send` with the relative `path` returned by import. Images, video and audio can all be delivered this way.
Do not report only a device path or claim an external package's download folder is already in Orbit's file library.
Previews depend on format and device support; see [files-and-library.md](files-and-library.md).

## Audio

Use `orbit_voice` actions `speak` and `podcast` for voice messages and multi-speaker scripted audio; see [voice.md](voice.md).
These capabilities do not establish music generation, song identification or live calling. Check current tools for other audio capabilities.
