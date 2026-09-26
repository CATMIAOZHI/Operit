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

## 按需资源

离线 NCNN 语音模型、Ubuntu 安装包和 Android / Windows 导出模板不再打进 APK。
第一次使用对应功能时联网下载；已安装的 Ubuntu 和经过校验的本地资源可离线复用。
Silero VAD 仍内置，云端语音和唤醒检测不需要下载 NCNN 模型。

下载使用固定版本 URL、文件大小和 SHA-256，流式写入同目录临时文件，校验通过
后原子替换。取消会中断网络请求并清理当前临时文件；失败后重新使用功能即可重试。
完成的语音模型文件保留，不需要重新下载；当前未完成文件重新下载。

模板发布于 `https://github.com/CATMIAOZHI/OperitResources/releases/tag/templates-v1`。
更新资源时发布新 tag，不覆盖旧版本，再更新 `OnDemandResources` 中的地址、
大小和校验值。NCNN 固定 Hugging Face 模型提交，Ubuntu 固定个人终端仓库提交。
CI 不再下载旧 `subpack.zip`；模型依赖压缩包仍为内置 VAD 提供文件，但 NCNN
目录由 APK 的精确资源过滤规则排除。

个人终端子模块新增可选的 Ubuntu 资源准备接口，独立使用终端库时未注册接口
仍使用原内置资源。资源准备发生在 shell 的 30 秒启动计时之前，环境重置与准备
使用同一把锁，避免下载与清理交错。
子模块来源为 `CATMIAOZHI/OperitTerminalCore`，基于 `cd5d53c` 更新至 `52afb8c`。
后续推送时先推送终端的 `codex/on-demand-resources`，再推送引用它的主仓库提交，
否则 CI 无法取到新的子模块版本。
