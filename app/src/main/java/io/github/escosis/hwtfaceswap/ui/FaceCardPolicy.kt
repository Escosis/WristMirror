package io.github.escosis.hwtfaceswap.ui

import io.github.escosis.hwtfaceswap.ActionPrompt
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.FaceItem
import io.github.escosis.hwtfaceswap.ReplacementDialogInfo
import io.github.escosis.hwtfaceswap.SourceInfo
import io.github.escosis.hwtfaceswap.ThemeMode
import io.github.escosis.hwtfaceswap.UiPolicy
import io.github.escosis.hwtfaceswap.protocol.Core
import io.github.escosis.hwtfaceswap.protocol.PayloadKinds

/** Explicit app theme wins over the system setting; never infer dark mode from color luminance. */
internal fun faceCardIconAlpha(theme: ThemeMode, systemDark: Boolean): Float =
    if (theme == ThemeMode.DARK || (theme == ThemeMode.SYSTEM && systemDark)) 0.7f else 0.9f

/** 逐卡操作只使用原始 ID / 版本观察；显示名称不参与目标身份。 */
internal enum class FaceCardAction(val label: String) {
    REPLACE("替换"), ENABLE("启用"), DELETE("删除"),
}

internal enum class FacePageAction { REFRESH, CHOOSE_HWT, CLEAR_IMPORT_CACHE, DIRECT_INSTALL }

internal data class FaceCardDisabledReason(val key: String, val message: String)

/** 只冻结实际影响入口资格的界面上下文，不把名称回显或进度文本当作目标。 */
internal data class FaceCardGuardFrame(
    val epoch: Long,
    val deviceAddress: String,
    val introductionPending: Boolean,
    val connected: Boolean,
    val connecting: Boolean,
    val listReady: Boolean,
    val listRefreshing: Boolean,
    val quarantine: Boolean,
    val pickingHwt: Boolean,
    val operationActive: Boolean,
    val replacementDialog: ReplacementDialogInfo?,
    val activationDialog: ReplacementDialogInfo?,
    val deletionDialog: ReplacementDialogInfo?,
    val prompt: ActionPrompt?,
    val needsPin: Boolean,
    val unpairWorking: Boolean,
    val unpairing: Boolean,
    val unpairAddress: String?,
    val unpairToken: Long,
) {
    companion object {
        fun capture(state: AppUiState) = FaceCardGuardFrame(
            state.connectionEpoch, state.deviceAddress, state.introductionPending,
            state.connected, state.connecting, state.listReady, state.listRefreshing,
            state.quarantine, state.pickingHwt, state.operation.active,
            state.replacementDialog, state.activationDialog, state.deletionDialog,
            state.prompt, state.needsPin, state.unpairWorking, state.unpairing,
            state.unpairAddress, state.unpairToken,
        )
    }
}

internal data class FaceCardActionFrame(
    val action: FaceCardAction,
    val face: FaceItem,
    val epoch: Long,
    val guard: FaceCardGuardFrame,
    val source: SourceInfo?,
    val renderedRowWasUnique: Boolean,
    val disabledReason: FaceCardDisabledReason?,
) {
    val enabled: Boolean get() = disabledReason == null && renderedRowWasUnique
}

internal data class FacePageActionFrame(
    val action: FacePageAction,
    val guard: FaceCardGuardFrame,
    val source: SourceInfo?,
    val disabledReason: FaceCardDisabledReason?,
) {
    val enabled: Boolean get() = disabledReason == null
}

/** 实际窗口/布局由 Compose 桥接提供；任何缺失项均拒绝动作和禁用提示。 */
internal data class FaceCardInteractionGuard(
    val ownerAlive: Boolean = false,
    val frameCurrent: Boolean = false,
    val actionsCurrent: Boolean = false,
    val contextCurrent: Boolean = false,
    val lifecycleOwnerCurrent: Boolean = false,
    val windowOwnerCurrent: Boolean = false,
    val resumed: Boolean = false,
    val windowFocused: Boolean = false,
    val attached: Boolean = false,
    val visibleBounds: Boolean = false,
    val intentCurrent: Boolean = false,
) {
    val allowsInteraction: Boolean get() = ownerAlive && frameCurrent && actionsCurrent &&
        contextCurrent && lifecycleOwnerCurrent && windowOwnerCurrent && resumed &&
        windowFocused && attached && visibleBounds && intentCurrent
}

