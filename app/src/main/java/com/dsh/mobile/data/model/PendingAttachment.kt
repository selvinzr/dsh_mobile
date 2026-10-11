package com.dsh.mobile.data.model

/**
 * 待发送的附件（**先挂上、再跟文字一起发**，不再选完即发）。
 *
 * 协议里两类附件的投递方式不同：
 *  - 图片：内容直接内联在 prompt 的 content part 里（`{type:"image",mediaType,data,name}`）
 *  - 文档：必须先 POST `/api/session/uploadFileBinary?sessionId=…` 上传原始字节，
 *          拿到 `receiptId` 后在 prompt 里引用（`{type:"file",receiptId}`）
 */
data class PendingAttachment(
    /** 本地唯一键（用于列表 key 与删除） */
    val id: String,
    val name: String,
    val mediaType: String,
    val bytes: Long,
    /** "image" | "file" */
    val kind: String,
    /** 图片：base64 内联数据 */
    val base64: String? = null,
    /** 文档：上传后拿到的凭证 */
    val receiptId: String? = null,
    /** 文档：本地缓存路径（发送时写入；气泡里"打开"用） */
    val localPath: String? = null,
) {
    val isImage: Boolean get() = kind == "image"
    val kb: Int get() = (bytes / 1024).toInt()
}
