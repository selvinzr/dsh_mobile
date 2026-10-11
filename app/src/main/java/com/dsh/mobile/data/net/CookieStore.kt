package com.dsh.mobile.data.net

import android.content.Context

/**
 * 双层 Cookie 存储。
 * dsh-auth-<key> 是长期身份；dsh_pocket_token 是会话令牌，DSH 每次重启都会失效。
 */
class CookieStore(context: Context) {

    private val prefs = context.getSharedPreferences("dsh_cookies", Context.MODE_PRIVATE)
    // 会被 IO 协程与 OkHttp 的 WS 线程并发读写，必须用并发安全容器
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun save(base: String, setCookieHeaders: List<String>) {
        val pairs = setCookieHeaders.mapNotNull { raw ->
            val nv = raw.substringBefore(';').trim()
            if (nv.contains('=')) nv else null
        }
        if (pairs.isEmpty()) return
        val header = pairs.joinToString("; ")
        memory[base] = header
        prefs.edit().putString(base, header).apply()
    }

    fun header(base: String): String? = memory[base] ?: prefs.getString(base, null)?.also { memory[base] = it }

    fun clear(base: String) {
        memory.remove(base)
        prefs.edit().remove(base).apply()
    }
}
