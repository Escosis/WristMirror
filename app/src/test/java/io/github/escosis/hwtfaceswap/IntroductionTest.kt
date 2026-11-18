package io.github.escosis.hwtfaceswap

import io.github.escosis.hwtfaceswap.ui.canShowDeviceWindow
import io.github.escosis.hwtfaceswap.ui.canShowHistoryWindow
import io.github.escosis.hwtfaceswap.ui.canSelectWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公开纯 JVM 测试：本地持久提交 seam 与 UI/一次启动策略；不假装运行 Android Widget/蓝牙。 */
class IntroductionTest {
    @Test fun `旧公开fixture默认不新增模态占用`() {
        assertFalse(AppUiState().introductionPending)
        assertFalse(UiPolicy.showIntroduction(AppUiState()))
    }

    @Test fun `新安装与尚无标记的旧安装都应首先显示`() {
        val prefs = CommitModel()
        assertFalse(prefs.marker().isAcknowledged())
        for(version in listOf("1.0.0", "1.0.1", "2.0.0")) {
            val state = AppUiState(appVersion = version, introductionPending = !prefs.marker().isAcknowledged())
            assertTrue(UiPolicy.showIntroduction(state))
        }
        assertEquals(0, prefs.commits)
    }

    @Test fun `明确确认可靠提交后重建与版本变更不重复显示`() {
        val prefs = CommitModel()
        val first = prefs.marker()
        assertTrue(first.acknowledge())
        assertTrue(prefs.memory)
        assertTrue(prefs.durable)
        assertTrue(prefs.marker().isAcknowledged())
        // 纯持久 seam：新的 owner 只读落盘值，模拟新进程的读取，而非真的重启 Android。
        val restarted = IntroductionReadMarker(Any(), { prefs.durable }, { error("不应重新提交") })
        assertTrue(restarted.isAcknowledged())
        for(version in listOf("1.0.1", "1.0.2", "2.0.0")) {
            assertFalse(UiPolicy.showIntroduction(AppUiState(appVersion = version, introductionPending = !restarted.isAcknowledged())))
        }
        assertEquals(1, prefs.commits)
    }

    @Test fun `commitfalse即使改变内存也不能关闭且同进程重建仍需重试`() {
        val prefs = CommitModel(result = CommitResult.FALSE)
        val marker = prefs.marker()
        assertFalse(marker.acknowledge())
        assertTrue(prefs.memory)
        assertFalse(prefs.durable)
        assertFalse(marker.isAcknowledged())
        assertFalse(prefs.marker().isAcknowledged())
        assertFalse(IntroductionReadMarker(Any(), { prefs.durable }, { true }).isAcknowledged())
        prefs.result = CommitResult.TRUE
        assertTrue(prefs.marker().acknowledge())
        assertTrue(prefs.durable)
        assertTrue(marker.isAcknowledged())
        assertEquals(2, prefs.commits)
    }

    @Test fun `commit抛异常即使先改内存也保持未读且可真实重试`() {
        val prefs = CommitModel(result = CommitResult.THROW)
        assertFalse(prefs.marker().acknowledge())
        assertTrue(prefs.memory)
        assertFalse(prefs.durable)
        assertFalse(prefs.marker().isAcknowledged())
        prefs.result = CommitResult.TRUE
        assertTrue(prefs.marker().acknowledge())
        assertTrue(prefs.marker().isAcknowledged())
        assertEquals(2, prefs.commits)
    }

    @Test fun `读取异常不能假定已读`() {
        val marker = IntroductionReadMarker(Any(), { throw IllegalStateException("unreadable") }, { false })
        assertFalse(marker.isAcknowledged())
    }

    @Test fun `说明标记不跟随设备断连清缓存或解除配对清理变化`() {
        val unread = AppUiState(introductionPending = true)
        assertTrue(unread.copy(selectedDeviceAddress = "AA:BB:CC:DD:EE:FF").introductionPending)
        assertTrue(unread.copy(connected = false, connecting = false, source = null).introductionPending)
        assertTrue(unread.copy(unpairing = true, unpairAddress = "AA:BB:CC:DD:EE:FF").introductionPending)
        val read = unread.copy(introductionPending = false)
        assertFalse(read.copy(selectedDeviceAddress = null, connected = false, source = null,
            unpairing = false, unpairAddress = null).introductionPending)
        val prefs = CommitModel()
        assertTrue(prefs.marker().acknowledge())
        assertTrue(prefs.marker().isAcknowledged())
    }

