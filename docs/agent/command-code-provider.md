# Command Code 账户提供商

在模型设置选择「Command Code（账户登录）」，可通过浏览器登录，或填写 API Key 后点击「验证并保存」。两种入口都用 `/alpha/whoami` 验证身份，再加密保存到本机账户存储；不会进入模型配置导出。取消验证或退出设置不会保存迟到的验证结果，验证失败不会覆盖原账户。此 Key 没有 OAuth 刷新步骤，退出登录只清除本机账户。

模型列表由用户手动获取，使用带 Bearer 的 `/provider/v1/models`，不内置静态模型名单。已知模型按支持的思考档位映射；按用户既定偏好，未知模型直接传入用户选择，接口不支持时正常报告错误。

聊天走 `/alpha/generate` 专有协议。复用现有多轮工具循环和 `reasoning_content` 历史处理，在网络边界转换消息和 NDJSON 事件；用户选择非流式也会在内部消费服务端流式响应。工具调用按 ID 配对，缺失结果明确标记执行状态未知，孤立结果保留为普通文本。图片使用原生 image part，终止事件合并 usage，错误或无终止事件的断流不会伪装为成功。

浏览器登录使用随机 state、本机回环 POST 回调和限定来源的 CORS；首选 5959，端口占用时使用随机端口。会话 2 分钟超时，取消或离开页面释放回调监听。实现参考本地 opencodex 的 `src/oauth/command-code.ts`、`src/adapters/command-code.ts`、`src/providers/command-code-efforts.ts`。
