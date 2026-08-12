# Java Bridge Interface

这份文档是 **QuickJS + Java Bridge 的接口契约**。

目标只有一个：

- 给脚本开发者一份**简洁、可直接依赖**的 API 文档
- 给测试提供**明确的验收基准**
- 给 Bridge 实现提供**需要对齐的目标行为**

如果实现与本文档不一致，应优先把问题视为 **Bridge / Runtime 待修项**，而不是先降低文档承诺。

> 安全边界：Java Bridge 在所有 `JsEngine` 运行时中都只提供受限的纯数据处理能力。脚本不能借此取得 Android `Context` / `Activity`、应用内部类、文件/进程/网络 API，也不能加载外部 dex/jar。需要宿主能力时必须使用经过权限与参数检查的 `Tools` API。

旧包 `apktool`、`deepsearching`、`message_insert`、`pdf_vision_parser.js`、`qqbot`、`subagent` 依赖已撤销能力，源码仅保留作迁移参考，已从发布白名单（若原先在其中）与 example build-check 排除。旧 `androidTest/js/.../bridge_contract` 和 `bridge_edges` 同样是 unrestricted bridge 的历史资产；当前真机入口是 `restricted_bridge/restricted_bridge.js`。

## 1. 入口

运行时注入：

- `Java`
- `Kotlin`

它们是同一个桥的两个别名。

## 2. 类与包访问

支持以下入口：

```js
Java.type('java.lang.StringBuilder')
Java.use('java.lang.StringBuilder')
Java.importClass('java.lang.StringBuilder')
Java.package('java.lang')
Java.java.lang.StringBuilder
```

类名必须属于以下 allowlist（primitive 及其 boxed 类型按同一规则处理）：

- `java.lang.Boolean`, `Byte`, `Character`, `Double`, `Float`, `Integer`, `Long`, `Number`, `Short`, `String`, `StringBuilder`
- `java.util.ArrayList`, `Collections`, `HashMap`, `HashSet`, `LinkedHashMap`, `LinkedHashSet`, `UUID`

`classExists(...)` 对 allowlist 外的类返回 `false`；`type` / `use` / `importClass` 会在创建 class proxy 时立即抛出“not allowed”错误，包链遇到类名段时也会立即拒绝，而不是返回一个延迟失败的伪 package proxy。包链语法不会扩大 allowlist。

类名 allowlist 只是第一层。当前 profile 还使用正向成员契约：

- boxed primitive 与 `UUID`：使用固定的正向方法名/签名集合，开放 API 26 已存在的数据转换、比较和格式化方法；继承自 `Object` 的 `wait` / `notify` / `getClass`、读取 JVM system property 的 `getBoolean` / `getInteger` / `getLong`，以及设备升级后新增但不在固定集合中的方法都不开放。
- `String`：开放字符/码点读取、比较、查找、大小写/空白转换、切片、`concat`、`getBytes` / `getChars`、`valueOf` / `copyValueOf`；不开放 `repeat`、`intern`、格式化、正则和可产生高放大结果的替换接口。
- `StringBuilder`：只开放无参、`String` 构造器，以及不接收动态 `CharSequence` 的 append/insert/replace/delete/reverse/切片/读取/`toString` 等受长度预算约束的成员；容量构造器、`CharSequence` 构造器/重载、`ensureCapacity`、`setLength` 不开放。
- 成员契约以应用的 `minSdk 26` 为兼容下限；只在更高 Android API 才出现的 `String.isBlank` / `strip*`、`StringBuilder.compareTo`、boxed primitive 新方法或同名新 overload 都不开放。
- `ArrayList` / Map / Set：只开放无参或同类容器复制构造器，以及线性/受界的增删改查、比较、clone/toArray/toString；容量构造器、`ensureCapacity`、`ArrayList.containsAll` 以及 collection `removeAll` / `retainAll` 等可能退化为集合大小乘积的成员不开放。
- `Collections`：只开放 bounded list/set/map helpers，例如 sort/reverse/rotate/swap/copy/fill/search/min/max/frequency、empty/singleton、`nCopies` 和 `addAll`；回调型以及 `disjoint` / `indexOfSubList` / `lastIndexOfSubList` 等可能二次退化的 helper 不开放。
- 静态字段只允许读取 boxed primitive 的已列常量；当前 profile 不允许任何静态或实例字段写入。