    @Test fun `PIN确认与所有操作窗口优先且旧ack和链接使用同一守卫`() {
        val unread = AppUiState(introductionPending = true)
        assertTrue(IntroductionPolicy.canInteract(ownerAlive = true, ownerVisible = true, unread))
        blockedStates(unread).forEach { state ->
            assertFalse(UiPolicy.showIntroduction(state))
            assertFalse(IntroductionPolicy.canInteract(ownerAlive = true, ownerVisible = true, state))
        }
    }

    @Test fun `未可见已退出或被替换的owner不能确认或打开URL`() {
        val unread = AppUiState(introductionPending = true)
        assertFalse(IntroductionPolicy.canInteract(ownerAlive = false, ownerVisible = true, unread))
        assertFalse(IntroductionPolicy.canInteract(ownerAlive = true, ownerVisible = false, unread))
        assertFalse(IntroductionPolicy.canInteract(ownerAlive = false, ownerVisible = false, unread))
        assertFalse(IntroductionPolicy.canInteract(ownerAlive = true, ownerVisible = true, unread.copy(introductionPending = false)))
    }

    @Test fun `仅同一确认owner正常动画完成可通知启动旧关闭callback不能启动`() {
        val read = AppUiState(introductionPending = false)
        assertTrue(IntroductionPolicy.canFinishDismissal(true, true, false, read))
        assertFalse(IntroductionPolicy.canFinishDismissal(false, true, false, read))
        assertFalse(IntroductionPolicy.canFinishDismissal(true, false, false, read))
        assertFalse(IntroductionPolicy.canFinishDismissal(true, true, true, read))
        assertFalse(IntroductionPolicy.canFinishDismissal(true, true, false, read.copy(introductionPending = true)))
        blockedStates(read).forEach { assertFalse(IntroductionPolicy.canFinishDismissal(true, true, false, it)) }
    }

    @Test fun `真实退出只退休自己的宿主安全抢占不能借退休通知连接`() {
        assertTrue(IntroductionPolicy.canRetireDismissal(true, true, false))
        assertFalse(IntroductionPolicy.canRetireDismissal(false, true, false))
        assertFalse(IntroductionPolicy.canRetireDismissal(true, false, false))
        assertFalse(IntroductionPolicy.canRetireDismissal(true, true, true))
        assertFalse(IntroductionPolicy.canFinishDismissal(true, true, false, AppUiState(needsPin = true)))
        assertFalse(IntroductionPolicy.canFinishDismissal(true, true, false, AppUiState(operation = OperationInfo(active = true))))
    }

    @Test fun `库动画完成还没有Native子Compositiondisposal收据时不能通知启动`() {
        val read = AppUiState()
        assertFalse(IntroductionPolicy.canCompleteAfterDisposal(true, true, false, false, false, read))
        assertFalse(IntroductionPolicy.canCompleteAfterDisposal(true, true, false, true, false, read))
        assertFalse(IntroductionPolicy.canCompleteAfterDisposal(true, true, false, false, true, read))
        assertTrue(IntroductionPolicy.canCompleteAfterDisposal(true, true, false, true, true, read))
        assertFalse(IntroductionPolicy.canCompleteAfterDisposal(false, true, false, true, true, read))
        assertFalse(IntroductionPolicy.canCompleteAfterDisposal(true, false, false, true, true, read))
        assertFalse(IntroductionPolicy.canCompleteAfterDisposal(true, true, true, true, true, read))
        blockedStates(read).forEach { assertFalse(IntroductionPolicy.canCompleteAfterDisposal(true, true, false, true, true, it)) }
    }

    @Test fun `提交栈内父state先清未读时inflight仍保留窗口直到本地成功确认`() {
        assertTrue(IntroductionPolicy.retainWindow(true, false, false, false))
        assertTrue(IntroductionPolicy.retainWindow(true, true, false, false))
        // 对应同步父重组：VM 已成功写入并清 flag，ACK callback 仍未返回。
        assertTrue(IntroductionPolicy.retainWindow(false, true, false, false))
        assertTrue(IntroductionPolicy.retainWindow(false, true, true, false))
        assertTrue(IntroductionPolicy.retainWindow(false, false, true, false))
        assertFalse(IntroductionPolicy.retainWindow(false, false, true, true))
        assertFalse(IntroductionPolicy.retainWindow(false, false, false, false))
        // 提交失败时父 state 仍未读，退出 inflight 不关闭窗口。
        assertTrue(IntroductionPolicy.retainWindow(true, false, false, false))
    }

