# RPC 方法目录

> 由运行时实测自动生成，共 **67** 个存活方法。
> 生成时间：2026-10-10T03:22:32.780Z
>
> 原始数据：[`_recon/rpc-catalog.json`](../_recon/rpc-catalog.json) · [`_recon/rpc-catalog.txt`](../_recon/rpc-catalog.txt)

## 阅读说明

| 列 | 含义 |
|---|---|
| **方法** | 端点 = `/api/<命名空间>/<方法>` |
| **类型** | 🌊 = 流式（必须走 WebSocket），其余为一元 POST |
| **args** | 顶层参数形态。`empty` = 传 `{}` 即可（已验证） |
| **必需字段** | 描述符强制要求的字段名（网关报错原文） |


---

## `session` — 会话（核心）  <sub>19 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `attachment` | 一元 | `{}` | `request` |
| `cancel` | 一元 | `{}` | `request` |
| `canOpenWorkspacePath` | 一元 | `{}` | — |
| `control` | 🌊 流式 | `{}` | — |
| `create` | 一元 | `{"request":{}}` | `request` |
| `follow` | 🌊 流式 | `{}` | `request` |
| `fork` | 一元 | `{}` | `request` |
| `initializeDefaultModel` | 一元 | `{}` | — |
| `list` | 一元 | `{"_request":{}}` | `_request` |
| `modelCatalog` | 一元 | `{}` | — |
| `openWorkspacePath` | 一元 | `{}` | `request` |
| `page` | 一元 | `{}` | `request` |
| `projections` | 一元 | `{}` | `request` |
| `prompt` | 一元 | `{}` | `request` |
| `rename` | 一元 | `{}` | `request` |
| `search` | 一元 | `{}` | `request` |
| `selectModel` | 一元 | `{}` | `request` |
| `updateQueue` | 一元 | `{}` | `request` |
| `workspacePathApplications` | 一元 | `{}` | `request` |

---

## `workspace` — 工作区  <sub>11 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `archiveSession` | 一元 | `{}` | `request` |
| `create` | 一元 | `{}` | `request` |
| `delete` | 一元 | `{}` | `request` |
| `follow` | 🌊 流式 | `{}` | — |
| `initializeDefault` | 一元 | `{}` | — |
| `insertBefore` | 一元 | `{}` | `request` |
| `insertSessionBefore` | 一元 | `{}` | `request` |
| `pinSession` | 一元 | `{}` | `request` |
| `rename` | 一元 | `{}` | `request` |
| `unarchiveSession` | 一元 | `{}` | `request` |
| `unpinSession` | 一元 | `{}` | `request` |

---

## `job` — 后台任务  <sub>3 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `follow` | 🌊 流式 | `{}` | `request` |
| `kill` | 一元 | `{}` | `request` |
| `list` | 🌊 流式 | `{}` | `request` |

---

## `terminal` — 终端  <sub>10 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `close` | 一元 | `{}` | `agentId`, `id` |
| `create` | 一元 | `{}` | `agentId`, `request` |
| `environment` | 一元 | `{}` | `agentId` |
| `follow` | 🌊 流式 | `{}` | `agentId`, `id`, `attachmentId` |
| `list` | 一元 | `{}` | `sessionId` |
| `rename` | 一元 | `{}` | `agentId`, `id`, `title` |
| `resize` | 一元 | `{}` | `agentId`, `id`, `attachmentId`, `cols`, `rows` |
| `retain` | 🌊 流式 | `{}` | `sessionId`, `id` |
| `shells` | 一元 | `{}` | `agentId` |
| `write` | 一元 | `{}` | `agentId`, `id`, `attachmentId`, `data` |

---

## `account` — DeepSeek 账号  <sub>11 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `ackBonusNotified` | 一元 | `{}` | `accountId`, `orderId`, `client` |
| `cancelSignIn` | 一元 | `{}` | `attemptId` |
| `getBalance` | 一元 | `{}` | `client` |
| `getProfile` | 一元 | `{}` | `client` |
| `getState` | 一元 | `{}` | — |
| `getUnnotifiedBonuses` | 一元 | `{}` | `client` |
| `hasRunningAccountTasks` | 一元 | `{}` | — |
| `signOut` | 一元 | `{}` | `client` |
| `startSignIn` | 一元 | `{}` | `client`, `callbackOrigin`, `loginSource` |
| `watch` | 🌊 流式 | `{}` | — |
| `watchExpiry` | 🌊 流式 | `{}` | — |

---

## `settings` — 设置  <sub>5 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `describe` | 一元 | `{}` | — |
| `mutate` | 一元 | `{}` | `ns`, `ops` |
| `openSettingsDocument` | 一元 | `{}` | — |
| `replace` | 一元 | `{}` | `ns`, `section` |
| `update` | 一元 | `{}` | `ns`, `patch` |

---

## `credentials` — 凭据  <sub>3 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `describe` | 一元 | `{}` | `refs` |
| `set` | 一元 | `{}` | `ref`, `value` |
| `unset` | 一元 | `{}` | `ref` |

---

## `directoryPicker` — 目录选择器  <sub>3 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `createDirectory` | 一元 | `{}` | `path`, `name` |
| `list` | 一元 | `{}` | — |
| `pick` | 一元 | `{}` | — |

---

## `fileReferences` — 文件引用  <sub>1 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `list` | 一元 | `{}` | `agentId`, `query` |

---

## `skills` — 技能  <sub>1 个</sub>

| 方法 | 类型 | args | 必需字段 |
|---|---|---|---|
| `list` | 一元 | `{}` | `request` |

---

## 汇总

| 命名空间 | 方法数 |
|---|---|
| `session` | 19 |
| `workspace` | 11 |
| `job` | 3 |
| `terminal` | 10 |
| `account` | 11 |
| `settings` | 5 |
| `credentials` | 3 |
| `directoryPicker` | 3 |
| `fileReferences` | 1 |
| `skills` | 1 |
| **合计** | **67**（其中流式 9） |

## 已实测可直接调用的方法

以下方法用 `args` 列给出的形态**实测返回 ok:true**：

- `account/getState` ← payload `{"args":{}}`
- `account/hasRunningAccountTasks` ← payload `{"args":{}}`
- `directoryPicker/pick` ← payload `{"args":{}}`
- `session/canOpenWorkspacePath` ← payload `{"args":{}}`
- `session/create` ← payload `{"args":{"request":{}}}`
- `session/initializeDefaultModel` ← payload `{"args":{}}`
- `session/list` ← payload `{"args":{"_request":{}}}`
- `session/modelCatalog` ← payload `{"args":{}}`
- `settings/describe` ← payload `{"args":{}}`
- `settings/openSettingsDocument` ← payload `{"args":{}}`
- `workspace/initializeDefault` ← payload `{"args":{}}`

## 实测样例返回

