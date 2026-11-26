---
title: 浏览器引擎路线图
status: in-progress
document_type: implementation-plan
fork: https://github.com/CATMIAOZHI/Operit
branch: personal/dev
last_reviewed: 2026-11-16
---

# 浏览器引擎路线图

## 目标

让主应用和 Orbit 的 AI 与用户共用同一个真实浏览器页面，具备开发者工具级别的调试能力（截图、点击、DOM、Console、Network、JavaScript）。登录状态分两套：主应用一套，Orbit 一套（App 只有一个 Orbit），始终分开。

## 三个版本

| 版本 | 内核 | 状态 |
|---|---|---|
| 第一版 | 系统 WebView + 应用内 CDP | 进行中 |
| 第二版 | Cefrium（现成的 Android Chromium 嵌入 SDK，LGPL-3.0） | 0.21.0 复测通过，待接入 |
| 第三版 | 自己编译 Chromium | 可能不需要（见第二版） |

## 第一版：WebView + 应用内 CDP

真机实验（2026-10-19，Android 15，WebView 155）结论：

- Operit 自己的进程能连上自己 WebView 的调试 socket `@webview_devtools_remote_<pid>`；Chromium 的授权允许同 UID。普通终端（应用身份）实测 `/json/version` 返回 `Android-Package=com.rainy.operitry.dev`。
- 截图、读 DOM、Console、Network 状态码（含 404）、鼠标点击、坐标换算（DPR 3.5）均通过。
- shell/Shizuku 身份连接其他应用（`org.chromium.chrome`）的 `@chrome_devtools_remote` 被 SELinux 拒绝：`avc: denied { connectto } scontext=u:r:shell:s0 tcontext=u:r:untrusted_app ... tclass=unix_stream_socket`。所以控制外部浏览器只能经 adbd 转发，不作为默认方案。
- 调试开关开启后，已授权 ADB 的电脑也能检查和修改应用内 WebView；手机上的其他应用不能连接。

登录状态：主应用一套、Orbit 一套（androidx.webkit 多 Profile，需要 `MULTI_PROFILE`）。Orbit 始终用自己那套，不能改成和主应用共用（2026-11-18 决定，原来的开关改成只显示状态）。

### 第一版已实现

- `BrowserCdp`：用 `LocalSocket` 连 `webview_devtools_remote_<pid>`，手写最小 WebSocket 客户端；给页面打一次标记来找到对应的 DevTools 目标，每个标签页一条长连接。
- 网络日志：DevTools 连上后，`browser:network_requests` 每条请求带状态码、类型或失败原因；连不上时保持原来的 WebView 记录。
- 页面截图优先走 DevTools（能拍到视频、画布），缩放到与 WebView 截图同样大小；失败回退 `webView.draw`，并在一分钟内不再尝试。
- 新工具 `browser:click_xy`（按截图坐标点按，原生触摸事件）和 `browser:devtools`（单条 CDP 命令，只开放页面范围内的域，不开放 Target、Browser、Storage、Fetch）。
- `BrowserProfiles`：AI 调用按调用者选登录空间，只能看到和使用自己那套登录的标签页；用户界面看到全部标签页，Orbit 的标签页标出名字。Orbit 设置里的“浏览器登录”一行只显示状态（和主应用分开 / 手机不支持时只能共用）；删除 Orbit 时关闭它的标签页并删除它的 Profile。系统 WebView 不支持多 Profile 时共用主应用一套。
- 用户代理改为 Chrome 精简格式，版本号取自手机 WebView，Client Hints 品牌写 Chromium/Google Chrome；去掉 `X-Requested-With` 包名请求头。

### 第一版做不到的事

- Google 等网站拒绝“内嵌浏览器”登录，主要依据浏览器标识（UA 里的 `wv`、`X-Requested-With` 包名头）。第一版已改成普通 Chrome 标识并去掉包名头，**可能已经能登录，需要真机验证**；网站还可能看其它痕迹（如 TLS 指纹、缺少的浏览器接口），不保证长期通过。真不行时回落到外部浏览器登录。
- 改 TLS/网络栈指纹、安装真正的浏览器扩展：第二、三版。
- 网页通知（Web Notification/Push）：WebView 不支持。
- 真正无窗口的后台运行：WebView 需要挂在窗口上，后台依赖 1×1 悬浮窗。
- 系统 WebView 不支持 `MULTI_PROFILE` 时，Orbit 只能共用主应用一套登录。