    @Test fun `尚未真正退出或尚未确认时不能登记启动收据`() {
        val owner = Any()
        val completion = IntroductionStartupCompletion(owner)
        val read = AppUiState()
        assertFalse(completion.take(owner, resumed = true, read))
        assertFalse(completion.recordRetired(owner, acknowledged = false, read))
        assertFalse(completion.recordRetired(owner, acknowledged = true, read.copy(introductionPending = true)))
        assertFalse(completion.take(owner, resumed = true, read))
    }

    @Test fun `我已了解后在PAUSED真实退休返回同ActivityRESUMED仍消费一次`() {
        val owner = Any()
        val completion = IntroductionStartupCompletion(owner)
        val read = AppUiState(connectionEpoch = 5L, connectionFormRevision = 7L, selectedDeviceAddress = "AA:BB:CC:DD:EE:FF")
        assertTrue(completion.recordRetired(owner, acknowledged = true, read))
        repeat(3) { assertFalse(completion.take(owner, resumed = false, read)) }
        assertTrue(completion.take(owner, resumed = true, read))
        repeat(3) { assertFalse(completion.take(owner, resumed = true, read)) }
        assertFalse(completion.recordRetired(owner, acknowledged = true, read))
    }

    @Test fun `稳定已RESUMED状态登记后立即可消费不用等待下一次state变化`() {
        val owner = Any()
        val completion = IntroductionStartupCompletion(owner)
        val read = AppUiState()
        assertTrue(completion.recordRetired(owner, acknowledged = true, read))
        assertFalse(completion.recordRetired(owner, acknowledged = true, read))
        assertTrue(completion.take(owner, resumed = true, read))
        assertFalse(completion.take(owner, resumed = true, read))
    }

    @Test fun `收据等待当前Native模态结束但不在PIN操作背后启动`() {
        val owner = Any()
        val completion = IntroductionStartupCompletion(owner)
        val read = AppUiState()
        assertTrue(completion.recordRetired(owner, acknowledged = true, read))
        blockedStates(read).forEach { assertFalse(completion.take(owner, resumed = true, it)) }
        assertTrue(completion.take(owner, resumed = true, read))
        assertFalse(completion.take(owner, resumed = true, read))
    }

    @Test fun `旧Activity或暂停期间换epoch目标清理后不能复活新启动`() {
        val owner = Any()
        val otherOwner = Any()
        val read = AppUiState(connectionEpoch = 5L, connectionFormRevision = 7L,
            selectedDeviceAddress = "AA:BB:CC:DD:EE:FF", unpairToken = 9L)
        val completion = IntroductionStartupCompletion(owner)
        assertFalse(completion.recordRetired(otherOwner, acknowledged = true, read))
        assertTrue(completion.recordRetired(owner, acknowledged = true, read))
        assertFalse(completion.take(otherOwner, resumed = true, read))
        assertFalse(IntroductionStartupCompletion(otherOwner).take(otherOwner, resumed = true, read))
        assertTrue(completion.take(owner, resumed = true, read))
        for(changed in listOf(read.copy(connectionEpoch = 6L), read.copy(connectionFormRevision = 8L),
            read.copy(selectedDeviceAddress = "11:22:33:44:55:66"), read.copy(unpairToken = 10L))) {
            val stale = IntroductionStartupCompletion(owner)
            assertTrue(stale.recordRetired(owner, acknowledged = true, read))
            assertFalse(stale.take(owner, resumed = true, changed))
            assertFalse(stale.take(owner, resumed = true, read))
            assertFalse(stale.recordRetired(owner, acknowledged = true, read))
        }
    }

