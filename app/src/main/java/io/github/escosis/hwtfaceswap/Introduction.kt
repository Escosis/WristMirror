package io.github.escosis.hwtfaceswap

import java.util.WeakHashMap

/** 本地说明标记；按软件安装版本确认，不含 MAC、账户或厂商协议接受状态。 */
internal class IntroductionReadMarker(
    private val preferenceOwner: Any,
    private val readAcknowledged: () -> Boolean,
    private val commitAcknowledged: () -> Boolean,
) {
    fun isAcknowledged(): Boolean = synchronized(uncommittedOwners) {
        !uncommittedOwners.containsKey(preferenceOwner) && runCatching(readAcknowledged).getOrDefault(false)
    }

    fun acknowledge(): Boolean = synchronized(uncommittedOwners) {
        // SharedPreferences.commit(false/throw) 可能已更新内存，不能把该值当作可靠保存。
        // 同进程新 VM 仍需显示并重试；重试必须真的 commit，不能仅重读内存后跳过写入。
        val committed = runCatching(commitAcknowledged).getOrDefault(false)
        if (committed) uncommittedOwners.remove(preferenceOwner)
        else uncommittedOwners[preferenceOwner] = Unit
        committed
    }

    companion object {
        const val KEY = "introduction_acknowledged"
        const val INSTALLATION_KEY = "introduction_acknowledged_installation"
        fun isCurrentInstallation(acknowledged: String?, current: String): Boolean =
            acknowledged != null && acknowledged == current
        private val uncommittedOwners = WeakHashMap<Any, Unit>()
    }
}

/** 说明未读不消费机会；确认后仍保持原有“首次尝试即消费，包括设置关闭/无设备”的规则。 */
internal class StartupConnectionOpportunity {
    private var taken = false

    fun take(introductionPending: Boolean, eligible: Boolean): Boolean {
        if (introductionPending || taken) return false
        taken = true
        return eligible
    }
}

/** 只在本 Activity 的真实说明退出后登记；暂停可等待 RESUMED，不向其他 Activity/owner 转移。 */
internal class IntroductionStartupCompletion(private val owner: Any) {
    // 只记本地代次/目标，不携带明文 ID；暂停期间换连接/设备/清理后不能借旧说明启动。
    private data class Receipt(val epoch: Long, val revision: Long, val address: String?, val unpairToken: Long) {
        fun isCurrent(state: AppUiState): Boolean = epoch == state.connectionEpoch && revision == state.connectionFormRevision &&
            address == state.selectedDeviceAddress && unpairToken == state.unpairToken
    }
    private var pending: Receipt? = null
    private var consumed = false

    fun recordRetired(callbackOwner: Any, acknowledged: Boolean, current: AppUiState): Boolean {
        if (callbackOwner !== owner || !acknowledged || current.introductionPending || consumed || pending != null) return false
        pending = Receipt(current.connectionEpoch, current.connectionFormRevision, current.selectedDeviceAddress, current.unpairToken)
        return true
    }

    fun take(currentOwner: Any, resumed: Boolean, current: AppUiState): Boolean {
        val receipt = pending ?: return false
        if (currentOwner !== owner || consumed || !resumed) return false
        if (!receipt.isCurrent(current)) { pending = null; consumed = true; return false }
        if (current.introductionPending || !UiPolicy.introductionModalAvailable(current)) return false
        pending = null
        consumed = true
        return true
    }
}

/** 只保护当前说明 owner 的 UI 回调；真实 VM/Activity 另核对最新运行时和连接代次。 */
internal object IntroductionPolicy {
    fun retainWindow(introductionPending: Boolean, acknowledgingHere: Boolean, acknowledgedHere: Boolean, finished: Boolean): Boolean =
        !finished && (introductionPending || acknowledgingHere || acknowledgedHere)

    fun canInteract(ownerAlive: Boolean, ownerVisible: Boolean, current: AppUiState): Boolean =
        ownerAlive && ownerVisible && UiPolicy.showIntroduction(current)

    fun canRetireDismissal(ownerAlive: Boolean, acknowledgedHere: Boolean, finished: Boolean): Boolean =
        ownerAlive && acknowledgedHere && !finished

    fun canFinishDismissal(ownerAlive: Boolean, acknowledgedHere: Boolean, finished: Boolean, current: AppUiState): Boolean =
        canRetireDismissal(ownerAlive, acknowledgedHere, finished) && !current.introductionPending && UiPolicy.introductionModalAvailable(current)

    fun canCompleteAfterDisposal(ownerAlive: Boolean, acknowledgedHere: Boolean, finished: Boolean,
        libraryFinished: Boolean, nativeDisposed: Boolean, current: AppUiState): Boolean =
        libraryFinished && nativeDisposed && canFinishDismissal(ownerAlive, acknowledgedHere, finished, current)
}

internal data class IntroductionTextPart(val text: String, val url: String? = null)
internal data class IntroductionListItem(val parts: List<IntroductionTextPart>) {
    val text: String get() = parts.joinToString("") { it.text }
}
internal data class IntroductionSection(val title: String, val items: List<IntroductionListItem>)

/** 内置使用说明；仅使用三级标题和列表，不依赖完整 Markdown 渲染。 */
internal object IntroductionContent {
    // 保留供现有说明弹窗中的复制 helper 编译使用；当前说明不生成链接片段。
    const val VMALL_URL = "https://www.vmall.com"
    val markdown = """
        ### 项目说明
        - 一个华为手表表盘管理和第三方 HWT 表盘安装工具，面向绝大多数采用兼容表盘协议的 WATCH、GT、FIT 等系列设备，第三方表盘安装通过替换法支持直装法失灵的新鸿蒙版本。
        ### 使用建议
        - 使用前请先退出运动健康并清理其后台，避免同时占用连接通道导致操作失败。
        - 建议使用内置功能获取华为 ID。填写错误可能导致手表询问是否清空数据，遇到此提示请勿确认，应取消并核对配置。
        - 替换会覆盖原表盘，请先从运动健康下载免费表盘（建议从“官方免费表盘”中下载而非“表盘市场”中），或选择不再使用的表盘作为目标。原表盘仍在商店提供时，可重新下载恢复。
        - WATCH 系列全能模式使用 XML 或兼容格式直装，不支持替换；WATCH 省电模式以及 GT、FIT 等系列使用 BIN 资源，直装适用于固件 6.0.0.32 之前，较新固件可尝试替换。
        - WATCH 系列切换模式后，若无法连接，可先断开连接，再使用“重新协议绑定”。
    """.trimIndent()

    val sections: List<IntroductionSection> = parse(markdown)

    /** 仅解析内置三级标题和列表，条目按普通文本显示，不生成可点击链接。 */
    internal fun parse(source: String): List<IntroductionSection> {
        val sections = mutableListOf<IntroductionSection>()
        var title: String? = null
        val items = mutableListOf<IntroductionListItem>()
        fun finishSection() {
            title?.let { sections += IntroductionSection(it, items.toList()) }
            items.clear()
        }
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("### ") -> {
                    finishSection()
                    title = line.removePrefix("### ")
                }
                line.startsWith("- ") && title != null -> {
                    val text = line.removePrefix("- ")
                    items += IntroductionListItem(listOf(IntroductionTextPart(text)))
                }
            }
        }
        finishSection()
        return sections.toList()
    }
}
