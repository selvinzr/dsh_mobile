package com.dsh.mobile.core

/** 协议错误 → 领域错误。code 形如 gateway/bad-request、session/invalid-time-zone。 */
class DshException(
    val code: String,
    override val message: String,
    val details: String? = null,
) : Exception(message) {

    val isUnauthorized: Boolean
        get() = code == CODE_UNAUTHORIZED || code == CODE_HTTP_401

    companion object {
        const val CODE_UNAUTHORIZED = "unauthorized"
        const val CODE_HTTP_401 = "http/401"
        const val CODE_NETWORK = "network/error"
        const val CODE_PARSE = "client/parse"
    }
}
