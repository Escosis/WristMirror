package io.github.escosis.hwtfaceswap

/** 界面操作资格集中计算，与手表协议的接受判断分开。 */
internal object UiPolicy {
    fun connecting(ready: Boolean, nativeConnecting: Boolean, queued: Boolean): Boolean =
        !ready && (nativeConnecting || queued)

    fun isUnpairTarget(state: AppUiState, address: String?): Boolean =
        state.unpairing && address != null && state.unpairAddress?.equals(address, ignoreCase = true) == true

    fun canRequestUnpair(state: AppUiState): Boolean =
        !state.unpairWorking && !state.unpairing && !state.connecting && !state.operation.active && !state.pickingHwt &&
            state.prompt == null && !state.needsPin

    fun canEditHuaweiId(state: AppUiState): Boolean =
        !state.unpairWorking && !isUnpairTarget(state, state.selectedDeviceAddress) && !state.connecting && !state.operation.active && state.prompt == null && !state.needsPin

    fun canOperateFaces(state: AppUiState): Boolean =
        !state.unpairWorking && state.connected && !isUnpairTarget(state, state.deviceAddress) && state.listReady && !state.listRefreshing && !state.quarantine &&
            !state.operation.active && !state.pickingHwt && !state.connecting && state.prompt == null && !state.needsPin

    /** 普通列表查询与删除前置查询共用短提示，后置核验仍由操作进度展示。 */
    fun showFaceRefreshNotice(state: AppUiState): Boolean =
        state.connected && state.listRefreshing && !state.quarantine && !state.connecting &&
            !state.unpairWorking && !isUnpairTarget(state, state.deviceAddress) &&
            !state.operation.active && !state.pickingHwt && state.prompt == null && !state.needsPin

    fun canChooseHwt(state: AppUiState): Boolean =
        !state.unpairWorking && !state.operation.active && !state.pickingHwt && !state.connecting && state.prompt == null && !state.needsPin
}
