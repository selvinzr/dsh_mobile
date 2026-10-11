# dsh_mobile

**DSH 的原生 Android 客户端** · **Native Android client for DSH** — 直连 dsh-pocket 协议，不用 WebView / speaks the dsh-pocket protocol directly, no WebView.

<p align="center">
  <img src="docs/images/onboarding.png" width="42%" alt="连接引导页 / Onboarding" />
  &nbsp;&nbsp;
  <img src="docs/images/chat.png" width="42%" alt="会话主界面 / Chat" />
</p>

> ⚠️ **第三方客户端**：本项目不是 DSH 官方项目。协议行为通过抓包与源码反查得出，记录在 [docs/protocol.md](docs/protocol.md)。上游协议变化可能导致部分功能失效。
>
> **Third-party client.** Not affiliated with DSH. Protocol behavior was reverse-engineered; see [docs/protocol.md](docs/protocol.md).

---

## 中文

### 依赖

本客户端**必须配合 [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket) 使用** —— 它是运行在电脑侧的中转服务，提供手机配对用的二维码与 PIN。

支持两种连接模式，**扫码流程完全一样**：

| 模式 | 场景 | 说明 |
|---|---|---|
| **局域网** | 手机与电脑同一 Wi-Fi | 明文 HTTP，延迟最低 |
| **外网模式** | 手机在外网（蜂窝 / 其他网络） | 由 dsh-pocket 提供隧道，走 HTTPS/WSS |

完整链路：**手机 App ←(局域网 / 外网隧道)→ dsh-pocket ←→ DSH**。没有 dsh-pocket，本 App 无法连接。

### 功能

| 分类 | 能力 |
|---|---|
| **连接** | 扫码配对 / 手动输入地址+PIN；多服务器保存与切换；断线指数退避重连 + 快照重同步；DSH 重启后自动重登 |
| **会话** | 侧边栏按工作区分组（可折叠）、置顶、归档、搜索、重命名、派生新会话、**在工作区里新建会话** |
| **对话** | token 级流式输出、思维链折叠、工具调用卡片（含参数与结果）、Markdown 渲染、历史分页加载、中断当前回合 |
| **排队 / 插队** | 任务运行中发送自动排队（悬浮胶囊显示），支持**编辑 / 立刻发送 / 取消** |
| **附件** | 图片（内联发送 + 气泡内缩略图渲染）、文档（PDF / Office / 文本 / 代码，走 `uploadFileBinary` 上传），选中后**挂载待发**而不是立即发出；文档可用系统 App 打开 |
| **语音** | 系统语音识别转文字；语音态下输入栏整体切换为「波形 / 停止 / 发送」 |
| **界面** | 沉浸式无标题头部（浮动半透明控件）、单行输入胶囊、深浅双主题 + 4 种强调色 |

### 环境要求

- **手机**：Android 8.0+（minSdk 26）
- **电脑**：运行 DSH + 启动 [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket)
- **网络**：手机能访问到 dsh-pocket —— 同一局域网，**或** dsh-pocket 开启外网模式

### 快速开始

**1. 电脑侧**：启动 [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket)，它会显示二维码和 PIN。

**2. 构建安装**

```bash
# 需要 JDK 17 + Android SDK（compileSdk 36）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 使用 **AGP 9 + Gradle 9**，不要添加 `org.jetbrains.kotlin.android` 插件（Kotlin 支持已内置）。

**3. 配对**：打开 App → **扫码连接**（推荐）或手动输入地址与 PIN。二维码只含地址不含 PIN，PIN 是凭据需输入一次，之后 App 会记住。

### 架构

```
app/src/main/java/com/dsh/mobile/
├── core/                 语音输入、麦克风电平、配对码解析、错误类型
├── data/
│   ├── net/              WS 多路复用、RPC 客户端、Cookie 存储、PIN 握手
│   ├── repo/             所有 DSH RPC 的唯一出口
│   └── model/            会话/消息/工作区/排队/附件模型与事件归并（reducer）
└── ui/
    ├── theme/            配色与排版
    ├── components/       Markdown、消息卡片、工具卡片
    └── screens/          聊天、会话抽屉、设置、连接引导
