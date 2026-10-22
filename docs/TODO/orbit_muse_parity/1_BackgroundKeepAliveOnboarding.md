# 首次初始化 Orbit 时引导打开后台保活

状态：暂缓，等 Orbit 首次初始化流程做好后再做。

## 现在的情况

- Orbit 的后台任务（夜间对齐复盘、动态、点子、用户安排的任务）都在 Operit 的保活前台服务 `AIForegroundService` 里跑，WorkManager 只负责到点叫醒（`OrbitTaskHost`）。
- 没开「后台保活」、也没有电池优化豁免时，Android 12 以上不让 App 在后台启动前台服务。任务到点后不会偷偷降级去跑，而是发一条通知「（Orbit 的名字）有任务没能在后台开始」，用户打开 Operit 后接着跑，15 分钟后也会再试一次。
- 现在没有任何地方主动引导用户打开保活或忽略电池优化。

## 想做成什么样

- 第一次初始化 Orbit 时，由 Orbit 用自己的口吻（名字、语气都按它自己的人设）在聊天里说明：它需要在后台按时做事，所以请用户打开两样东西：
  1. 设置里的「后台保活」（`DisplayPreferencesManager.enableBackgroundKeepAlive`，打开后调 `AIForegroundService.refreshBackgroundKeepAlive`）；
  2. 系统的「忽略电池优化」（`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，manifest 已声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。有了这个豁免，Android 才允许 App 半夜在后台重新拉起前台服务。
- 用卡片做，用户点一下直接跳到对应开关；拒绝也不反复催，只在任务因为这个没跑成时，通过上面那条通知再提一次。
- 两项都打开后，卡片显示已完成。

## 作用域

- Orbit 首次初始化流程（还没做好，等它落地）。
- 一个只读的状态检查：保活是否打开、是否已忽略电池优化，给卡片和设置页用。
- 不改 `OrbitTaskHost` 的运行逻辑。
