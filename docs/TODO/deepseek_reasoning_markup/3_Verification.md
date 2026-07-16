# Compatibility and verification

- 覆盖 `&`、`<`、`>` 和非递归单次解码
- 覆盖任意 reasoning chunk 切分和纯空白 chunk
- 覆盖流式、非流式和 ToolCall 开关两种状态
- 验证 reasoning 内伪工具不执行，真实原生工具只执行一次
- 验证旧无 marker 历史解释规则不变
- 通过 fork 的手动 GitHub Action 构建 debug APK
