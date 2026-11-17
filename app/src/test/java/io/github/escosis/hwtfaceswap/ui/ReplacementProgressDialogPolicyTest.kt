package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.OperationInfo
import io.github.escosis.hwtfaceswap.ReplacementDialogInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production ownership and native-property policy; not rendered Android dialogs or gestures. */
class ReplacementProgressDialogPolicyTest {
    private fun info(stage: String = "TRANSFERRING", active: Boolean = true, failed: Boolean = false) =
        ReplacementDialogInfo(11L, 7L, "777", "1.0.1", "Synthetic face", "synthetic.hwt",
            OperationInfo("replacement", stage, stage, 100, active, failed))

    @Test fun replacementNeverRendersThePageCardButOtherOperationsStillDo() {
        for (stage in listOf("WAIT_OPERATION", "TRANSFERRING", "WAIT_POST", "WAIT_LIST", "LISTED", "NO_TRANSFER", "FAILED", "CANCELLED")) {
            assertFalse(pageOperationCardVisible(info(stage).operation))
            assertFalse(pageOperationCardVisible(info(stage, false, true).operation))
        }
        assertFalse(pageOperationCardVisible(OperationInfo()))
        assertTrue(pageOperationCardVisible(OperationInfo("source", "PREPARING", active = true)))
        assertTrue(pageOperationCardVisible(OperationInfo("deletion", "DELETED")))
        assertTrue(pageOperationCardVisible(OperationInfo("deletion", "FAILED", failed = true)))
    }

    @Test fun deviceWindowsCannotOverlayActiveOrTerminalReplacementDialog() {
        for (stage in listOf("TRANSFERRING", "LISTED", "NO_TRANSFER", "FAILED", "CANCELLED")) {
            val modal = info(stage, active = stage == "TRANSFERRING", failed = stage == "FAILED")
            val state = io.github.escosis.hwtfaceswap.AppUiState(replacementDialog = modal)
            assertFalse(canShowDeviceWindow(state))
            assertFalse(canShowHistoryWindow(state))
            assertFalse(canSelectWatch(state))
            assertFalse(canEditHistoryHuaweiId(state, "AA:BB:CC:DD:EE:FF"))
        }
        assertTrue(canShowDeviceWindow(io.github.escosis.hwtfaceswap.AppUiState()))
    }

    @Test fun activeProgressIncludingOneHundredCannotDismissByBackOrOutside() {
        for (stage in listOf("WAIT_OPERATION", "TRANSFERRING", "WAIT_REPORT", "WAIT_POST", "WAIT_LIST", "LISTED")) {
            val current = info(stage)
            assertFalse(replacementDialogProperties(current).dismissOnBackPress)
            assertFalse(replacementDialogProperties(current).dismissOnClickOutside)
            assertFalse(replacementDialogCanDismiss(true, current, current))
            assertFalse(replacementDialogSucceeded(current))
        }
    }

    @Test fun onlyRealListedTerminalIsSuccessButAllTerminalResultsCanClose() {
        for (stage in listOf("LISTED", "NO_TRANSFER", "PRECHECK_FAILED", "FAILED", "TIMED_OUT", "CANCELLED")) {
            val current = info(stage, false, stage == "FAILED" || stage == "TIMED_OUT" || stage == "PRECHECK_FAILED")
            assertTrue(replacementDialogProperties(current).dismissOnBackPress)
            assertFalse(replacementDialogProperties(current).dismissOnClickOutside)
            assertTrue(replacementDialogCanDismiss(true, current, current))
            if (stage == "LISTED") assertTrue(replacementDialogSucceeded(current))
            else assertFalse(replacementDialogSucceeded(current))
        }
    }

    @Test fun legacyRequestFlagCannotUnlockAnActiveDialog() {
        val rendered = info()
        val current = rendered.copy(interruptRequested = true)
        assertFalse(replacementDialogProperties(current).dismissOnBackPress)
        assertFalse(replacementDialogProperties(current).dismissOnClickOutside)
        assertFalse(replacementDialogCanDismiss(true, rendered, current))
        assertFalse(replacementDialogSucceeded(current))
    }

    @Test fun oldTokenEpochTargetAndRetiredOwnerCannotControlCurrentDialog() {
        val rendered = info()
        for (current in listOf(rendered.copy(token = 12L), rendered.copy(connectionEpoch = 8L),
            rendered.copy(targetId = "778"), rendered.copy(targetVersion = "1.0.2"))) {
            assertFalse(replacementDialogSameOwner(rendered, current))
            assertFalse(replacementDialogCanDismiss(true, rendered, current.copy(operation = current.operation.copy(active = false))))
        }
        assertFalse(replacementDialogCanDismiss(false, rendered, rendered.copy(operation = rendered.operation.copy(active = false))))
        assertFalse(replacementDialogCanDismiss(true, rendered, null))
    }

    @Test fun latestActiveStateRejectsOldSuccessCloseAndLatestSuccessAllowsClose() {
        val active = info()
        val success = info("LISTED", false)
        assertFalse(replacementDialogCanDismiss(true, success, active))
        assertTrue(replacementDialogCanDismiss(true, active, success))
    }

    @Test fun connectionFailureDoesNotTrapItsTerminalResult() {
        val active = info()
        assertFalse(replacementDialogCanDismiss(true, active, active))
        val ended = active.copy(operation = active.operation.copy(stage = "FAILED", active = false, failed = true))
        assertTrue(replacementDialogCanDismiss(true, active, ended))
        assertTrue(replacementDialogProperties(ended).dismissOnBackPress)
    }

    @Test fun lateErrorCannotRetainTheSuccessLabel() {
        val error = info("LISTED", false, true)
        assertFalse(replacementDialogSucceeded(error))
        assertTrue(replacementDialogCanDismiss(true, error, error))
    }
}
