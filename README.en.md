# dsh_mobile

[中文](README.md) | **English**

**Native Android client for DSH** — speaks the dsh-pocket protocol directly, no WebView.

Use DSH on your phone like a normal chat app: QR pairing, streaming output, visible tool calls, voice input, attachments, session management.

<p align="center">
  <img src="docs/images/onboarding.png" width="42%" alt="Onboarding" />
  &nbsp;&nbsp;
  <img src="docs/images/chat.png" width="42%" alt="Chat" />
</p>

> ⚠️ **Third-party client.** This is not an official DSH project. Protocol behavior was reverse-engineered by capturing traffic and reading source; it is documented in [docs/protocol.md](docs/protocol.md). Upstream protocol changes may break parts of this app.

---

## Dependency

This client **requires [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket)** — a relay running on your computer that exposes DSH and provides the pairing QR code and PIN.

Two connection modes, **the QR flow is identical**:

| Mode | When | Notes |
|---|---|---|
| **LAN** | Phone and computer on the same Wi-Fi | Plain HTTP, lowest latency |
| **Public network** | Phone on cellular / another network | dsh-pocket provides a tunnel (HTTPS/WSS) |

Full path: **Phone app ←(LAN / tunnel)→ dsh-pocket ←→ DSH**. Without dsh-pocket the app cannot connect at all.

## Features

| Area | Capabilities |
|---|---|
| **Connection** | QR pairing / manual address + PIN; multiple saved servers; exponential-backoff reconnect with snapshot resync; automatic re-login after DSH restarts |
| **Sessions** | Sidebar grouped by workspace (collapsible), pin, archive, search, rename, fork, **create a new session inside a workspace** |
| **Chat** | Token-level streaming, collapsible reasoning, tool-call cards (arguments + result), Markdown rendering, paged history, interrupt the current turn |
| **Queue / steer** | Messages sent while a task is running are queued (shown as a floating chip) and can be **edited / sent immediately / cancelled** |
| **Attachments** | Images (sent inline, rendered as thumbnails in the bubble), documents (PDF / Office / text / code, uploaded via `uploadFileBinary`). Attachments are **staged** and sent together with your text; documents open in a system app |
| **Voice** | System speech-to-text; while recording, the composer switches to waveform / stop / send |
| **UI** | Immersive title-less header with floating translucent controls, single-line composer pill, light & dark themes with 4 accent colors |

## Requirements

- **Phone**: Android 8.0+ (minSdk 26)
- **Computer**: DSH running + [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket) started
- **Network**: the phone must be able to reach dsh-pocket — same LAN, **or** dsh-pocket in public-network mode

## Getting started

**1. On the computer**: start [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket); it shows a QR code and a PIN.

**2. Build and install**

```bash
# Requires JDK 17 + Android SDK (compileSdk 36)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or grab the signed APK from [Releases](https://github.com/selvinzr/dsh_mobile/releases).

> This project uses **AGP 9 + Gradle 9**. Do not add the `org.jetbrains.kotlin.android` plugin — Kotlin support is built in.

**3. Pair**: open the app → **Scan QR** (recommended), or enter the address and PIN manually. The QR contains only the address, never the PIN — the PIN is a credential you type once, and the app remembers it.

## Architecture

```
app/src/main/java/com/dsh/mobile/
├── core/                 speech input, mic level, pairing-code parsing, error types
├── data/
│   ├── net/              WS multiplexer, RPC client, cookie store, PIN handshake
│   ├── repo/             single entry point for every DSH RPC
│   └── model/            session/message/workspace/queue/attachment models + reducer
└── ui/
    ├── theme/            colors and typography
    ├── components/       Markdown, message cards, tool cards
    └── screens/          chat, session drawer, settings, onboarding
```

- **One multiplexed WebSocket**: every subscription shares `/api/remote.mux`, demultiplexed by `streamId`, with a replay buffer so the first frame is never lost
- **History and live events are isomorphic**: `session/follow` snapshots and increments run through the **same reducer**
- **A single source of truth for the queue**: the server-side inbox (fetched by resubscribing), never guessed locally
- **All state lives in `AppViewModel`**; the UI layer holds no business logic

## Release builds

```bash
# Reads signing config from keystore.properties (not committed)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk (already signed)
```

If `keystore.properties` is absent, CI can supply `DSH_STORE_FILE` / `DSH_STORE_PASSWORD` / `DSH_KEY_ALIAS` / `DSH_KEY_PASSWORD` as environment variables.

## Known limitations

These are **upstream capability or protocol limits**, not bugs:

| Limitation | Why |
|---|---|
| Cannot answer ask-user prompts | There is no answering entry point at the RPC level (verified in source); the question card is read-only |
| Historical document attachments cannot be re-downloaded | The server exposes no attachment retrieval; only documents just sent from this device can be opened |
| `session/search` is disabled in this deployment | Falls back to local filtering |
| Directory browser | This deployment exposes native capability only; an offline tree is built from known workspace paths |

## Contributing

PRs are welcome. Please skim [docs/protocol.md](docs/protocol.md) first — it documents real protocol behavior, including counter-intuitive traps (for example, sending `open` twice for the same `streamId` makes the server close the whole connection with 1008).

## License

[MIT](LICENSE)

## Credits

- [`dsh-pocket`](https://github.com/shaobeichen/dsh-pocket) — the relay this client depends on
- DSH (DeepSeek Harness)