嵌套类不会因为外层类被允许而自动获权；只有嵌套类的 `$` 完整类名本身也进入 allowlist 时才能访问。当前 profile 没有允许任何嵌套类。

## 3. 构造与调用语法糖

对于类代理 `Cls`，保证支持：

```js
new Cls(...args)
Cls(...args)
Cls.newInstance(...args)
```

对于实例代理 `obj`，保证支持：

```js
obj.method(...args)
```

并且：

```js
obj.method(...args) === obj.call('method', ...args)
```

底层仍保留 `get` / `set` 兼容入口，但当前受限 profile 不声明实例字段，也不允许字段写入；不要把任意 public 字段视作脚本 API。

对于类代理 `Cls`，保证支持：

```js
Cls.STATIC_FIELD
Cls.staticMethod(...args)
```

`STATIC_FIELD` 仅限前述 boxed primitive 只读常量。嵌套类必须以自身完整类名独立进入 allowlist；当前没有允许的 nested class。

并且：

```js
Cls.staticMethod(...args) === Cls.callStatic('staticMethod', ...args)
```

## 4. 顶层桥接 API

以下兼容入口仍存在并会生成 JS marker：

```js
Java.classExists(className)
Java.newInstance(className, ...args)
Java.callStatic(className, methodName, ...args)
Java.callSuspend(className, methodName, ...args)
Java.listLoadedCodePaths()
```

以下兼容入口仍存在，但始终拒绝并返回失败，不能用于取得额外权限：

```js
Java.getApplicationContext()
Java.getCurrentActivity()
Java.loadDex(path, options?)
Java.loadJar(path, options?)
```

## 5. 接口实现

保证支持：

```js
Java.implement(interfaceNameOrClassProxy, impl)
Java.proxy(interfaceNameOrClassProxy, impl)
```

单接口 / SAM 写法同样保留：

```js
Java.implement(() => { ... })
Java.proxy(() => { ... })
```

但当前严格 profile 没有开放任何可消费代理的接口类型。尤其 `CharSequence` 代理和所有接收动态 `CharSequence` 的重载均被拒绝，以免有状态回调在检查后改变长度；需要字符数据时直接传 JS 字符串。只有未来把具体接口名加入独立的 interface-proxy allowlist 后，对象同名方法、accessor 映射和非 `void` 返回规则才会生效。

## 6. 挂起调用

`callSuspend(...)` 永远返回 `Promise`。

保证支持：

```js
await Java.callSuspend('java.lang.StringBuilder', 'someSuspendMethod', 'arg')
await SomeClass.callSuspend('load', 'arg')
await someInstance.callSuspend('load', 'arg')
```

类名、实例 handle、参数 JSON、方法匹配或同步反射准备阶段失败时，Promise 会 reject；不会永久 pending。

## 7. Java -> JS 转换

Java / Kotlin 返回到 JS 时，保证按下表转换：

| Java / Kotlin | JS |
|------|------|
| `null` / `Unit` | `null` |
| `String` / `char` | `string` |
| Java 方法声明返回 `CharSequence`、当前开放路径实际值为 `String` | 可按 `string` 使用；其他返回值仍按其实际类经过 allowlist 与 handle 校验 |
| `boolean` / `Boolean` | `boolean` |
| `Number` | `number`；`NaN`、`Infinity`、`-Infinity` 通过 bridge 内部标记透明往返，不会被 JSON 降为 `null` |
| `Enum` | `string` |
| `Class<?>` | `string` |
| `Map` / `JSONObject` | plain object |
| `Iterable` / `List` / `Set` | JS array |
| Java 数组 | JS array |
| `JSONArray` | JS array |
| allowlist 中的其他普通对象 | Java 实例代理 |
| allowlist 外对象 | 拒绝 |

补充说明：

