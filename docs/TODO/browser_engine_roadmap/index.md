---
title: 浏览器引擎路线图
status: in-progress
document_type: implementation-plan
fork: https://github.com/CATMIAOZHI/Operit
branch: personal/dev
last_reviewed: 2026-10-19
---

# 浏览器引擎路线图

## 目标

让主应用和 Orbit 的 AI 与用户共用同一个真实浏览器页面，具备开发者工具级别的调试能力（截图、点击、DOM、Console、Network、JavaScript），并为不同 Orbit 提供独立的登录状态。

## 三个版本

| 版本 | 内核 | 状态 |
|---|---|---|
| 第一版 | 系统 WebView + 应用内 CDP | 进行中 |
| 第二版 | Cefrium（现成的 Android Chromium 嵌入 SDK，LGPL-3.0） | 待做 |
| 第三版 | 自己编译 Chromium | 待做 |

## 第一版：WebView + 应用内 CDP

真机实验（2026-10-19，Android 15，WebView 155）结论：

- Operit 自己的进程能连上自己 WebView 的调试 socket `@webview_devtools_remote_<pid>`；Chromium 的授权允许同 UID。普通终端（应用身份）实测 `/json/version` 返回 `Android-Package=com.rainy.operitry.dev`。
- 截图、读 DOM、Console、Network 状态码（含 404）、鼠标点击、坐标换算（DPR 3.5）均通过。
- shell/Shizuku 身份连接其他应用（`org.chromium.chrome`）的 `@chrome_devtools_remote` 被 SELinux 拒绝：`avc: denied { connectto } scontext=u:r:shell:s0 tcontext=u:r:untrusted_app ... tclass=unix_stream_socket`。所以控制外部浏览器只能经 adbd 转发，不作为默认方案。
- 调试开关开启后，已授权 ADB 的电脑也能检查和修改应用内 WebView；手机上的其他应用不能连接。

登录状态：默认主应用一套、每个 Orbit 一套（androidx.webkit 多 Profile，需要 `MULTI_PROFILE`）。每个 Orbit 可切换为“和主应用共用”，切换不删除 Orbit 自己的数据；共用时以主应用的数据为准。

### 第一版已实现

- `BrowserCdp`：用 `LocalSocket` 连 `webview_devtools_remote_<pid>`，手写最小 WebSocket 客户端；给页面打一次标记来找到对应的 DevTools 目标，每个标签页一条长连接。
- 网络日志：DevTools 连上后，`browser:network_requests` 每条请求带状态码、类型或失败原因；连不上时保持原来的 WebView 记录。
- 页面截图优先走 DevTools（能拍到视频、画布），缩放到与 WebView 截图同样大小；失败回退 `webView.draw`，并在一分钟内不再尝试。
- 新工具 `browser:click_xy`（按截图坐标点按，原生触摸事件）和 `browser:devtools`（单条 CDP 命令，只开放页面范围内的域，不开放 Target、Browser、Storage、Fetch）。
- `BrowserProfiles`：AI 调用按调用者选登录空间，只能看到和使用自己那套登录的标签页；用户界面看到全部标签页，Orbit 的标签页标出名字。Orbit 设置里有“浏览器和主应用共用登录”开关；删除 Orbit 时关闭它的标签页并删除它的 Profile。系统 WebView 不支持多 Profile 时所有人共用主应用一套。
- 用户代理改为 Chrome 精简格式，版本号取自手机 WebView，Client Hints 品牌写 Chromium/Google Chrome；去掉 `X-Requested-With` 包名请求头。

## 第二、三版：按需下载（定为 A 方案）

不打进主 APK，首次启用时下载内核。定为 **A 方案：占位 + 同包附加安装包（feature split）**。

- 原因：Chromium 的渲染进程运行在 isolated 沙盒里。SELinux 的 `isolated_app` 域不能执行应用私有目录里下载的 `.so`；经 Binder 传入的 FD 也只有读和映射权限，没有执行权限。已安装 APK 的代码（`apk_data_file`）没有这个限制。
- 做法：主 APK 预留浏览器模块入口；下载与主 APK 同包名、同 versionCode、同签名的 split，用 `PackageInstaller` 的 `MODE_INHERIT_EXISTING` 会话安装。split 可以声明沙盒渲染进程的 Service。
- 代价：安装时系统会确认一次，安装后应用会重启一次（`setDontKillApp` 只是请求，要处理 `STATUS_PENDING_USER_ACTION`）；内核升级走同样的流程。
- 不采用 B 方案（关闭渲染进程沙盒，直接加载私有目录里的内核）：网页一旦攻破内核，就能读到应用里的 API Key、聊天记录和登录状态。
- 共享内存加载器（ASharedMemory + `PROT_EXEC`）在理论上可行，但没有验证过，只作为实验路线。

## 第二版 Cefrium 待验证

工具链要求（JDK、AGP、compileSdk）、SDK 体积、渲染进程是否沙盒化、能否接 CDP、中文输入法、HyperOS 兼容性。先在独立测试工程验证，再接入 Operit。
