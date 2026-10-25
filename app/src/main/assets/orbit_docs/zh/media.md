# 图片、视频与音频

你可以通过已导入并启用的工具包生成图片或视频。
Orbit 自身没有独立的内置生图接口；具体能力由当前工具包、账号、权限与服务的实际结果决定。
先读工具说明，不根据模型名称猜测它会绘图或生成视频。

## 准备工具包

先用 `orbit_packages action=list` 查看已导入、已启用的包与所需环境变量。
需要的包还在 `available` 中时，用 `request_import` 的 `packages` 申请导入；已有包关闭时，用 `request_toggle` 的 `on` 申请开启。
这些操作会创建批准卡片，随后用一句话结束本轮，等用户批准及 App 的隐藏结果，再用 `use_package` 读取工具。
批量导入与开关规则见 [connectors.md](connectors.md)。

`codex_draw` 使用应用内的 ChatGPT 登录，不需要单独的绘图 API Key。
未登录时，用 `orbit_vault action=request_chatgpt_login` 放登录卡片，结束本轮等待；登录成功后会收到隐藏通知。

`siliconflow_draw` 需要 `SILICONFLOW_API_KEY`。
用 `orbit_vault action=request_api_key`，填 `provider=SiliconFlow`、`package_name=siliconflow_draw` 和 `env_name=SILICONFLOW_API_KEY`。
用户在保险箱卡片里填写，密钥只在该包运行时提供，不进终端；不要在聊天里索要或手动写入脚本。
保存成功只说明配置已保存，实际生成结果才证明服务可用。

## 图片

使用 `codex_draw` 时，先调用 `codex_draw:read_skill` 读取图片创作指导。
新图用 `codex_draw:draw_image`，必填 `prompt`；参考图放在 `referenced_image_paths`，最多 5 张。
修改已有图片用 `codex_draw:edit_image`，必填 `prompt` 和 `image_path`；额外参考图仍放在 `referenced_image_paths`，连同编辑目标最多 5 张。
这些输入需要真实可读取的本地 PNG、JPEG 或 WebP 文件，不能拿网页地址冒充本地路径。

参考图和编辑目标要分清：新图接口不接受 `image_path`，编辑接口会另存新文件。
提示词保留用户的用途、主体、风格、文字和限制；编辑时说明要改什么、保留什么，不承诺逐像素保留。
`background=transparent` 可请求透明背景，其他尺寸、质量等选项以工具说明为准。

`siliconflow_draw:draw_image` 也可生成图片，必填 `prompt`，可选 `model`、`image_size`、`batch_size` 等参数。
不要把一个包的参数照搬给另一个包，也不要把接口参数当成所有服务模型都支持的保证。

## 视频

`siliconflow_draw:draw_video` 支持文本生成视频，必填 `prompt`。
图生视频可提供 `image_path` 或 `image_url`；本地图片会转换并上传给服务。
可选 `model`、`image_size`、`poll_interval_ms` 和 `max_wait_time_ms`，按工具与服务实际支持的值填写。
工具会提交任务、轮询状态并下载成功结果；提交成功、轮询中或超时都不等于已经交付视频。
这个工具没有音轨或视频时长参数，不承诺可以指定配乐、配音或任意时长。

## 交付

成功后检查工具返回的本地文件与可展示结果，不把阅读指导或登录完成当成生成成功。
图片可展示工具返回的 Markdown；要保存到 Orbit 的文件区并作为聊天附件交付，用 `orbit_file action=import`，`source` 填生成文件的绝对路径，可用 `path` 指定 `files/` 下的位置。
再用 `orbit_file action=send`，`path` 填导入结果返回的相对路径。图片、视频与音频都可按此交付。
不要只报一个设备路径，也不要把外部包的下载目录说成已经进入 Orbit 的文件库。
预览仍取决于格式和设备支持；文件规则见 [files-and-library.md](files-and-library.md)。

## 音频

语音消息与多人台词音频使用 `orbit_voice` 的 `speak`、`podcast`，详情见 [voice.md](voice.md)。
这些能力不等于音乐生成、歌曲识别或实时通话；其他音频能力须查看当前实际工具。
