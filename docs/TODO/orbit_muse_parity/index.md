---
title: Orbit 对标 Muse 功能清单
fork: https://github.com/CATMIAOZHI/Operit
branch: personal/dev
status: in-progress
---

# Orbit 对标 Muse 功能清单

对照 Muse 完整系统提示词（`muse-orbit-reference/system-prompt.md`）里写到的功能，记录 Orbit 现在有什么、以后要做什么、哪些做不了。

## 规则

- Orbit 的系统提示词（`app/src/main/java/com/ai/assistance/operit/features/orbit/OrbitPromptDefaults.kt`）只写**已经能用**的功能。
- 还没做的功能只写在这份清单里，不写进提示词，免得 Orbit 去调不存在的工具或向用户承诺做不到的事。
- 某项功能做好后：
  1. 在提示词的中文版和英文版里同时补上它的用法；
  2. 把本清单里那一项移到「已有」，在后面写上 `[DONE]` 和提交号。

## 已有（已写进提示词）

| 功能 | Orbit 里的实现 |
| - | - |
| 人格和身份文件 | SOUL.md、IDENTITY.md，`orbit_soul` 读写，`orbit_set_name` 改名 |
| 工作手册 | PROMPT.md，可在 Orbit 设置里查看和编辑 |
| 用户资料和长期笔记 | user.md、memory.md，`memory_review` 工具包 |
| user.md 查看编辑入口 | Orbit 详情页「身份」里的 USER 卡片，或提示词分层页点 user.md；用户保存走 saveUserEdit 并留审核记录 [DONE b541e2e25] |
| 记忆文件夹 | 像 Muse 一样只有 memory.md，没有记忆图谱；memory.md 和它的改动记录放在 `orbit/<UUID>/memory/`，技能放在 `orbit/<UUID>/skills/`，图谱工具对 Orbit 一律拒绝 [DONE] |
| 记忆隔离 | 每个 Orbit 只读写自己的记忆，用户要求时才读别处 |
| 找回聊天原话 | `memory_review` 工具包的 `history` |
| 技能按需加载 | `orbit_skills` 查目录，再加载正文 |
| 沉淀新技能 | `memory_review` 工具包的 `manage`（action=skill_create） |
| 提醒、每日、每周、每月、每年、间隔、接力任务 | `orbit_task`（ONCE / DAILY / WEEKLY / MONTHLY / YEARLY / INTERVAL / EVENT）[DONE 5414eec65] |
| 任务执行时限 | `orbit_task` 的 `timeout_minutes`（1–1440 分钟，默认 5，和 Muse 一样最多 24 小时；8 分钟以内走普通后台，更长的走单独的前台队列并显示通知，提醒不会被长任务耽误）[DONE edaf27ee7, ca6626aaf] |
| 网页变化监测 | `orbit_task` 的 `kind=URL_CHANGE` |
| 汇报方式 | `notify_policy`：有变化才说 / 每次都说 / 每日汇总 |
| 运行记录、暂停、取消、继续 | `orbit_task` 的 detail / pause / resume / cancel |
| 长期目标 | `orbit_goal` |
| 成果版本 | `orbit_artifact` |
| 子代理 | `spawn_agent`（v2 协作） |
| 待办清单 | `todowrite` |
| 浏览器和人工接管 | `browser_*` 工具 |
| 审批 | 固定「自动审核快速」权限，审批卡由系统弹出 |
| 头像和名字 | 头像用户可换（相册 / 角色卡 / 宠物），名字只能 Orbit 自己改 |
| 人物和群组笔记 | `orbit_file` 写 `memory/people/`、`memory/groups/`，应用自动维护 INDEX.md；详情页「身份 → 文件」可看可改 [DONE batch1] |
| 每日流水 | `orbit_file` 的 daily，后台任务结果自动记一行 [DONE batch1] |
| 经验本、主动消息偏好、时区、对齐总结、设备 | LESSONS.md、PROACTIVE_PREFERENCES.md（后台任务会读）、timezones.yaml、ALIGNMENT_SYNTHESIS.md、DEVICES.md [DONE batch1] |
| 生成的文件 | `files/` 目录，构建页和文件页都能打开、分享、删除 [DONE batch1] |
| 回收站 | 删掉的文件留 30 天，可恢复或彻底删除 [DONE batch1] |
| 系统提示词副本 | `orbit_file` 的 prompt_copy，存 Orbit 部分的副本到 `files/` [DONE batch1] |
| 技能管理 | 详情页「技能与学习」里查看、编辑、删除 Orbit 自己的技能 [DONE] |
| 富格式成果 | `orbit_file` 的 export，把 `files/` 里的 Markdown 导出成网页、Word、PDF；import 把应用里别处的文件收进 `files/` [DONE batch2] |
| 多音色播客 / 语音消息 | `orbit_voice` 用手机自带语音引擎做语音消息和多人对话，存在 `files/voice/`，聊天里能直接播放 [DONE batch2] |
| Hook 轻量脚本 / 跨来源推送 | `orbit_task` 的 `kind=WATCH`：盯通知、短信、日历或 `files/hooks/` 里按哈希钉住的只读脚本，内容没变不叫醒 Orbit；缺权限时任务详情有「去打开权限」[DONE batch3a] |
| 主动消息逐条决定 | 每次后台运行都记下 `decision`（发 / 不发和理由），任务详情显示「为什么」[DONE batch3a] |
| 待审批 / 等待中的任务 | `orbit_task` 的 `pending`：等用户答复、等核实、失败后停下的任务 [DONE batch3a] |
| 目标简报和进展记录 | `orbit_goal_note`：log 记进展，后台「目标简报」任务每天早上给每个进行中的目标写一封简报，显示在目标页；没有进行中的目标时自动跳过 [DONE batch3b] |
| 后台自我提升 | 任务列表里的「夜间自我整理」：有新对话的夜里整理记忆、人物笔记、经验本、对齐总结，检查技能，合适时提点子；对话没变就不唤醒模型 [DONE batch3b] |
| 媒体库 | 构建页下方的媒体库：按图片、音频、文档筛选，图片有缩略图，长按置顶、分享、删除 [DONE batch3b] |
| 设备命令族 | `orbit_device`：describe 列出命令和授权情况，invoke 读通讯录、日历、短信、位置；没授权时当场弹框请用户批准 [DONE batch3b] |
| 数据导出 | 详情页「身份」里的「导出数据」：把 Orbit 文件夹、任务、目标、运行记录和聊天打成一个 zip 交给分享面板 [DONE batch3b] |
## 部分有，需要接进 Orbit