    @Test fun `旧安装恢复idle长pending仍可阅读确认但目标A后端入口仍阻断`() {
        val address = "AA:BB:CC:DD:EE:FF"
        val unread = AppUiState(introductionPending = true, unpairing = true, unpairWorking = false,
            unpairAddress = address, unpairToken = 9L, selectedDeviceAddress = address)
        assertTrue(UiPolicy.showIntroduction(unread))
        assertTrue(IntroductionPolicy.canInteract(true, true, unread))
        val prefs = CommitModel()
        assertTrue(prefs.marker().acknowledge())
        val read = unread.copy(introductionPending = !prefs.marker().isAcknowledged())
        assertTrue(UiPolicy.isUnpairTarget(read, address))
        assertFalse(UiPolicy.canEditHuaweiId(read))
        assertFalse(UiPolicy.canRequestUnpair(read))
        assertEquals(address, read.unpairAddress)
        assertEquals(9L, read.unpairToken)
        assertTrue(read.unpairing)
        assertFalse(read.autoConnect)
        assertTrue(canShowDeviceWindow(read)) // 可恢复设置/重新检查，不被首启说明永久锁住。
    }

    @Test fun `首次等待说明不消费自动连接机会确认后按设置只允许一次尝试`() {
        val opportunity = StartupConnectionOpportunity()
        val prefs = CommitModel()
        repeat(3) { assertFalse(opportunity.take(introductionPending = !prefs.marker().isAcknowledged(), eligible = true)) }
        assertTrue(prefs.marker().acknowledge())
        assertTrue(opportunity.take(introductionPending = !prefs.marker().isAcknowledged(), eligible = true))
        repeat(3) { assertFalse(opportunity.take(introductionPending = false, eligible = true)) }
    }

    @Test fun `自动连接关闭或既有Runtime活动仍保持首次消费不重试规则`() {
        // eligible 由真实 VM 根据设置/运行时/目标计算；这里只测试生产 one-shot，不模拟蓝牙结果。
        for(reason in listOf("autoConnect=false", "connected", "active operation", "no available target")) {
            val opportunity = StartupConnectionOpportunity()
            assertFalse(reason, opportunity.take(introductionPending = true, eligible = false))
            assertFalse(reason, opportunity.take(introductionPending = false, eligible = false))
            assertFalse(reason, opportunity.take(introductionPending = false, eligible = true))
        }
    }

    @Test fun `保存失败不提前释放自动连接机会`() {
        val prefs = CommitModel(result = CommitResult.FALSE)
        val opportunity = StartupConnectionOpportunity()
        assertFalse(prefs.marker().acknowledge())
        assertFalse(opportunity.take(introductionPending = !prefs.marker().isAcknowledged(), eligible = true))
        prefs.result = CommitResult.TRUE
        assertTrue(prefs.marker().acknowledge())
        assertTrue(opportunity.take(introductionPending = !prefs.marker().isAcknowledged(), eligible = true))
    }

    @Test fun `普通操作和更新避让但说明不改变Native结果关闭资格`() {
        val result = dialog("replacement")
        val ready = AppUiState(introductionPending = true, connected = true, listReady = true)
        assertFalse(UiPolicy.canRequestUnpair(ready))
        assertFalse(UiPolicy.canEditHuaweiId(ready))
        assertFalse(UiPolicy.canOperateFaces(ready))
        assertFalse(UiPolicy.canChooseHwt(ready))
        assertFalse(UiPolicy.showUpdate(ready))
        assertTrue(UiPolicy.showIntroduction(ready)) // 不停止已经就绪的后台连接。
        assertTrue(ReplacementDialogPolicy.canDismiss(ready.copy(replacementDialog = result).operationDialog, result.token))
        val deletion = dialog("deletion")
        assertTrue(ReplacementDialogPolicy.canDismiss(ready.copy(deletionDialog = deletion).operationDialog, deletion.token))
        assertTrue(UiPolicy.showUpdate(ready.copy(introductionPending = false)))
        assertFalse(UiPolicy.showUpdate(ready.copy(introductionPending = false, needsPin = true)))
    }

    @Test fun `设备普通单窗与历史窗口不得遮挡未读说明`() {
        val ordinary = AppUiState()
        assertTrue(canShowDeviceWindow(ordinary))
        assertTrue(canShowHistoryWindow(ordinary))
        assertTrue(canSelectWatch(ordinary))
        val unread = ordinary.copy(introductionPending = true)
        assertFalse(canShowDeviceWindow(unread))
        assertFalse(canShowHistoryWindow(unread))
        assertFalse(canSelectWatch(unread))
    }

