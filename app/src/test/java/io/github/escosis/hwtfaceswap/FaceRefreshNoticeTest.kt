package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRefreshNoticeTest {
    private val refreshing = AppUiState(
        connected = true, listReady = false, listRefreshing = true,
        deviceAddress = "A1:B2:C3:D4:E5:F6",
    )

    @Test fun `手动刷新与删除前置刷新显示短提示`() {
        assertTrue(UiPolicy.showFaceRefreshNotice(refreshing))
        assertTrue(UiPolicy.showFaceRefreshNotice(refreshing.copy(operation = OperationInfo(kind = "deletion"))))
    }

    @Test fun `空列表与保留旧列表的刷新都显示提示`() {
        assertTrue(UiPolicy.showFaceRefreshNotice(refreshing))
        assertTrue(UiPolicy.showFaceRefreshNotice(refreshing.copy(
            faces = listOf(FaceItem("2185215633", "2.12.3", 5, 0)),
        )))
    }

    @Test fun `刷新结束后不再提示`() {
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(listRefreshing = false, listReady = true)))
    }

    @Test fun `断连与连接过程中不显示旧查询提示`() {
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(connected = false)))
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(connecting = true)))
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(quarantine = true)))
    }

    @Test fun `传输与删除后置核验不重复弹刷新提示`() {
        for (kind in listOf("replacement", "deletion")) {
            assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(
                operation = OperationInfo(kind = kind, stage = "WAIT_LIST", active = true),
            )))
        }
    }

    @Test fun `确认窗口与文件选择期间不抢提示`() {
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(pickingHwt = true)))
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(needsPin = true)))
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(
            prompt = ActionPrompt(1, "deletion", "删除？", "确认删除", true, "删除"),
        )))
    }

    @Test fun `解除配对目标不显示旧刷新提示且不影响其它设备`() {
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(unpairWorking = true)))
        assertFalse(UiPolicy.showFaceRefreshNotice(refreshing.copy(
            unpairing = true, unpairAddress = "a1:b2:c3:d4:e5:f6",
        )))
        assertTrue(UiPolicy.showFaceRefreshNotice(refreshing.copy(
            unpairing = true, unpairAddress = "11:22:33:44:55:66",
        )))
    }
}