- [ ] 后台并行浏览器任务（Muse 的 `browser.spawn_task`）——暂不做
- [ ] 深度研究（Muse 的 `browser.deep_research`）——暂不做
- [ ] 图片生成工具接进 Orbit（已有 `features/draw/CodexDrawBridge.kt`）——暂缓
- [x] Tracking：跟踪预订、快递、承诺这类具体事项，并在目标页显示（25d778e8c）
- [ ] 动态 UI 专用卡片：选项卡片（Muse 的 `muse.create_options`）、地图卡片等——不新做卡片框架，用现有动态 UI 包
- [ ] 表情回应：给用户消息点一个表情（Muse 的 `react_to_user_message`）
- [ ] 侧聊：Orbit 下开独立话题对话，结果回到对应侧聊

## 还没做，可以做

- [ ] 从别的 AI 导入记忆和偏好
- [ ] 记忆溯源：说明一条记忆从哪来、替换了什么（Muse 的 `memory_explain`）
- [ ] 发送前检查：对外发消息前自动核对隐私和授权
- [ ] 密码保险箱：安全输入卡片，凭证不进入聊天和记忆（Muse 的 Secure Vault）
- [ ] 文件分享链接：上传文件生成有效期链接——不做
- [x] Feed：Orbit 在后台写的短文章信息流（25d778e8c）
- [x] Ideas：Orbit 主动提出的建议卡片（25d778e8c）
- [ ] 分享到 Operit：从别的 App 分享内容给 Orbit
- [ ] Tailscale 等内网设备连接——暂缓
- [ ] 「忘掉」流程：先找出一条信息在记忆、聊天、目标、任务里的所有副本，给用户确认后一起删（Muse 的 `forget.plan` / `forget.confirm`）
- [ ] 起名卡片：新 Orbit 第一次聊天时用卡片让用户选名字和气质（Muse 的 `onboarding.*`）
- [ ] 专项技能：购物、订机票、订酒店、出行交通、约时间、替用户发消息、管理邮件（Muse 预装技能，可改写成 Orbit 技能）

