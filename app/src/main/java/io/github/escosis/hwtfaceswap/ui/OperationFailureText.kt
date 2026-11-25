package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.protocol.DeviceRefusal

/**
 * 只把设备明确给出的“表盘/文件数量受限、空间不足”翻成可执行提示。
 * 其它失败原因保持原有通用文案：不把未解释的码说成容量问题，也不假装安装成功。
 */
object OperationFailureText {
    @JvmStatic
    fun forReason(reason: String): String? {
        val code = DeviceRefusal.codeOf(reason)
        return when {
            DeviceRefusal.limited(code) -> "手表表盘/文件数量已达上限，请先在手表上删除一个不用的表盘（如装了音乐也清理一些）后重试"
            DeviceRefusal.noSpace(code) -> "手表存储空间不足，请清理表盘或音乐后重试"
            else -> null
        }
    }
}