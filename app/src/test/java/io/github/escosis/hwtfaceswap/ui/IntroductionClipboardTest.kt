package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.ActionPrompt
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.IntroductionContent
import io.github.escosis.hwtfaceswap.IntroductionPolicy
import io.github.escosis.hwtfaceswap.IntroductionReadMarker
import io.github.escosis.hwtfaceswap.OperationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实复制写回 helper 与既有说明策略的纯测试，不执行 Android 剪贴板或浏览器。 */
class IntroductionClipboardTest {
    @Test fun `复制完整纯文本网址而非Markdown或页面内容`() {
        val writes = mutableListOf<Pair<String, String>>()
        assertTrue(tryCopyIntroductionMallLink { label, value -> writes += label to value })
        assertEquals(listOf("华为商城链接" to "https://www.vmall.com"), writes)
        assertEquals(IntroductionContent.VMALL_URL, writes.single().second)
        assertFalse(writes.single().second.contains("[华为商城]"))
    }

    @Test fun `写入失败不能返回复制成功`() {
        var attempts = 0
        assertFalse(tryCopyIntroductionMallLink { _, _ ->
            attempts++
            throw IllegalStateException("clipboard unavailable")
        })
        assertEquals(1, attempts)
    }

    @Test fun `安全异常也保持失败而非假成功`() {
        assertFalse(tryCopyIntroductionMallLink { _, _ -> throw SecurityException("clipboard denied") })
    }

    @Test fun `复制不提交说明已读也不改变窗口状态`() {
        var committed = false
        var commits = 0
        val marker = IntroductionReadMarker(Any(), { committed }, { commits++; committed = true; true })
        val state = AppUiState(introductionPending = true)
        var copies = 0
        assertTrue(tryCopyIntroductionMallLink { _, _ -> copies++ })
        assertEquals(1, copies)
        assertEquals(0, commits)
        assertFalse(marker.isAcknowledged())
        assertTrue(state.introductionPending)
    }

    @Test fun `明确再次点击可以再次复制但每次仅写一次`() {
        var copies = 0
        repeat(2) {
            assertTrue(tryCopyIntroductionMallLink { _, value ->
                assertEquals("https://www.vmall.com", value)
                copies++
            })
        }
        assertEquals(2, copies)
    }

    @Test fun `旧关闭后台或Native抢占的说明回调不得复制`() {
        var writes = 0
        fun click(alive: Boolean, visible: Boolean, state: AppUiState) {
            if (IntroductionPolicy.canInteract(alive, visible, state)) {
                tryCopyIntroductionMallLink { _, _ -> writes++ }
            }
        }
        val unread = AppUiState(introductionPending = true)
        click(false, true, unread)
        click(true, false, unread)
        for (blocked in listOf(
            unread.copy(introductionPending = false),
            unread.copy(needsPin = true),
            unread.copy(prompt = ActionPrompt(1L, "rebind", "确认", "确认", true, "确认")),
            unread.copy(operation = OperationInfo(active = true)),
            unread.copy(connecting = true),
            unread.copy(pickingHwt = true),
        )) click(true, true, blocked)
        assertEquals(0, writes)
        click(true, true, unread)
        assertEquals(1, writes)
    }
}