```

- **单条 WebSocket 多路复用**：所有订阅共用 `/api/remote.mux`，按 `streamId` 分发，带 replay 缓冲避免首帧丢失
- **历史与实时同构**：`session/follow` 的快照记录与增量事件走**同一个 reducer**
- **队列的真相只有一个来源**：服务端 inbox（通过重订阅拿快照），不做本地臆测
- **状态收敛在 `AppViewModel`**，UI 层无业务逻辑

### 已知限制

| 限制 | 说明 |
|---|---|
| ask-user 提问无法回答 | RPC 层面没有回答入口（源码级确认），只能看到提问卡片 |
| 历史文档附件无法重新下载 | 服务端不提供附件回取接口，只有本机刚发送的文档能打开 |
| `session/search` 在本部署被禁用 | 已改为本地过滤 |
| 目录浏览器 | 该部署只提供 native 能力，用已知工作区路径构建离线目录树 |

---

## English

### Dependency

This client **requires [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket)** — a relay running on your computer that exposes DSH and provides the pairing QR code and PIN.

Two connection modes, **the QR flow is identical**:

| Mode | When | Notes |
|---|---|---|
| **LAN** | Phone and computer on the same Wi-Fi | Plain HTTP, lowest latency |
| **Public network** | Phone on cellular / another network | dsh-pocket provides a tunnel (HTTPS/WSS) |

Full path: **Phone app ←(LAN / tunnel)→ dsh-pocket ←→ DSH**. Without dsh-pocket, the app cannot connect.

### Features

| Area | Capabilities |
|---|---|
| **Connection** | QR pairing / manual address+PIN; multiple saved servers; exponential-backoff reconnect with snapshot resync; auto re-login after DSH restarts |
| **Sessions** | Sidebar grouped by workspace (collapsible), pin, archive, search, rename, fork, **new session inside a workspace** |
| **Chat** | Token-level streaming, collapsible reasoning, tool-call cards (args + result), Markdown, paged history, interrupt current turn |
| **Queue / steer** | Messages sent while a task runs are queued (floating chip) — **edit / send now / cancel** |
| **Attachments** | Images (inline + thumbnail rendered in the bubble), documents (PDF / Office / text / code via `uploadFileBinary`); attachments are **staged** and sent together, and documents open in a system app |
| **Voice** | System speech-to-text; the composer switches to waveform / stop / send while recording |
| **UI** | Immersive title-less header with floating translucent controls, single-line composer pill, light/dark themes + 4 accents |

### Requirements

- **Phone**: Android 8.0+ (minSdk 26)
- **Computer**: DSH running + [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket) started
- **Network**: the phone must reach dsh-pocket — same LAN, **or** dsh-pocket in public-network mode

### Getting started

**1. On the computer**: start [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket); it shows a QR code and a PIN.

**2. Build & install**

```bash
# Requires JDK 17 + Android SDK (compileSdk 36)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Uses **AGP 9 + Gradle 9**. Do not add the `org.jetbrains.kotlin.android` plugin (Kotlin support is built in).

**3. Pair**: open the app → **Scan QR** (recommended) or enter the address and PIN manually. The QR contains only the address, never the PIN — the PIN is a credential you type once, and the app remembers it.

### Architecture

- **Single multiplexed WebSocket**: every subscription shares `/api/remote.mux`, demultiplexed by `streamId`, with a replay buffer so the first frame is never lost
- **History and live events are isomorphic**: `session/follow` snapshots and increments go through the **same reducer**
- **One source of truth for the queue**: the server-side inbox (fetched via resubscribe), never guessed locally
- **All state lives in `AppViewModel`**; the UI layer holds no business logic

### Known limitations

| Limitation | Why |
|---|---|
| Cannot answer ask-user prompts | No answering entry point at the RPC level (verified in source); the question card is read-only |
| Historical document attachments cannot be re-downloaded | The server offers no attachment retrieval; only documents just sent from this device can be opened |
| `session/search` is disabled in this deployment | Falls back to local filtering |
| Directory browser | This deployment exposes native capability only; an offline tree is built from known workspace paths |

---

## 参与贡献 / Contributing

欢迎 PR。改动前建议先读 [docs/protocol.md](docs/protocol.md) —— 里面记录了协议的真实行为（含若干反直觉的坑，例如同一 `streamId` 重复 `open` 会被服务端以 1008 关闭整条连接）。

PRs welcome. Please skim [docs/protocol.md](docs/protocol.md) first — it documents real protocol behavior, including counter-intuitive traps (e.g. sending `open` twice for the same `streamId` makes the server close the whole connection with 1008).

## 许可证 / License

[MIT](LICENSE)

## 致谢 / Credits

- [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket) — the relay this client depends on
- DSH (DeepSeek Harness)
