package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.ActionPrompt
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.NearbyWatch
import io.github.escosis.hwtfaceswap.OperationInfo
import io.github.escosis.hwtfaceswap.ReplacementDialogInfo
import io.github.escosis.hwtfaceswap.SavedWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18 个有限纯情景；合成状态与代次，不是 Android Widget、系统 Toast 或真实设备验证。 */
class DeviceControlsPolicyTest {
    private val a = NearbyWatch("02:18:00:00:00:01", "合成手表 A", bonded = false)
    private val b = NearbyWatch("02:18:00:00:00:02", "合成手表 B", bonded = false)
    private val controls = DeviceControl.entries
    private fun selected() = AppUiState(selectedDeviceAddress = a.address, devices = listOf(a, b),
        connectionEpoch = 7L, connectionFormRevision = 12L, unpairToken = 3L)
    private fun configured() = selected().copy(accountConfigured = true, huaweiIdInput = "synthetic-configured-id")
    private fun act(rendered: AppUiState, current: AppUiState = rendered, control: DeviceControl = DeviceControl.CONNECT,
        owner: Boolean = true, renderedLocal: Long = 4L, currentLocal: Long = 4L, window: Boolean = false) =
        DeviceControlsPolicy.canAct(rendered, current, control, owner, renderedLocal, currentLocal, window)
    private fun hint(rendered: AppUiState, current: AppUiState = rendered, control: DeviceControl = DeviceControl.CONNECT,
        owner: Boolean = true, renderedLocal: Long = 4L, currentLocal: Long = 4L, window: Boolean = false) =
        DeviceControlsPolicy.hintIfCurrent(rendered, current, control, owner, renderedLocal, currentLocal, window)
    private fun modal(kind: String, active: Boolean) = ReplacementDialogInfo(21L, 7L, "synthetic-face", "1",
        "合成结果", "synthetic.hwt", OperationInfo(kind = kind, active = active))

    @Test fun emptyHasThreeDisabledSelectionHintsAndNoRealAction() {
        val empty = AppUiState()
        for (control in controls) {
            assertFalse(DeviceControlsPolicy.enabled(empty, control))
            assertFalse(act(empty, control = control))
            assertEquals(DeviceControlHint.SELECT_DEVICE, hint(empty, control = control))
        }
    }

    @Test fun historyWithoutSelectionDoesNotBecomeACurrentDevice() {
        val state = AppUiState(savedDevices = listOf(SavedWatch(a.address, a.name)))
        for (control in controls) {
            assertFalse(DeviceControlsPolicy.enabled(state, control))
            assertEquals(DeviceControlHint.SELECT_DEVICE, hint(state, control = control))
        }
    }

    @Test fun nearbyWithoutSelectionDoesNotBecomeACurrentDevice() {
        val state = AppUiState(devices = listOf(a, b))
        for (control in controls) {
            assertFalse(act(state, control = control))
            assertEquals(DeviceControlHint.SELECT_DEVICE, hint(state, control = control))
        }
    }

