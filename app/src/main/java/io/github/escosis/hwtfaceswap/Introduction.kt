package io.github.escosis.hwtfaceswap

import java.util.WeakHashMap

/** 本地说明标记，不含版本、MAC、账户或任何厂商协议接受状态。 */
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

/** 用户提供的 release 说明，原文保留；其中理论兼容性文字不是本应用的实机验证保证。 */
internal object IntroductionContent {
    const val VMALL_URL = "https://www.vmall.com"
    val markdown = """
        ### 项目说明
        - 利用替换法为华为手表安装本地第三方 HWT 表盘，支持采用兼容表盘协议的 GT、FIT 等设备，并为直接传输失灵的新鸿蒙固件提供既有资源替换路径。
        ### 使用建议
        - 该应用无法与运动健康同时连接手表，请先清除其后台再运行此应用。
        - 请先在电脑浏览器中登录 [华为商城](https://www.vmall.com) 并按以下路径找到华为 ID（一串纯数字）：开发人员工具 - 应用程序 - Cookie - https://www.vmall.com - uid，或通过腕连手表助手一键获取。如已知 ID 则跳过此步。注意，乱填会导致手表数据清空！
        - 替换法等效于删除原表盘，请先前往华为运动健康下载免费表盘，或选择无用表盘进行替换操作。被替换的表盘可重新从商店中下回。
        - 该应用可能不支持 WATCH 系列设备或鸿蒙手机，此类用户如使用时遇到问题可直接使用 GadgetBridge 或腕连手表助手安装第三方表盘。
    """.trimIndent()

    val sections: List<IntroductionSection> = parse(markdown)

    /** 仅解析内置的三级标题、列表和商城链接；不读取外部 Markdown、Cookie 或 ID。 */
    internal fun parse(source: String): List<IntroductionSection> {
        val sections = mutableListOf<IntroductionSection>()
        var title: String? = null
        val items = mutableListOf<IntroductionListItem>()
        fun finishSection() {
            title?.let { sections += IntroductionSection(it, items.toList()) }
            items.clear()
        }
        val link = Regex("\\[([^]]+)]\\(([^)]+)\\)")
        source.replace("\\/", "/").lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("### ") -> {
                    finishSection()
                    title = line.removePrefix("### ")
                }
                line.startsWith("- ") && title != null -> {
                    val text = line.removePrefix("- ")
                    val parts = mutableListOf<IntroductionTextPart>()
                    var offset = 0
                    link.findAll(text).forEach { match ->
                        if (match.range.first > offset) parts += IntroductionTextPart(text.substring(offset, match.range.first))
                        parts += IntroductionTextPart(match.groupValues[1], match.groupValues[2].takeIf { it == VMALL_URL })
                        offset = match.range.last + 1
                    }
                    if (offset < text.length) parts += IntroductionTextPart(text.substring(offset))
                    items += IntroductionListItem(parts.toList())
                }
            }
        }
        finishSection()
        return sections.toList()
    }
}
