package io.github.escosis.hwtfaceswap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import io.github.escosis.hwtfaceswap.ui.WanJingApp

/** 系统界面边界；设备、账户和协议行为由界面模型与运行时处理。 */
class MainActivity : ComponentActivity() {
    private val watchViewModel by viewModels<WatchViewModel>()
    private var platformMessage by mutableStateOf("")
    private var pendingBluetoothAction: BluetoothAction? = null
    private var pendingNotificationAction: BluetoothAction? = null
    private var notificationRequested = false
    private var bluetoothRequestSerial = 0L
    private var pendingHwtPickToken: Long? = null

    private data class BluetoothAction(
        val serial: Long,
        val needsNotification: Boolean,
        val isValid: () -> Boolean,
        val run: () -> Unit,
    )

    private fun isCurrent(action: BluetoothAction): Boolean =
        !isFinishing && !isDestroyed && action.serial == bluetoothRequestSerial && action.isValid()

    private val bluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (action == null || !isCurrent(action)) return@registerForActivityResult
        if (requiredBluetoothPermissions().all(::permissionGranted)) {
            ensureBluetoothEnabled(action)
        } else {
            platformMessage = if (Build.VERSION.SDK_INT >= 31) {
                "需要附近设备权限才能搜索或连接手表。可在系统设置中授权后重试。"
            } else {
                "Android 11 及更早版本需要定位权限用于蓝牙搜索。"
            }
        }
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (action == null || !isCurrent(action)) return@registerForActivityResult
        if (bluetoothEnabled()) {
            requestNotificationIfNeeded(action)
        } else {
            platformMessage = "蓝牙尚未开启。请开启系统蓝牙后重试。"
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (action == null || !isCurrent(action)) return@registerForActivityResult
        if (!granted) {
            platformMessage = "通知权限未开启，连接仍可进行。后台连接状态可在系统任务管理中查看。"
        }
        action.run()
    }

