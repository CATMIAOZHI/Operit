# Antigravity 与 Grok 账户提供商

在模型配置中选择 **Google Antigravity** 或 **Grok（账户登录）**，点击登录，在同一设备的浏览器完成授权，然后获取账户可用模型。原有 API Key 配置不受影响。

两者分别保存一个当前账户，使用 Android Keystore 加密凭据；稳定版与开发版独立。Antigravity 默认沿用 opencodex 的公开安装型应用 OAuth 客户端配置，可在折叠的客户端设置中覆盖。客户端元数据不是个人账户授权；登录取得的访问和刷新令牌不会进入模型配置导出或源码。

Grok 使用专用 CLI Chat Completions 端点，并携带账户认证、会话与逻辑请求标识。单次请求重试复用逻辑请求标识。思考内容通过 `reasoning_content` 回传；已知模型按各自支持的档位映射，未知模型保留用户选择。思考开关关闭时不声称可以关闭始终推理的模型，界面显示未传入档位。

Antigravity 登录需成功发现 Cloud Code Assist 项目才保存账户。调用复用 Gemini 原生工具循环，经 CCA 封装、响应解包、工具名称往返映射。模型列表来自账户的 agent 与 tiered 模型目录，保留真实 wire model ID。思考签名随原始回复保存，但只能在同一登录身份与模型下回传；切换账户或模型时移除旧签名。

关闭登录窗口或超时会释放回调端口。刷新令牌的网络请求最多等待 30 秒；刷新已开始时，即使调用者取消，也完成令牌持久化，避免服务端轮换后丢失新令牌。退出登录清除本应用保存的账户，不操作其它客户端的凭据。

实现参考本地 opencodex 的 `src/oauth/xai.ts`、`src/oauth/google-antigravity.ts`、`src/providers/xai-transport.ts`、`src/providers/antigravity-models.ts` 与 Google wire compiler。
