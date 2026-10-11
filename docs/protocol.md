# dsh-pocket 协议规范

> 本文档所有内容均**经运行时实测验证**，非推测。验证方式与原始数据见 [`_recon/`](../_recon/)。
>
> 验证环境：DSH Desktop `0.2.0-rc.2` / dsh-pocket `2.10.6` / Windows
> 验证时间：2026-10-10

---

## 目录

- [1. 拓扑与连接](#1-拓扑与连接)
- [2. 认证](#2-认证)
- [3. 一元 RPC](#3-一元-rpc)
- [4. 流式 RPC（WebSocket 多路复用）](#4-流式-rpcwebsocket-多路复用)
- [5. 会话事件模型](#5-会话事件模型)
- [6. 错误分类](#6-错误分类)
- [7. 端到端示例](#7-端到端示例)
- [8. 待确认清单](#8-待确认清单)

---

## 1. 拓扑与连接

```
┌──────────────────────┐
│  Android App         │
└──────────┬───────────┘
           │  HTTP + WebSocket（局域网 / Cloudflare 隧道）
           │  http://192.168.1.100:3081
           │  ws://192.168.1.100:3081/api/remote.mux
┌──────────▼───────────┐
│  dsh-pocket 代理      │  监听 0.0.0.0:3081
│  · PIN 门禁           │
│  · Host/Origin 改写    │
│  · HTTP + WS 透传      │
└──────────┬───────────┘
           │  loopback（Host 改写为 127.0.0.1:19387）
┌──────────▼───────────┐
│  DSH web             │  仅监听 127.0.0.1:19387
│  · 浏览器会话认证      │
│  · /api RPC 通道      │
└──────────────────────┘
```

### 关键约束

| 约束 | 说明 |
|---|---|
| **必须走 pocket** | DSH web 只绑 `127.0.0.1`，App 无法直连 |
| **必须改写 Host** | 由 pocket 自动完成，App 无需关心 |
| **WebSocket 需要 Cookie** | WS 握手不带 Cookie 会被拒 |
| **HTTPS 不必须** | 局域网内为明文 HTTP；隧道模式由 CF 终止 TLS |

---

## 2. 认证

认证是**两层**的，两层都通过 Cookie 承载。

### 2.1 第一层：pocket 门禁

```http
GET http://192.168.1.100:3081/?token=<PIN>
```

| 响应头 | 说明 |
|---|---|
| `Set-Cookie: dsh_pocket_token=<sha256(PIN:sessionKey)>` | `HttpOnly; SameSite=Lax; Max-Age=2592000` |
| `Set-Cookie: dsh-auth-<随机>_0=v1.<payload>.<hmac>` | 第二层 cookie，由代理代取 |

实测响应（`HTTP 200`）：

```http
set-cookie: dsh-auth-<随机后缀>_0=v1.<payload>.<hmac>; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict
set-cookie: dsh_pocket_token=<sha256(PIN:sessionKey)>; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000
```

> **注意**：根路径带 `?token=` 时返回的是一个 **303 过渡页**（被代理改写成 200 + `meta refresh`），
> 响应头 `x-dsh-pocket-handshake: transition`。App 只需保存 Cookie，不必解析 HTML。

### 2.2 第二层：DSH 浏览器会话

`dsh-auth-*` cookie 由代理在首屏自动补发，**App 不需要自己处理**。

其内容为 `v1.<base64url(payload)>.<base64url(hmacSHA256)>`，payload 解码后：

```json
{ "version": 1, "authority": "127.0.0.1:19387", "issuedAt": 1791599041453, "expiresAt": 1794191041453 }
```

签名密钥持久化在 `~/.dsh/.credentials.yaml` 的 `records.client-connection/browser-session`，
**因此它跨 DSH 重启依然有效**。

### 2.3 会话有效期（重要）

| Cookie | 跨 DSH 重启 | 原因 |
|---|---|---|
| `dsh-auth-*` | ✅ 有效 | 签名密钥持久化在 credentials |
| `dsh_pocket_token` | ❌ **失效** | `sessionKey = randomBytes(16)` 是**进程级**随机 |

**结论**：DSH 每次重启，App 都会收到 401，必须重新用 PIN 登录一次。
这是设计行为，不是 bug。App 应在收到 401 时自动跳回登录页，而不是报错。

### 2.4 认证错误区分（排查用）

| 响应体 | 来源 | 含义 |
|---|---|---|
| `{"error":"unauthorized"}`（JSON） | **pocket 层** | PIN 未通过 / cookie 失效 |
| `unauthorized`（纯文本） | **DSH 层** | 浏览器会话无效 |

---

## 3. 一元 RPC

### 3.1 端点格式

```
POST /api/<namespace>/<method>
```

**注意是斜杠 `/`，不是点号 `.`** —— 端点是 `<namespace>/<method>` 形式。

### 3.2 请求封包

```json
{
  "type": "client-request",
  "rpcId": "<调用方自定的关联 ID>",
  "method": "<namespace>/<method>",
  "payload": {
    "args": { "...": "..." }
  }
}
```

- `payload` **必须**是 `{"args": {...}}`，且只能有 `args` 一个字段
- `rpcId` 会在响应中原样返回，用于并发关联

### 3.3 响应封包

成功：

```json
{ "type": "server-response", "rpcId": "<原样返回>",
  "result": { "ok": true, "value": { ... } } }
```

失败（**HTTP 仍是 200**）：

```json
{ "type": "server-response", "rpcId": "<原样返回>",
  "result": { "ok": false,
              "error": { "code": "gateway/input-invalid",
                         "message": "...",
                         "details": { "endpoint": "session/follow", "field": "request" } } } }
```

> ⚠️ **判断成败要看 `result.ok`，不能看 HTTP 状态码。**

### 3.4 参数校验是强类型的

网关会严格校验参数描述符，错误信息**会直接列出缺失字段名**，这是极好的调试线索：

```
[session/create] args._request → [gateway/arguments-invalid]
  args fields do not match the descriptor: missing "request"; unexpected "_request"

[terminal/resize] args.empty → [gateway/arguments-invalid]
  args fields do not match the descriptor:
  missing "agentId", "id", "attachmentId", "cols", "rows"
```

完整 67 个方法的字段清单见 [`rpc-catalog.md`](./rpc-catalog.md)。

---

## 4. 流式 RPC（WebSocket 多路复用）

### 4.1 连接

```
ws://192.168.1.100:3081/api/remote.mux
Cookie: dsh_pocket_token=...; dsh-auth-...=...
Origin: http://192.168.1.100:3081
```

一条 WebSocket 上可**多路复用任意数量**的逻辑流，用 `streamId` 区分。

### 4.2 客户端 → 服务端帧

| 帧 | 结构 | 用途 |
|---|---|---|
| `open` | `{"type":"open","streamId":"<id>","endpoint":"<ns>/<method>","payload":{"args":{...}}}` | 开启一条流 |
| `item` | `{"type":"item","streamId":"<id>","value":<any>}` | 上行数据（部分流需要） |
| `cancel` | `{"type":"cancel","streamId":"<id>"}` | 取消 |
| `end` | `{"type":"end","streamId":"<id>"}` | 正常结束 |

以上四种帧的键集合是**精确校验**的（多一个字段就报错）。

### 4.3 服务端 → 客户端帧

| 帧 | 结构 |
|---|---|
| `item` | `{"type":"item","streamId":"<id>","value":<any>}` |
| `error` | `{"type":"error","streamId":"<id>","error":{"code","message","details"}}` |
| `end` | `{"type":"end","streamId":"<id>"}` |

### 4.4 实测帧样例

**开启全局事件流**（`$events` 的 args 必须是**空对象**）：

```json
→ {"type":"open","streamId":"ev","endpoint":"$events","payload":{"args":{}}}
← {"type":"item","streamId":"ev","value":{"type":"ready","clientId":"74fa9167-9405-4e30-9678-269f968cfa17","host":{"home":"C:\\Users\\64034"}}}
```

**开启会话订阅**：

```json
→ {"type":"open","streamId":"fl","endpoint":"session/follow",
   "payload":{"args":{"request":{
     "address":{"kind":"session","sessionId":"session-3cf17921-..."},
     "maxMessages":30,"assistantStream":true}}}}
← {"type":"item","streamId":"fl","value":{"type":"snapshot", ...}}
```

**流式端点用普通 POST 调用会报错**（这是识别流式方法的标志）：

```
[gateway/signature-invalid] session/follow:
  stream Remote methods must be opened through the stream carrier
```

### 4.5 streamId 命名建议

App 侧建议用 `<用途>-<自增序号>`（如 `ev`、`fl-1`），并在本地维护 `streamId → 订阅者` 映射表，
以便把帧分发给对应的 UI 组件。

---

## 5. 会话事件模型

`session/follow` 首帧是 `snapshot`，之后持续推送增量 `event` 帧。

### 5.1 snapshot 帧结构（实测）

```json
{
  "type": "item",
  "streamId": "fl",
  "value": {
    "type": "snapshot",
    "header": { "version", "id", "createdAt", "cwd", "isSeeded", "delegationDepth", "agentPreset" },
    "cursor": 709,
    "records": [ /* 157 条历史记录，见下 */ ],
    "hasMore": true,
    "projections": { "asOfSeq": 579, "values": { "title": "...", "goal": null, "tokenUsage": {...}, "contextPressure": {...} } },
    "assistantStream": { "revision": 0 }
  }
}
```

`hasMore: true` 表示还有更早的历史，用 `session/page` 向前翻页。

### 5.2 records 记录结构

```json
{
  "type": "event",
  "event": {
    "type": "assistant/message",
    "seq": 553,
    "time": 1791601528238,
    "data": {
      "turn": 7,
      "step": 9,
      "message": {
        "role": "assistant",
        "content": [
          { "type": "reasoning", "text": "..." },
          { "type": "text", "text": "..." },
          { "type": "tool_use", "id": "...", "name": "...", "input": {...} }
        ]
      }
    }
  }
}
```

### 5.3 实测事件类型分布

来自一个真实会话的 157 条记录：

| 事件类型 | 条数 | 说明 |
|---|---|---|
| `assistant/message` | 28 | 助手消息（含 reasoning / text / tool_use 块） |
| `tool/call` | 34 | 工具调用 |
| `tool/result` | 33 | 工具结果 |
| `step/start` | 27 | 单个 agent step 开始 |
| `step/end` | 27 | 单个 agent step 结束 |
| `user/message` | 2 | 用户消息 |
| `goal/change` | 2 | 目标变更 |
| `agent/inbox/spliced` | 2 | 收件箱拼接 |
| `turn/start` | 1 | 回合开始 |
| `turn/end` | 1 | 回合结束 |

> **渲染要点**：`assistant/message` 的 `content[]` 里 `reasoning` 块是思维链，
> 建议默认折叠；`text` 是正文；`tool_use` 要配合 `tool/result` 按 `id` 配对。

---

## 6. 错误分类

实测到的错误码：

| code | 触发条件 | 处理建议 |
|---|---|---|
| `gateway/arguments-invalid` | 参数缺字段或多字段 | 开发期 bug，按 message 修正 |
| `gateway/input-invalid` | 参数类型/取值不合法 | 校验用户输入 |
| `gateway/signature-invalid` | 用 POST 调用了流式方法 | 改走 WebSocket |
| `gateway/bad-request` | 业务参数非法（如 `maxMessages` 非正整数） | 提示用户 |
| `gateway/internal` | 内部错误 | 上报 |
| `directory-picker/unavailable` | 能力不可用（本部署为 `native`） | 降级或隐藏入口 |
| HTTP 401 + `{"error":"unauthorized"}` | pocket 层未认证 | **跳登录页** |
| HTTP 401 + `unauthorized` | DSH 层未认证 | 重新触发 `GET /?token=<PIN>` |
| HTTP 404 + `not found` | 端点不存在 | 开发期 bug |

---

## 7. 端到端示例

### 7.1 最小可用流程（curl 验证过）

```bash
PIN=你的PIN
BASE=http://192.168.1.100:3081
JAR=cookies.txt

# 1) 登录
curl -s -c $JAR "$BASE/?token=$PIN" -o /dev/null

# 2) 列会话
curl -s -b $JAR -X POST "$BASE/api/session/list" \
  -H 'content-type: application/json' \
  -d '{"type":"client-request","rpcId":"1","method":"session/list","payload":{"args":{"_request":{}}}}'

# 3) 新建会话
curl -s -b $JAR -X POST "$BASE/api/session/create" \
  -H 'content-type: application/json' \
  -d '{"type":"client-request","rpcId":"2","method":"session/create","payload":{"args":{"request":{}}}}'
```

实测输出：

```json
// session/list
{"type":"server-response","rpcId":"1","result":{"ok":true,"value":{"items":[
  {"sessionId":"session-3cf17921-36cb-4c63-8e34-d526798f1f89",
   "updatedAt":1791601447924,"agentAvailable":true,"running":true,"blank":false,
   "cwd":"D:\\AI工作目录\\小学学习台",
   "projections":{"kind":"sequenced","asOfSeq":579,"values":{"title":"...","tokenUsage":{...}}}}]}}}

// session/create
{"type":"server-response","rpcId":"2","result":{"ok":true,"value":
  {"sessionId":"session-35235a41-e505-41a7-9ad7-2dc505915ccd","agentPreset":"standard"}}}
```

### 7.2 会话地址（Address）结构

```ts
type SessionAddress =
  | { kind: "session";  sessionId: string }
  | { kind: "subagent"; parentSessionId: string; childSessionId: string }
```

注意：`kind` 字段**不可省略**，网关按它分支校验。

### 7.3 核心方法的 request 结构（源码验证）

以下结构从控制器源码的校验逻辑反推得出（见 `_recon/dsh-api-session-controller.js`），
字段名与类型均为源码原文。

#### `session/prompt` — 发送消息

```ts
{
  sessionId: string,
  requestId: string,            // 幂等键：同 requestId 重复提交会被去重，直接返回 {accepted:true}
  mode: "queue" | "steer",      // ⚠️ 必填（源码 SessionPromptRequest 里不是可选）——漏了会 boundary validation 失败
  content: Array<
    | { type: "text";  text: string }
    | { type: "image"; ... }
  >,
  clientTimeZone?: string,      // 可选，须为 "UTC" 或合法 IANA 名称（如 "Asia/Shanghai"）
}
```

> ⚠️ **实测补充**：文档早期版本把 `mode` 写成可选，实际**必填**。
> 省略 `mode` 时报错形如：
> `typert gateway: session/prompt: wire field "request" failed boundary validation`，
> 它不会提示缺哪个字段，只能靠读源码声明 `<接口名>Request` 定位。
> 成功返回 `{"accepted": true}`。

校验规则（源码）：

- `content` 必须含**非空白文本或附件**，否则报 `gateway/bad-request`：
  `"prompt content must include non-whitespace text or an attachment"`
- `clientTimeZone` 非法时报 `session/invalid-time-zone`
- 同 `requestId` 重复提交**直接返回 `{accepted: true}`**，不会重复执行 —— App 重试时可安全复用

#### `session/page` — 历史翻页

```ts
{
  address: SessionAddress,
  throughSeq: number,        // 必填，安全整数，且 >= -1（-1 有特殊含义）
  beforeSeq?: number,        // 可选，非负安全整数
}
```

配合 `session/follow` 返回的 `snapshot.cursor` 与 `hasMore` 使用：
`hasMore === true` 时，用 `beforeSeq = cursor` 继续向前取。

#### `session/follow` — 会话订阅

```ts
{
  address: SessionAddress,
  maxMessages?: number,          // 正整数
  turnWindow?: {                 // 可选
    minMessages: number,         // 正整数且 <= maxMessages
    minTurns: number             // 正整数
  },
  assistantStream?: boolean      // true 时订阅 token 级增量
}
```

#### `session/list` — 会话列表

唯一需要 `_request` 的方法：

```ts
{ _request: {} }
```

---

## 7.4 `assistantStream` 增量帧实测

开启 `session/follow` 的 `assistantStream: true` 后，会额外收到 `assistant-stream` 类下行帧。
统计来自一次真实会话抓取（24,656 帧，脚本 [`_recon/stream-capture.mjs`](../_recon/stream-capture.mjs)）：

```jsonc
// 外层（走 WS 的 item 信封）
{"type":"item","streamId":"fl","value":{"type":"assistant-stream","frame":{ ... }}}
```

| 顶层 `frame.type` | 次数 | 说明 |
|---|---|---|
| `start` | 35 | 一段生成开始，带 `attemptId` / `turn` / `step` / `startedAfterSeq` |
| `chunk` | 24585 | 见下表 |
| `end` | 36 | 结束，`outcome.kind = "committed"`，并给出落盘事件 `eventType` / `seq` |

`chunk.chunk.type` 的实际分布与处理方式：

| chunk 类型 | 次数 | 字段 | 客户端动作 |
|---|---|---|---|
| `block-start` | 79 | `index`, `blockType`（`text`/`reasoning`/`tool-call`） | 开启新内容块 |
| `reasoning-delta` | 6830 | `index`, `text` | 追加到思维链 |
| `text-delta` | 311 | `index`, `text` | 追加到正文（逐字显示） |
| `tool-call-delta` | 17214 | `index`, `dt`/args 分片，**无 `text`** | 忽略（工具参数无需逐字渲染） |
| `usage` | 36 | `usage.{input,output,...}Tokens` | 忽略 |
| `finish` | 36 | `reason.reason.kind`（如 `tool-calls`） | 忽略 |

**契约要点**

1. 增量帧**不是**最终事实：同一内容随后一定会有 `assistant/message` 落盘事件。客户端应在收到
   `assistant/message` 时用落盘内容覆盖增量拼出的文本（本项目做法：`liveText` 清空，交给 reducer）。
2. 判定「一段结束」用 `frame.type == "end"`；此时 `outcome.eventType == "assistant/message"`。
3. 解析要**宽松**：按 `type.endsWith("-delta")` + 存在 `text` 字段来收，未来新增的 `*-delta` 才能自动兼容。

---

## 7.5 事件记录的字段陷阱（`tool/result`）

`tool/result` 的配对 id **不在 `data` 顶层**，而在嵌套的 message 里：

```jsonc
{"type":"event","event":{"type":"tool/result","seq":363,"data":{
  "turn":1,"step":64,
  "message":{
    "role":"tool",
    "source":{"kind":"tool","callId":"call_xxx"},   // ← 这里
    "toolCallId":"call_xxx",                        // ← 和这里
    "content":[{"type":"text","text":"(no output)"}],
    "isError":false
  }}}}
```

配对顺序（`id` 取值优先级）：`data.message.toolCallId` → `data.message.source.callId`。
`assistant/message` 里的 `tool-call` 块给出的 `id` 与上者相同，可据此把结果挂回工具卡片。
**乱序到达是常态**，应按 `id` 建索引而不是按顺序 zip。

同理，`user/message` 需要用 `data.source.kind == "user"` 过滤：
`time-context` / 系统注入类消息若不过滤，会把 Context 提示当成用户气泡渲染出来。

---

## 7.6 代理的握手限流（实测踩坑）

`GET /?token=<PIN>` 是**握手**，`dsh-pocket` 代理对它有频次保护。短时间内反复交换 PIN 会收到：

```
HTTP 503，且只回一个 Set-Cookie：
Set-Cookie: dsh_pocket_token=...        ← 缺少 dsh-auth-* 那一层
```

此时拿到的 Cookie 是不完整的，用它连 `/api/remote.mux` 会 **401**，而 HTTP 一元 RPC 也会失败。

**客户端对策**

- PIN 只在「没有 Cookie」或「收到 401」时交换一次，成功后就复用 Cookie；
- 不要把登录放在重试循环里（重连只重连 WS，不要重新握手）；
- 遇到 503 时给用户明确提示并退避，而不是立刻重试。

---

## 7.7 会话管理 RPC 一览（已实测）

会话列表的**真正数据源是 `workspace/follow`**，不是 `session/list`：
前者给出工作区分组、顺序、归档与置顶集合，后者只提供会话本身（标题/运行态/cwd）。

`workspace/follow` 的 baseline：

```jsonc
{
  "items": [
    { "workspaceId": "...", "path": "D:\\develop\\dsh_mobile", "title": "dsh_mobile",
      "sessionIds": ["session-..."], "createdAt": "...", "updatedAt": "..." }
  ],
  "archivedSessionIds": ["session-..."],
  "pinnedSessionIds": ["session-..."]      // 最近置顶的排前面
}
```

之后按顺序推送 `upsert` / `remove` / `order` / `archived` / `pinned` 增量。
**增量帧的字段名各版本可能不同**，客户端解析要宽容（本项目做法：先找已知键，再退化为"扫描帧内的字符串数组"）。

| 方法 | `request` 字段 | 返回 |
|---|---|---|
| `session/rename` | `{ sessionId, title }` | `{ title, seq }` |
| `session/fork` | `{ sessionId, atSeq? }` | `{ sessionId }`（新会话） |
| `session/page` | `{ address, throughSeq, beforeSeq?, maxMessages?, turnWindow? }` | `{ records, hasMore }`，见 7.9 |
| `session/modelCatalog` | `{}` | `{ default, routableProviders, groups[] }` |
| `session/selectModel` | `{ sessionId, provider, model, reasoningEffort? }` | `{ selected }` |
| `workspace/pinSession` | `{ sessionId }` | `{ pinnedSessionIds: [...] }` |
| `workspace/unpinSession` | `{ sessionId }` | `{ pinnedSessionIds: [...] }` |
| `workspace/archiveSession` | `{ sessionId, stopActivity? }` | `{ archivedSessionIds: [...] }` |
| `workspace/unarchiveSession` | `{ sessionId }` | `{ archivedSessionIds: [...] }` |
| `workspace/create` | `{ path }` | **`{ workspace: { workspaceId, path, title, ... } }`**（嵌套一层） |
| `workspace/rename` | `{ workspaceId, title }` | — |
| `workspace/delete` | `{ workspaceId }` | `{ deleted: true }` |

模型选择的数据结构（源码）：

```ts
interface ModelSelection { provider: string; model: string; reasoningEffort?: string }
interface SessionSelectModelRequest extends ModelSelection { sessionId: string }
```

`session/modelCatalog` 实测返回（本机）：

```jsonc
{ "default": { "provider": "workbuddy", "model": "cn:deepseek-v4.1-flash" },
  "routableProviders": ["workbuddy", "xiaomi-token-plan-cn", "deepseek-official", "deepseek-account"],
  "groups": [ { "id": "workbuddy", "name": "workbuddy",
                "models": [ { "id": "cn:hy4-preview-x", "name": "cn:hy4-preview-x" }, ... ] }, ... ] }
```

会话当前模型可从 `session/list` 的 `projections.values.modelSelection.next`（或 `lastUsed`）读到，
UI 无需额外请求即可显示"当前模型"。

**归档正在运行的会话会被拒绝**：`workspace/archiveSession` 不带 `stopActivity` 时，
若会话有在跑的工作（`turn` / `subagent` / `job` / `schedule`），返回错误码
`workspace/session-active`，details 里列出这些工作。带 `stopActivity: true` 则先停再归档。

**`session/search` 可能被部署禁用**：

```json
{"ok":false,"error":{"code":"gateway/internal",
 "message":"session search failed: SessionQueryError: session search is disabled:
            this deployment configures the session-query index with openAt \"never\""}}
```

客户端不要把搜索做成硬依赖 —— 本地按标题 / cwd 过滤体验更好，也不受部署配置影响。

---

## 7.8 WebSocket 接入的两个硬坑（实测踩过）

### (1) 同一个 `streamId` 重复 `open` 会关闭整条连接

如果客户端在**握手完成前**就把 open 帧交给 OkHttp（它会排队等待连接建立），
同时又在 `onOpen` 回调里"补发一遍活跃流"，服务端就会收到同一 `streamId` 的两个 `open`，
直接以 **1008** 关闭整条连接：

```
ws closing 1008 invalid Remote stream request
```

现象很迷惑：baseline 会正常收到，随后整条连接静默死掉，之后的订阅全部无响应
（表现为"只有重连之后才能收到快照"）。

**正确做法**：只在一个地方发 —— 未连接时把流登记到 `defs`，统一由 `onOpen` 发送；
已连接时才允许立即发送。

### (2) 不要给 OkHttp 设 `pingInterval`

dsh-pocket 代理**不回 pong**，OkHttp 会因此判定连接失活并主动断开：

```
ws failure: sent ping but didn't receive pong within 20000ms (after 0 successful ping/pongs)
```

结果是每 20 秒一次假重连，快照时有时无。保持 `pingInterval` 默认（禁用）即可；
保活用应用层心跳或直接依赖 TCP。

---

## 7.9 `session/page` 的翻页语义（实测澄清）

文档早前写的 "`hasMore === true` 时用 `beforeSeq = cursor` 继续向前取" **会让服务端返回空页**。
真实语义是 **`throughSeq` / `beforeSeq` 构成一个开区间上/下界**：

```jsonc
// 取 seq ∈ (beforeSeq, throughSeq) 的记录
{ "address": {...}, "throughSeq": 1750, "beforeSeq": 1200, "maxMessages": 30 }
```

实测（同一会话）：

| throughSeq | beforeSeq | maxMessages | 结果 |
|---|---|---|---|
| 1750 | 1700 | 5 | 28 条事件（约 5 条消息），`hasMore=true` |
| 1750 | 1200 | 30 | 146 条事件，`hasMore=true` |
| 1054 | 54 | 30 | 54 条事件（seq 0..53），`hasMore=false` |
| 1750 | **-1** | 5 | **空**（`throughSeq=-1` 不再是"特殊含义"，会返回空） |
| 1750 | **0** | 30 | **空**（下界为 0 会得到空集，别用 0 当"从头取"） |

**客户端翻页的正确做法**

1. 从 `session/follow` 的 snapshot 里记录**最旧一条的 seq**（`oldestSeq`）与 `hasMore`；
2. 滚动到顶部时请求 `throughSeq = oldestSeq`、`beforeSeq = max(1, oldestSeq - 600)`；
3. 把返回记录按 key 去重后**前置**拼接到当前列表，更新 `oldestSeq` 与 `hasMore`；
4. `hasMore=false` 或返回空页即停止。

> `maxMessages` 限的是"消息"条数，实际会展开成多倍事件
> （一条 assistant 回合 ≈ `assistant/message` + `step/*` + 若干 `tool/call`/`tool/result`）。
> 想要 30 条消息就把 `maxMessages` 设 30，不要按事件数估算。

---

## 7.10 ask-user 提问与 `session/control`（部分确认）

### 提问是怎么来的

`$events`（全局流，`args` 必须是 `{}`）会推送 `waterfall` 帧，其中 `user-questions/request`
就是 agent 的提问：

```jsonc
{"type":"item","streamId":"ev","value":{
  "type":"waterfall","event":"user-questions/request","eventId":"...",
  "agentId":"session-8f1f037f-...",              // ← 就是 sessionId
  "request":{"questions":[{
    "id":"clarify","header":"确认需求","question":"...",
    "options":[{"label":"...","description":"..."}]}]}}}
```

手机端可以据此渲染提问卡片（本项目已实现）。

### 应答通道：**不是**普通 prompt

从源码注释可以确认应答的语义：

- 应答是一条 **`user/message`，其 `source` 为
  `{ kind: 'user-question-reply'; callId: ToolCallId; outcome: 'answered' }`**，并以 `steer` 注入 agent；
- **没有任何 Remote 方法用于"放弃提问"** —— 客户端收起面板时什么都不发。

实测结论：**用普通 `session/prompt`（哪怕 `mode:"steer"`）发答案不够**。
答案会以 `agent/inbox/spliced` 进入 agent 收件箱，但 `ask_user_question` 的
`tool/call` 仍然挂起，回合不会继续（实测停在 `tool/call` 之后）：

```
seq=70 assistant/message reasoning(1182),tool-call(ask_user_question)
seq=71 tool/call
seq=72 agent/inbox/spliced          ← steer 消息进来了，但工具调用没被闭合
（此后无新记录）
```

### 疑似正规入口：`session/control`（流式）

`session/control` 不能用一元 RPC 调（会报
`stream Remote methods must be opened through the stream carrier`），必须走 WS 打开。
实测它的 baseline 是**按会话 id 索引的 `projections`**：

```jsonc
// open: {"type":"open","streamId":"ctl","endpoint":"session/control","payload":{"args":{}}}
{"type":"baseline","value":{"projections":{
  "session-ba2786e7-...": { ... },
  "session-<你的会话ID>...": { ... } }}}
```

它很可能同时是**审批与问答应答的通道**（客户端往上发 `item` 帧）。
入站 item 的确切结构尚未逆向 —— 这是本协议面剩下的主要空白。

**后续深挖的结论（源码级）**：`SessionControlFrame` 只有 `baseline` 和 `projection` 两种 —— 控制流是**纯下行**状态流，`SessionControlController.control()` 是只 yield 不接收的 async generator，客户端发上来的 item 根本不会到达它。

应答的真正实现在 host 内部：`answer(agent, callId, answer)` 构造 `createUserMessage({ source: { kind: 'user-question-reply', callId, outcome: 'answered' }, content: [{ type:'text', text: JSON.stringify({ kind:'answer_to_pending_question', tool:'ask_user_question', callId, questions, answers }) }] })` 然后 `agent.steer(message)`。该函数属于 host 内部的 questions owner 类，**没有任何 Remote 端点暴露它** —— 桌面端能应答只因它与 host 同进程。

**最终结论：在现有 RPC 面上，远程客户端无法正确应答 ask-user 提问**；需上游在 `session/prompt` 增加 source 字段或新增专用 Remote 方法。

**15 分钟后复查**：被 steer 注入答案的会话仍停在 `seq=72`（`tool/call` 之后无新记录），
彻底证实 steer 无法闭合 `ask_user_question`。

---

## 7.11 图片消息（已实测打通）

`session/prompt` 的 `content` 支持图片 part（源码 schema 确认）：

```ts
| { type: "image"; mediaType: "image/png" | "image/jpeg" | "image/webp" | "image/gif";
    data: string /* base64 */; name?: string }
```

实测一张 245 字节的真实 PNG：

```jsonc
{"type":"client-request","rpcId":"img2","method":"session/prompt",
 "payload":{"args":{"request":{"requestId":"img-test-2","sessionId":"...",
   "mode":"queue","content":[{"type":"image","mediaType":"image/png",
   "data":"<base64>","name":"green"}]}}}}
// → {"ok":true,"value":{"accepted":true}}
```

数据非法时报 `session/attachment-invalid`（`details.reason: "INVALID_IMAGE"`）——
这个错误码本身也印证了 schema 正确。

**客户端对策**：相册选图后先降采样（最长边 1600、JPEG 85），再 base64，
否则大图 base64 会把请求撑到十几 MB。另外 `session/list` 的
`projections.values.imageLimits` 里有服务端限制（`maxImageBytes` 20MB、
`maxImagesPerMessage` 20 等），可按需校验。

---

## 8. 待确认清单

以下内容本次未完全验证，实现前建议先用 `_recon/` 里的脚本探一遍：

| 项目 | 状态 |
|---|---|
| `session/prompt` 的 `request` 内部结构 | ✅ 已从源码确认并实测发送成功（**`mode` 必填**，见 7.3） |
| `session/page` 的翻页参数 | ✅ 已确认（`throughSeq` / `beforeSeq`） |
| `session/follow` 的参数 | ✅ 已确认并实测订阅成功 |
| `session/cancel` 的 `request` 内部结构 | ✅ 实测确认：`{ sessionId }`（`{ address }` 会被 boundary validation 拒） |
| `assistantStream` 增量帧格式（token 级流式） | ✅ 已实测，见 [7.4](#74-assistantstream-增量帧实测) |
| `tool/result` 的配对 id 位置 | ✅ 已实测（在 `data.message` 里，见 7.5） |
| 代理握手限流行为 | ✅ 已实测 503 + 半套 Cookie，见 7.6 |
| 会话管理 RPC（置顶/归档/重命名/派生/工作区） | ✅ 已实测，见 [7.7](#77-会话管理-rpc-一览已实测) |
| WebSocket 重复 open 与服务端 1008 | ✅ 已实测，见 [7.8](#78-websocket-接入的两个硬坑实测踩过) |
| `session/page` 翻页语义 | ✅ 已实测澄清，见 [7.9](#79-sessionpage-的翻页语义实测澄清) |
| `session/selectModel` / `modelCatalog` | ✅ 已实测，见 7.7 |
| ask-user 提问的接收 | ✅ 已实测（$events 的 waterfall 帧），见 [7.10](#710-ask-user-提问与-sessioncontrol部分确认) |
| 图片消息 | ✅ 已实测打通（content 支持 image part），见 [7.11](#711-图片消息已实测打通) |
| ask-user 应答的正规通道 | ❌ 源码级确认：`answer()` 是 host 内部方法、无 Remote 暴露；`session/control` 为纯下行。远程客户端当前无法应答，见 7.10 |
| 审批（approval）弹窗 | ⚠️ `session/control` baseline 里可见相关 projections，应答方式同上未解 |
| `directoryPicker/list`（可视化目录浏览） | ❌ 需要 browse capability，本部署只提供 native（弹系统框）——客户端改用「已知目录树」方案，见代码 KnownDirs.kt |
| `session/search` | ⚠️ 本部署被禁用（`openAt "never"`），改用客户端本地过滤 |
| `session/control` 的参数（不是 `request`） | ⚠️ 待挖；`mode:"steer"` 可能与之相关 |
| `session/selectModel` 的 `request` 内部结构 | ⚠️ 待挖 |
| `$events` 全局流推送的具体事件负载 | ⚠️ 只验证了 `ready` 帧与 `waterfall` 帧 |
| `session/updateQueue`（消息队列）语义 | ⚠️ 未验证 |
| 隧道模式下 WebSocket 的行为 | ⚠️ 未验证（当前无隧道） |

> **挖参数的方法**：先补齐顶层字段 → 再从 `missing` 报错逐层收敛。
> 参考脚本：[`_recon/discover-args.mjs`](../_recon/discover-args.mjs)。
> 更快的办法是直接读控制器源码里的校验函数（`prompt` / `page` / `follow` 均用此法确认），
> 源码已提取到 [`_recon/`](../_recon/) 说明中。


---

## 7.12 排队与插队（`session/prompt` 的 mode + `session/updateQueue`）

`session/prompt` 的 `mode` 决定消息如何进入会话：

| mode | 行为 | 落点 |
|---|---|---|
| `queue` | 回合运行中则排队，等当前回合结束 | inbox 的 `next-turn` |
| `steer` | 立刻插入当前回合（引导） | inbox 的 `next-step` |

排队内容可以从**会话 projections 的 `inbox`** 读到（实测结构）：

```jsonc
{"inbox":{
  "next-turn":[{"content":[{"type":"text","text":"…"}],
                "source":{"kind":"user","rpcId":"…"},"role":"user",
                "id":"<itemId>"}],
  "next-step":[]}}
```

> 注意 `session/projections` 的返回是 `{asOfSeq, values:{…}}`，inbox 在 `values` 下；
> 而 `session/follow` 的 snapshot 里 `projections` 是 `{asOfSeq, values}` 同构。

### `session/updateQueue` —— 管理排队项

`request = { sessionId, itemId, action }`，其中 `action` 是**对象**（源码 `QueueAction`）：

```ts
type QueueAction =
  | { kind: 'edit'; content: readonly TextBlock[] }   // 编辑消息
  | { kind: 'steer' }                                 // 改为引导（next-turn → next-step）
  | { kind: 'remove' }                                // 取消消息发送
```

⚠️ 传字符串（如 `"remove"`）会被 boundary validation 拒绝，必须传对象。

**实测注意**：排队项会在**下一个 step 边界**就被消费（不必等回合结束），
所以客户端的排队列表要跟随 `agent/inbox/spliced` / `step/start` / `step/end` /
`turn/start` / `turn/end` 等事件刷新，否则会残留已消费的"幽灵胶囊"。
### ⚠️ `session/projections` 的 inbox 与 follow 快照不一致（实测）

多次实测：`session/projections` 的 `values.inbox` 在**队列确实有排队项**时仍返回
`{next-turn:[], next-step:[]}`，而同一时刻 `session/follow` 的 snapshot 里能看到该项。

```
session/projections → next-turn=0 next-step=0
session/follow snapshot → next-turn=[{id:"c49d9e95-…", content:[{text:"QUEUED-MESSAGE-TEST"}]}]
```

**客户端对策**：把 follow 快照的 `inbox` 当作权威来源，
`session/projections` 只作为补充且**仅在返回非空时覆盖本地列表**，
否则会出现"幽灵胶囊被误清空 / 取消时拿到过期 itemId"的问题。
动作（编辑/引导/取消）之后直接做本地乐观更新即可。