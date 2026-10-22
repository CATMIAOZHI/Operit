# 首次初始化 Orbit 时引导打开后台保活

状态：已完成（本批）。

## 现在怎么做

- Orbit 的后台任务都在 Operit 的保活前台服务 `AIForegroundService` 里跑，WorkManager 只负责到点叫醒（`OrbitTaskHost`）。
- 没开「后台保活」、也没有电池优化豁免时，Android 12 以上不让 App 在后台启动前台服务。这时任务不降级去跑，只把这次被拦记下来（`OrbitTaskBlock`：从什么时候起、原因是保活关着 / 电池优化 / 系统限制），15 分钟后再试。不弹系统通知。
- 后台自己恢复了（后来的叫醒成功），记录作废，什么都不说。
- 用户打开 Operit 时，先读出哪些任务延后了，再开始补跑，然后给有延后任务的 Orbit 主聊天发一条隐藏消息（不弹通知）。Orbit 按 `PROACTIVE_PREFERENCES.md` 和聊天记录自己判断要不要提；要提就用自己的话说哪些事晚了、为什么，原因是保活或电池时放一张保活卡。
- 程序只做去重：延后不到 10 分钟不说；同一个原因 3 天内不再叫醒同一个 Orbit。用户说“以后别提”，由 Orbit 写进 `PROACTIVE_PREFERENCES.md` 的「别跟我说」，程序不存禁令。
- 保活卡：`orbit_permission_card`（permission=keep_alive）。两行：后台保活「开启」（直接打开设置开关）、忽略电池优化「去允许」（系统弹框）；开着的显示已开启；回到 App 时重新读状态。底部「以后别提醒」用隐藏消息告诉 Orbit 去写偏好。两项都开着时工具不放卡。
- 首次初始化：起名完成后（`OrbitOnboarding` 的 `.onboarded`），主聊天提示词里加一段一次性的 `<orbit_keep_alive>`，让 Orbit 找自然的时机用自己的口吻提一次并放卡。放过卡（`.keepalive_offered`）、两项都已开着、或主聊天用户消息超过 14 条（老 Orbit）都不再提示。

## 以后

- 同一张卡扩展更多权限：通知、短信、日程、位置等（`OrbitPermissionCards.kinds`）。
