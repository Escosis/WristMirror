package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPolicyTest {
    @Test fun `连接就绪覆盖所有正在连接标记`() {
        for(native in listOf(false, true)) for(queued in listOf(false, true)) {
            assertFalse(UiPolicy.connecting(true, native, queued))
        }
    }
    @Test fun `连接尚未就绪时保留真实或排队进度`() {
        assertFalse(UiPolicy.connecting(false, false, false))
        assertTrue(UiPolicy.connecting(false, true, false))
        assertTrue(UiPolicy.connecting(false, false, true))
        assertTrue(UiPolicy.connecting(false, true, true))
    }
    @Test fun `已连接仍可编辑华为ID`() {
        assertTrue(UiPolicy.canEditHuaweiId(AppUiState(connected = true, phaseCode = "READY")))
        assertTrue(UiPolicy.canEditHuaweiId(AppUiState()))
    }
    @Test fun `连接与设备操作期间暂缓编辑华为ID`() {
        assertFalse(UiPolicy.canEditHuaweiId(AppUiState(connecting = true)))
        assertFalse(UiPolicy.canEditHuaweiId(AppUiState(operation = OperationInfo(active = true))))
        assertFalse(UiPolicy.canEditHuaweiId(AppUiState(needsPin = true)))
        assertFalse(UiPolicy.canEditHuaweiId(AppUiState(prompt = prompt())))
    }
    @Test fun `新鲜列表在就绪状态允许选择操作`() {
        assertTrue(UiPolicy.canOperateFaces(AppUiState(connected = true, listReady = true)))
    }
    @Test fun `刷新保留条目但暂停提交替换与删除`() {
        val original = AppUiState(connected = true, listReady = true,
            faces = listOf(FaceItem("2185215633", "2.12.3", 5, 0, "本机表盘", current = true, selectable = true)))
        val refreshing = original.copy(listReady = false, listRefreshing = true)
        assertTrue(refreshing.faces == original.faces)
        assertFalse(UiPolicy.canOperateFaces(refreshing))
        assertFalse(UiPolicy.canOperateFaces(original.copy(listRefreshing = true)))
        assertTrue(UiPolicy.canOperateFaces(refreshing.copy(listReady = true, listRefreshing = false)))
    }
    @Test fun `断开过期与隔离状态不能提交表盘操作`() {
        val ready = AppUiState(connected = true, listReady = true)
        assertFalse(UiPolicy.canOperateFaces(ready.copy(connected = false)))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(listReady = false)))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(quarantine = true)))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(connecting = true)))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(operation = OperationInfo(active = true))))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(prompt = prompt())))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(needsPin = true)))
    }
    @Test fun `自动更新检查默认开启而自动连接默认关闭`() {
        assertTrue(AppUiState().autoCheckUpdate)
        assertFalse(AppUiState().autoConnect)
    }
    private fun prompt() = ActionPrompt(1L, "replacement", "确认", "确认操作", true, "继续")
}
