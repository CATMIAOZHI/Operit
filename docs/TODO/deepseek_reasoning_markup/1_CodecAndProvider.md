# Codec and Provider boundary

- 新增 DeepSeek 专用 `xml-text-v1` codec
- OpenAI Provider 提供默认无行为变化的 reasoning emission seam
- DeepSeek 流式与非流式输出统一编码，token 统计使用原始文本
- DeepSeek 历史只对精确版本 marker 解码
- 工具子轮次逐字符保留 reasoning，不 trim、不插入换行
- 原生工具调用保持在已关闭的 think block 外

[DONE]
