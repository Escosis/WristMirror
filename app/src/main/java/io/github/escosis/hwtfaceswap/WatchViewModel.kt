package io.github.escosis.hwtfaceswap

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import io.github.escosis.hwtfaceswap.protocol.Core
import io.github.escosis.hwtfaceswap.protocol.FacePolicy
import io.github.escosis.hwtfaceswap.protocol.HwtSource
import io.github.escosis.hwtfaceswap.protocol.TlvView
import io.github.escosis.hwtfaceswap.protocol.VersionPolicy
import io.github.escosis.hwtfaceswap.store.SavedWatchStore
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalController
import io.github.escosis.hwtfaceswap.lifecycle.DeviceRemovalJournal
import io.github.escosis.hwtfaceswap.transport.ConnectionService
import io.github.escosis.hwtfaceswap.transport.WatchScanner
import io.github.escosis.hwtfaceswap.transport.WatchfaceManager
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** 界面与运行时的适配层；设备写操作必须先消费一次可见确认令牌。 */
class WatchViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val app = application
    private val runtime = AppRuntime.get(application)
    private val scanner = WatchScanner(application)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val updateIo = Executors.newSingleThreadExecutor { task -> Thread(task, "wristmirror-updates").apply { isDaemon = true } }
    private val tokens = AtomicLong(System.nanoTime())
    private val prefs = app.getSharedPreferences("ui", Application.MODE_PRIVATE)
    private val savedWatches = SavedWatchStore(app)
    private val mutable = MutableStateFlow(AppUiState(theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)))
    val state: StateFlow<AppUiState> = mutable
    private var closed = false
    private var startupDeviceTaken = false
    private var credentialEpoch = -1L
    private var defaultEpoch = -1L
    private var savedEditorId = ""
    private var lastRuntimeMessage = ""
    private var appliedOperationRevision = -1L
    private var appliedReplacementDialogRevision = -1L
    private var removalRevision = -1L
    private var removalToken: Long? = null
    private var removalUiAddress: String? = null
    private var scanGeneration = 0L
    private var desiredId: String? = null
    private var filePickToken: Long? = null
    private var source: HwtSource? = null
    private var sourcePayload: HwtSource.Snapshot? = null
    private var fileOnlyPreview = false
    private var startingOperation: QueuedStart? = null
    private var preparing = false
    private var pending: Pending? = null
    private data class Pick(val face: TlvView.Face, val version: String, val epoch: Long)
    private data class QueuedStart(val ticket: Long, val kind: String, val epoch: Long, val operationRevision: Long)
    private data class Pending(val token: Long, val action: () -> Unit, val cancel: () -> Unit)
    private companion object {
        // 界面提供的连接元数据只共享给运行时身份/历史观察者，不保存账户或密钥材料。
        // 新界面模型可恢复进行中的设备连接，无需持久保存候选密钥。
        @Volatile private var sessionDevice: NearbyWatch? = null
        @Volatile private var queuedConnectEpoch: Long? = null
        @Volatile private var rememberedReadyEpoch = -1L
        private var agreementSkippedEpoch = -1L
        private var startupUpdateTaken = false
        private var historyObserver: AppRuntime.Observer? = null
        private fun successfulHistory(runtime: AppRuntime, store: SavedWatchStore, s: AppRuntime.Snapshot): List<SavedWatch>? {
            if(s.epoch != runtime.connection.epoch()) return null
            if(queuedConnectEpoch != null && queuedConnectEpoch != s.epoch) queuedConnectEpoch = null
            if(s.code != "READY" || !s.ready || !runtime.connection.ready() || queuedConnectEpoch != null) return null
            val current = runtime.snapshot()
            val address = SavedWatchStore.canonicalAddress(runtime.connection.address()) ?: return null
            if(runtime.unpair.blocksAddress(address) || runtime.secure.removals().blocked(address)) return null
            if(current.epoch != s.epoch || current.code != "READY" || !current.ready || runtime.connection.epoch() != s.epoch ||
                !s.deviceAddress.equals(address, true) || !current.deviceAddress.equals(address, true)) return null
            val journal = runtime.secure.removals()
            val generation = journal.generation(address)
            val device = sessionDevice?.takeIf { it.address.equals(address, true) }
                ?: store.devices().firstOrNull { it.address.equals(address, true) }?.nearby() ?: return null
            // 快照在锁外读取，避免 runtime 发布锁与 journal 锁倒序；锁内再次核实代次、MAC 和 READY。
            return synchronized(DeviceRemovalJournal.LOCK) {
                if(!journal.usable(address, generation) || runtime.unpair.blocksAddress(address) ||
                    !runtime.connection.address().equals(address, true) || !runtime.connection.ready() || runtime.connection.epoch() != s.epoch) return@synchronized null
                if(rememberedReadyEpoch != s.epoch) store.remember(device).also { rememberedReadyEpoch = s.epoch }
                // 进程级观察者在首个 READY 后仍接收 01/07；只补首个身份字段，不重复 remember 或改变历史顺序。
                store.rememberModel(address, current.deviceModel, generation)
                store.rememberSerial(address, current.serialNumber, generation)
            }
        }
        private fun observeSuccessfulDevices(runtime: AppRuntime, application: Application) {
            if(historyObserver != null) return
            // 此观察者随进程运行时和前台服务存活，不依附界面模型。
            // 不捕获活动页面、编辑值、凭据或界面文件执行器。
            val store = SavedWatchStore(application.applicationContext)
            val handler = Handler(Looper.getMainLooper())
            val observer = object : AppRuntime.Observer {
                private var identityEpoch = -1L
                private var identityModel: String? = null
                private var identitySerial: String? = null
                override fun changed(snapshot: AppRuntime.Snapshot) {
                    // 只在首个 READY/型号/序列号回调返回、界面可见之前同步保存；传输进度不在 actor 重读历史。
                    // 此小锁只去重，读取运行时快照和 journal 之前已释放；紧接断开也不丢失已取得身份字段。
                    val newIdentity = synchronized(this) {
                        if(snapshot.code != "READY" || !snapshot.ready ||
                            identityEpoch == snapshot.epoch && identityModel == snapshot.deviceModel && identitySerial == snapshot.serialNumber) false
                        else { identityEpoch = snapshot.epoch; identityModel = snapshot.deviceModel; identitySerial = snapshot.serialNumber; true }
                    }
                    if(newIdentity) runCatching { successfulHistory(runtime, store, snapshot) }
                        .onFailure { runtime.logs.record("DEVICE_HISTORY_ERROR", "code=${Core.code(it)}") }
                    handler.post { runCatching { successfulHistory(runtime, store, snapshot) }
                        .onFailure { runtime.logs.record("DEVICE_HISTORY_ERROR", "code=${Core.code(it)}") } }
                }
                override fun rebindRequested(message: String) {}
                override fun deletionPrepared(face: TlvView.Face, epoch: Long) {}
            }
            historyObserver = observer
            runtime.addObserver(observer)
        }
    }
    private val observer = object : AppRuntime.Observer {
        override fun changed(snapshot: AppRuntime.Snapshot) { main.post { if (!closed) apply(snapshot) } }
        override fun rebindRequested(message: String) {
            val requestedEpoch = runtime.connection.epoch()
            main.post {
                val current = runtime.snapshot()
                if (!closed && !removalBlocks(runtime.connection.address()) && requestedEpoch == runtime.connection.epoch() && current.epoch == requestedEpoch && current.code == "REBIND_CONFIRM")
                    prompt("rebind", "重新协议绑定？", "重新绑定可能改变手表与其他应用的连接关系，是否继续？", true, "确认重新绑定",
                        { runtime.connection.confirmRebind(requestedEpoch) }, { runtime.connection.disconnectIfEpoch(requestedEpoch) })
            }
        }
        override fun deletionPrepared(face: TlvView.Face, epoch: Long) { main.post { if (!closed) confirmDeletion(face, epoch) } }
    }
    private val removalObserver = DeviceRemovalController.Listener { snapshot ->
        main.post { if(!closed) applyRemoval(snapshot) }
    }
    init {
        observeSuccessfulDevices(runtime, app)
        applyRemoval(runtime.unpair.snapshot())
        val history = runCatching { savedWatches.devices() }.getOrElse { message("无法读取设备记录：${Core.code(it)}"); emptyList() }
        val autoConnect = runCatching { savedWatches.autoConnect() }.getOrElse { message("无法读取自动连接设置：${Core.code(it)}"); false }
        val autoCheck = prefs.getBoolean("auto_check_update", true)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0.0"
        mutable.update { it.copy(savedDevices = history, autoConnect = autoConnect, autoCheckUpdate = autoCheck, appVersion = version) }
        val initial = runtime.snapshot()
        if (queuedConnectEpoch != null && queuedConnectEpoch != initial.epoch) queuedConnectEpoch = null
        val activeAddress = if (queuedConnectEpoch != null) sessionDevice?.address
            else if (initial.ready || initial.connecting || initial.active || initial.code == "FAILED") runtime.connection.address() else null
        // 未有活动连接时回到最后成功设备，只读取该设备自己的账户配置。
        val initialAddress = activeAddress?.let(SavedWatchStore::canonicalAddress)?.takeUnless(::removalBlocks)
            ?: history.firstOrNull { !removalBlocks(it.address) }?.address
        if(initialAddress != null) {
            val device = deviceMetadata(initialAddress)
            mutable.update { it.copy(selectedDeviceAddress = initialAddress, watchName = device?.name ?: "华为手表") }
        }
        restoreHuaweiId(initialAddress)
        // 先同步读取实际快照，再允许活动页面消费启动连接机会。
        apply(initial)
        runtime.logs.setListener { lines -> main.post { if (!closed) mutable.update { it.copy(logs = lines.toList()) } } }
        runtime.addObserver(observer)
        runtime.unpair.addListener(removalObserver)
    }
    private fun removalBlocks(address: String?): Boolean =
        address != null && (UiPolicy.isUnpairTarget(mutable.value, address) || runtime.unpair.blocksAddress(address) || runtime.secure.removals().blocked(address))
    private fun removalWorking(): Boolean = runtime.unpair.snapshot().working
    private fun retireRemovalUi(address: String, token: Long) {
        if(closed || (removalToken == token && removalUiAddress == address)) return
        removalToken = token; removalUiAddress = address
        // 按确认捕获的 MAC/token 幂等退休；不依赖必须观察到 pending 快照。
        // actor 可能在 request 返回后、界面首次读取 snapshot 前已完成全部清理。
        invalidatePrompt(); filePickToken = null; scanGeneration++; scanner.stop()
        val sessionMatches = sessionDevice?.address?.equals(address, true) == true
        if(sessionMatches) { sessionDevice = null; queuedConnectEpoch = null; rememberedReadyEpoch = -1L; agreementSkippedEpoch = -1L }
        val currentMatches = runtime.connection.address().equals(address, true) || mutable.value.deviceAddress.equals(address, true)
        if(currentMatches) { desiredId = null; defaultEpoch = -1L; credentialEpoch = -1L }
        mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L, pickingHwt = false, scanning = false,
            needsPin = if(currentMatches) false else it.needsPin,
            selectedId = if(currentMatches) null else it.selectedId) }
    }
    private fun applyRemoval(s: DeviceRemovalController.Snapshot) {
        if(closed || s.revision != runtime.unpair.snapshot().revision || s.revision <= removalRevision) return
        removalRevision = s.revision
        val address = s.address
        if(s.pending && address != null) retireRemovalUi(address, s.token)
        if(s.success && address != null) {
            val selectedMatches = mutable.value.selectedDeviceAddress?.equals(address, true) == true
            if(selectedMatches) { savedEditorId = ""; credentialEpoch = -1L }
            if(sessionDevice?.address?.equals(address, true) == true) { sessionDevice = null; queuedConnectEpoch = null; rememberedReadyEpoch = -1L }
            val history = runCatching { savedWatches.devices() }.getOrElse { mutable.value.savedDevices.filterNot { watch -> watch.address.equals(address, true) } }
            val otherConnection = runtime.connection.address().takeIf { it.isNotBlank() && !it.equals(address, true) }?.let(::deviceMetadata)
            mutable.update { old -> old.copy(savedDevices = history, devices = old.devices.filterNot { it.address.equals(address, true) },
                selectedDeviceAddress = if(selectedMatches) null else old.selectedDeviceAddress,
                huaweiIdInput = if(selectedMatches) "" else old.huaweiIdInput,
                accountConfigured = if(selectedMatches) false else old.accountConfigured,
                huaweiIdDirty = if(selectedMatches) false else old.huaweiIdDirty,
                watchName = if(selectedMatches) otherConnection?.name ?: "" else old.watchName,
                connectionFormRevision = old.connectionFormRevision + if(selectedMatches) 1L else 0L) }
        }
        mutable.update { it.copy(unpairing = s.pending, unpairWorking = s.working, unpairToken = s.token, unpairAddress = address,
            unpairMessage = removalUiMessage(s), unpairNeedsSettings = s.needsSettings) }
    }
    // 只缩短界面说明；Controller 的原始原因、日志与状态判据保持不变。
    private fun removalUiMessage(s: DeviceRemovalController.Snapshot): String {
        if(s.message.isBlank()) return ""
        if(s.success) return "配对已解除，设备资料已清除"
        val detail = s.message
        return when {
            detail.startsWith("无法读取待处理解除配对标记") -> "无法读取待处理状态，请重新打开应用。"
            detail.startsWith("系统蓝牙未开启") -> "蓝牙已关闭，请开启后重新检查。"
            detail.startsWith("附近设备连接权限不可用") -> "请在系统应用权限中授予附近设备权限后重新检查。"
            detail.startsWith("无法可靠读取此 MAC") -> "无法确认配对状态，请重新检查。"
            detail.contains("本应用清理尚未可靠保存") -> "设备资料清理未完成，请重新检查"
            detail.startsWith("待处理标记或连接停止未可靠完成") -> "操作未完成，请重新检查。"
            detail.contains("当前表盘操作尚未结束") || detail.startsWith("请先结束当前表盘操作") -> "请先结束当前表盘操作。"
            s.needsSettings -> "请在系统蓝牙设置解除配对后返回检查"
            s.pending -> "正在确认解除配对"
            else -> "操作未完成，请重新检查。"
        }
    }
    private fun phaseLabel(code: String): String = when (code) {
        "IDLE" -> "尚未连接"
        "CONNECTING" -> "连接手表"
        "SYSTEM_PAIRING" -> "等待系统配对"
        "LINK_PARAMS", "DEVICE_STATUS" -> "读取链路参数"
        "SECURITY", "LITE_AUTH", "ISO_AUTH", "BOND_PARAMS", "BOND_KEY" -> "验证设备认证"
        "PIN_REQUEST", "PIN_INPUT" -> "等待配对码"
        "VERIFY_SESSION" -> "验证当前会话密钥"
        "COMMAND_CAPS", "EXPAND_CAPS" -> "认证已通过，读取设备能力"
        "ACCOUNT", "SETTINGS", "AGREEMENTS", "REVERSE_CAPS", "SETUP_DEVICE", "ACTIVITY_TYPES", "WEAR_STATUS", "CONNECT_NOTIFY" -> "认证已通过，完成连接初始化"
        "AGREEMENT_CONFIRM" -> "完成连接初始化"
        "REBIND_CONFIRM", "REBINDING" -> "重新协议绑定"
        "READY" -> "连接就绪"
        else -> "连接未完成"
    }
    private fun operationLabel(kind: String, stage: String, active: Boolean, failed: Boolean): String = when(stage) {
        "LISTED" -> if(!active && !failed) "表盘已替换" else if(failed) "替换结果未确认" else "正在核对替换结果"
        "DELETED" -> "两次列表已无所选 ID，请检查手表"
        "NO_TRANSFER" -> "设备未要求文件，本次未传输"
        "PRECHECK_FAILED" -> "替换未开始，尚未向手表发送指令"
        "FAILED", "TIMED_OUT", "CANCELLED" -> "操作未可靠完成，请重新连接并查询"
        "WAIT_OPERATION", "WAIT_DEVICE" -> "等待设备接受回执"
        "TRANSFERRING" -> "传输原始内容"
        "WAIT_REPORT", "WAIT_POST" -> "等待手表报告与应用确认"
        "WAIT_LIST" -> if(kind == "deletion") "核对删除后的两次新列表" else "核对新版本列表"
        else -> stage
    }
    private fun replacementIdentityMatches(dialog: ReplacementDialogInfo?, identity: WatchfaceManager.ReplacementIdentity?): Boolean =
        dialog != null && identity != null && dialog.token == identity.token && dialog.connectionEpoch == identity.epoch &&
            dialog.targetId == identity.id && dialog.targetVersion == identity.version
    private fun replacementActive(): Boolean = runtime.snapshot().replacementDialog?.operation?.active == true
    private fun replacementDialog(s: AppRuntime.Snapshot): ReplacementDialogInfo? {
        val dialog = s.replacementDialog ?: return null
        if(!replacementIdentityMatches(dialog, s.replacementDialogIdentity)) return null
        val operation = dialog.operation
        val detail = when {
            operation.stage == "PRECHECK_FAILED" -> replacementError(operation.detail)
            operation.stage == "CANCELLED" && operation.detail == "USER_CANCELLED_BEFORE_SEND" -> "替换已取消，尚未向手表发送指令"
            operation.stage == "CANCELLED" -> "替换已取消，请重新连接并查询"
            else -> operationLabel(operation.kind, operation.stage, operation.active, operation.failed)
        }
        return dialog.copy(operation = operation.copy(detail = detail))
    }
    private fun apply(s: AppRuntime.Snapshot) {
        val current = runtime.snapshot()
        if(s.epoch != runtime.connection.epoch() || s.epoch != current.epoch ||
            s.operationRevision != current.operationRevision || s.replacementDialogRevision != current.replacementDialogRevision ||
            s.replacementIdentity !== current.replacementIdentity || s.replacementDialogIdentity !== current.replacementDialogIdentity ||
            s.operationRevision < appliedOperationRevision || s.replacementDialogRevision < appliedReplacementDialogRevision) return
        appliedOperationRevision = s.operationRevision
        appliedReplacementDialogRevision = s.replacementDialogRevision
        if(queuedConnectEpoch != null && queuedConnectEpoch != s.epoch) queuedConnectEpoch = null
        val address = SavedWatchStore.canonicalAddress(runtime.connection.address())
        if(removalBlocks(address) && (s.ready || s.connecting)) return
        val device = address?.let(::deviceMetadata)
        if(address != null && mutable.value.selectedDeviceAddress == address && credentialEpoch != s.epoch && (s.ready || s.connecting)) {
            credentialEpoch = s.epoch
            restoreHuaweiId(address)
        }
        val faces = s.faces.map { f -> FaceItem(f.id, f.version, f.type ?: 0, f.expandType, s.names["${f.id}_${f.version}"], FacePolicy.current(f), FacePolicy.eligible(f), FacePolicy.kind(f)) }
        if(s.listReady) {
            if(defaultEpoch != s.epoch) { defaultEpoch = s.epoch; desiredId = FacePolicy.defaultSelection(s.faces)?.id }
            else if(desiredId != null && (faces.count { it.id == desiredId } != 1 || faces.none { it.id == desiredId && it.selectable })) desiredId = null
        }
        val queuedStart = startingOperation
        val dialog = replacementDialog(s)
        val op = when {
            preparing -> OperationInfo("source", "PREPARING", "读取并冻结本地 HWT", null, true, false)
            queuedStart != null -> OperationInfo(queuedStart.kind, "WAIT_OPERATION", if(queuedStart.kind == "deletion") "正在开始删除" else "正在开始替换", null, true, false)
            dialog != null && (dialog.operation.active || !s.active) -> dialog.operation
            fileOnlyPreview && !s.active && !s.failed -> OperationInfo()
            else -> OperationInfo(s.kind, s.stage, operationLabel(s.kind, s.stage, s.active, s.failed), s.progress, s.active, s.failed)
        }
        // 就绪状态优先于本地排队标记，连接完成后不再保留“正在连接”。
        if(s.ready) queuedConnectEpoch = null
        mutable.update { old -> old.copy(phaseCode = s.code, connectionEpoch = s.epoch,
            phase = if(queuedConnectEpoch != null) "连接手表" else phaseLabel(s.code), detail = connectionDetail(s),
            connected = s.ready, connecting = UiPolicy.connecting(s.ready, s.connecting, queuedConnectEpoch != null),
            watchName = if(queuedConnectEpoch != null) sessionDevice?.name ?: old.watchName else device?.name ?: old.watchName,
            deviceAddress = s.deviceAddress, serialNumber = s.serialNumber, firmware = s.firmware, deviceModel = s.deviceModel,
            batteryPercent = s.batteryPercent, freeMemoryBytes = s.freeMemoryBytes,
            width = s.width, height = s.height, capability = s.capability,
            faces = faces, listReady = s.listReady, listRefreshing = s.listRefreshing,
            selectedId = if(s.listReady) desiredId else old.selectedId, operation = op, replacementDialog = dialog, needsPin = s.needsPin, pinMessage = s.pinMessage,
            rebindCount = s.rebind, quarantine = (s.failed && !s.ready) || runtime.watchfaces.reconnectRequired(s.epoch),
            statusMessage = when {
                s.kind == "replacement" && s.stage == "PRECHECK_FAILED" && s.message.isNotBlank() -> replacementError(s.message)
                s.message.isNotBlank() -> s.message
                (s.ready && old.phaseCode != "READY") || (old.listRefreshing && !s.listRefreshing) -> ""
                else -> old.statusMessage
            }) }
        if(s.message != lastRuntimeMessage) {
            lastRuntimeMessage = s.message
            if(s.message.isNotBlank()) message(runtimeError(s.kind, s.stage, s.message))
        }
        syncSuccessfulHistory(s)
        // 使用原有跳过分支，继续初始化而不发送同意声明。
        // 排队或过时快照不能操作其他连接或已推进的阶段。
        if(s.code == "AGREEMENT_CONFIRM" && s.epoch == runtime.connection.epoch() && runtime.snapshot().code == s.code && !runtime.connection.ready()) {
            if(agreementSkippedEpoch != s.epoch) {
                agreementSkippedEpoch = s.epoch
                runtime.connection.respondAgreements(s.epoch, false)
            }
        }
        if(mutable.value.prompt?.kind == "rebind" && runtime.snapshot().code != "REBIND_CONFIRM") invalidatePrompt()
    }
    private fun connectionDetail(s: AppRuntime.Snapshot): String = when(s.code) {
        "AGREEMENT_CONFIRM" -> "继续连接初始化"
        "SYSTEM_PAIRING" -> "请在手机和手表确认系统配对"
        "ACCOUNT" -> "同步内部华为 ID"
        "PIN_REQUEST" -> "请求手表配对信息"
        "PIN_INPUT" -> "输入手表显示的配对码"
        "BOND_PARAMS" -> "读取协议绑定参数"
        "BOND_KEY" -> "完成协议绑定"
        "REBINDING" -> "正在重新进行协议绑定"
        "READY" -> if(s.detail.contains("部分可选回执未确认")) "连接初始化完成；部分可选回执未确认" else "连接初始化完成"
        else -> s.detail
    }
    private fun deviceMetadata(address: String): NearbyWatch? = sessionDevice?.takeIf { it.address.equals(address, true) }
        ?: mutable.value.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
    private fun savedHuaweiId(address: String?): String = if(address == null || removalBlocks(address)) "" else runCatching {
        runtime.secure.huaweiId(address)
    }.getOrElse { message("无法读取该设备的内部华为 ID：${Core.code(it)}"); "" }
    private fun restoreHuaweiId(address: String?) {
        val id = savedHuaweiId(address)
        savedEditorId = id
        mutable.update { it.copy(huaweiIdInput = id, accountConfigured = id.isNotBlank(), huaweiIdDirty = false) }
    }
    private fun syncSuccessfulHistory(s: AppRuntime.Snapshot) {
        runCatching { successfulHistory(runtime, savedWatches, s) ?: savedWatches.devices() }.onSuccess { history ->
            // 断开时也同步本地身份缓存；已保存的型号与序列号不依赖旧 READY 界面任务再次执行。
            mutable.update { it.copy(savedDevices = history) }
        }.onFailure { message("设备记录保存失败：${Core.code(it)}") }
    }
    /**
     * 第一次调用即消费启动机会，包括无法取得设备的情况。
     * 本界面模型在权限取消或页面重新进入时不自动重试；新模型
     * 只在共享运行时与界面均无活动操作时尝试启动连接。
     * 蓝牙和通知权限仍由活动页面的系统权限流程处理。
     */
    fun takeStartupDevice(): NearbyWatch? {
        if(startupDeviceTaken) return null
        startupDeviceTaken = true
        val s = runtime.snapshot()
        if(closed || removalWorking() || !mutable.value.autoConnect || queuedConnectEpoch != null || runtime.connection.ready() || s.ready || s.connecting || s.active || mutable.value.connecting || mutable.value.scanning || preparing || filePickToken != null || startingOperation != null || replacementActive() || pending != null || runtime.watchfaces.busy()) return null
        val device = mutable.value.savedDevices.firstOrNull { !removalBlocks(it.address) }?.nearby() ?: return null
        selectDevice(device)
        if(!mutable.value.accountConfigured) { message("上次设备的内部华为 ID 无法读取，请重新输入后连接"); return null }
        return device
    }
    private fun message(text: String) {
        if(closed) return
        runtime.logs.record("界面提示", text)
        mutable.update { it.copy(statusMessage = text, noticeSerial = it.noticeSerial + 1L, noticeMessage = text) }
    }
    override fun setTheme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); mutable.update { it.copy(theme = mode) } }
    override fun selectDevice(device: NearbyWatch) {
        if(closed || removalWorking() || startingOperation != null || replacementActive()) return
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("设备地址无效"); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message("无法核实设备资料状态：${Core.code(it)}"); return }
        if(generation > 0L && knownUnpairDevice(address) == null) { message("此设备记录已清除，请重新搜索后选择设备"); return }
        if(mutable.value.selectedDeviceAddress != address && mutable.value.prompt != null) invalidatePrompt()
        mutable.update { it.copy(selectedDeviceAddress = address, watchName = device.name, connectionFormRevision = it.connectionFormRevision + 1L) }
        credentialEpoch = runtime.connection.epoch()
        restoreHuaweiId(address)
    }
    override fun setHuaweiId(value: String) {
        if(closed || removalWorking() || mutable.value.selectedDeviceAddress == null || removalBlocks(mutable.value.selectedDeviceAddress)) return
        mutable.update { if(it.huaweiIdInput == value) it else it.copy(huaweiIdInput = value,
            huaweiIdDirty = value.trim() != savedEditorId, connectionFormRevision = it.connectionFormRevision + 1L) }
    }
    override fun saveHuaweiId() {
        if(closed || removalWorking() || removalBlocks(mutable.value.selectedDeviceAddress) || mutable.value.connecting || preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.prompt != null || mutable.value.needsPin) return
        val address = mutable.value.selectedDeviceAddress ?: run { message("请先选择一台手表"); return }
        val id = mutable.value.huaweiIdInput.trim()
        if(id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 128 || id.any(Char::isISOControl) || (id.all(Char::isDigit) && id.length > 19)) {
            message("请输入有效的内部华为 ID"); return
        }
        runCatching { runtime.secure.saveHuaweiId(address, id) }.onSuccess {
            savedEditorId = id
            mutable.update { it.copy(huaweiIdInput = id, huaweiIdDirty = false, accountConfigured = true,
                connectionFormRevision = it.connectionFormRevision + 1L) }
            message(if(mutable.value.connected) "华为 ID 已保存，下次连接时生效" else "华为 ID 已保存")
        }.onFailure { message("华为 ID 保存失败：${Core.code(it)}") }
    }
    // 该边界不切换设备或读取全局编辑草稿。持久 pending 只阻断目标 MAC，短工作仍全局互斥。
    private fun canAccessDeviceHuaweiId(address: String, epoch: Long): Boolean {
        val ui = mutable.value
        val removal = runtime.unpair.snapshot()
        // 清理可在 main 回报之前全部完成；旧窗口须先消费最新清理代次，不能借新 generation 重建资料。
        return !closed && removalRevision == removal.revision && ui.unpairToken == removal.token &&
            epoch == runtime.connection.epoch() && ui.connectionEpoch == epoch &&
            knownUnpairDevice(address) != null && !ui.unpairWorking && !removalWorking() && !removalBlocks(address) &&
            queuedConnectEpoch == null && !ui.connecting && !preparing && filePickToken == null &&
            startingOperation == null && !replacementActive() && !runtime.watchfaces.busy() && !ui.operation.active && !ui.pickingHwt &&
            pending == null && ui.prompt == null && !ui.needsPin
    }
    private fun deviceHuaweiIdSnapshotCurrent(address: String, epoch: Long, snapshot: AppRuntime.Snapshot): Boolean =
        canAccessDeviceHuaweiId(address, epoch) && snapshot.epoch == epoch && !snapshot.connecting && !snapshot.active && !snapshot.needsPin
    override fun readHuaweiIdForDevice(device: NearbyWatch): String? {
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("设备地址无效"); return null }
        val snapshot = runtime.snapshot()
        val epoch = snapshot.epoch
        if(!deviceHuaweiIdSnapshotCurrent(address, epoch, snapshot)) return null
        return runCatching {
            val journal = runtime.secure.removals()
            val generation = journal.generation(address)
            // 不在 journal 锁内读取 runtime.snapshot，避免与运行时发布的锁顺序相反。
            val id = synchronized(DeviceRemovalJournal.LOCK) {
                if(!canAccessDeviceHuaweiId(address, epoch) || !journal.usable(address, generation)) return@synchronized null
                runtime.secure.huaweiId(address)
            } ?: return@runCatching null
            if(deviceHuaweiIdSnapshotCurrent(address, epoch, runtime.snapshot()) && journal.usable(address, generation)) id else null
        }.getOrElse { message("无法读取该设备的内部华为 ID：${Core.code(it)}"); null }
    }
    override fun saveHuaweiIdForDevice(device: NearbyWatch, value: String): Boolean {
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("设备地址无效"); return false }
        val snapshot = runtime.snapshot()
        val epoch = snapshot.epoch
        if(!deviceHuaweiIdSnapshotCurrent(address, epoch, snapshot)) return false
        val id = value.trim()
        if(id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 128 || id.any(Char::isISOControl) || (id.all(Char::isDigit) && id.length > 19)) {
            message("请输入有效的内部华为 ID"); return false
        }
        return runCatching {
            val journal = runtime.secure.removals()
            val generation = journal.generation(address)
            val saved = synchronized(DeviceRemovalJournal.LOCK) {
                // 代次核对与真实写入共享现有锁；清理完成后的旧回调不能重建已删除资料。
                if(!canAccessDeviceHuaweiId(address, epoch) || !journal.usable(address, generation)) return@synchronized false
                runtime.secure.saveHuaweiId(address, id)
                true
            }
            if(!saved || !deviceHuaweiIdSnapshotCurrent(address, epoch, runtime.snapshot()) || !journal.usable(address, generation)) return@runCatching false
            if(mutable.value.selectedDeviceAddress?.equals(address, true) == true) {
                savedEditorId = id
                mutable.update { current ->
                    if(current.selectedDeviceAddress?.equals(address, true) == true) current.copy(huaweiIdInput = id,
                        huaweiIdDirty = false, accountConfigured = true, connectionFormRevision = current.connectionFormRevision + 1L)
                    else current
                }
            }
            message(if(mutable.value.connected && runtime.connection.address().equals(address, true)) "华为 ID 已保存，下次连接时生效" else "华为 ID 已保存")
            true
        }.getOrElse { message("华为 ID 保存失败：${Core.code(it)}"); false }
    }
    override fun setAutoConnect(enabled: Boolean) {
        if(closed) return
        runCatching { savedWatches.setAutoConnect(enabled) }.onSuccess { mutable.update { it.copy(autoConnect = enabled) } }
            .onFailure { message("自动连接设置保存失败：${Core.code(it)}") }
    }
    override fun setAutoCheckUpdate(enabled: Boolean) {
        if(closed) return
        if(prefs.edit().putBoolean("auto_check_update", enabled).commit()) mutable.update { it.copy(autoCheckUpdate = enabled) }
        else message("自动检查更新设置保存失败")
    }
    fun checkUpdatesAtStartup() {
        if(startupUpdateTaken) return
        startupUpdateTaken = true
        if(mutable.value.autoCheckUpdate) checkUpdatesOwned(automatic = true)
    }
    override fun checkUpdates() = checkUpdatesOwned(automatic = false)
    override fun dismissUpdate() { if(!closed) mutable.update { it.copy(pendingUpdate = null) } }
    private fun checkUpdatesOwned(automatic: Boolean) {
        if(closed || mutable.value.checkingUpdate) return
        mutable.update { it.copy(checkingUpdate = true, updateMessage = "正在检查更新") }
        try { updateIo.execute {
            val result = UpdateChecker.fetchLatest()
            main.post {
                if(closed) return@post
                val current = mutable.value
                val found = (result as? UpdateResult.Available)?.info?.takeIf { UpdateChecker.isNewer(it.version, current.appVersion) }
                val description = when(result) {
                    is UpdateResult.Available -> if(found != null) "发现新版本 ${found.version}" else "已是最新版本（${current.appVersion}）"
                    UpdateResult.NoRelease -> "项目暂未发布发行版本"
                    is UpdateResult.Failure -> result.message
                }
                runtime.logs.record("更新检查", description)
                mutable.update { it.copy(checkingUpdate = false, updateMessage = description,
                    pendingUpdate = when {
                        automatic && !it.autoCheckUpdate -> it.pendingUpdate
                        result is UpdateResult.Failure -> it.pendingUpdate
                        else -> found
                    }) }
            }
        } } catch(_: RejectedExecutionException) {
            mutable.update { it.copy(checkingUpdate = false, updateMessage = "检查更新未开始，请重新打开应用后重试") }
        }
    }
    override fun scan() {
        if(closed || removalWorking()) return
        if(!scanner.hasPermissions()) { message("请授予附近设备权限；Android 11 或更早系统还需定位权限"); return }
        val generation = ++scanGeneration
        mutable.update { it.copy(scanning = true, statusMessage = "搜索附近设备及已有系统配对") }
        scanner.start(object : WatchScanner.Listener {
            override fun onItems(items: MutableList<WatchScanner.Item>?) {
                val result = items.orEmpty().map { NearbyWatch(it.address, it.name, it.bonded, it.rssi, if(it.bleOnly) "低功耗蓝牙" else "经典蓝牙") }
                main.post { if(!closed && scanGeneration == generation) mutable.update { it.copy(devices = result) } }
            }
            override fun onStopped(reason: String) { main.post { if(!closed && scanGeneration == generation) mutable.update { it.copy(scanning = false, statusMessage = reason) } } }
        })
    }
    override fun stopScan() { scanGeneration++; scanner.stop(); mutable.update { it.copy(scanning = false) } }
    fun platformActionRevisionIsCurrent(expectedRevision: Long): Boolean =
        !closed && !removalWorking() && mutable.value.connectionFormRevision == expectedRevision
    fun connectionEpochIsCurrent(expectedEpoch: Long): Boolean =
        !closed && runtime.connection.epoch() == expectedEpoch && runtime.snapshot().epoch == expectedEpoch
    fun connectFromPlatform(device: NearbyWatch, huaweiId: String, expectedEpoch: Long, expectedFormRevision: Long) {
        if(closed || mutable.value.connectionFormRevision != expectedFormRevision) return
        connectOwned(device, huaweiId, expectedEpoch)
    }
    // 旧无表单令牌调用只兼容从未进入解除配对生命周期的运行时；不能复活已清理目标。
    fun connectFromPlatform(device: NearbyWatch, huaweiId: String, expectedEpoch: Long) {
        if(runtime.unpair.snapshot().revision == 0L) connectOwned(device, huaweiId, expectedEpoch)
    }
    override fun connect(device: NearbyWatch, huaweiId: String) =
        connectOwned(device, huaweiId, runtime.connection.epoch())
    private fun connectOwned(device: NearbyWatch, huaweiId: String, expectedEpoch: Long) {
        if(closed) return
        if(!connectionEpochIsCurrent(expectedEpoch)) { message("连接状态已变化，请重新连接所选设备"); return }
        if(removalWorking() || preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy()) { message("请先结束当前操作"); return }
        if(Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { message("请授予附近设备连接权限"); return }
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("设备地址无效"); return }
        if(removalBlocks(address)) { message("此 MAC 的解除配对尚待确认或清理，未重新连接；请重新检查"); return }
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message("无法核实设备资料状态：${Core.code(it)}"); return }
        if(generation > 0L && knownUnpairDevice(address) == null) { message("此设备记录已清除，请重新搜索后连接设备"); return }
        val id = huaweiId.trim()
        if(id.isNotEmpty() && (id.toByteArray(Charsets.UTF_8).size > 128 || (id.all(Char::isDigit) && id.length > 19))) { message("内部华为 ID 长度不支持"); return }
        if(mutable.value.selectedDeviceAddress != address) selectDevice(device)
        if(id.isEmpty()) {
            // 明确的空输入代表当前设备已加密保存的内部 ID，
            // 不代表界面草稿或另一设备的最近使用值。
            restoreHuaweiId(address)
            if(!mutable.value.accountConfigured) { message("此手表尚无保存的内部华为 ID，请输入后连接"); return }
        } else setHuaweiId(id)
        val requested = device.copy(address = address)
        val revision = mutable.value.connectionFormRevision
        val action = {
            if(closed || expectedEpoch != runtime.connection.epoch() || revision != mutable.value.connectionFormRevision || !runtime.secure.removals().usable(address, generation)) message("连接状态已变化，请重新连接所选设备")
            else if(removalWorking() || removalBlocks(address) || preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy()) message("当前操作尚未结束，未切换连接")
            else {
                stopScan(); invalidatePrompt(); desiredId = null; defaultEpoch = -1L
                try {
                    ContextCompat.startForegroundService(app, Intent(app, ConnectionService::class.java))
                    sessionDevice = requested; queuedConnectEpoch = expectedEpoch
                    mutable.update { it.copy(watchName = requested.name, selectedDeviceAddress = address,
                        connectionFormRevision = it.connectionFormRevision + 1L, connecting = true, statusMessage = "") }
                    // 连接层加密保存明确输入的 ID，复用当前设备认证密钥，
                    // 不重置系统配对。
                    runtime.connection.connectIfEpoch(expectedEpoch, address, requested.transport == "低功耗蓝牙", id)
                } catch(e: Exception) {
                    queuedConnectEpoch = null
                    val code = if(e is SecurityException) "BLUETOOTH_PERMISSION_REQUIRED" else Core.code(e)
                    mutable.update { it.copy(connecting = runtime.snapshot().connecting) }
                    message("连接未开始：$code")
                }
            }
        }
        if(id.isNotEmpty() && (!id.all(Char::isDigit) || id.length == 11)) prompt("account-warning", "确认内部 ID", "这可能不是内部华为 ID。请核对与运动健康或腕连显示的 ID 是否一致，是否继续使用？", false, "确认使用", action)
        else action()
    }
    override fun disconnect() {
        if(closed) return
        if(replacementActive()) { message("请等待当前替换完成"); return }
        invalidatePrompt(); mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L) }; runtime.connection.disconnect(); message("已请求断开连接")
    }
    private fun knownUnpairDevice(address: String): NearbyWatch? =
        mutable.value.devices.firstOrNull { it.address.equals(address, true) }
            ?: mutable.value.savedDevices.firstOrNull { it.address.equals(address, true) }?.nearby()
            ?: sessionDevice?.takeIf { it.address.equals(address, true) && runtime.connection.address().equals(address, true) }
    override fun requestUnpair(device: NearbyWatch) {
        if(closed) return
        if(runtime.unpair.snapshot().pending) { message("已有一台设备等待解除配对核实，请先重新检查该设备"); return }
        if(preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) { message("请先结束当前表盘操作，未开始解除配对"); return }
        if(pending != null) return
        val address = SavedWatchStore.canonicalAddress(device.address) ?: run { message("设备地址无效，未开始解除配对"); return }
        val target = knownUnpairDevice(address) ?: run { message("请从当前已选择、发现或历史记录中的真实设备发起解除配对"); return }
        val epoch = runtime.connection.epoch()
        val generation = runCatching { runtime.secure.removals().generation(address) }.getOrElse { message("无法核实待解除配对设备：${Core.code(it)}"); return }
        val token = tokens.incrementAndGet()
        prompt("unpair", "解除配对？",
            "${target.name}\nMAC：$address\n\n解除系统配对，并清除此设备在腕镜中的记录、华为 ID 和认证资料。手表表盘不受影响。",
            true, "解除配对", {
                if(closed || epoch != runtime.connection.epoch() || knownUnpairDevice(address) == null || !runtime.secure.removals().usable(address, generation)) throw Core.Fault("UNPAIR_TARGET_CHANGED")
                if(preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.operation.active) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
                runtime.logs.record("UNPAIR_UI_CONFIRM", "target_known=true token_consumed=true no_watchface_command=true")
                if(!runtime.unpair.request(address, token)) throw Core.Fault("UNPAIR_NOT_STARTED")
                retireRemovalUi(address, token)
                applyRemoval(runtime.unpair.snapshot())
            }, ownedToken = token)
    }
    override fun openUnpairSettings() { if(!closed) runtime.unpair.openSettings() }
    override fun retryUnpair() { if(!closed && !preparing && startingOperation == null && !replacementActive()) runtime.unpair.retry() }
    fun verifyUnpairAfterSettings() { if(!closed) runtime.unpair.verify() }
    override fun submitPin(expectedEpoch: Long, pin: String) {
        if(closed || removalBlocks(runtime.connection.address()) || expectedEpoch != runtime.connection.epoch() || mutable.value.connectionEpoch != expectedEpoch || !mutable.value.needsPin) return
        mutable.update { it.copy(needsPin = false) }; runtime.connection.submitPin(expectedEpoch, pin)
    }
    override fun cancelPin(expectedEpoch: Long) {
        if(closed || removalBlocks(runtime.connection.address()) || expectedEpoch != runtime.connection.epoch() || mutable.value.connectionEpoch != expectedEpoch || !mutable.value.needsPin) return
        mutable.update { it.copy(connectionFormRevision = it.connectionFormRevision + 1L) }
        runtime.connection.disconnectIfEpoch(expectedEpoch)
    }
    override fun refreshFaces() {
        if(!closed && !removalWorking() && !removalBlocks(runtime.connection.address()) && !preparing && startingOperation == null && !replacementActive()) {
            mutable.update { it.copy(statusMessage = "") }
            runtime.logs.record("列表刷新", "请求读取手表表盘列表")
            runtime.watchfaces.refresh()
        }
    }
    override fun selectFace(id: String) {
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.prompt != null) return
        val matches = mutable.value.faces.filter { it.id == id }
        val f = matches.singleOrNull()?.takeIf { it.selectable } ?: return
        desiredId = f.id; mutable.update { it.copy(selectedId = f.id) }
    }
    private fun selected(): TlvView.Face? {
        val id = desiredId ?: mutable.value.selectedId ?: return null
        return runtime.snapshot().faces.filter { it.id == id }.singleOrNull()?.takeIf { FacePolicy.eligible(it) }
    }
    private fun replacementError(code: String): String = when(code) {
        "FRESH_DEVICE_LIST_REQUIRED" -> "列表已过期或仍在查询，尚未发送替换指令。请刷新列表后重新选择 HWT。"
        "SELECTED_FACE_CHANGED", "SELECTED_ID_NOT_UNIQUE", "VERSION_CHANGED" -> "所选表盘或版本已变化，尚未发送替换指令。请刷新并重新选择。"
        "SELECTED_CONNECTION_CHANGED" -> "连接已变化，尚未发送替换指令。请在当前手表重新选择。"
        "PAYLOAD_SNAPSHOT_UNAVAILABLE" -> "本地文件副本已失效，尚未发送替换指令。请重新选择 HWT。"
        "SOURCE_SELECTION_CHANGED" -> "HWT 文件已变化，请重新点击替换。"
        else -> "替换未开始，尚未发送指令：$code"
    }
    private fun runtimeError(kind: String, stage: String, code: String): String {
        if(kind == "replacement" && stage == "PRECHECK_FAILED") return replacementError(code)
        return when(code) {
            "WATCHFACE_OPERATION_BUSY", "WAIT_FOR_CURRENT_OPERATION_OR_QUERY" -> "请等待当前操作或查询完成"
            "CONNECT_AND_AUTHENTICATE_FIRST" -> "请先连接手表后重试"
            "LIST_QUERY_TIMEOUT_RECONNECT" -> "读取表盘列表超时，请重新连接后刷新"
            "DEVICE_LIST_ERROR" -> "手表未返回有效列表，请重新刷新"
            "RECONNECT_AFTER_UNCERTAIN_FILE_RESULT" -> "上次操作尚未可靠收尾，请重新连接后刷新"
            "DELETE_PREPARATION_CHANGED" -> "所选表盘已变化，请刷新列表后重新选择"
            else -> "操作未完成：$code"
        }
    }
    private fun replacementUiRejected(code: String, identity: WatchfaceManager.ReplacementIdentity? = null) {
        runtime.logs.record("REPLACE_UI_PRECHECK_FAILED", "code=$code command_sent=false upload_sent=false")
        if(identity != null) runtime.rejectReplacementDialog(identity, code)
        if(!closed) {
            if(identity != null) apply(runtime.snapshot())
            mutable.update { it.copy(operation = if(identity == null) OperationInfo("replacement", "PRECHECK_FAILED", replacementError(code), null, false, true) else it.operation, statusMessage = replacementError(code)) }
        }
    }
    private fun currentPickFace(target: Pick): TlvView.Face {
        val own = runtime.connection.epoch(); val s = runtime.snapshot()
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || own != target.epoch || s.epoch != target.epoch || !runtime.connection.ready() || !s.ready || mutable.value.selectedDeviceAddress?.equals(runtime.connection.address(), true) != true) throw Core.Fault("SELECTED_CONNECTION_CHANGED")
        if(mutable.value.selectedId != target.face.id) throw Core.Fault("SELECTED_FACE_CHANGED")
        if(!s.listReady || !runtime.watchfaces.replacementListFresh(target.epoch)) throw Core.Fault("FRESH_DEVICE_LIST_REQUIRED")
        val actual = s.faces.filter { it.id == target.face.id }.singleOrNull() ?: throw Core.Fault("SELECTED_ID_NOT_UNIQUE")
        if(actual.version != target.face.version || !FacePolicy.eligible(actual)) throw Core.Fault("SELECTED_FACE_CHANGED")
        if(VersionPolicy.next(actual.id, actual.version, s.faces) != target.version) throw Core.Fault("VERSION_CHANGED")
        return actual
    }
    override fun prepareHwtPick(): Boolean {
        if(closed || removalWorking() || preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || !UiPolicy.canChooseHwt(mutable.value)) return false
        // 文件只与本次系统选择请求关联，选择文件本身不捕获或操作手表目标。
        filePickToken = tokens.incrementAndGet()
        // 已结束的旧摘要不属于刚选择的文件，不能让准备文件看起来像已替换。
        fileOnlyPreview = true
        mutable.update { it.copy(pickingHwt = true, operation = if(it.operation.failed) it.operation else OperationInfo()) }
        apply(runtime.snapshot())
        runtime.logs.record("SOURCE_PICK_OPENED", "no_command_sent=true")
        return true
    }
    override fun cancelHwtPick() {
        filePickToken = null
        if(!closed) { mutable.update { it.copy(pickingHwt = false) }; apply(runtime.snapshot()) }
    }
    fun currentHwtPickToken(): Long? = filePickToken
    fun hwtPickIsCurrent(token: Long): Boolean = !closed && filePickToken == token
    fun importHwtFromPlatform(uri: Uri, expectedToken: Long) { if(hwtPickIsCurrent(expectedToken)) importHwt(uri) }
    fun cancelHwtPickFromPlatform(expectedToken: Long) { if(hwtPickIsCurrent(expectedToken)) cancelHwtPick() }
    override fun importHwt(uri: Uri) {
        if(closed || filePickToken == null) { if(!closed) message("文件选择已失效，请重新选择 HWT"); return }
        filePickToken = null
        preparing = true
        mutable.update { it.copy(pickingHwt = false, operation = OperationInfo("source", "PREPARING", "读取并冻结本地 HWT", null, true)) }
        runtime.logs.record("SOURCE_READ_STARTED", "no_command_sent=true")
        try { io.execute {
            var loaded: HwtSource? = null
            try {
                val filename = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "本地 HWT"
                app.contentResolver.openInputStream(uri).use { input -> if(input == null) throw Core.Fault("HWT_INPUT_UNAVAILABLE"); loaded = HwtSource.load(input, app.filesDir) }
                val imported = loaded!!
                val payload = imported.freeze(HwtSource.Mode.BIN); payload.verify()
                main.post {
                    preparing = false
                    if(closed) { cleanAsync(imported); return@post }
                    val old = source
                    source = imported; sourcePayload = payload
                    old?.let(::cleanAsync)
                    runtime.logs.record("SOURCE_IMPORTED", "bytes=${payload.size} sha256=${payload.hash} source_sha256=${imported.sourceHash} xml_not_rewritten=true no_command_sent=true")
                    mutable.update { it.copy(source = SourceInfo(imported.title, filename, imported.original.length(), payload.size, imported.xmlVersion, payload.hash), operation = OperationInfo()) }
                    apply(runtime.snapshot())
                    message("HWT 已就绪，选择目标后点击替换")
                }
            } catch(e: Exception) {
                loaded?.let(::cleanAsync)
                val code = if(e is SecurityException) "FILE_READ_PERMISSION_REQUIRED" else Core.code(e)
                runtime.logs.record("SOURCE_ERROR", "code=$code no_command_sent=true")
                main.post {
                    preparing = false
                    if(!closed) { mutable.update { it.copy(operation = OperationInfo()) }; apply(runtime.snapshot()); message("HWT 读取失败：$code") }
                }
            }
        } } catch(_: RejectedExecutionException) {
            preparing = false
            if(!closed) { mutable.update { it.copy(operation = OperationInfo()) }; apply(runtime.snapshot()); message("文件读取未开始，请重新选择 HWT") }
        }
    }
    override fun requestReplacement() {
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || preparing || filePickToken != null || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || mutable.value.prompt != null || mutable.value.needsPin) return
        val imported = source ?: run { message("请先选择 HWT 文件"); return }
        val payload = sourcePayload ?: run { replacementUiRejected("PAYLOAD_SNAPSHOT_UNAVAILABLE"); return }
        try {
            val own = runtime.connection.epoch(); val snapshot = runtime.snapshot()
            if(!UiPolicy.canOperateFaces(mutable.value) || snapshot.epoch != own || !snapshot.ready || !runtime.connection.ready()) throw Core.Fault("FRESH_DEVICE_LIST_REQUIRED")
            val selected = selected() ?: throw Core.Fault("SELECT_EXISTING_ORDINARY_FACE")
            val target = Pick(selected, VersionPolicy.next(selected.id, selected.version, snapshot.faces), own)
            val current = currentPickFace(target)
            if(payload.source !== imported || !payload.file.isFile || payload.file.length() != payload.size.toLong()) throw Core.Fault("PAYLOAD_SNAPSHOT_UNAVAILABLE")
            val title = mutable.value.faces.singleOrNull { it.id == current.id }?.title ?: current.id
            val filename = mutable.value.source?.fileName ?: imported.title
            val currentWarning = if(FacePolicy.current(current)) "\n该表盘正在使用，替换时手表可能切换显示。" else ""
            prompt("replacement", "替换选中表盘？", "$title\nID：${current.id}\n文件：$filename\n${current.version} → ${target.version}\n传输内容：${payload.size} B$currentWarning\n\n将覆盖该 ID 的现有内容。", true, "替换", {
                // 确认先创建会话，再廉价重验；主线程前检拒绝也有属于此次确认的可关闭结果。
                startReplacement(target, imported, payload, title, filename)
            })
        } catch(e: Exception) { replacementUiRejected(Core.code(e)) }
    }
    private fun startReplacement(target: Pick, imported: HwtSource, payload: HwtSource.Snapshot, title: String, fileName: String) {
        val request = runtime.watchfaces.newReplacementRequest(target.face, target.version, target.epoch)
        if(!runtime.openReplacementDialog(request, title, fileName)) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        fileOnlyPreview = true
        apply(runtime.snapshot())
        runtime.logs.record("REPLACE_UI_CONFIRM", "id=${target.face.id} version=${target.version} captured_epoch=${target.epoch} current_epoch=${runtime.connection.epoch()} token_consumed=true")
        try {
            val actual = currentPickFace(target)
            if(source !== imported || sourcePayload !== payload) throw Core.Fault("SOURCE_SELECTION_CHANGED")
            if(runtime.watchfaces.busy()) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
            runtime.watchfaces.beginReplacement(actual, target.version, payload, target.epoch, request)
        } catch(e: Exception) { replacementUiRejected(Core.code(e), request.identity) }
    }
    override fun interruptReplacement(token: Long) {
        val visible = mutable.value.replacementDialog
        if(closed || !ReplacementDialogPolicy.canInterrupt(visible, token)) return
        val captured = runtime.snapshot()
        val identity = captured.replacementDialogIdentity ?: return
        if(!replacementIdentityMatches(visible, identity) || !replacementIdentityMatches(captured.replacementDialog, identity) ||
            !ReplacementDialogPolicy.canInterrupt(captured.replacementDialog, token)) return
        val epoch = identity.epoch
        main.post {
            val current = runtime.snapshot()
            if(closed || current.replacementDialogIdentity !== identity || epoch != runtime.connection.epoch() || current.epoch != epoch ||
                !replacementIdentityMatches(mutable.value.replacementDialog, identity) || !ReplacementDialogPolicy.canInterrupt(mutable.value.replacementDialog, token) ||
                !replacementIdentityMatches(current.replacementDialog, identity) || !ReplacementDialogPolicy.canInterrupt(current.replacementDialog, token)) return@post
            runtime.interruptReplacement(identity)
            apply(runtime.snapshot())
        }
    }
    override fun dismissReplacementResult(token: Long) {
        val visible = mutable.value.replacementDialog
        if(closed || !ReplacementDialogPolicy.canDismiss(visible, token)) return
        val captured = runtime.snapshot()
        val identity = captured.replacementDialogIdentity ?: return
        if(!replacementIdentityMatches(visible, identity) || !replacementIdentityMatches(captured.replacementDialog, identity) ||
            !ReplacementDialogPolicy.canDismiss(captured.replacementDialog, token)) return
        main.post {
            val current = runtime.snapshot()
            // 失败可能已关闭并推进连接 epoch；结果关闭只要求原会话身份和非活动，不要求旧连接仍存活。
            if(closed || current.replacementDialogIdentity !== identity || !replacementIdentityMatches(mutable.value.replacementDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(mutable.value.replacementDialog, token) || !replacementIdentityMatches(current.replacementDialog, identity) ||
                !ReplacementDialogPolicy.canDismiss(current.replacementDialog, token)) return@post
            runtime.dismissReplacementResult(identity)
            apply(runtime.snapshot())
        }
    }
    private fun dispatchOperation(kind: String, epoch: Long, action: () -> Unit) {
        if(removalWorking() || removalBlocks(runtime.connection.address())) throw Core.Fault("DEVICE_UNPAIR_PENDING")
        if(replacementActive()) throw Core.Fault("WATCHFACE_OPERATION_BUSY")
        val queued = QueuedStart(tokens.incrementAndGet(), kind, epoch, runtime.snapshot().operationRevision)
        startingOperation = queued
        mutable.update { it.copy(operation = OperationInfo(kind, "WAIT_OPERATION", if(kind == "deletion") "正在开始删除" else "正在开始替换", null, true)) }
        try {
            action()
            // 串行屏障确保已执行原生入队；只有新的操作快照才能替代此前摘要。
            runtime.connection.post {
                main.post {
                    if(!closed && startingOperation == queued) {
                        startingOperation = null
                        val current = runtime.snapshot()
                        val newOperation = current.epoch == queued.epoch && current.operationRevision > queued.operationRevision && current.kind == queued.kind
                        if(newOperation) fileOnlyPreview = false
                        else if(current.message.isNotBlank()) fileOnlyPreview = true
                        apply(current)
                        if(!newOperation && current.message.isNotBlank()) {
                            mutable.update { it.copy(operation = OperationInfo(kind, "PRECHECK_FAILED", runtimeError(kind, "PRECHECK_FAILED", current.message), null, false, true)) }
                        }
                    }
                }
            }
        } catch(e: Exception) {
            startingOperation = null
            apply(runtime.snapshot())
            throw e
        }
    }
    override fun requestDeletion() {
        if(closed || removalWorking() || removalBlocks(runtime.connection.address()) || preparing || startingOperation != null || replacementActive() || runtime.watchfaces.busy() || !mutable.value.connected || !mutable.value.listReady) { message("先连接并获取新鲜列表"); return }
        val f = selected() ?: run { message("请选择普通可删除表盘"); return }; runtime.logs.record("删除准备", "删除前重新查询同设备、原 ID 和版本"); runtime.watchfaces.prepareDeletion(f.id, f.version, runtime.connection.epoch())
    }
    private fun confirmDeletion(face: TlvView.Face, epoch: Long) {
        if(epoch != runtime.connection.epoch() || !runtime.connection.ready() || removalWorking() || removalBlocks(runtime.connection.address())) { message("连接变化，未继续删除"); return }
        val currentWarning = if(FacePolicy.current(face)) "\n该表盘正在使用，删除后手表可能切换到其他表盘。" else ""
        prompt("deletion", "删除选中表盘？", "ID：${face.id}\n原版本：${face.version}$currentWarning\n\n将从手表移除该表盘，删除后无法直接恢复。", true, "删除", { dispatchOperation("deletion", epoch) { runtime.watchfaces.beginDeletion(face, epoch) } })
    }
    private fun prompt(kind: String, title: String, message: String, danger: Boolean, positive: String, action: () -> Unit, cancel: () -> Unit = {}, negative: String = "取消", ownedToken: Long? = null) {
        invalidatePrompt(); val token = ownedToken ?: tokens.incrementAndGet(); pending = Pending(token, action, cancel); mutable.update { it.copy(prompt = ActionPrompt(token, kind, title, message, danger, positive, negative)) }
    }
    private fun invalidatePrompt() { pending = null; mutable.update { it.copy(prompt = null) } }
    override fun confirmAction(token: Long) { val p = pending ?: return; if(p.token != token || mutable.value.prompt?.token != token || closed) return; pending = null; mutable.update { it.copy(prompt = null) }; runCatching(p.action).onFailure { val code = Core.code(it); runtime.logs.record("UI_ACTION_ERROR", "code=$code"); message("操作未开始：$code") } }
    override fun dismissAction(token: Long) { val p = pending ?: return; if(p.token != token) return; val connectionCancel = mutable.value.prompt?.kind == "account-warning"; pending = null; mutable.update { it.copy(prompt = null, connectionFormRevision = it.connectionFormRevision + if(connectionCancel) 1L else 0L) }; p.cancel() }
    override fun exportLogs(uri: Uri) { val text = runtime.logs.snapshot(); io.execute { runCatching { app.contentResolver.openOutputStream(uri, "wt").use { if(it == null) throw IllegalStateException(); it.write(text.toByteArray(Charsets.UTF_8)) } }.onSuccess { main.post { message("已导出脱敏诊断日志") } }.onFailure { main.post { message("日志导出失败") } } } }
    override fun clearLogs() { if(!closed && !runtime.watchfaces.busy() && !replacementActive() && !preparing && startingOperation == null) runtime.logs.clear() else message("操作中保留日志") }
    private fun cleanAsync(s: HwtSource) {
        val cleanup = Runnable { runCatching { HwtSource.cleanup(app.filesDir, s.directory) }.onFailure { runtime.logs.record("CACHE_ERROR", "code=${it.javaClass.simpleName}") } }
        try { io.execute(cleanup) }
        catch (_: RejectedExecutionException) {
            // 排队导入可能在界面关闭、文件执行器停止接收任务后完成。
            // 在后台清理已退休的私有副本，避免影响前台连接服务，
            // 界面关闭时不删除仍由传输运行时使用的文件。
            runCatching { Thread(cleanup, "hwt-retired-cleanup").apply { isDaemon = true }.start() }
                .onFailure { runtime.logs.record("CACHE_ERROR", "code=CLEANUP_SCHEDULING_FAILED") }
        }
    }
    override fun clearCache() {
        if(closed) return
        if(runtime.watchfaces.busy() || replacementActive() || preparing || startingOperation != null || filePickToken != null) { message("请先结束当前表盘操作"); return }
        invalidatePrompt()
        val old = source; source = null; sourcePayload = null
        old?.let(::cleanAsync)
        mutable.update { it.copy(source = null) }
        message("导入缓存已清除")
    }
    override fun onCleared() { closed = true; filePickToken = null; scanGeneration++; invalidatePrompt(); scanner.stop(); runtime.removeObserver(observer); runtime.unpair.removeListener(removalObserver); updateIo.shutdownNow(); io.shutdown(); super.onCleared() }
}