    private val openHwtDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val token = pendingHwtPickToken
        pendingHwtPickToken = null
        // 被解除配对确认退休的旧系统选择结果不能导入文件或取得新的持久授权。
        if(token == null || !watchViewModel.hwtPickIsCurrent(token)) return@registerForActivityResult
        if (uri != null) {
            persistDocumentGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            watchViewModel.importHwtFromPlatform(uri, token)
        } else {
            watchViewModel.cancelHwtPickFromPlatform(token)
        }
    }

    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            persistDocumentGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            watchViewModel.exportLogs(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRequested = savedInstanceState?.getBoolean("notificationRequested") ?: false
        pendingHwtPickToken = savedInstanceState?.takeIf { it.containsKey("pendingHwtPickToken") }?.getLong("pendingHwtPickToken")
        enableEdgeToEdge()
        setContent {
            val state by watchViewModel.state.collectAsStateWithLifecycle()
            WanJingApp(
                state = state,
                actions = watchViewModel,
                platformMessage = platformMessage,
                onDismissPlatformMessage = {
                    if(platformMessage.isNotBlank()) AppRuntime.get(this).logs.record("系统提示", platformMessage)
                    platformMessage = ""
                },
                onScan = ::scanWithPermissions,
                onConnect = { device, internalId -> connectWithPermissions(device, internalId) },
                onPickHwt = ::pickHwt,
                onExportLogs = ::exportLogs,
            )
        }
        connectAtStartup()
        watchViewModel.checkUpdatesAtStartup()
    }

    override fun onResume() {
        super.onResume()
        // 设置页返回不是成功证据：重新经过当前适配器/权限/MAC 的真实 bond-state 读取。
        watchViewModel.verifyUnpairAfterSettings()
    }

    private fun connectAtStartup() {
        val device = watchViewModel.takeStartupDevice() ?: return
        connectWithPermissions(device, "", automatic = true)
    }

    private fun connectWithPermissions(device: NearbyWatch, internalId: String, automatic: Boolean = false) {
        val requested = watchViewModel.state.value
        val revision = requested.connectionFormRevision
        val draft = requested.huaweiIdInput
        val epoch = requested.connectionEpoch
        // 过时的界面帧不能向新表单提供旧的明确凭据。
        // 历史或启动连接使用空输入标记，复用当前设备已加密保存的 ID。
        if (requested.selectedDeviceAddress != device.address ||
            (internalId.isNotEmpty() && internalId.trim() != draft.trim())
        ) return
        val isValid = {
            val current = watchViewModel.state.value
            (!automatic || current.autoConnect) && current.selectedDeviceAddress == device.address &&
                current.connectionFormRevision == revision && watchViewModel.platformActionRevisionIsCurrent(revision) &&
                !UiPolicy.isUnpairTarget(current, device.address) && current.huaweiIdInput == draft &&
                current.connectionEpoch == epoch && watchViewModel.connectionEpochIsCurrent(epoch) &&
                !current.connected && !current.connecting &&
                !current.operation.active && current.prompt == null && !current.needsPin
        }
        withBluetoothPermissions(needsNotification = true, isValid = isValid) {
            watchViewModel.connectFromPlatform(device, internalId, epoch, revision)
        }
    }

    private fun scanWithPermissions() {
        val revision = watchViewModel.state.value.connectionFormRevision
        val isValid = {
            val current = watchViewModel.state.value
            watchViewModel.platformActionRevisionIsCurrent(revision) &&
                !current.connected && !current.connecting && !current.operation.active &&
                current.prompt == null && !current.needsPin
        }
        withBluetoothPermissions(needsNotification = false, isValid = isValid) { watchViewModel.scan() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notificationRequested", notificationRequested)
        pendingHwtPickToken?.let { outState.putLong("pendingHwtPickToken", it) }
        super.onSaveInstanceState(outState)
    }

    private fun requiredBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun withBluetoothPermissions(needsNotification: Boolean, isValid: () -> Boolean, action: () -> Unit) {
        if (!isValid()) return
        // 系统活动结果不带本地令牌，因此保持原请求的归属。
        // 后续点击可修改表单，但不能替换该回调已捕获的操作。
        if (pendingBluetoothAction != null || pendingNotificationAction != null) {
            platformMessage = "请先完成系统权限或蓝牙提示，再重新点击。"
            return
        }
        platformMessage = ""
        val pending = BluetoothAction(++bluetoothRequestSerial, needsNotification, isValid, action)
        val missing = requiredBluetoothPermissions().filterNot(::permissionGranted)
        if (missing.isEmpty()) {
            ensureBluetoothEnabled(pending)
        } else {
            pendingBluetoothAction = pending
            bluetoothPermissions.launch(missing.toTypedArray())
        }
    }

    private fun bluetoothEnabled(): Boolean = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    private fun ensureBluetoothEnabled(action: BluetoothAction) {
        if (!isCurrent(action)) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            platformMessage = "这台设备没有可用的蓝牙适配器。"
            return
        }
        if (bluetoothEnabled()) {
            requestNotificationIfNeeded(action)
        } else {
            pendingBluetoothAction = action
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: ActivityNotFoundException) {
                pendingBluetoothAction = null
                platformMessage = "无法打开系统蓝牙开关，请在系统设置中开启蓝牙。"
            } catch (_: SecurityException) {
                pendingBluetoothAction = null
                platformMessage = "蓝牙权限已被撤销，请重新授权后重试。"
            }
        }
    }

    private fun requestNotificationIfNeeded(action: BluetoothAction) {
        if (!isCurrent(action)) return
        if (action.needsNotification && Build.VERSION.SDK_INT >= 33 &&
            !permissionGranted(Manifest.permission.POST_NOTIFICATIONS) && !notificationRequested
        ) {
            notificationRequested = true
            pendingNotificationAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action.run()
        }
    }

    private fun pickHwt() {
        if(pendingHwtPickToken != null) { platformMessage = "请先完成此前的系统文件选择，再重新点击。"; return }
        if (!watchViewModel.prepareHwtPick()) return
        val token = watchViewModel.currentHwtPickToken() ?: return
        pendingHwtPickToken = token
        try {
            openHwtDocument.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            pendingHwtPickToken = null
            watchViewModel.cancelHwtPickFromPlatform(token)
            platformMessage = "系统没有可用的文件选择器。请选择提供 Storage Access Framework 的文件应用。"
        }
    }

    private fun exportLogs() {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        try {
            createLogDocument.launch("腕镜-脱敏日志-$stamp.txt")
        } catch (_: ActivityNotFoundException) {
            platformMessage = "系统没有可用的文档保存器，暂时无法导出日志。"
        }
    }

    private fun persistDocumentGrant(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // 部分文档提供方授予临时访问权限，可用于当前操作。
        }
    }
}
