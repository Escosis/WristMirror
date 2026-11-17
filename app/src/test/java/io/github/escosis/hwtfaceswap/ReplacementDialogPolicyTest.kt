package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Primitive-only guards; protocol success remains LISTED && !failed && !active. */
class ReplacementDialogPolicyTest {
    private fun dialog(stage: String = "WAIT_OPERATION", active: Boolean = true, failed: Boolean = false) =
        ReplacementDialogInfo(41L, 7L, "10000001", "1.0.1", "Captured face", "Captured.hwt",
            OperationInfo("replacement", stage, active = active, failed = failed))

    @Test fun absentSessionDoesNothing() {
        assertNull(AppUiState().replacementDialog)
        assertFalse(ReplacementDialogPolicy.canInterrupt(null, 41L))
        assertFalse(ReplacementDialogPolicy.canDismiss(null, 41L))
    }

    @Test fun activeSessionRequiresExactTokenAndCannotClose() {
        for(stage in listOf("WAIT_OPERATION", "TRANSFERRING", "WAIT_REPORT", "WAIT_POST", "WAIT_LIST", "LISTED")) {
            val current = dialog(stage)
            assertTrue(ReplacementDialogPolicy.canInterrupt(current, 41L))
            assertFalse(ReplacementDialogPolicy.canInterrupt(current, 42L))
            assertFalse(ReplacementDialogPolicy.canDismiss(current, 41L))
            assertFalse(ReplacementDialogPolicy.canDismiss(current, 42L))
        }
    }

    @Test fun requestingInterruptDoesNotMakeAnActiveSessionClosable() {
        val current = dialog().copy(interruptRequested = true)
        assertTrue(current.operation.active)
        assertFalse(ReplacementDialogPolicy.canInterrupt(current, 41L))
        assertFalse(ReplacementDialogPolicy.canDismiss(current, 41L))
    }

    @Test fun allTerminalResultsRequireExactTokenButCanClose() {
        for(stage in listOf("LISTED", "NO_TRANSFER", "PRECHECK_FAILED", "FAILED", "TIMED_OUT", "CANCELLED")) {
            val current = dialog(stage, active = false, failed = stage in listOf("PRECHECK_FAILED", "FAILED", "TIMED_OUT"))
            assertTrue(ReplacementDialogPolicy.canDismiss(current, 41L))
            assertFalse(ReplacementDialogPolicy.canDismiss(current, 42L))
            assertFalse(ReplacementDialogPolicy.canInterrupt(current, 41L))
        }
    }

    @Test fun progressRevisionsRetainTaskAndPresentationSnapshots() {
        val original = dialog()
        val progress = original.copy(operation = original.operation.copy(stage = "TRANSFERRING", progress = 53))
        assertEquals(original.token, progress.token)
        assertEquals(original.connectionEpoch, progress.connectionEpoch)
        assertEquals(original.targetId, progress.targetId)
        assertEquals(original.targetVersion, progress.targetVersion)
        assertEquals(original.title, progress.title)
        assertEquals(original.fileName, progress.fileName)
        assertNull(original.operation.progress)
        assertEquals(Integer.valueOf(53), progress.operation.progress)
    }
}
