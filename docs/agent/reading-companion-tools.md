# 阅读伴侣工具接口

普通对话先发现工具包简介，再通过 `use_package` 加载接口及使用说明。CLI 模型通过
`search` 发现接口时同时获得该包的使用说明。

## 工具分组

- `reading_companion`：选书、当前上下文、人物和章节回顾、读者记忆。
- `reading_companion_tasks`：`start_task`、`get_task`、`list_tasks`、`cancel_task`。
- `reading_companion_manage`：文件浏览、摘要范围设置、段评和审计记录。
- `reading_companion_auto_commentary`：既有后台自动段评的状态和配置。启用手动生成任务不要求启用后台自动段评。

新对话接口使用从 1 开始的章节号；Kotlin 内部仍使用零基索引。现有管理界面的
`summary_batch_prefs` 保留一基章号。

## 生成任务

`start_task` 接收 `kind=summary|commentary`、`count`，可传 `book_id`、
`start_chapter`、`end_chapter` 和 `request_id`。省略书籍时在启动时固定当前选书。
`mode=fill_missing` 补缺；段评还支持 `mode=regenerate` 重生成明确的起止范围。
补缺模式的 `count` 为处理预算；段评重生成使用明确起止范围（最多 10 章）。
同一次请求发生传输重试时复用 `request_id`，不要另发一个新的生成请求。
要再次生成时使用新的 `request_id`；旧标识会返回原任务，包括原任务已结束的情况。

启动立即返回 `task_id`。用该 ID 查询状态或取消；`list_tasks` 可找回近期任务。
任务继续使用现有逐章子代理及审计记录，取消不会删除已完成章节。
任务记录保存在应用私有目录，进程重启后未完成任务标记为 `interrupted`，
不会自动重放或自动产生新模型费用。它不承诺进程退出后仍继续生成。

## 文件与人物

人物查询基于 `characters.md` 和有效章节文件，不再依赖缺少生产路径的人物 FTS 索引。
这些文档中的人物看法和读者记忆仍需与小说原文证据区分。

`read_persisted_file` 接收 `offset` 和 `max_characters`，使用结果中的 `nextOffset`
继续读取。默认每次读取 16000 字符。路径仍需属于当前书籍的有效文件目录；
文件查看页以分段方式加载。

本次重构不迁移、删除书籍文件或 reading 数据库。旧原生批次动作保留给既有调用方，
新的工具目录和侧栏生成入口使用任务接口。