## 第二、三版：只发内置浏览器的版本（2026-11-26 决定）

App 默认内置 Cefrium（内核压缩存放，约多 100 MB 下载），只发这一种 APK，最低 Android 10。Android 8、9 不再支持，停在最后一个普通版。本地可用 `-PoperitBrowser=false` 打出系统 WebView 版做快速验证，不发布。原先「普通版 / 带浏览器版两种 APK」的计划作废，下面「两种版本来回切换」只剩老用户升级那一次。

- 原因：Chromium 的渲染进程运行在 isolated 沙盒里。SELinux 的 `isolated_app` 域不能执行应用私有目录里下载的 `.so`；经 Binder 传入的 FD 也只有读和映射权限，没有执行权限。内核必须装在 APK 里（`apk_data_file` 没有这个限制）。
- 之后的更新走差异补丁（nightly 和正式版都默认先试补丁，见 `docs/agent/dev-to-main-promotion.md` 第 9 节）：补丁按 APK 里的文件逐个比较，内核没变时补丁里没有内核。跳过了几个版本或补丁失败时，仍下载完整 APK（带浏览器版就是大包）。
- 最低系统 Android 10（Cefrium 0.21.0 仍是 minSdk 29）。从普通版升级上来的第一次更新，补丁会带上整个内核，接近下载完整包。
- 还在用 Android 8、9 的老版本，检查更新时仍会拿到新包，但系统会拒绝安装（minSdk 29）；已发出去的旧版代码改不了，只能在发版说明里写明。
- 不采用附加安装包（feature split）：系统要求所有 split 的 versionCode 一致，App 每次更新都要连浏览器模块一起重下，而且安装时要系统确认、应用会重启。
- 不采用关闭渲染进程沙盒、直接加载私有目录里内核的做法：网页一旦攻破内核，就能读到应用里的 API Key、聊天记录和登录状态。

## 第二版 Cefrium 实测结论（2026-10-20）

独立测试工程 `workspace/cefrium-probe`（包名 `com.rainy.cefriumprobe`），详细证据见该工程的 `实测报告.md`，独立只读复审 CLEAN。

- 版本锁定 **0.10.1**（源码 commit `8ee6fffa753e074cbe42089839fda0c40d21316e`）。CDP 走 SDK 内部 JNI，不是公开接口；每次升级必须回归 CDP、严格 CSP 页面填字、中文输入。
- 工具链要求 JDK 25、Gradle 9.7.1、AGP 9.4、compileSdk 37、minSdk 29、仅 arm64/x86_64。接入时 Operit 整体 minSdk 保持 26，Android 10+ 用 Cefrium，旧机回退 WebView。
- 体积：arm64 Debug APK 约 221 MiB（`libcef.so` 未压缩约 216 MiB）。
- 已通过：真实渲染（自带 `libcef.so`）、App 内 CDP、GitHub 严格 CSP 下 JS 填字、中文输入、渲染进程为 isolated UID。
- 浏览器标识：SDK 用宿主 `versionName` 当 Chrome 版本（显示 `Chrome/0.1`）。`--user-agent-product` 和关闭 `CefriumInitProvider` 手动初始化都会被覆盖。可用补救：CDP `Emulation.setUserAgentOverride` 带 `userAgentMetadata`，网页 JS、HTTP UA、brands、mobile 都能修正；需每个页面、每次重连重新设置。CDP `Browser.getVersion` 仍为 0.1（网站看不到）。不用内置扩展：Cefrium 没有扩展加载接口，效果也等同于此方案。
- 权限：宿主 Manifest 用 `tools:node="remove"` 后从 11 条减到 5 条（联网 2、凭据 2、SDK 内部 1），复测通过。
- 防护：SDK 无条件 `no-sandbox`，Chromium 额外的渲染进程 seccomp 初始化被跳过；Android isolated UID 和系统 seccomp 仍在，namespace 无法验证。恢复 Chromium 沙盒需改 SDK 重新编译，归第三版。

