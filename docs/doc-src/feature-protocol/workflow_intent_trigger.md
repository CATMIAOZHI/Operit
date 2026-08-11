# Workflow Intent 触发（自定义 Action）

本文档说明如何通过 **Intent 广播**触发 Operit 的工作流（Workflow）。

当前实现支持：

- **每个工作流的 Intent Trigger 可以配置自己的 `action` 和独立、由当前安装签名的 `auth_token`**
- 外部 App 发送广播时必须同时提供正确 action 与令牌；推荐使用显式广播

对应代码：

- 接收入口：`app/src/main/java/com/ai/assistance/operit/integrations/tasker/WorkflowTaskerReceiver.kt`
- 匹配与触发：`WorkflowRepository.triggerWorkflowsByIntentEvent(intent)`

---

## 1. 核心概念

### 1.1 TriggerNode(intent)

当某个工作流的触发器节点满足：

- `triggerType == "intent"`
- `triggerConfig["action"] == intent.action`
- `triggerConfig["auth_token"]` 与广播 extra
  `com.ai.assistance.operit.extra.WORKFLOW_AUTH_TOKEN` 完全一致

则该工作流会被触发执行。

### 1.2 为什么推荐“显式广播”

Android 对隐式广播有各种限制（尤其是后台、Android 8+ 等）。

为了确保广播能稳定投递给 Operit，推荐使用：

- **显式广播**：指定 `component`（包名 + Receiver 类名）

这样即使 action 是自定义的，也能确保发给 Operit 的 `WorkflowTaskerReceiver`。

---

## 2. Receiver / Component 信息

- **Receiver 类**：`com.ai.assistance.operit.integrations.tasker.WorkflowTaskerReceiver`
- **Receiver 代码类名**：`com.ai.assistance.operit.integrations.tasker.WorkflowTaskerReceiver`
- **默认个人版 applicationId**：`com.rainy.operitry`（clone 等变体后缀不同）
- **Component 格式**：`<applicationId>/com.ai.assistance.operit.integrations.tasker.WorkflowTaskerReceiver`

---

## 3. 配置 action 与令牌

在工作流编辑器中，将触发器设置为：

- **类型**：Intent
- **action**：填写你希望外部触发的 action，例如：
  - `com.example.myapp.TRIGGER_OPERIT_WORKFLOW_A`
- **auth_token**：由编辑器使用当前安装的私有签名密钥自动生成。复制到发送方配置中，不要手工构造、缩短、记录到日志或公开分享；其他安装生成的 token 也不会通过校验。

注意：

- action 与 auth_token 必须同时匹配。
- 触发时普通 intent extras 会作为 TriggerNode 的输出 JSON（字符串）提供给下游节点（可用 ExtractNode(JSON) 提取字段）。认证令牌会在匹配后移除，不会传给下游节点。

---

## 4. adb 触发示例

### 4.1 方式 A：显式广播（推荐，支持任意自定义 action）

```bash
adb shell am broadcast \
  -n com.rainy.operitry/com.ai.assistance.operit.integrations.tasker.WorkflowTaskerReceiver \
  -a com.example.myapp.TRIGGER_OPERIT_WORKFLOW_A \
  --es com.ai.assistance.operit.extra.WORKFLOW_AUTH_TOKEN "<工作流中的 auth_token>" \
  --es message "hello from adb" \
  --es request_id "req-1001"
```

- `-n` 指定 component，确保发给正确 applicationId；使用其他构建变体时替换前半部分。
- `-a` 为你在工作流 Trigger 里配置的 action。
- `--es/--ez/--ei/...` 为 extras，会被工作流 TriggerNode 收集并输出。

### 4.2 方式 B：隐式广播（仅当系统允许投递时）

如果你不想指定 component，可以试：

```bash
adb shell am broadcast \
  -a com.ai.assistance.operit.TRIGGER_WORKFLOW \
  --es com.ai.assistance.operit.extra.WORKFLOW_AUTH_TOKEN "<工作流中的 auth_token>" \
  --es message "hello"
```

Manifest 只为内置默认 action 注册隐式过滤器；自定义 action 必须使用显式 component。投递还取决于系统版本和 ROM 的后台限制。

### 4.3 方式 C：使用内置默认 action（兼容用法）

如果你的工作流 Trigger 里 `action` 配置的是默认值：

- `com.ai.assistance.operit.TRIGGER_WORKFLOW`

那么可以使用：

```bash
adb shell am broadcast \
  -a com.ai.assistance.operit.TRIGGER_WORKFLOW \
  --es com.ai.assistance.operit.extra.WORKFLOW_AUTH_TOKEN "<工作流中的 auth_token>" \
  --es message "hello" \
  --es request_id "req-1002"
```

---

## 5. WORKFLOW_RESULT：工作流回传广播（示范模板默认值）

在内置的“Intent 触发 + 发送消息 + 回传广播”示范模板中，会使用工具节点 `send_broadcast` 回传结果：

- **action**：`com.ai.assistance.operit.WORKFLOW_RESULT`
- **extra_key**：`result`
- **extra_value**：来自 `send_message_to_ai` 节点的输出（字符串）

你也可以在工作流里自定义：

- 回传 action（例如回传给你自己的 App）
- extra 的 key/value（例如同时回传 `request_id`、`chat_id` 等）

---

## 6. 如何接收 WORKFLOW_RESULT

`adb` 本身无法直接作为“广播接收端”来打印收到的广播内容（它只能发送广播）。要接收回传广播，推荐两种方式：

### 6.1 用 Tasker 接收（最方便）

- 在 Tasker 创建 Profile：Event -> System -> Intent Received
- Action 填：`com.ai.assistance.operit.WORKFLOW_RESULT`
- 在 Task 中读取变量（通常可直接用 `%result` 或从 extras 映射中取）

### 6.2 写一个最小接收 App / Receiver（用于调试）

在你的测试 App 中注册一个 `BroadcastReceiver` 监听 `com.ai.assistance.operit.WORKFLOW_RESULT`，在 `onReceive()` 里读取：

- `intent.getStringExtra("result")`

然后你可以用 `adb logcat` 看接收端打印的内容。

---

## 7. 工作流内如何读取 extras（Trigger JSON + Extract(JSON)）

TriggerNode 会把收到的 extras 转为 JSON 字符串作为输出，例如：

- 收到 extras：
  - `message=hello`
  - `request_id=req-1001`

TriggerNode 输出（示意）：

```json
{"message":"hello","request_id":"req-1001"}
```

下游可以使用 `ExtractNode(mode=JSON)`：

- `source = NodeReference(triggerNodeId)`
- `expression = "message"`

从而得到 `hello`。

---

## 8. 注意事项

- 该 Receiver 为 `exported=true`，但无有效的每工作流令牌时会在读取工作流前拒绝请求。
- `auth_token` 是执行工作流的能力凭据，由当前安装的私有密钥签名并使用 URL-safe 字符编码；必须复制编辑器生成的完整值，不要手工构造。不要放入公共仓库、截图、日志或回传 extras；怀疑泄露时，在编辑器中删除旧值并保存，让应用生成新的签名 token，再同步更新发送方。
- 旧公共 Downloads 工作流只通过“读取旧工作流”兼容开关显示，内部存储中的同名版本始终优先，原文件不会被修改。仅存在于旧目录的工作流不能直接接受外部 Intent/Tasker 触发；第一次修改、启停或执行时会写时复制到内部存储，并在内部副本可见前轮换全部外部触发 token。复制后请从编辑器读取新 token 并同步更新发送方；原有计划触发与启用状态会保留。
