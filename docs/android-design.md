# Android 客户端工程设计

> 前置阅读：[`protocol.md`](./protocol.md) · [`rpc-catalog.md`](./rpc-catalog.md)
> 协议数据均来自运行时实测，工程实现应严格按协议文档，不要凭直觉改写端点和封包。

---

## 1. 目标与非目标

### 目标（MVP / P1）

| 功能 | 说明 |
|---|---|
| PIN 登录 | 输入 pocket PIN，换取双层 Cookie 并持久化 |
| 会话列表 | 读取会话列表与标题、运行状态 |
| 历史浏览 | 打开会话，渲染历史消息（含工具调用/结果） |
| 发送消息 | 提交 prompt |
| 流式回复 | 实时显示助手输出与思维链 |
| 中断 | 取消正在运行的回合 |
| 多服务器 | 保存多个地址（家里/公司） |

### 非目标（MVP 不做）

- 插件市场管理
- 终端（`terminal/*`，10 个方法）
- 设置编辑（`settings/mutate` 等）
- 账号登录（`account/*`）
- 文件上传/附件（`session/attachment`）
- 目标/待办（`goal` 相关投影）

---

## 2. 技术栈

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | Kotlin | 原生 |
| UI | Jetpack Compose + Material 3 | 官方推荐 |
| 异步 | Coroutines + Flow | 流式天然契合 |
| 网络(HTTP) | OkHttp | 成熟、可控 Cookie |
| 网络(WS) | OkHttp WebSocket | 与 HTTP 共用连接池与 Cookie |
| JSON | kotlinx.serialization | 编译期序列化，无需反射 |
| DI | Hilt（或手写 `AppContainer`） | 小型项目手写即可 |
| 本地存储 | DataStore(Proto/Preferences) + Room | 设置用 DataStore，消息缓存用 Room |
| 最低版本 | minSdk 26 / targetSdk 36 | 与已装 SDK 匹配 |

> **不引入 Retrofit**：协议只有一种 POST 形态，OkHttp 直连更清晰。

---

## 3. 工程结构

```
dsh_mobile/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/dsh/mobile/
            ├── DshApp.kt                     Application + 手写 DI 容器
            ├── MainActivity.kt
            ├── core/
            │   ├── Result.kt                 密封结果类型
            │   └── DshError.kt               协议错误 → 领域错误映射
            ├── data/
            │   ├── net/
            │   │   ├── PocketAuth.kt         ★ 第 2.1 节：PIN → Cookie
            │   │   ├── CookieStore.kt        ★ 双层 Cookie 持久化
            │   │   ├── RpcClient.kt          ★ 第 3 节：一元 RPC
            │   │   ├── StreamMux.kt          ★ 第 4 节：WS 多路复用
            │   │   ├── MuxFrame.kt           ★ 帧的序列化模型
            │   │   └── Endpoints.kt          端点常量（见 rpc-catalog）
            │   ├── model/                    协议数据模型
            │   │   ├── SessionSummary.kt
            │   │   ├── SessionAddress.kt
            │   │   ├── SessionSnapshot.kt
            │   │   ├── SessionEvent.kt       ★ 第 5 节的事件模型
            │   │   └── MessageContent.kt
            │   ├── repo/
            │   │   ├── SessionRepository.kt  会话 CRUD + 事件流聚合
            │   │   └── ServerRepository.kt   多服务器配置
            │   └── local/
            │       ├── ServerPrefs.kt        DataStore：服务器列表
            │       └── db/                   Room：消息缓存
            └── ui/
                ├── theme/
                ├── nav/AppNavHost.kt
                ├── login/LoginScreen.kt      PIN 输入
                ├── servers/ServerListScreen.kt
                ├── sessions/SessionListScreen.kt
                ├── chat/
                │   ├── ChatScreen.kt
                │   ├── ChatViewModel.kt      ★ 核心状态机
                │   └── components/
                │       ├── MessageBubble.kt
                │       ├── ReasoningBlock.kt  思维链（默认折叠）
                │       ├── ToolCallCard.kt    工具调用 + 结果配对
                │       └── Composer.kt        输入框 + 发送/中断
                └── settings/SettingsScreen.kt
```

---

## 4. 网络层设计（核心）

### 4.1 PocketAuth — PIN 换 Cookie

```kotlin
class PocketAuth(private val client: OkHttpClient, private val store: CookieStore) {

    /**
     * GET {base}/?token={pin}
     * 注意：响应可能是 303 过渡页（被代理改写成 200 + meta refresh）。
     * 我们只关心 Set-Cookie，不解析 HTML。
     */
    suspend fun login(base: String, pin: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/?token=${pin.urlEncode()}")
            // 关键：禁止跟随重定向，避免重复交换 token 造成 303 循环
            .build()
        client.newBuilder().followRedirects(false).build().newCall(req).execute().use { resp ->
            val cookies = resp.headers.values("Set-Cookie")
            if (cookies.isEmpty()) return@use false
            store.save(base, cookies)
            true
        }
    }
}
```

