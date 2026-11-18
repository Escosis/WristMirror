package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.OperationInfo
import io.github.escosis.hwtfaceswap.ReplacementDialogInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared production dialog policies, not rendered Android windows or system Back gestures. */
class DeletionProgressDialogPolicyTest {
    private fun deletion(stage: String = "WAIT_DEVICE", active: Boolean = true, failed: Boolean = false) =
        ReplacementDialogInfo(21L, 8L, "777", "2.0.4", "Synthetic face", "",
            OperationInfo("deletion", stage, "删除状态", null, active, failed))

    @Test fun deletionUsesTheSharedModalAndNeverShowsAPageCard() {
        assertNull(AppUiState().operationDialog)
        for (stage in listOf("WAIT_OPERATION", "WAIT_DEVICE", "WAIT_LIST", "DELETED", "PRECHECK_FAILED", "FAILED", "TIMED_OUT")) {
            val info = deletion(stage, active = stage.startsWith("WAIT"), failed = stage == "FAILED")
            assertEquals(info, AppUiState(deletionDialog = info).operationDialog)
            assertFalse(pageOperationCardVisible(info.operation))
            assertEquals("删除表盘", operationDialogTitle(info))
        }
        assertTrue(pageOperationCardVisible(OperationInfo("source", "PREPARING", active = true)))
    }

    @Test fun activeDeletionIsNotDismissibleIncludingAnUnconfirmedDeletedStage() {
        for (stage in listOf("WAIT_OPERATION", "WAIT_DEVICE", "WAIT_LIST", "DELETED")) {
            val info = deletion(stage)
            assertFalse(replacementDialogProperties(info).dismissOnBackPress)
            assertFalse(replacementDialogProperties(info).dismissOnClickOutside)
            assertFalse(replacementDialogCanDismiss(true, info, info))
            assertFalse(replacementDialogSucceeded(info))
        }
    }

    @Test fun onlyAnUnfailedTerminalDeletedResultShowsDeletionSuccess() {
        for (stage in listOf("DELETED", "WAIT_LIST", "LISTED", "NO_TRANSFER", "PRECHECK_FAILED", "FAILED", "TIMED_OUT")) {
            val info = deletion(stage, active = false, failed = stage == "FAILED" || stage == "TIMED_OUT" || stage == "PRECHECK_FAILED")
            assertTrue(replacementDialogProperties(info).dismissOnBackPress)
            assertFalse(replacementDialogProperties(info).dismissOnClickOutside)
            assertTrue(replacementDialogCanDismiss(true, info, info))
            assertEquals(stage == "DELETED", replacementDialogSucceeded(info))
        }
        assertEquals("表盘已删除", operationDialogStatusText(deletion("DELETED", active = false)))
        assertFalse(replacementDialogSucceeded(deletion("DELETED", active = false, failed = true)))
    }

    @Test fun replacementAndDeletionCannotBorrowEachOthersSuccessStage() {
        val deleted = deletion("DELETED", active = false)
        val replaced = deleted.copy(operation = OperationInfo("replacement", "LISTED", active = false))
        assertEquals("替换表盘", operationDialogTitle(replaced))
        assertEquals("表盘已替换", operationDialogStatusText(replaced))
        assertTrue(replacementDialogSucceeded(replaced))
        assertFalse(replacementDialogSucceeded(replaced.copy(operation = replaced.operation.copy(stage = "DELETED"))))
        assertFalse(replacementDialogSucceeded(deleted.copy(operation = deleted.operation.copy(stage = "LISTED"))))
        assertFalse(replacementDialogSucceeded(deleted.copy(operation = deleted.operation.copy(kind = "source"))))
    }

    @Test fun sameTokenAndTargetStillCannotCloseAnotherOperationKind() {
        val old = deletion("DELETED", active = false)
        val other = old.copy(operation = OperationInfo("replacement", "LISTED", active = false))
        assertFalse(replacementDialogSameOwner(old, other))
        assertFalse(replacementDialogCanDismiss(true, old, other))
        assertFalse(replacementDialogCanDismiss(true, other, old))
    }

    @Test fun staleTokenEpochTargetAndDisposedOwnerCannotCloseDeletion() {
        val old = deletion("DELETED", active = false)
        for (current in listOf(old.copy(token = 22L), old.copy(connectionEpoch = 9L),
            old.copy(targetId = "778"), old.copy(targetVersion = "2.0.5"))) {
            assertFalse(replacementDialogCanDismiss(true, old, current))
        }
        assertFalse(replacementDialogCanDismiss(false, old, old))
        assertFalse(replacementDialogCanDismiss(true, old, null))
    }

    @Test fun allDeviceWindowsStayBehindActiveAndTerminalDeletionModals() {
        for (stage in listOf("WAIT_OPERATION", "WAIT_DEVICE", "WAIT_LIST", "DELETED", "FAILED", "TIMED_OUT")) {
            val info = deletion(stage, active = stage.startsWith("WAIT"), failed = stage == "FAILED")
            val state = AppUiState(deletionDialog = info)
            assertFalse(canShowDeviceWindow(state))
            assertFalse(canShowHistoryWindow(state))
            assertFalse(canSelectWatch(state))
            assertFalse(canEditHistoryHuaweiId(state, "AA:BB:CC:DD:EE:FF"))
            val ready = state.copy(connected = true, listReady = true, selectedDeviceAddress = "AA:BB:CC:DD:EE:FF")
            assertFalse(io.github.escosis.hwtfaceswap.UiPolicy.canOperateFaces(ready))
            assertFalse(io.github.escosis.hwtfaceswap.UiPolicy.canChooseHwt(ready))
            assertFalse(io.github.escosis.hwtfaceswap.UiPolicy.canEditHuaweiId(ready))
            assertFalse(io.github.escosis.hwtfaceswap.UiPolicy.canRequestUnpair(ready))
        }
        assertTrue(canShowDeviceWindow(AppUiState()))
    }

    @Test fun disconnectDoesNotTrapTheDeletionFailureResult() {
        val rendered = deletion()
        val failed = rendered.copy(operation = rendered.operation.copy(stage = "FAILED", active = false, failed = true))
        val state = AppUiState(connectionEpoch = 9L, connected = false, deletionDialog = failed)
        assertTrue(replacementDialogCanDismiss(true, rendered, state.operationDialog))
        assertTrue(replacementDialogProperties(failed).dismissOnBackPress)
        assertFalse(replacementDialogSucceeded(failed))
    }

    @Test fun oldSuccessCannotDismissAnActiveResultAndLateErrorsRemoveSuccess() {
        val success = deletion("DELETED", active = false)
        assertFalse(replacementDialogCanDismiss(true, success, deletion()))
        assertTrue(replacementDialogCanDismiss(true, deletion(), success))
        val lateError = success.copy(operation = success.operation.copy(stage = "FAILED", detail = "删除结果未确认", failed = true))
        assertFalse(replacementDialogSucceeded(lateError))
        assertEquals("删除结果未确认", operationDialogStatusText(lateError))
        assertTrue(replacementDialogCanDismiss(true, success, lateError))
    }
}
