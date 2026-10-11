package com.dsh.mobile.data.net

import com.dsh.mobile.core.DshException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

internal val DSH_JSON: Json = Json {
    ignoreUnknownKeys = true
    // 必须开：RpcRequest.type 有默认值 "client-request"，
    // 关闭时该字段会被静默省略，服务端直接判 gateway/bad-request。
    encodeDefaults = true
    explicitNulls = false
}

internal fun jsonBody(text: String) = text.toRequestBody("application/json".toMediaType())

/**
 * 一元 RPC：POST {base}/api/{namespace}/{method}
 *
 * 三个易错点：
 *  - 端点是 `命名空间/方法`（斜杠），不是点号
 *  - payload 必须是 {"args": {...}} 且只有 args 一个键
 *  - 业务失败也是 HTTP 200，成败看 result.ok
 */
class RpcClient(
    private val client: OkHttpClient,
    private val store: CookieStore,
) {
    /** 401 时的重登钩子（DSH 重启会让 dsh_pocket_token 失效，属高频正常现象）。 */
    var reauth: (suspend (String) -> Boolean)? = null

    suspend fun call(base: String, endpoint: String, args: JsonObject): JsonElement =
        withContext(Dispatchers.IO) { callOnce(base, endpoint, args, allowReauth = true) }

    /**
     * 原始字节上传（非 RPC 信封）。
     *
     * DSH 的文档附件走 `POST /api/session/uploadFileBinary?sessionId=…`，
     * body 是文件原始字节，返回 `{receiptId, file:{…}}`；随后在 prompt 里用
     * `{type:"file",receiptId}` 引用它。
     */
    suspend fun postBytes(
        base: String,
        path: String,
        query: Map<String, String>,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
    ): JsonElement = withContext(Dispatchers.IO) {
        // percent 编码（URLEncoder 的空格是 '+'，服务端按 %20 解析时文件名会错）
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val q = query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val url = if (q.isEmpty()) "$base$path" else "$base$path?$q"

        fun buildReq() = Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
            .apply { store.header(base)?.let { header("Cookie", it) } }
            .build()

        var resp = try {
            client.newCall(buildReq()).execute()
        } catch (e: Exception) {
            throw DshException(DshException.CODE_NETWORK, "上传失败：${e.message}")
        }
        // 401：与其他 RPC 一致走重登路径（Cookie 失效后上传不该直接失败）
        if (resp.code == 401 && reauth?.invoke(base) == true) {
            resp.close()
            resp = try {
                client.newCall(buildReq()).execute()
            } catch (e: Exception) {
                throw DshException(DshException.CODE_NETWORK, "上传失败：${e.message}")
            }
        }
        resp.use {
            if (it.code == 401) throw DshException(DshException.CODE_UNAUTHORIZED, "登录已失效，无法上传")
            val text = it.body?.string().orEmpty()
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?: throw DshException(DshException.CODE_PARSE, "上传返回非 JSON")
            // 应答形如 {"ok":true,"value":{…}} 或直接 {…}
            val rootObj = root as? JsonObject
            val ok = rootObj?.get("ok")?.jsonPrimitive?.booleanOrNull
            if (ok == false) {
                // 错误信息在 result 里（与 RPC 同构），从根上取而不是 value 分支
                val msg = rootObj?.get("result")?.jsonObject?.get("error")?.jsonObject
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                throw DshException(DshException.CODE_PARSE, msg ?: "上传被拒绝")
            }
            rootObj?.get("value")?.takeIf { v -> v is JsonObject } ?: root
        }
    }

    private suspend fun callOnce(
        base: String,
        endpoint: String,
        args: JsonObject,
        allowReauth: Boolean,
    ): JsonElement {
        val rpcId = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", endpoint)
            putJsonObject("payload") { put("args", args) }
        }
        val req = Request.Builder()
            .url("$base/api/$endpoint")
            .post(jsonBody(envelope.toString()))
            .header("Content-Type", "application/json")
            .apply { store.header(base)?.let { header("Cookie", it) } }
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            throw DshException(DshException.CODE_NETWORK, "请求 $endpoint 失败：${e.message}")
        }

        return resp.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 401) {
                if (allowReauth && reauth?.invoke(base) == true) {
                    return callOnce(base, endpoint, args, allowReauth = false)
                }
                throw DshException(DshException.CODE_HTTP_401, "未授权（Cookie 已失效，需重新登录）")
            }
            val parsed = try {
                DSH_JSON.decodeFromString(RemoteResponse.serializer(), text)
            } catch (e: Exception) {
                throw DshException(DshException.CODE_PARSE, "响应不是合法 RPC 封包：${text.take(200)}")
            }
            val result = parsed.result
            if (!result.ok) {
                val err = result.error
                throw DshException(
                    code = err?.code ?: "unknown",
                    message = err?.message ?: "RPC 失败",
                    details = err?.details?.toString(),
                )
            }
            result.value ?: JsonNull
        }
    }
}

@kotlinx.serialization.Serializable
internal data class RemoteResponse(
    val type: String = "",
    val rpcId: String = "",
    val result: RemoteResult,
)

@kotlinx.serialization.Serializable
internal data class RemoteResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: RemoteError? = null,
)

@kotlinx.serialization.Serializable
internal data class RemoteError(
    val code: String = "unknown",
    val message: String = "",
    val details: JsonObject? = null,
)