## Cefrium 维护者的更新（2026-11-01，issue #75，未复测）

Cefrium 维护者在 [issue #75](https://github.com/CATMIAOZHI/Operit/issues/75) 说明 0.10.1 之后的变化；已回复会复测。以下都是对方说法，要用 0.20.0（2026-11-13 发布，Maven 仓库现只列 0.18.0–0.20.0）实测后才算数：

- 浏览器标识：0.10.2 起 Chrome 版本取自内核，不再是 `Chrome/0.1`，不用再每页用 CDP 改标识。
- 沙盒：0.10.2 起不再传 `no-sandbox`，渲染进程在 isolated UID 之上安装 Chromium 的 seccomp-BPF 策略。上面“恢复沙盒归第三版”可能不再需要。
- 登录：0.14.0 起有持久 Profile，各带独立 Cookie 和存储，可以直接做主应用一套、Orbit 一套。
- CDP：0.18.0 起有公开接口 `executeDevToolsMethod`（带结果）和 `setDevToolsEventListener`（事件），不再走内部 JNI。
- 维护者没提：体积（0.10.1 约 221 MiB，按需下载方案照旧）和工具链要求（JDK 25、AGP 9.4、minSdk 29），复测时一并确认。

复测清单：浏览器标识、渲染进程沙盒、两套持久 Profile 互不串号、公开 CDP 接口、严格 CSP 页面填字、中文输入、体积和工具链。

## 第二版 Cefrium 0.21.0 复测结论（2026-11-16）

独立测试工程 `workspace/cefrium-probe-021`（包名 `com.rainy.cefriumprobe`，只用公开接口），在 Android 14 x86_64 模拟器上测试。0.20.0 之后已发布 0.21.0（内核 Chromium 156.0.8078.96），直接测最新版。依赖从 Cefrium 自己的 Maven 仓库 `https://maven.cefrium.com`（限定 `com.cefrium` 组）获取，Maven Central 只到 0.17.1。

| 项目 | 结果 |
|---|---|
| 浏览器标识 | 版本正确：`Chrome/156.0.8078.96`。但 UA 里多一个 `Cefrium/1.0`，Client Hints 品牌只有 Chromium（没有 Google Chrome），高熵值 `mobile` 为 false。接入时用公开的 `setUserAgentOverride` 统一成手机 Chrome 标识 |
| 渲染进程沙盒 | 通过：渲染进程是 isolated UID（u0_i*），`NoNewPrivs=1`，seccomp 过滤器 4 个（应用主进程 1 个），说明 Chromium 自己的 seccomp-BPF 策略装上了。“恢复沙盒归第三版”不再需要 |
| 两套登录空间 | 通过：`CefriumProfile.get("main")` 和 `get("orbit")` 的 Cookie 互不可见；重启 App 后两边的 Cookie 都还在 |
| 公开 CDP | 通过：`executeDevToolsMethod` 跑 `Runtime.evaluate`、`Network.enable`、`Page.captureScreenshot`、`Input.insertText`；`setDevToolsEventListener` 收到网络事件（状态码和类型）。不再需要内部 JNI 或调试 socket |
| 严格 CSP 页面填字 | 通过：GitHub 登录页 `evaluateJavaScript` 填入并返回结果 |
| 中文输入 | CDP `Input.insertText` 写入“中文输入测试”成功；`imeCommitText` 在 Surface 模式下没有写入（它面向离屏模式）。系统输入法输入中文待真机确认 |
| 和 WebView 同进程 | 通过：同一进程里先用 Cefrium 再建 WebView，两边都正常。第一版的 WebView 可以和第二版并存 |
| 无窗口后台运行 | 通过：离屏模式 `new CefriumBrowser(w, h, scale, profile)` 不挂任何窗口就能加载页面、执行脚本；截图用 `captureBitmap`（先 `invalidate()`），离屏模式下 CDP `Page.captureScreenshot` 不返回。可以取代第一版的 1×1 悬浮窗 |
| 体积 | arm64 的 `libcef.so` 202 MB。默认不压缩存进 APK，arm64 调试包 306 MB；`useLegacyPackaging = true` 压缩存放后能正常运行（x86_64 包从 332 MB 降到 208 MB），arm64 估计比普通版多约 90 MB 下载 |
| 工具链 | JDK 25、Gradle 9.7.1、AGP 9.4、compileSdk 37、minSdk 29。Operit 现在是 AGP 9.1.1、Gradle 9.3.1、compileSdk 36、JDK 17，接入前要先升级工具链；Cefrium 的类文件是 Java 25（major 69），Kotlin 编译器也要能读 |

结论：维护者在 issue #75 说的变化都成立。第二版可以开始接入，第一步是升级 Operit 的构建工具链。

## 第二版接入进度

1. 工具链与构建开关（2026-11-16，已完成）：Gradle 升到 9.7.1、AGP 升到 9.4.0，普通版仍用 JDK 21/17 构建，单测通过。命令行加 `-PoperitBrowser=true` 打出带浏览器版：加入 Cefrium 插件和 `cefrium-sdk:0.21.0`，`minSdk 29`，`BuildConfig.BROWSER_ENGINE_BUILT_IN = true`，需要 JDK 25。arm64 调试包：普通版 166 MB，带浏览器版 308 MB（`libcef.so` 压缩后 93 MB，`icudtl.dat` 9 MB）。用法见 `docs/agent/build-guide.md`。
2. 引擎接入（2026-11-17，已完成，待真机验证）：带浏览器版的所有浏览器标签页都跑在离屏 Cefrium 上（`app/src/browser/java`），普通版保持 WebView（`app/src/webview/java` 里是空实现）。主应用用引擎默认 Profile，Orbit 用 `orbit-<id>` Profile；CDP 走 `executeDevToolsMethod`；引擎建页失败时这个标签页自动回退 WebView。模拟器（x86_64 包）实测通过：打开网页、UA 与 Client Hints（Google Chrome 品牌、无 Cefrium 字样）、截图、点击、中文输入、GitHub 严格 CSP 登录页填字、读 DOM、异步 JS、网络日志状态码、确认弹窗、新窗口、下载交给 App 下载列表、真人触摸、App 在后台继续工作、两套登录不串号。接入中踩到的坑：
   - 引擎的网页进程和 GPU 进程也会创建 `OperitApplication`；沙盒进程不能注册广播，原来会一启动就崩，网页永远加载不出来。现在这些子进程跳过 App 初始化。
   - Cefrium 0.21 的 DevTools 消息编号会错位：浏览器建好前发的调用要等到第一次开始加载才发出，建好后发的调用立即发出；后者插队后前者被 CEF 改号，之后这个标签页的每个 DevTools 调用（包括带返回值的执行 JS）都被拒绝，回复也会对错号。新窗口每次都会触发。现在每个标签页在第一次开始加载前不发任何 DevTools 调用，先在 App 里排队。源码：`libcef/browser/android/cefrium_sdk_devtools.cc`（`ExecuteDevTools` 比较返回编号）、`cefrium_sdk_handlers.h`（`OnLoadStart` 才放行排队的调用）。
   - 文档开头脚本运行时 `window.cefriumQuery` 可能还没注入，新窗口的第一个页面也可能错过开头脚本；App 的脚本桥改成调用时才找 `cefriumQuery`，并在每次执行 JS 前补上。
   - 新窗口的设置（脚本通道、各种回调）也被引擎扣到第一个页面提交时才生效；第一次加载没提交就停了（比如直接变成下载），窗口就一直半残。现在这种窗口会自动加载一次 `about:blank`，DevTools 排队也在加载停止或出错时放行。
   - 引擎的查询通道只能回答发起查询的那个框架，也不支持一次查询多次回答；页面里的子框架靠一个一直挂着的 listen 查询批量收回复（用户脚本在 iframe 里要靠它拿到启动数据）。
   - 引擎默认直接离开有未保存内容的页面，现在 `beforeunload` 会弹 App 自己的确认框。
   - 模拟器是 x86_64，arm64 的 `libcef.so` 在模拟器的 ARM 翻译层里会崩；模拟器测试用 `-PoperitAbi=x86_64` 打包（见 `docs/agent/build-guide.md`）。
3. 发版（2026-11-26 改为默认内置）：默认构建就带内核，CI 改用 JDK 25，只发一种 APK。
4. 通行密钥（权限已有，白名单待申请）：见下面「通行密钥（第二版）」。

## 老用户第一次升级到内置浏览器

包名、签名、versionCode 都不变，覆盖安装，聊天、记忆、设置都保留。只有浏览器数据换了地方：

- 网站登录不会跟着走：旧版的登录存在系统 WebView 里，新版用 Cefrium，看不到旧登录，要重新登录一次。待做：第一次打开网页时提示一句“浏览器换了，网站要重新登录”。
- 系统 WebView 里的旧登录和缓存还在手机上；引擎建页失败回退 WebView 时主应用的标签页仍会用到，暂不删除（Orbit 的标签页回退时，WebView 不支持多 Profile 就直接报错，不会用主应用的登录）。

## 通行密钥（第二版）

- 现状：两个版本都声明了 `CREDENTIAL_MANAGER_SET_ORIGIN` 和 `CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS`（App 清单里写了；带浏览器版 Cefrium SDK 也会自动加入），不要用 `tools:node="remove"` 删掉它们（Cefrium 实测：删掉前者通行密钥报 `NotAllowedError`，删掉后者遇到带通行密钥的登录框会崩）。只在 Android 14 及以上生效。普通版的浏览器标签页在 WebView 支持 `WEB_AUTHENTICATION` 时打开浏览器模式通行密钥（`WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER`，`configureWebView`），手机 WebView 太旧就保持原样。
- 浏览器代表网站请求通行密钥时，密码管理器会自己判断 App 是否可信，光有权限不够：
  - Google 密码管理器只认官方白名单 [apps.json](https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json)，用户不能自己加。Operit 不在名单里，所以存在 Google 密码管理器里的通行密钥用不了。
  - Bitwarden（设置 → 自动填充 → 通行密钥管理 → 特权应用）和 KeePassDX（设置 → 表单填充 → 通行密钥设置 → 特权应用）可以让用户手动信任 Operit。
- 以后要做：申请进 Google 密码管理器白名单。
  1. 先在 Android 14 及以上的真机上，用 Bitwarden 或 KeePassDX 跑通一次带浏览器版的通行密钥登录，证明我们这边没问题（审核可能会实测）。
  2. 用用户的 Google 账号填[官方申请表](https://docs.google.com/forms/d/e/1FAIpQLScRuk9tTg0QPfSWl9SbpbIorX8xx2FCXlmoUYftCX2MxG4qyg/viewform)（说明见 [Android 文档](https://developer.android.com/identity/sign-in/privileged-apps)），提交后开工单人工审核。
  3. 要填：包名 `com.rainy.operitry`，开发版也要的话加 `com.rainy.operitry.dev`；发布签名证书的 SHA-256 指纹（公开信息，可以填）；说明内置浏览器基于 Cefrium、按网页来源设置 origin、登录仍需用户指纹或锁屏确认。
- 白名单按「包名 + 签名证书指纹」登记。指纹来自签名证书，每次发版不变；只有换了发布签名文件才要重新申请，所以签名文件和密码要备份好。
- 风险：审核标准和时间都没公开。名单里有小型开源浏览器（Helium、Vanadium、Waterfox）和 AI 浏览器 Perplexity Comet，但 Operit 主体是 AI 助手，审核可能问得更细。

## 两套登录空间（第二版）

- 主应用一套、Orbit 一套，用 Cefrium 的持久 Profile（0.21.0 复测通过）；和第一版一样始终分开。
- 不采用：给 Orbit 安装一个独立浏览器 App。约 220 MB，安装和升级都要确认；CDP socket 只接受浏览器自身 UID，要另做转发通道；打开浏览器会跳出 Operit。