    @Test fun `两个标题四个列表保留原文且移除功能介绍`() {
        assertEquals(USER_MARKDOWN, IntroductionContent.markdown)
        assertEquals(listOf("项目说明", "使用建议"), IntroductionContent.sections.map { it.title })
        assertEquals(listOf(1, 3), IntroductionContent.sections.map { it.items.size })
        assertFalse(IntroductionContent.sections.any { it.title.startsWith("###") })
        val displayed = IntroductionContent.sections.flatMap { it.items }.map { it.text }
        assertEquals(4, displayed.size)
        assertFalse(IntroductionContent.markdown.contains("功能介绍"))
        assertFalse(IntroductionContent.markdown.contains("此法理论上支持所有轻鸿蒙版本的设备"))
        assertFalse(displayed.any { it.startsWith("- ") || it.contains("[华为商城]") })
        assertTrue(displayed[2].contains("注意，乱填会导致手表数据清空！"))
        assertTrue(displayed[2].contains("Cookie - https://www.vmall.com - uid"))
        assertEquals(listOf(IntroductionContent.VMALL_URL), IntroductionContent.sections.flatMap { it.items }.flatMap { it.parts }.mapNotNull { it.url })
    }

    @Test fun `Cookie路径转义斜线只按正常https显示商城链接仍唯一`() {
        val escaped = USER_MARKDOWN.replace("https://", "https:\\/\\/")
        val parsed = IntroductionContent.parse(escaped)
        assertEquals(IntroductionContent.sections, parsed)
        assertFalse(parsed.flatMap { it.items }.any { it.text.contains("\\/") })
    }

    private fun dialog(kind: String) = ReplacementDialogInfo(1L, 1L, "face", "1.0.0", "表盘", "local.hwt", OperationInfo(kind = kind))

    private fun blockedStates(base: AppUiState): List<AppUiState> = listOf(
        base.copy(needsPin = true),
        base.copy(prompt = ActionPrompt(1L, "rebind", "重绑", "确认", true, "确认")),
        base.copy(prompt = ActionPrompt(2L, "replacement", "替换", "确认", true, "确认")),
        base.copy(prompt = ActionPrompt(3L, "deletion", "删除", "确认", true, "确认")),
        base.copy(prompt = ActionPrompt(4L, "unpair", "解除配对", "确认", true, "确认")),
        base.copy(operation = OperationInfo(active = true)),
        base.copy(replacementDialog = dialog("replacement")),
        base.copy(deletionDialog = dialog("deletion")),
        base.copy(connecting = true),
        base.copy(pickingHwt = true),
        base.copy(scanning = true),
        base.copy(listRefreshing = true),
        base.copy(unpairWorking = true),
    )

    private enum class CommitResult { TRUE, FALSE, THROW }
    /** 纯提交 seam，显式建模 Android 文档允许的“内存已改、落盘失败”；不是平台 SharedPreferences fake。 */
    private class CommitModel(var result: CommitResult = CommitResult.TRUE) {
        val owner = Any()
        var memory = false
        var durable = false
        var commits = 0
        fun marker() = IntroductionReadMarker(owner, { memory }, {
            commits++
            memory = true
            when(result) {
                CommitResult.TRUE -> { durable = true; true }
                CommitResult.FALSE -> false
                CommitResult.THROW -> throw IllegalStateException("write failed after memory update")
            }
        })
    }

    companion object {
        private val USER_MARKDOWN = """
            ### 项目说明
            - 利用替换法为华为手表安装本地第三方 HWT 表盘，支持采用兼容表盘协议的 GT、FIT 等设备，并为直接传输失灵的新鸿蒙固件提供既有资源替换路径。
            ### 使用建议
            - 该应用无法与运动健康同时连接手表，请先清除其后台再运行此应用
            - 请先在电脑浏览器中登录 [华为商城](https://www.vmall.com) 并按以下路径找到华为 ID（一串纯数字）：开发人员工具 - 应用程序 - Cookie - https://www.vmall.com - uid，如已知 ID 则跳过此步。注意，乱填会导致手表数据清空！
            - 替换法等效于删除原表盘，请先前往华为运动健康下载一个免费表盘，或选择一个无用表盘进行替换操作。被替换的表盘可重新从商店中下回
        """.trimIndent()
    }
}
