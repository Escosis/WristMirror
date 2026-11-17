package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {
    @Test fun `蓝牙地址完整展示`() {
        val address = "A1:B2:C3:D4:E5:F6"
        assertEquals(address, NearbyWatch(address, "手表", false).addressLabel)
    }
    @Test fun `未提供地址时有明确占位`() {
        assertEquals("地址未提供", NearbyWatch("", "手表", false).addressLabel)
    }
    @Test fun `无名趣味贴纸直接显示类型`() {
        assertEquals("趣味贴纸", face("趣味贴纸").title)
    }
    @Test fun `无名相册表盘直接显示类型`() {
        assertEquals("相册表盘", face("相册表盘").title)
    }
    @Test fun `无名视频表盘直接显示类型`() {
        assertEquals("视频表盘", face("视频表盘").title)
    }
    @Test fun `特殊表盘即使有名称仍统一显示类型`() {
        for(kind in listOf("趣味贴纸", "相册表盘", "视频表盘")) {
            assertEquals(kind, face(kind, "设备名称").title)
        }
    }
    @Test fun `普通表盘保持真实名称`() {
        assertEquals("我的表盘", face("普通表盘", "我的表盘").title)
    }
    @Test fun `无名普通表盘保留唯一占位而不编造名称`() {
        assertEquals("表盘 2185215633", face("普通表盘").title)
        assertEquals("表盘 2185215633", face("普通表盘", "  ").title)
    }
    @Test fun `未连接也可准备HWT但不能提交替换`() {
        val state = AppUiState()
        assertTrue(UiPolicy.canChooseHwt(state))
        assertFalse(UiPolicy.canOperateFaces(state))
    }
    @Test fun `文件选择请求和进行中的操作互斥`() {
        val ready = AppUiState(connected = true, listReady = true)
        assertTrue(UiPolicy.canChooseHwt(ready))
        assertFalse(UiPolicy.canChooseHwt(ready.copy(pickingHwt = true)))
        assertFalse(UiPolicy.canOperateFaces(ready.copy(pickingHwt = true)))
        assertFalse(UiPolicy.canChooseHwt(ready.copy(operation = OperationInfo(active = true))))
        assertFalse(UiPolicy.canChooseHwt(ready.copy(connecting = true)))
        assertFalse(UiPolicy.canChooseHwt(ready.copy(needsPin = true)))
        assertFalse(UiPolicy.canChooseHwt(ready.copy(prompt = ActionPrompt(1L, "replacement", "确认", "确认操作", true, "替换"))))
    }
    private fun face(kind: String, name: String? = null) =
        FaceItem("2185215633", "2.12.3", 5, 0, name, current = true, selectable = kind == "普通表盘", kind = kind)
}