- 这里说的“返回到 JS 时按字符串/数组/对象使用”，指的是**Java/Kotlin 方法返回值**的归一化语义。
- 如果你显式构造的是普通 Java 对象，例如 `new Java.java.lang.StringBuilder()`、`new Java.java.util.ArrayList()`，得到的仍然是 Java 实例代理，而不是直接拍平成 JS primitive / array / object。
- 为防止循环容器、无限惰性集合、重复大字符串或异常深度数据拖垮运行时，单次 Java bridge 返回值最多遍历 64 层、65,536 个容器元素（`Map` 每个 entry 计一个元素，但 key 和 value 仍分别递归检查循环、深度与标量字符）和 1,048,576 个标量字符（重复引用按每次输出计数）。检测到循环或超限时，本次调用会 reject/返回失败，bridge 仍可继续使用。
- QuickJS 调用 `java*` bridge 方法以及 SandboxPackage_DEV 的两个固定只读 asset API 时，外层参数 envelope 在第一处 `JSONTokener` 前限制为 2,113,536 个字符、64 层和 65,536 个 array element/object entry；该长度为最坏双重转义的 1 MiB 内层参数保留额外 envelope 空间。图片、ToolPkg 注册与其他大 payload NativeInterface API 保持各自既有契约，不套用此限制。envelope 内的 Java bridge 入参 JSON 最多 1,048,576 个原始字符（包含首尾空白），同样在解析前限制 64 层，解析后再应用 65,536 元素/entry 预算。mutable container handle 仅由已校验的构造路径创建；后续变异会在反射调用前增量校验实际写入的 key/value/element、循环引用和容量，而只读调用不会重复遍历整个容器。
- bridge 创建或扩张的 `StringBuilder` 与 mutable collection/map 上限为 65,536 个字符/元素/entry；触及上限的调用会在 `Constructor.newInstance` / `Method.invoke` 之前被拒绝。
- 单个引擎最多保留 1,024 个 live Java object handles；JS 代理释放或 GC 后槽位可复用，达到上限时新的构造或对象返回会在注册前失败。
- Java bridge 适合小型数据与安全工具类互操作；大块二进制或更大的结构化数据应使用 `Tools` 提供的受控文件、网络等宿主 API，不要通过反射返回值搬运。

## 8. JS -> Java 转换

JS 传给 Java / Kotlin 时，保证按目标参数类型进行转换：

| JS | Java / Kotlin |
|------|------|
| `null` | 非 primitive 参数 |
| `string` | `String` / `char` / `enum` / `Class<?>` / `JSONObject` / `JSONArray` |
| `number` | 各种数字类型 |
| `boolean` | `boolean` / `Boolean` |
| JS array | Java 数组 / `Collection` / `JSONArray` / varargs |
| plain object | `Map` / `JSONObject` / 接口实现代理 |
| Java 实例代理 | 原始 Java 对象 |
| `Java.implement(...)` / `Java.proxy(...)` 返回值 | 兼容 marker；当前 profile 无可消费的接口代理目标 |

原生 JavaScript `bigint` 不是 bridge primitive：底层调用使用 `JSON.stringify`，直接传入 `bigint` 会在进入 native 前失败。需要传递其文本值时必须先显式调用 `.toString()`。

## 9. 返回结果

导出函数允许两种完成方式：

```js
return result;
complete(result);
```

`complete(result)` 与直接 `return result` 都是正式接口。

结果对象保证支持：

- 普通 JSON 对象 / 数组 / 字符串 / 数字 / 布尔 / `null`
- Java Bridge 实例
- Java Bridge 回调代理

## 10. 推荐写法

默认推荐开发者直接使用语法糖：

```js
const Integer = Java.java.lang.Integer;
const value = Integer.parseInt('123');
const max = Integer.MAX_VALUE;

const StringBuilder = Java.java.lang.StringBuilder;
const text = new StringBuilder().append('safe').append('-bridge').toString();
```

`.call(...)` / `.get(...)` / `.set(...)` / `callStatic(...)` 属于显式底层写法，主要用于：

- 调试
- 字段 / 方法同名冲突
- 排查桥接问题