**要点**

- `followRedirects(false)`：代理专门为 Safari 的无 3xx cookie 问题做了 303→200 改写，
  客户端跟随后可能重复触发握手，代理有 `handshakeLimit` 保护但会返回 503。
- `dsh_pocket_token` 跨 DSH 重启失效 → 见 [4.5 401 处理](#45-401-处理)。

### 4.2 RpcClient — 一元 RPC

```kotlin
@Serializable
data class RpcRequest(
    val type: String = "client-request",
    val rpcId: String,
    val method: String,
    val payload: RpcPayload,
)

@Serializable data class RpcPayload(val args: JsonObject)

@Serializable
data class RpcResponse(
    val type: String,
    val rpcId: String,
    val result: RpcResult,
)

@Serializable
data class RpcResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class RpcError(val code: String, val message: String, val details: JsonObject? = null)
```

```kotlin
class RpcClient(private val client: OkHttpClient, private val store: CookieStore) {

    suspend inline fun <reified T> call(
        base: String,
        endpoint: String,          // "session/list"
        args: JsonObject,          // 注意：不要自己加 _request，见下
    ): T {
        val rpcId = UUID.randomUUID().toString()
        val body = json.encodeToString(
            RpcRequest(rpcId = rpcId, method = endpoint, payload = RpcPayload(args))
        ).toRequestBody(JSON)

        val req = Request.Builder()
            .url("$base/api/$endpoint")
            .post(body)
            .header("Content-Type", "application/json")
            .apply { store.cookieHeader(base)?.let { header("Cookie", it) } }
            .build()

        client.newCall(req).execute().use { resp ->
            // ⚠️ 成败看 result.ok，不是 HTTP 状态码
            if (resp.code == 401) throw DshError.Unauthorized(resp.body?.string())
            val parsed = json.decodeFromString<RpcResponse>(resp.body!!.string())
            if (!parsed.result.ok) throw DshError.Rpc(parsed.result.error!!)
            return json.decodeFromJsonElement(parsed.result.value!!)
        }
    }
}
```

**易错点（实测踩过）**

| 坑 | 正解 |
|---|---|
| 端点写成 `session.list` | 必须是 `session/list`（斜杠） |
| `payload` 直接放业务字段 | 必须是 `{"args": {...}}`，且只能有 `args` 一个键 |
| 用 HTTP 状态码判断成败 | 业务失败也是 **HTTP 200**，要看 `result.ok` |
| 给所有方法都加 `_request` | 只有 `session/list` 需要 `_request`；`session/control` 加了反而报错 |

### 4.3 StreamMux — WebSocket 多路复用

```kotlin
@Serializable
data class MuxOpen(
    val type: String = "open",
    val streamId: String,
    val endpoint: String,
    val payload: RpcPayload,
)
// 上行：{"type":"item","streamId":...,"value":...}
// 取消：{"type":"cancel","streamId":...}
// 结束：{"type":"end","streamId":...}
```

```kotlin
class StreamMux(
    private val client: OkHttpClient,
    private val store: CookieStore,
) {
    private var ws: WebSocket? = null
    private val streams = mutableMapOf<String, MutableSharedFlow<MuxFrame>>()

    fun connect(base: String) {
        val wsUrl = base.replaceFirst("http", "ws") + "/api/remote.mux"
        val req = Request.Builder()
            .url(wsUrl)
            .apply {
                store.cookieHeader(base)?.let { header("Cookie", it) }
                header("Origin", base)           // 代理会改写，但带上更稳
            }
            .build()
        ws = client.newWebSocket(req, listener)
    }

    /** 打开一条逻辑流，返回该流的下行帧。 */
    fun open(base: String, streamId: String, endpoint: String, args: JsonObject):
        Flow<MuxFrame> {
        val flow = MutableSharedFlow<MuxFrame>(extraBufferCapacity = 256)
        streams[streamId] = flow
        ws?.send(json.encodeToString(MuxOpen(streamId = streamId, endpoint = endpoint,
                                             payload = RpcPayload(args))))
        return flow
    }
}
```

**生命周期建议**

- 全 App **只保持一条** WebSocket（`remote.mux` 本就为复用设计）
- 断线用指数退避重连；重连后需**重新 open 所有活跃流**
- `streamId` 用 `<用途>-<自增>`，并在 ViewModel 销毁时 `cancel`

### 4.4 会话订阅（MVP 的主动脉）

```kotlin
// $events：全局事件流，args 必须是空对象
mux.open(base, "ev", "\$events", buildJsonObject {})

// session/follow：会话级历史 + 增量（address 的 kind 不可省）
mux.open(base, "fl-1", "session/follow", buildJsonObject {
    putJsonObject("request") {
        putJsonObject("address") {
            put("kind", "session")
            put("sessionId", sessionId)
        }
        put("maxMessages", 30)
        put("assistantStream", true)   // 需要 token 级流式时开
    }
})
```

首帧是 `snapshot`（含 `records[]` 历史与 `projections`），此后持续推送增量。

### 4.5 401 处理

```
收到 401
 ├─ body == {"error":"unauthorized"}  → pocket 层：清 Cookie，跳登录页
 └─ body == "unauthorized"            → DSH 层：重新 GET /?token=<PIN>，再重试原请求一次
```

**务必实现**：DSH 每次重启都会让 `dsh_pocket_token` 失效（进程级 `sessionKey`），
这是**高频正常现象**，不能让用户看到冷冰冰的报错。

---

## 5. 数据层设计

### 5.1 事件归并

`session/follow` 的 `records[]` 与后续增量是**同构**的 `SessionEvent`，
用同一个 reducer 处理即可保证「历史 + 实时」渲染一致：

```kotlin
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val errors: List<String> = emptyList(),
)

fun ChatUiState.reduce(event: SessionEvent): ChatUiState = when (event.type) {
    "user/message"      -> copy(messages = messages + event.toUserMessage())
    "assistant/message" -> copy(messages = upsertAssistant(messages, event))
    "tool/call"         -> copy(messages = attachToolCall(messages, event))
    "tool/result"       -> copy(messages = attachToolResult(messages, event))
    "turn/start"        -> copy(running = true)
    "turn/end"          -> copy(running = false)
    else                -> this          // step/*, goal/change 等先忽略
}
```

**实测事件类型**（真实会话 157 条记录）：
`assistant/message`(28) `tool/call`(34) `tool/result`(33) `step/start`(27) `step/end`(27)
`user/message`(2) `goal/change`(2) `agent/inbox/spliced`(2) `turn/start`(1) `turn/end`(1)

### 5.2 工具调用配对

`tool/call` 与 `tool/result` 靠 `id` 配对，**可能乱序到达**（尤其在首屏 snapshot 中）。
渲染层应按 `id` 建索引，而不是按顺序 zip。

### 5.3 缓存策略

| 数据 | 策略 |
|---|---|
| 服务器列表 + Cookie | DataStore（Cookie 加密存储，用 `EncryptedSharedPreferences` 或 Keystore） |
| 会话列表 | 内存 + Room，进页面先出缓存再刷新 |
| 消息 | Room，按 `sessionId + seq` 存；`seq` 是天然的去重键与排序键 |

---

## 6. UI 层

### 屏幕清单

| 屏幕 | 关键交互 |
|---|---|
| `LoginScreen` | 输入 PIN；显示当前服务器地址；错误区分两层 401 |
| `ServerListScreen` | 多服务器切换、增删、连通性检测 |
| `SessionListScreen` | 会话列表 + 运行中角标 + 下拉刷新 + 搜索（`session/search`） |
| `ChatScreen` | 消息流、思维链折叠、工具卡片、输入框、发送/中断按钮 |
| `SettingsScreen` | 主题、默认 `maxMessages`、清缓存 |

### 渲染优先级（MVP）

1. `user/message` — 普通气泡
2. `assistant/message` 的 `text` 块 — Markdown 渲染
3. `assistant/message` 的 `reasoning` 块 — **默认折叠**的可展开区
4. `tool/call` + `tool/result` — 合并成一张卡片，显示工具名、参数摘要、结果摘要
5. `turn/start` / `turn/end` — 驱动「运行中」指示器与中断按钮可用性

---

## 7. 分阶段计划

### P0 — 协议 Spike（**先做这个，通关再往下投**）

**唯一目标：证明流式能跑通。** 不做 UI，不做架构。

| 步骤 | 验收标准 |
|---|---|
| P0.1 建最小 Compose 工程 | 能在真机 `9a1acac9` 上跑起来 |
| P0.2 实现 PIN 登录 | logcat 打印出两个 Cookie 名 |
| P0.3 实现一元 RPC | `session/list` 打印出会话数量 |
| P0.4 实现 WS 连接与 `open` | 收到 `$events` 的 `ready` 帧（含 clientId） |
| P0.5 **订阅 `session/follow`** | 收到 `snapshot` 帧并打印 `records.size` |
| P0.6 **实时增量** | 在电脑端发一条消息，手机 logcat 实时打出 `assistant/message` |

**P0.6 是生死线**：它证明「手机能实时看到 AI 输出」。过了才继续。

### P1 — MVP 读写闭环（✅ 已完成，真机验收）

会话列表 → 进入会话 → 历史渲染 → 发送 → 流式 → 中断。

已落地：`SessionListScreen`（标题 / cwd / 运行中角标 / 空会话标记 / 新建 / 下拉刷新按钮）
→ `ChatScreen`（用户气泡、助手正文、**默认折叠的思维链**、工具卡片按 `id` 配对结果、
运行中显示「中断」）→ `AppViewModel` 状态机（snapshot 重建 + 增量归并 + liveText 逐字渲染）。

实现期补充的工程约束（P0 计划里没有预见）：

| 约束 | 说明 |
|---|---|
| `session/prompt` 的 `mode` 必填 | 漏了只报 boundary validation，不提示字段名 |
| `tool/result` 的配对 id 在 `data.message` 里 | 不在 `data` 顶层，否则工具卡片永远停在「执行中」 |
| `user/message` 要按 `source.kind` 过滤 | 否则 time-context 等系统注入会被渲染成用户气泡 |
| 单条 WS 的流要在重连后重放 | 否则息屏/断网回来就收不到增量了 |
| snapshot 要以「重建」而非「追加」方式归并 | 重连补发 snapshot 时不重建会导致历史重复 |
| kotlinx.serialization `encodeDefaults = true` | 否则 `type:"client-request"` 被静默省略 |
| 工具链 AGP 9.0 + Gradle 9.7 | AGP 8.x 在 Gradle ≥9.6 上无法应用插件 |

仍未验证：真实 DSH 重启触发的 401 自动重登、真实息屏 5 分钟、ask-user 应答回填。

### P2 — 补齐体验

审批弹窗、ask-user 问答、模型切换（`session/selectModel`）、搜索、多服务器。

### P3 — 进阶

后台任务（`job/*`）、终端（`terminal/*`）、设置编辑、通知、资料库。

---

## 8. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| `assistantStream` 增量帧格式未知 | 🔴 高 | P0.6 时用 `_recon/` 脚本抓真实帧；退化方案：不订阅 token 级流式，改为收到 `assistant/message` 整块后再渲染 |
| `session/cancel` / `session/control` 参数未确认 | 🟡 中 | 开工前用 `discover-args.mjs` 或读控制器源码补上（`skip`/`control` 逻辑集中在 `SessionControlController`） |
| WebSocket 在隧道模式下行为 | ✅ 已验证 | 实机确认外网模式下连接成功，无需额外适配 |
| DSH 重启导致频繁掉登录 | 🟡 中 | App 内做好自动重登，PIN 可记住 |
| 事件类型可能随版本变化 | 🟡 中 | reducer 用 `else -> this` 兜底，未知类型忽略而非崩溃 |
| `dsh-pocket` 升级改变代理行为 | 🟢 低 | 协议文档记录版本号，升级后跑 `_recon/` 脚本回归 |
| 明文 HTTP 传输 | 🟡 中 | 局域网为明文 HTTP；外网模式由隧道终止 TLS（HTTPS/WSS）|

> ✅ **已解决的初始风险**：`session/prompt` 与 `session/page` 的参数结构已从控制器源码确认，
> 见 [`protocol.md` 7.3](./protocol.md#73-核心方法的-request-结构源码验证)。

---

## 9. 验收标准（P1 完成定义）

- [x] 手机与电脑同一 Wi-Fi，输入 PIN 后可进入会话列表（外网模式同样已验证）
- [ ] 能在手机上新建会话并看到历史
- [ ] 发送一条消息，能看到**逐字流式**输出
- [ ] 能中断正在运行的回合
- [ ] DSH 重启后，App 给出「需要重新登录」提示并一键重登，不崩溃
- [ ] 手机息屏 5 分钟后回到 App，仍能收到增量（或自动重连并补齐）

---

## 10. 开发环境备忘

| 项 | 路径 / 值 |
|---|---|
| Android Studio | `C:\Program Files\Android\Android Studio` |
| 自带 JDK | `C:\Program Files\Android\Android Studio\jbr`（OpenJDK 25） |
| SDK | `C:\Users\64034\AppData\Local\Android\Sdk` |
| 已装 platforms | `android-35` / `android-36` / `android-37.0` |
| build-tools | `36.0.0` |
| 真机序列号 | `9a1acac9` |

```powershell
# 真机安装并启动（P0 期间常用）
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s DshMobile:D
```

> **Gradle 首次构建**需要联网下载依赖，第一次会比较慢。