/** 真实 UI 使用的纯入口策略。既不查询手表，也不推定协议、许可或安装结果。 */
internal object FaceCardPolicy {
    private val introduction = FaceCardDisabledReason("introduction", "请先阅读并确认使用说明")
    private val unpair = FaceCardDisabledReason("unpair", "解除配对处理中，请稍候")
    private val busy = FaceCardDisabledReason("busy", "操作进行中，请保持连接并等待。")
    private val disconnected = FaceCardDisabledReason("disconnected", "请先前往设备页连接手表")
    private val quarantined = FaceCardDisabledReason("quarantine", "请重新连接后刷新表盘列表")
    private val refreshing = FaceCardDisabledReason("refreshing", "正在刷新表盘列表，请稍候")
    private val expired = FaceCardDisabledReason("expired", "请刷新表盘列表")
    private val sourceRequired = FaceCardDisabledReason("source", "请先选择 HWT 文件")
    private val alreadyCurrent = FaceCardDisabledReason("current", "该表盘正在使用，无需重复启用")

    /** 覆盖/删除沿用 selectable，启用只看独立的 activatable；不放宽特殊表盘覆盖门槛。 */
    fun reason(state: AppUiState, face: FaceItem, action: FaceCardAction): FaceCardDisabledReason? {
        commonReason(state)?.let { return it }
        if (!rowCurrent(face, state.connectionEpoch, state)) return expired
        when (action) {
            FaceCardAction.ENABLE -> {
                if (!face.activatable) return FaceCardDisabledReason("activation_unsupported", "该表盘的类型或资源信息不支持启用，请刷新后重试")
                if (face.current) return alreadyCurrent
            }
            FaceCardAction.REPLACE, FaceCardAction.DELETE -> {
                if (!face.selectable) return overwriteReason(face, action)
                if (action == FaceCardAction.REPLACE && state.source == null) return sourceRequired
                if (action == FaceCardAction.REPLACE && state.source?.replacementAvailable == false)
                    return FaceCardDisabledReason(
                        "bin_missing",
                        if (state.source.payloadKinds.any { it == PayloadKinds.CONTAINER || it == PayloadKinds.STRIPPED })
                            "XML 格式仅支持 WATCH 系列全能模式直装，不支持替换"
                        else "该 HWT 未找到可传输的表盘内容",
                    )
            }
        }
        return null
    }

    fun capture(state: AppUiState, face: FaceItem, epoch: Long, action: FaceCardAction): FaceCardActionFrame {
        val unique = rowCurrent(face, epoch, state)
        return FaceCardActionFrame(
            action, face, epoch, FaceCardGuardFrame.capture(state),
            if (action == FaceCardAction.REPLACE) state.source else null,
            unique, if (unique) reason(state, face, action) else expired,
        )
    }

    /** 两个状态都必须含唯一的同一观察；仅换名称不改变 ID / 版本或操作资格。 */
    fun rowCurrent(rendered: FaceItem, epoch: Long, latest: AppUiState): Boolean {
        if (latest.connectionEpoch != epoch) return false
        val actual = latest.faces.singleOrNull { it.id == rendered.id } ?: return false
        return sameObservation(rendered, actual)
    }

    fun sameObservation(rendered: FaceItem, actual: FaceItem): Boolean =
        rendered.id == actual.id && rendered.version == actual.version &&
            rendered.rawType == actual.rawType && rendered.expandType == actual.expandType &&
            rendered.current == actual.current && rendered.selectable == actual.selectable &&
            rendered.activatable == actual.activatable && rendered.kind == actual.kind

    fun current(frame: FaceCardActionFrame, latest: AppUiState): Boolean =
        frame.renderedRowWasUnique && frame.guard == FaceCardGuardFrame.capture(latest) &&
            rowCurrent(frame.face, frame.epoch, latest) &&
            (frame.action != FaceCardAction.REPLACE || frame.source == latest.source) &&
            frame.disabledReason == reason(latest, frame.face, frame.action)

    /** enabled 回调只允许原本可用的帧，不用最新资格把旧 disabled 帧提升为真实动作。 */
    fun invokeEnabled(
        frame: FaceCardActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
    ): Boolean {
        if (!frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !current(frame, latest)) return false
        when (frame.action) {
            FaceCardAction.REPLACE -> renderedActions.requestReplacement(frame.face, frame.epoch)
            FaceCardAction.ENABLE -> renderedActions.requestActivation(frame.face, frame.epoch)
            FaceCardAction.DELETE -> renderedActions.requestDeletion(frame.face, frame.epoch)
        }
        return true
    }

