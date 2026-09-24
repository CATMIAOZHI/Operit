# Antigravity、Grok 与 Claude 账户提供商

在模型配置中选择 **Google Antigravity**、**Grok（账户登录）** 或 **Claude（账户登录）**，点击登录，在同一设备的浏览器完成授权，然后获取账户可用模型。原有 API Key 配置不受影响。

三者分别保存一个当前账户，使用 Android Keystore 加密凭据；稳定版与开发版独立。Antigravity 默认沿用 opencodex 的公开安装型应用 OAuth 客户端配置，可在折叠的客户端设置中覆盖。客户端元数据不是个人账户授权；登录取得的访问和刷新令牌不会进入模型配置导出或源码。

Grok 使用专用 CLI Chat Completions 端点，并携带账户认证、会话与逻辑请求标识。单次请求重试复用逻辑请求标识。思考内容通过 `reasoning_content` 回传；已知模型按各自支持的档位映射，未知模型保留用户选择。思考开关关闭时不声称可以关闭始终推理的模型，界面显示未传入档位。

Antigravity 登录需成功发现 Cloud Code Assist 项目才保存账户。调用复用 Gemini 原生工具循环，经 CCA 封装、响应解包、工具名称往返映射。模型列表来自账户的 agent 与 tiered 模型目录，保留真实 wire model ID。思考签名随原始回复保存，但只能在同一登录身份与模型下回传；切换账户或模型时移除旧签名。

Claude 走 Claude Code 的订阅凭据，因此请求形状也要像 Claude Code：Bearer 认证、Claude Code 的 beta 与客户端指纹头、每个请求的第一个 system 块固定为 Claude Code 的身份串、自定义工具名在线上带 `custom_` 前缀并在回传时剥掉。前缀只在发往线上时添加，历史回放与工具结果配对始终用原始工具名，否则执行结果会配不上调用。模型列表优先向接口查询，失败时回落到 Claude Code 的内置模型表，让选择器保持可用；订阅额度面板探测 5 小时与每周两个窗口。

关闭登录窗口或超时会释放回调端口。刷新令牌的网络请求最多等待 30 秒；刷新已开始时，即使调用者取消，也完成令牌持久化，避免服务端轮换后丢失新令牌。退出登录清除本应用保存的账户，不操作其它客户端的凭据。

实现参考本地 opencodex 的 `src/oauth/xai.ts`、`src/oauth/google-antigravity.ts`、`src/oauth/anthropic.ts`、`src/adapters/anthropic.ts`、`src/adapters/client-fingerprint.ts`、`src/providers/xai-transport.ts`、`src/providers/antigravity-models.ts`、`src/providers/quota.ts` 与 Google wire compiler。
