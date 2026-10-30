# 工具包与账号连接

工具包决定你能调用什么，账号连接决定服务是否能用。
包已导入、包已启用、密钥已保存、账号已登录和具体操作获准，是不同状态。
按当前工具说明与实际结果判断，不根据过去的聊天或服务名称推断权限。

## 默认核心包

每个 Orbit 默认保留并启用以下核心包，不能关闭或移除：

| 包名 | 用途 |
|---|---|
| `super_admin` | 终端 |
| `various_search` | 网络搜索 |
| `memory_review` | 记忆工具 |
| `dynamic_ui` | 动态界面与卡片 |
| `browser` | 浏览器 |

始终开启不保证每个操作都有权限或所需环境已经安装。

新 Orbit 还默认导入并打开下面两个包，用户可以关掉或移除，移除后不会自动加回：

| 包名 | 用途 |
|---|---|
| `extended_file_tools` | 移动、复制、压缩解压、查看文件信息、打开和分享文件 |
| `extended_http_tools` | 直接发 HTTP 请求（GET/POST 等）和上传文件 |

其他包按需导入；Orbit 的包清单和开关独立于主程序，不跟随主程序之后的开关变化。
`extended_chat` 和 `operit_editor` 不向 Orbit 开放：自己的聊天使用 `orbit_chat`，模型、语音、包和密钥用 Orbit 自己的工具。

## 查看和导入

用 `orbit_packages action=list` 查看 `imported` 与 `available`，可用 `query` 按名称或描述筛选。
`imported` 中的 `on` 表示当前开关，`always_on` 表示不能关闭；`env` 列出包声明的环境变量、是否必填及是否已设置。
变量已设置不代表密钥有效，也不表示账号已经连接。

导入用 `orbit_packages action=request_import`：

- `packages` 填 `available` 中的包名，逗号分隔，一次最多 20 个。
- `paths` 填设备上的包文件或文件夹，逗号或换行分隔。支持 `.js`、`.ts`、`.hjson`、`.toolpkg`；文件夹只展开其第一层包文件，合计最多 20 个文件。
- 两种来源可在同一次申请中使用；`title` 和 `reason` 可简短说明用途。卡片内容过长时拆成更小的申请。

只申请当前任务需要的包；不清楚文件包做什么时，先读内容。
工具放出批准卡后，用一句话结束本轮，等用户批准。
批准后由 App 导入并开启，你会收到隐藏结果，说明成功、失败以及仍缺的密钥或登录。
以结果为准，再用 `use_package` 读取成功导入的包；放出卡片不代表导入完成。

## 开关

已导入的包用 `orbit_packages action=request_toggle` 申请开关。
`on` 和 `off` 分别填逗号分隔的包名，同一包不能同时填两处，不申请已经是目标状态的包。
一次最多 20 个，计入需要一起开启的依赖；缺少的依赖要先导入，需要开启的依赖会列在卡片里。
核心包不能关闭，其他已开启的包仍依赖某个包时也不能关闭它。

放卡后结束本轮，批准后由 App 执行并通知结果。
新开启的包按需 `use_package`，关闭的包不能继续使用。
不要编辑配置文件或用其他设置工具绕过批准。

## API 密钥

包缺密钥时，用 `orbit_vault action=request_api_key` 放安全输入卡片。
`provider` 填服务名，`package_name` 填包名，`env_name` 原样填写该包要求的大写变量名。
包专用密钥不传 `api_hosts`，不需要 `env_reason`。
用户保存后只在该包运行时提供，其他包与终端拿不到，不需要写入主程序的环境变量。

不要在聊天中索要秘密，也不要读取或回显保存的值。
更新已保存的密钥用 `request_update` 和 `credential_id`；更多规则见 [privacy-and-credentials.md](privacy-and-credentials.md)。

## 账号登录与 OAuth

ChatGPT/Codex 登录使用 `orbit_vault action=request_chatgpt_login`，也用于 `codex_draw`。
Claude、Grok、Antigravity、Command Code 订阅账号使用 `request_account_login`，`provider` 分别为 `claude`、`grok`、`antigravity`、`command_code`。
登录卡新发出后结束本轮；成功会收到隐藏通知，跳过不会。返回 `already_sent` 时沿用已有卡片，`already_logged_in` 表示 App 已保存登录状态。
这些账号要作为聊天模型使用，还需对应模型配置：登录后用 `orbit_models action=request_create` 加对应的 `provider`（如 `GROK_ACCOUNT`）申请新建，`endpoint` 和 `models` 都不用填，用户在批准卡上从账号的模型列表里勾选；之后用 `orbit_models action=request_use` 申请切换。
账号登录不自动导入工具包，也不赋予任意网站或服务操作能力。

其他服务可按实际接口使用 `orbit_vault action=request_oauth`，仅支持公开客户端的授权码与 PKCE 流程，授权地址和 token 地址必须同为 HTTPS 且主机、端口一致。
它需要官方的授权配置和已登记的实际回调地址，不支持需要客户端密钥的流程；OAuth 令牌通过保险箱的 `http_request` 使用，不承诺自动交给工具包。
网站登录或验证码需要用户操作时，使用浏览器接管，见 [browser.md](browser.md)。

## 验证连接

先核对包是否启用、缺哪些变量，以及登录卡或隐藏通知的当前结果。
用 `use_package` 读实际工具；包有状态查询时优先使用，否则在已授权任务范围内，以最小必要调用核实可用性。
没有统一适用于所有包的账号状态命令，不编造 `status` 或登录入口。
保存密钥与登录状态都不保证服务端仍接受；实际鉴权、权限、网络和限流错误应按返回结果解释。
不要为测试连接发送消息、发布内容或重复可能已经成功的写入。
