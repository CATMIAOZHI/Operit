# 每个模型的协议处理

在模型配置的 API 设置中，使用多模态区域的模型选择器选中模型，然后设置「协议处理」。
配置按模型名保存，调整模型顺序不会互换设置；新模型默认继承提供商。

「自动配置协议」主动获取 models.dev 的 `api.json`，按账户端点匹配提供商，再匹配
其下的模型 SDK 与 `interleaved.field`。它会更新匹配模型的协议，保留手填端点；
未匹配或不支持的协议保持原设置。获取失败时使用上次缓存或内置目录，并在结果提示中说明。
等待期间修改过的模型设置不会被覆盖；切换账户端点或模型列表后需重新点击。
Go 使用独立的 `opencode-go` 条目，不使用模型原厂或 Zen 的协议配置。
Go 部分 Qwen 模型的 SDK 记录与官方端点表不同，对已核对的模型以官方 Messages 端点为准。

- **继承提供商**：沿用原有处理方式，已有配置升级后保持原样。
- **Chat Completions（标准）**：使用 OpenAI 兼容格式。
- **Chat Completions（回传 reasoning_content）**：将历史思考内容放入 `reasoning_content`，不自动添加 Kimi 的 `thinking` 参数。
- **OpenAI Responses**：使用 Responses 格式及现有加密思考上下文处理。
- **Anthropic Messages / Gemini 原生**：使用对应的现有协议实现。
- **DeepSeek / Kimi / MiMo**：使用现有专用处理，包括该实现的思考参数与上下文回传。

同一账户仍共享 API Key、自定义请求头和限流设置。模型级设置只决定请求协议与端点；
它不会自动检测服务端支持哪些协议，也不会补全服务端未返回的思考数据。
本地 MNN、llama.cpp 和插件自定义提供商继续使用各自的执行方式。

模型端点留空时继承账户地址，并将标准的 `/chat/completions`、`/responses`、
`/messages` 后缀转换为所选协议对应的路径。基础地址由对应协议继续补全。
非标准路径可为模型单独填写端点；末尾 `#` 延续现有的禁用自动补全语义。

例如 Go 账户地址使用 `https://opencode.ai/zen/go/v1/chat/completions`，
某模型选择 Responses 后会请求同一地址下的 `/v1/responses`；
选择 Anthropic Messages 则请求 `/v1/messages`。
连接测试、聊天、子代理和功能模型均使用选中模型的协议设置。
