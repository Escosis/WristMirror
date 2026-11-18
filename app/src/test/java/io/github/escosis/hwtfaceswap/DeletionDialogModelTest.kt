package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation/close guards only; genuine deletion acceptance remains in the unmodified protocol state. */
class DeletionDialogModelTest {
    private fun deletion(stage: String = "WAIT_OPERATION", active: Boolean = true, failed: Boolean = false) =
        ReplacementDialogInfo(52L, 9L, "10000002", "1.0.0", "Captured deletion target", "",
            OperationInfo("deletion", stage, active = active, failed = failed))

    @Test fun defaultStateHasNoOperationModalAndCurrentFallbackVersion() {
        val state = AppUiState()
        assertNull(state.replacementDialog)
        assertNull(state.deletionDialog)
        assertNull(state.operationDialog)
        assertEquals("1.0.1", state.appVersion)
    }

    @Test fun sharedGetterExposesDeletionWithoutInventingAReplacement() {
        val dialog = deletion()
        val state = AppUiState(deletionDialog = dialog)
        assertSame(dialog, state.operationDialog)
        assertNull(state.replacementDialog)
        assertEquals("deletion", state.operationDialog!!.operation.kind)
        assertEquals("", state.operationDialog!!.fileName)
    }

    @Test fun getterRetainsTheAgreedReplacementFirstContract() {
        val replacement = deletion().copy(token = 53L, fileName = "Original.hwt",
            operation = OperationInfo("replacement", "WAIT_OPERATION", active = true))
        val deletion = deletion()
        assertSame(replacement, AppUiState(replacementDialog = replacement).operationDialog)
        // Canonical runtime ownership, rather than this getter, prevents dual modals.
        assertSame(replacement, AppUiState(replacementDialog = replacement, deletionDialog = deletion).operationDialog)
    }

    @Test fun noDeletionStageExposesTheNativeReplacementInterruptGate() {
        for(stage in listOf("WAIT_OPERATION", "WAIT_DEVICE", "WAIT_LIST", "DELETED", "FAILED", "TIMED_OUT")) {
            assertFalse(ReplacementDialogPolicy.canInterrupt(deletion(stage), 52L))
            assertFalse(ReplacementDialogPolicy.canInterrupt(deletion(stage, active = false), 52L))
        }
    }

    @Test fun activeDeletionCannotCloseEvenWithAnOptimisticStageOrARequestedInterrupt() {
        for(stage in listOf("WAIT_OPERATION", "WAIT_DEVICE", "WAIT_LIST", "DELETED", "FAILED")) {
            val active = deletion(stage).copy(interruptRequested = true)
            assertFalse(ReplacementDialogPolicy.canDismiss(active, 52L))
            assertFalse(ReplacementDialogPolicy.canDismiss(active, 51L))
        }
    }

    @Test fun terminalDeletionClosesOnlyItsExactToken() {
        for(stage in listOf("DELETED", "PRECHECK_FAILED", "FAILED", "TIMED_OUT")) {
            val terminal = deletion(stage, active = false, failed = stage != "DELETED")
            assertTrue(ReplacementDialogPolicy.canDismiss(terminal, 52L))
            assertFalse(ReplacementDialogPolicy.canDismiss(terminal, 53L))
            assertFalse(ReplacementDialogPolicy.canInterrupt(terminal, 52L))
        }
    }

    @Test fun progressRevisionsRetainOriginalVersionTitleAndEmptyFileName() {
        val captured = deletion()
        val progress = captured.copy(operation = captured.operation.copy(stage = "WAIT_LIST", progress = null))
        assertEquals(captured.token, progress.token)
        assertEquals(captured.connectionEpoch, progress.connectionEpoch)
        assertEquals(captured.targetId, progress.targetId)
        assertEquals("1.0.0", progress.targetVersion)
        assertEquals(captured.title, progress.title)
        assertEquals("", progress.fileName)
        assertFalse(progress.interruptRequested)
    }
}
