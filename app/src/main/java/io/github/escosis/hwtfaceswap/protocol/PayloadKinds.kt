package io.github.escosis.hwtfaceswap.protocol

/**
 * 载荷类型只描述“本地 HWT 里能取出什么”，不代表手表端会接纳该内容。
 * 经典表盘取内层根 watchface.bin；Watch 全能模式取内层资源容器原字节；
 * 只有内层同时含 bin 与 watchface/ 资源树时，才额外提供移除 bin 的派生容器。
 */
object PayloadKinds {
    const val BIN = "bin"
    const val CONTAINER = "container"
    const val STRIPPED = "container_no_bin"
    const val NONE = "none"

    @JvmStatic
    fun available(source: HwtSource): List<String> {
        val kinds = mutableListOf<String>()
        if (source.payloadBin()) kinds.add(BIN)
        if (source.payloadContainer()) kinds.add(CONTAINER)
        return kinds
    }

    @JvmStatic
    fun kind(mode: HwtSource.Mode): String = when (mode) {
        HwtSource.Mode.BIN -> BIN
        HwtSource.Mode.CONTAINER -> CONTAINER
        HwtSource.Mode.CONTAINER_NO_BIN -> STRIPPED
        HwtSource.Mode.HWT -> NONE
    }

    @JvmStatic
    fun mode(kind: String): HwtSource.Mode? = when (kind) {
        BIN -> HwtSource.Mode.BIN
        CONTAINER -> HwtSource.Mode.CONTAINER
        STRIPPED -> HwtSource.Mode.CONTAINER_NO_BIN
        else -> null
    }

    @JvmStatic
    fun preferred(kinds: List<String>): String? = when {
        CONTAINER in kinds -> CONTAINER
        BIN in kinds -> BIN
        else -> null
    }

    /** 直装只用内层容器原字节（XML 格式）；BIN 只用于覆盖既有表盘。 */
    @JvmStatic
    fun directInstallKinds(kinds: List<String>): List<String> =
        if (CONTAINER in kinds) listOf(CONTAINER) else emptyList()

    /** 两种命名口径的汇总说明，仅用于兼容旧调用；界面按文件格式使用 formatNote(kinds)。 */
    @JvmStatic
    fun formatNote(): String =
        "BIN 格式仅支持 WATCH 系列省电模式以及 GT、FIT 等系列直装（6.0.0.32 之前）和替换，不支持 WATCH 系列全能模式；XML 格式仅支持 WATCH 系列全能模式直装；兼容格式同时支持两种直装并支持前者的替换。"

    /** 三种格式各自的用途；措辞固定，避免界面各处以不同口径解释。 */
    @JvmStatic
    fun formatNote(kinds: List<String>): String = when {
        BIN in kinds && (CONTAINER in kinds || STRIPPED in kinds) ->
            "兼容格式支持 WATCH 系列全能模式直装（不支持替换），也支持 WATCH 系列省电模式以及 GT、FIT 等系列直装（6.0.0.32 之前）和替换。"
        BIN in kinds ->
            "BIN 格式仅支持 WATCH 系列省电模式以及 GT、FIT 等系列直装（6.0.0.32 之前）和替换，不支持 WATCH 系列全能模式。"
        CONTAINER in kinds || STRIPPED in kinds ->
            "XML 格式仅支持 WATCH 系列全能模式直装，不支持替换，也不支持其他系列或模式。"
        else -> "未识别可用的表盘格式。"
    }

    /** 详情与提示共用的三分类：只有 BIN、只有 XML、两者兼有（兼容格式）。 */
    @JvmStatic
    fun displayFormat(kinds: List<String>): String = when {
        BIN in kinds && (CONTAINER in kinds || STRIPPED in kinds) -> "兼容格式"
        BIN in kinds -> "BIN 格式"
        CONTAINER in kinds || STRIPPED in kinds -> "XML 格式"
        else -> "未识别可用的表盘格式"
    }

    @JvmStatic
    fun supportedTypes(kinds: List<String>): String {
        val types = mutableListOf<String>()
        if (BIN in kinds) types.add("BIN 格式")
        if (CONTAINER in kinds || STRIPPED in kinds) types.add("XML 格式")
        return types.joinToString(" / ").ifEmpty { "未识别可用的表盘资源" }
    }

    @JvmStatic
    fun label(kind: String): String = when (kind) {
        BIN -> "BIN 格式"
        CONTAINER -> "XML 格式"
        STRIPPED -> "XML 格式（派生）"
        else -> "无可传输内容"
    }

    @JvmStatic
    fun shortLabel(kind: String): String = when (kind) {
        BIN -> "BIN"
        CONTAINER -> "XML"
        STRIPPED -> "XML 派生"
        else -> "未知"
    }

    @JvmStatic
    fun detail(kind: String): String = when (kind) {
        BIN -> "内层 watchface.bin（BIN 格式，仅用于覆盖既有表盘）"
        CONTAINER -> "内层 com.huawei.watchface 原字节（XML 格式，含 watchface.bin）"
        STRIPPED -> "由内层容器移除根 watchface.bin 后重新封装的全能模式载荷，其余成员逐字节复制"
        else -> "无"
    }

    /** 覆盖既有表盘仍只使用经典 BIN；全能模式载荷没有经过验证的覆盖路径。 */
    @JvmStatic
    fun replacementAllowed(kind: String): Boolean = kind == BIN
}