    /** 禁用 tap 只能解释当前仍匹配的原渲染原因，绝不调用动作、自动刷新或重定向到别的卡。 */
    fun invokeDisabledHint(
        frame: FaceCardActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
        hint: (String) -> Unit,
    ): Boolean {
        val reason = frame.disabledReason ?: return false
        if (frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !current(frame, latest)) return false
        hint(reason.message)
        return true
    }

    fun capturePageAction(state: AppUiState, action: FacePageAction) = FacePageActionFrame(
        action, FaceCardGuardFrame.capture(state), state.source, pageReason(state, action),
    )

    fun pageCurrent(frame: FacePageActionFrame, latest: AppUiState): Boolean =
        frame.guard == FaceCardGuardFrame.capture(latest) && frame.source == latest.source &&
            frame.disabledReason == pageReason(latest, frame.action)

    fun invokePageEnabled(
        frame: FacePageActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
        pickHwt: () -> Unit,
    ): Boolean {
        if (!frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !pageCurrent(frame, latest)) return false
        when (frame.action) {
            FacePageAction.REFRESH -> renderedActions.refreshFaces()
            FacePageAction.CHOOSE_HWT -> pickHwt()
            FacePageAction.CLEAR_IMPORT_CACHE -> renderedActions.clearCache()
            FacePageAction.DIRECT_INSTALL -> renderedActions.requestDirectInstall()
        }
        return true
    }

    fun invokePageDisabledHint(
        frame: FacePageActionFrame,
        latest: AppUiState,
        renderedActions: AppActions,
        latestActions: AppActions,
        interaction: FaceCardInteractionGuard,
        hint: (String) -> Unit,
    ): Boolean {
        val reason = frame.disabledReason ?: return false
        if (frame.enabled || renderedActions !== latestActions || !interaction.allowsInteraction || !pageCurrent(frame, latest)) return false
        hint(reason.message)
        return true
    }

    private fun modalReason(state: AppUiState): FaceCardDisabledReason? = when {
        state.introductionPending -> introduction
        state.unpairWorking || UiPolicy.isUnpairTarget(state, state.deviceAddress) -> unpair
        state.operation.active || state.operationDialog != null || state.pickingHwt ||
            state.connecting || state.prompt != null || state.needsPin -> busy
        else -> null
    }

    private fun commonReason(state: AppUiState): FaceCardDisabledReason? = modalReason(state) ?: when {
        !state.connected -> disconnected
        state.quarantine -> quarantined
        state.listRefreshing -> refreshing
        !state.listReady -> expired
        else -> null
    }

    private fun pageReason(state: AppUiState, action: FacePageAction): FaceCardDisabledReason? =
        modalReason(state) ?: if (action == FacePageAction.DIRECT_INSTALL) {
            commonReason(state) ?: when {
                state.source == null -> sourceRequired
                state.source.payloadKind !in state.source.payloadKinds || PayloadKinds.mode(state.source.payloadKind) == null || state.source.payloadBytes <= 0 ->
                    FaceCardDisabledReason("bin_missing", "该 HWT 未找到可传输的表盘内容")
                state.source.payloadBytes > Core.MAX_MEMBER -> FaceCardDisabledReason("direct_size", "表盘内容超过直接安装的大小上限")
                else -> null
            }
        } else if (action == FacePageAction.REFRESH) when {
            !state.connected -> disconnected
            state.quarantine -> quarantined
            state.listRefreshing -> refreshing
            else -> null
        } else null

    private fun overwriteReason(face: FaceItem, action: FaceCardAction): FaceCardDisabledReason {
        val label = action.label
        val special = face.kind in setOf("趣味贴纸", "相册表盘", "视频表盘", "万花筒表盘", "穿戴表盘")
        return if (special) FaceCardDisabledReason(
            "special_${action.name}",
            if (face.activatable) "${face.kind}仅支持启用，不支持$label"
            else "${face.kind}不支持$label；启用还需有效的已安装资源信息",
        ) else FaceCardDisabledReason("overwrite_${action.name}", "该类型表盘不支持$label，仅可对符合条件的普通表盘执行此操作")
    }
}
