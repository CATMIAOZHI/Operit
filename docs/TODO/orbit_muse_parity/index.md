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
| 旧记忆查询 | `extended_memory_tools` 工具包的 `query_memory` |
| 记忆隔离 | 每个 Orbit 只读写自己的记忆，用户要求时才读别处 |
| 找回聊天原话 | `memory_review` 工具包的 `history` |
| 技能按需加载 | `orbit_skills` 查目录，再加载正文 |
| 沉淀新技能 | `memory_review` 工具包的 `manage`（action=skill_create） |
| 提醒、每日、间隔、接力任务 | `orbit_task`（ONCE / DAILY / INTERVAL / EVENT） |
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

## 部分有，需要接进 Orbit

- [ ] USER.md 单独的查看编辑入口（现在只能在记忆设置里改 user.md）
- [ ] 人物和群组记忆页（Muse 的 `memory/people/`、`memory/groups/` + INDEX.md）
- [ ] 技能管理页：在 Orbit 详情页查看、编辑、删除它自己存的技能
- [ ] Hook：轻量脚本轮询条件，有变化才唤醒 Orbit（现在只有网页变化监测）
- [ ] 跨来源主动推送：通知、日历、短信等变化触发 Orbit
- [ ] 后台并行浏览器任务（Muse 的 `browser.spawn_task`）
- [ ] 深度研究（Muse 的 `browser.deep_research`）
- [ ] 富格式成果：Word / PDF / 网页成果（仓库已有 `util/DocumentConversionUtil.kt` 可借用）
- [ ] 媒体库（Muse 的 Library 标签页）
- [ ] 图片生成工具接进 Orbit（已有 `features/draw/CodexDrawBridge.kt`）
- [ ] Tracking：跟踪预订、快递、承诺这类具体事项，并在目标页显示
- [ ] 设备命令族：读取通讯录、日历、位置等（Muse 的 `device.invoke`）
- [ ] 数据导出：一键导出 Orbit 的文件、记忆和记录
- [ ] 动态 UI 专用卡片：选项卡片（Muse 的 `muse.create_options`）、地图卡片等
- [ ] 表情回应：给用户消息点一个表情（Muse 的 `react_to_user_message`）
- [ ] 主动消息偏好文件（Muse 的 `PROACTIVE_PREFERENCES.md`；现在记在 memory.md）
- [ ] 用户时区文件（Muse 的 `timezones.yaml`；现在记在 user.md）
- [ ] 侧聊：Orbit 下开独立话题对话，结果回到对应侧聊

## 还没做，可以做

- [ ] 从别的 AI 导入记忆和偏好
- [ ] 记忆溯源：说明一条记忆从哪来、替换了什么（Muse 的 `memory_explain`）
- [ ] 发送前检查：对外发消息前自动核对隐私和授权
- [ ] 密码保险箱：安全输入卡片，凭证不进入聊天和记忆（Muse 的 Secure Vault）
- [ ] 文件分享链接：上传文件生成有效期链接
- [ ] 多音色播客 / 语音消息
- [ ] 目标简报：定期汇总目标进展
- [ ] Feed：Orbit 在后台写的短文章信息流
- [ ] Ideas：Orbit 主动提出的建议卡片
- [ ] 后台自我提升任务：整理记忆、更新对齐总结、维护技能（Muse 的 self_improvement）
- [ ] 对齐总结文件（Muse 的 `ALIGNMENT_SYNTHESIS.md`）
- [ ] 系统提示词副本：用户要时直接发文件
- [ ] 分享到 Operit：从别的 App 分享内容给 Orbit
- [ ] Tailscale 等内网设备连接

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

## 还缺的参考文档

补齐这些，「部分有」和「可以做」的项目才能照着实现：

1. 工具目录和参数：cron、hooks、subagent、chat、device、credentials、artifact、widget、feed、idea、process、todo、workflow、tracking、user_goal 的真实函数名和参数
2. 预装技能原文：`tts/SKILL.md`、shopping、booking、`forget`、`muse_db/references/schema.md`
3. 运行时文件样例（脱敏）：AGENTS、SOUL、IDENTITY、USER、MEMORY、PROACTIVE_PREFERENCES、GOAL.md、ALIGNMENT_SYNTHESIS.md
4. 记忆库结构：bank / index 目录、people 和 groups 的 INDEX.md 与人物页格式
5. 一个 cron 的完整定义、运行记录样例、hook 脚本协议
6. `docs/chat-connections/` 下除 WhatsApp 以外的渠道
7. `docs/devices/` 里手机的 `device.describe` 命令族
8. 案例全文：PR 审计心跳、邮件监测的任务说明、脚本和记录字段
9. Feed 提示词原文和 taste summary 样例
