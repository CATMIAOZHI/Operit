# 应用体积精简

## 手机本地大模型

MNN 和 llama.cpp 的运行、模型下载及设置入口已移除。Ollama、LM Studio、
OpenAI 兼容远程服务不受影响，OCR、语音识别及 ONNX 语音功能保留。

旧配置和备份仍可读取，但这两个已退役的提供商不能执行请求或用于新配置。
保留序列化枚举和旧参数，避免删除功能同时破坏用户配置。
用户下载的 `Download/Operit/models` 文件不自动删除；旧配置页提供路径说明，
用户可通过文件管理器清理。

## 依赖、模板和缓存

移除没有业务调用的 TensorFlow Lite、MediaPipe Text、Devanagari OCR。
保留中文、拉丁文、日文、韩文 OCR，以及语音实际使用的 ONNX Runtime。
Android / Flutter 项目共用一份 aapt2，创建项目时仍复制到各自原有工具路径。

技能仓库 ZIP 缓存同时限制为 6 份和 128 MiB。正在导入的 ZIP 持有租约，
不参与并发淘汰；超大 ZIP 可用于当前导入，结束后不缓存。中断下载的临时
文件在下次访问缓存时清理，下载失败或取消也立即清理临时文件。