## 做不了（及原因）

| 功能 | 原因 |
| - | - |
| 支付、钱包、退款 | 需要支付牌照和支付平台商户接入，个人 App 接不了 |
| 订阅、邀请返利 | 依赖 Muse 的账号和计费后台，Operit 没有 |
| WhatsApp 等官方消息渠道 | 需要平台官方商业接口和审核 |
| 7×24 云端常驻（systemd 服务） | Orbit 跑在手机上，会被系统回收、断网、关机 |
| 视频生成 | 需要云端视频模型服务 |
| 实时语音通话 | 需要实时语音模型和通话后台 |
| HomeKit、健康数据、Home Link、Mac 配对 | 依赖苹果生态或 Muse 自家硬件 |
| 锁价、预订占位（holds） | 依赖 Muse 和商家的合作接口 |

## 参考资料

两个参考包都由用户从 Muse 导出：

- `muse-orbit-reference.zip`：完整系统提示词 `system-prompt.md`、导读和 `docs/` 下 29 份子系统文档。
- `muse-orbit-reference-supplement.zip`：工具参数、技能原文、运行时文件样例、记忆库和调度结构、实战案例。

做某一项之前，先读对应资料：

| 待办 | 补充包里的资料 | 要点 |
| - | - | - |
| Hook、更多定时方式、任务时限 | `tools/10`、`22-调度数据结构.md` | 时间触发和条件触发分开；hook 脚本每轮只能以一个 `silent` 或 `wake` 结束，新 hook 默认停用，试跑通过再启用 |
| Tracking、目标简报 | `tools/11` | Tracking 是「替用户扛的事」，目标是「用户自己的目标」，两者分开；只有进行中和已完成两种状态 |
| 主动消息偏好、主动消息逐条决定 | `tools/11`、`runtime-samples/20` | 偏好文件分「要说 / 别说 / 什么时间 / 怎么说」四块；默认不打扰 |
| 侧聊、聊天渠道 | `tools/12`、`23-聊天渠道.md` | 侧聊是和用户的一对一聊天；定时结果回到创建它的那个聊天 |
| 设备命令族 | `tools/12`、`24-设备命令.md`、`skills/device-data` | 先列设备、再查命令、再调用，命令表不写死；没授权的命令在调用时弹框让用户批准；离线读缓存必须告诉用户是缓存 |
| 密码保险箱 | `tools/12`、`skills/00-凭证注入模式.md` | 录入卡片直接把值存进保险箱，助手只看到站点和字段名；脚本拿不到明文，只能向白名单主机发带凭证的请求 |
| 富格式成果、动态 UI 卡片 | `tools/13` | 成果创建时就定好归属，之后不搬；改成果时传用户原话 |
| Feed | `31-Feed提示词与unit结构.md` | Feed 只由一段文字提示词决定；每条内容必须写一句「为什么给你看」 |
| Ideas、图片生成、回收站、「忘掉」流程、起名卡片 | `tools/13`、`skills/forget` | 「忘掉」先做只读规划，用户确认最新计划后才删 |
| 人物和群组记忆、每日流水日志 | `21-记忆库结构.md` | 人物页只记用户亲口说的关系；按需读取，不整个塞进提示词 |
| 经验本、对齐总结、用户资料格式 | `runtime-samples/20` | USER 的固定三项是名字、称呼、时区 |
| 专项技能 | `skills/*`、`skills/muse_db/references` | 可改写成 Orbit 技能包 |
| 定时任务写法 | `cases/30-实战案例全文.md` | 用水位文件去重，无新增就静默；对外发帖只由主代理统一发，子代理只起草 |

## 还缺的参考资料

下面这些在资料里被引用了，但两个包都没有。做到对应功能时再请用户补：

- 技能附属文件：flight-booking 的 `flights.md`、`browser-booking.md`、`checkout.md`；hotel-booking 的 `hotels.md`；shopping 的 `browser-checkout.md`、`social-shopping-links.md`、`marketplace.md`；spoken-audio 的 `speech.md`；forget 的 `artifact-inventory.md`
- 未打包的技能：`goals`、`custom-connector-setup`、`generate_podcast`、`trip-planning`、`marketplaces`、`wearables-comms`
- 后台循环的提示词：夜间 Dreaming、对齐总结、人物关系整理、Ideas 生成、主动消息准备
