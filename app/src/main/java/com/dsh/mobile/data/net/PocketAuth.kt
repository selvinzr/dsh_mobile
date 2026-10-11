package com.dsh.mobile.data.net

import android.util.Log
import com.dsh.mobile.core.DshException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "DshMobile"

/**
 * PIN 换双层 Cookie：GET {base}/?token={pin}
 *
 * 关键点：必须禁用重定向。代理为 Safari 做了 303→200 改写，
 * 客户端跟随后可能重复触发握手，代理的 handshakeLimit 会回 503。
 */
class PocketAuth(
    private val client: OkHttpClient,
    private val store: CookieStore,
) {
    private val noRedirect by lazy { client.newBuilder().followRedirects(false).build() }

    /** @return 拿到的 Cookie 名列表 */
    suspend fun login(base: String, pin: String): List<String> = withContext(Dispatchers.IO) {
        // PIN 走 query，必须 percent 编码（含特殊字符的 PIN 不编码会破坏 URL）
        val url = "$base/?token=" + java.net.URLEncoder.encode(pin.trim(), "UTF-8").replace("+", "%20")
        val req = Request.Builder().url(url).build()
        try {
            noRedirect.newCall(req).execute().use { resp ->
                val setCookies = resp.headers.values("Set-Cookie")
                if (setCookies.isEmpty()) {
                    throw DshException(
                        DshException.CODE_UNAUTHORIZED,
                        "PIN 未被接受（HTTP ${resp.code}，无 Set-Cookie）",
                    )
                }
                // 校验双层 Cookie 完整性：dsh_pocket_token（WS 凭证）+ dsh-auth- 前缀（会话）。
                // 代理限流（503）时会只发一层"半套 Cookie"，存了它会导致之后全部 401。
                val names = setCookies.map { it.substringBefore('=').trim() }
                val hasPocket = names.any { it == "dsh_pocket_token" }
                val hasAuth = names.any { it.startsWith("dsh-auth-") }
                if (!hasPocket || !hasAuth) {
                    throw DshException(
                        DshException.CODE_UNAUTHORIZED,
                        "登录不完整（${names.joinToString("/")}），可能被限流，稍后重试",
                    )
                }
                store.save(base, setCookies)
                Log.d(TAG, "login http=${resp.code} cookies=$names")
                names
            }
        } catch (e: DshException) {
            throw e
        } catch (e: Exception) {
            throw DshException(DshException.CODE_NETWORK, "无法连接 $base：${e.message}")
        }
    }
}