    @Test fun selectedNoIdDisablesConnectButAllowsSeparateConfigurationAndUnpair() {
        val state = selected()
        assertFalse(DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT))
        assertFalse(act(state))
        assertEquals(DeviceControlHint.CONFIGURE_ID, hint(state))
        for (control in listOf(DeviceControl.CONFIGURE_ID, DeviceControl.UNPAIR)) {
            assertTrue(DeviceControlsPolicy.enabled(state, control))
            assertTrue(act(state, control = control))
            assertNull(hint(state, control = control))
        }
    }

    @Test fun nonemptyUnconfiguredDraftIsNotReliableTopConnectionConfiguration() {
        val state = selected().copy(huaweiIdInput = "synthetic-unsaved-draft", huaweiIdDirty = true)
        assertFalse(DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT))
        assertFalse(act(state))
        assertEquals(DeviceControlHint.CONFIGURE_ID, hint(state))
        assertTrue(act(state, control = DeviceControl.CONFIGURE_ID))
    }

    @Test fun selectedConfiguredIdEnablesOriginalTopActionsWithoutHints() {
        val state = configured()
        for (control in controls) {
            assertTrue(DeviceControlsPolicy.enabled(state, control))
            assertTrue(act(state, control = control))
            assertNull(hint(state, control = control))
        }
    }

    @Test fun configuredNonemptyDirtyDraftKeepsExistingConnectionEligibility() {
        val state = configured().copy(huaweiIdInput = "synthetic-edited-id", huaweiIdDirty = true)
        assertTrue(DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT))
        assertTrue(act(state))
        assertNull(hint(state))
    }

    @Test fun configuredFlagCannotMakeEmptyCurrentInputUsable() {
        val state = configured().copy(huaweiIdInput = "  ", huaweiIdDirty = true)
        assertFalse(act(state))
        assertEquals(DeviceControlHint.CONFIGURE_ID, hint(state))
        assertTrue(act(state, control = DeviceControl.CONFIGURE_ID))
    }

    @Test fun unknownSelectedMetadataDoesNotInventConnectableNearbyWatch() {
        val state = configured().copy(devices = emptyList())
        assertFalse(DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT))
        assertFalse(act(state))
        assertNull(hint(state))
        assertTrue(act(state, control = DeviceControl.CONFIGURE_ID))
    }

    @Test fun connectedNeverUsesDisconnectedConnectionGuidance() {
        val state = configured().copy(connected = true, deviceAddress = a.address, watchName = a.name)
        assertFalse(DeviceControlsPolicy.enabled(state, DeviceControl.CONNECT))
        assertTrue(act(state, control = DeviceControl.CONFIGURE_ID))
        assertTrue(act(state, control = DeviceControl.UNPAIR))
        for (control in controls) assertNull(hint(state, control = control))
    }

    @Test fun connectingNeverUsesDisconnectedConnectionGuidanceOrTopRealActions() {
        val state = configured().copy(connecting = true, deviceAddress = a.address)
        for (control in controls) {
            assertFalse(DeviceControlsPolicy.enabled(state, control))
            assertFalse(act(state, control = control))
            assertNull(hint(state, control = control))
        }
    }

    @Test fun nativePinBlocksFreshAndOldGuidanceAndRealActions() {
        val noId = selected()
        val pin = noId.copy(needsPin = true)
        for (control in controls) {
            assertFalse(act(pin, control = control))
            assertNull(hint(pin, control = control))
            assertNull(hint(noId, pin, control))
        }
    }

    @Test fun nativePromptBlocksFreshAndOldGuidanceAndRealActions() {
        val noId = selected()
        val prompt = noId.copy(prompt = ActionPrompt(31L, "synthetic", "合成确认", "合成提示", false, "确认"))
        for (control in controls) {
            assertFalse(act(prompt, control = control))
            assertNull(hint(prompt, control = control))
            assertNull(hint(noId, prompt, control))
        }
    }

    @Test fun activeProgressAndBothTerminalNativeResultsBlockGuidance() {
        val noId = selected()
        val variants = listOf(
            noId.copy(operation = OperationInfo(kind = "source", active = true)),
            noId.copy(replacementDialog = modal("replacement", true)),
            noId.copy(replacementDialog = modal("replacement", false)),
            noId.copy(deletionDialog = modal("deletion", true)),
            noId.copy(deletionDialog = modal("deletion", false)),
        )
        for (state in variants) for (control in controls) {
            assertFalse(act(state, control = control))
            assertNull(hint(state, control = control))
            assertNull(hint(noId, state, control))
        }
    }

    @Test fun introductionPickerAndUnpairWorkBlockGuidanceWithoutExpandingPolicy() {
        val noId = selected()
        val variants = listOf(noId.copy(introductionPending = true), noId.copy(pickingHwt = true),
            noId.copy(unpairWorking = true), noId.copy(unpairing = true, unpairAddress = a.address),
            noId.copy(scanning = true), noId.copy(quarantine = true))
        for (state in variants) for (control in controls) {
            assertNull(hint(state, control = control))
            assertNull(hint(noId, state, control))
        }
        for (state in variants.take(3)) for (control in controls) assertFalse(act(state, control = control))
    }

    @Test fun oldNoDeviceFrameCannotGuideOrExecuteAfterSelectionOfAOrB() {
        val empty = AppUiState()
        for (device in listOf(a, b)) {
            val next = empty.copy(selectedDeviceAddress = device.address, devices = listOf(a, b), accountConfigured = true,
                huaweiIdInput = "synthetic-configured-id")
            for (control in controls) {
                assertFalse(act(empty, next, control))
                assertNull(hint(empty, next, control))
            }
        }
    }

    @Test fun oldMissingIdOrOtherTargetFrameCannotConnectOrEmitStaleHintAfterSaving() {
        val noId = selected()
        val variants = listOf(configured(), configured().copy(selectedDeviceAddress = b.address),
            noId.copy(accountConfigured = true), noId.copy(huaweiIdInput = "synthetic-draft"))
        for (current in variants) {
            assertFalse(act(noId, current))
            assertNull(hint(noId, current))
            assertFalse(DeviceControlsPolicy.frameMatches(noId, current))
        }
        assertTrue(act(configured()))
    }

    @Test fun ownerLocalWindowEpochAndAllRelevantFrameInvalidationsRejectOldCallbacks() {
        val noId = selected()
        assertNull(hint(noId, owner = false))
        assertNull(hint(noId, window = true))
        assertNull(hint(noId, currentLocal = 5L))
        val ready = configured()
        assertFalse(act(ready, owner = false))
        assertFalse(act(ready, window = true))
        assertFalse(act(ready, currentLocal = 5L))
        val variants = listOf(ready.copy(connectionEpoch = 8L), ready.copy(connectionFormRevision = 13L),
            ready.copy(unpairToken = 4L), ready.copy(huaweiIdInput = "synthetic-different-id"),
            ready.copy(huaweiIdDirty = true), ready.copy(deviceAddress = b.address))
        for (current in variants) {
            assertFalse(DeviceControlsPolicy.frameMatches(ready, current))
            assertFalse(act(ready, current))
            assertNull(hint(ready, current))
        }
    }
}
