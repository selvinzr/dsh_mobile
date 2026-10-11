package com.dsh.mobile.core

import kotlinx.serialization.json.contentOrNull

/**
 * 解析 dsh-pocket 的配对二维码。
 *
 * 由于 dsh-pocket 是独立工具、二维码格式未公开，这里对常见形态都做支持：
 *
 *  1. URL：      `http://192.168.1.100:3081/?token=<你的PIN>`
 *  2. JSON：     `{"url":"http://…","token":"…"}` / `{"host":"…","port":3081,"pin":"…"}`
 *  3. 紧凑文本： `192.168.1.100:3081 <你的PIN>` 或 `192.168.1.100:3081/<你的PIN>`
 *  4. 只有地址： `http://192.168.1.100:3081`（PIN 仍需手输）
 */
object PairingCode {

    data class Result(
        val base: String?,
        val pin: String?,
        /** 解析失败的说明（base 为空时给出）。 */
        val error: String? = null,
    ) {
        val usable: Boolean get() = !base.isNullOrBlank()
    }

    fun parse(raw: String): Result {
        val text = raw.trim().trim('"')
        if (text.isEmpty()) return Result(null, null, "二维码内容为空")

        // 1) JSON
        if (text.startsWith("{")) {
            runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(text)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?: return@runCatching null
                fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
                    (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }
                val url = s("url", "base", "address", "server")
                val host = s("host", "ip")
                val port = s("port")
                val pin = s("token", "pin", "password", "code")
                val base = when {
                    !url.isNullOrBlank() -> normalize(url)
                    !host.isNullOrBlank() -> "http://$host" + if (!port.isNullOrBlank()) ":$port" else ":3081"
                    else -> null
                }
                Result(base, pin)
            }.getOrNull()?.let { if (it.usable) return it }
        }

        // 2) URL（可能带 token 查询参数）
        if (text.startsWith("http://") || text.startsWith("https://")) {
            val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
            if (uri != null && !uri.host.isNullOrBlank()) {
                val scheme = uri.scheme ?: "http"
                val port = if (uri.port > 0) ":${uri.port}" else ""
                val base = "$scheme://${uri.host}$port"
                val pin = uri.getQueryParameter("token")
                    ?: uri.getQueryParameter("pin")
                    ?: uri.getQueryParameter("password")
                return Result(base, pin)
            }
        }

        // 3) 紧凑文本：host:port [pin]  或  host:port/pin
        val parts = text.split(Regex("[\\s,;|]+"), limit = 2)
        val addr = parts[0]
        val trailing = parts.getOrNull(1)?.trim()

        // host:port/pin 形式
        val slashIdx = addr.indexOf('/')
        val addrNoPath = if (slashIdx > 0) addr.substring(0, slashIdx) else addr
        val pinFromPath = if (slashIdx > 0) addr.substring(slashIdx + 1).trim().ifBlank { null } else null

        if (Regex("^[\\w.\\-]+(:\\d+)?$").matches(addrNoPath)) {
            val base = "http://" + if (addrNoPath.contains(':')) addrNoPath else "$addrNoPath:3081"
            return Result(base, trailing ?: pinFromPath)
        }

        // 4) 兜底：当成纯地址
        if (text.contains('.') || text.contains(':')) {
            return Result(normalize(text), null)
        }
        return Result(null, null, "无法识别的二维码内容")
    }

    private fun normalize(url: String): String {
        val t = url.trim().trimEnd('/')
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            else -> "http://$t"
        }
    }
}
