package io.github.escosis.hwtfaceswap.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebActivity
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebPolicy
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** 仅保存非敏感的获取进度；ID 只经回调进入当前设备草稿。 */
internal class HuaweiIdAcquisition(private val context: Context) {
    var busy by mutableStateOf(false)
        internal set
    var start: () -> Unit = {}
        internal set
    private var feedbackToast: Toast? = null

    fun notify(message: String) {
        cancelFeedback()
        feedbackToast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    fun cancelFeedback() {
        feedbackToast?.cancel()
        feedbackToast = null
    }
}

private class HuaweiIdRequest(val token: String) {
    var launched = false
    var handlingResult = false
    var job: Job? = null
}

private class HuaweiIdOwner {
    var alive = true
    var request by mutableStateOf<HuaweiIdRequest?>(null)
}

/** ownerKey 必须同时包含窗口世代与设备地址；不跨窗口保存请求或明文 ID。 */
@Composable
internal fun rememberHuaweiIdAcquisition(
    ownerKey: String,
    enabled: Boolean,
    isCurrent: () -> Boolean,
    onAcquired: (String) -> Unit,
): HuaweiIdAcquisition = key(ownerKey) {
    val context = LocalContext.current
    val cleanupContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val owner = remember { HuaweiIdOwner() }
    val acquisition = remember { HuaweiIdAcquisition(cleanupContext) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val latestOnAcquired by rememberUpdatedState(onAcquired)
    val currentAtComposition = isCurrent()

    fun owns(request: HuaweiIdRequest): Boolean = owner.alive && owner.request === request
    fun editable(): Boolean = latestEnabled && latestIsCurrent()

    fun abandonRequest() {
        val request = owner.request ?: return
        // 先撤销资格，再取消协程；晚到结果不能回填或释放新请求的保留权。
        owner.request = null
        acquisition.busy = false
        request.job?.cancel()
        HuaweiIdWebSession.abandon(cleanupContext, request.token, request.launched)
    }

    DisposableEffect(owner) {
        onDispose {
            owner.alive = false
            abandonRequest()
            acquisition.cancelFeedback()
        }
    }
    // show/canEdit 或当前设备资格撤销时立即放弃，包含退出动画仍挂载的窗口。
    DisposableEffect(owner, enabled, currentAtComposition) {
        onDispose { abandonRequest() }
    }

    val launchedRequest = owner.request
    // 每次重试也重新注册：无 token 的旧系统取消结果不能误清理下一次登录。
    val launcher = key(launchedRequest?.token) {
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val request = launchedRequest
            if (request != null && owns(request) && request.launched && !request.handlingResult) {
                request.handlingResult = true
                acquisition.notify("正在清理登录数据…")
                request.job = scope.launch {
                    var cleaned = false
                    try {
                        // 成功、取消、错误结果均须先等子进程退出并验证目录清空。
                        cleaned = HuaweiIdWebSession.clearAfterExit(cleanupContext)
                        if (!owns(request)) return@launch
                        if (!cleaned) {
                            acquisition.notify("登录数据未确认清理完成，本次不会填入 ID，请稍后重试。")
                            return@launch
                        }
                        if (!editable()) return@launch
                        val data = result.data
                        val sameRequest = data?.getStringExtra(HuaweiIdWebActivity.EXTRA_REQUEST) == request.token
                        val id = if (result.resultCode == Activity.RESULT_OK && sameRequest) {
                            data?.getStringExtra(HuaweiIdWebActivity.EXTRA_ID)
                        } else null
                        when {
                            result.resultCode == Activity.RESULT_OK && sameRequest && HuaweiIdWebPolicy.validId(id) && id != null -> {
                                // 回调仍只修改草稿；保存和连接由原有按钮单独执行。
                                if (owns(request) && editable()) {
                                    latestOnAcquired(id)
                                    acquisition.notify("已获取到华为 ID 并填入，请核对后保存。")
                                }
                            }
                            result.resultCode == Activity.RESULT_CANCELED && sameRequest &&
                                data?.getStringExtra(HuaweiIdWebActivity.EXTRA_ERROR) == "CANCELLED" -> {
                                acquisition.notify("已取消获取，登录数据已清理。")
                            }
                            else -> acquisition.notify("未获取到有效的华为 ID，登录数据已清理，请重试或手动填写。")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (owns(request) && editable()) {
                            acquisition.notify(if (cleaned) {
                                "未获取到有效的华为 ID，请重试或手动填写。"
                            } else {
                                "登录数据未确认清理完成，本次不会填入 ID，请稍后重试。"
                            })
                        }
                    } finally {
                        if (owns(request)) {
                            owner.request = null
                            acquisition.busy = false
                            if (cleaned) HuaweiIdWebSession.release(request.token)
                            else HuaweiIdWebSession.abandon(cleanupContext, request.token, request.launched)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(launchedRequest) {
        val request = launchedRequest ?: return@LaunchedEffect
        if (!owns(request)) return@LaunchedEffect
        request.job = currentCoroutineContext()[Job]
        try {
            // 父实现负责 IO 与跨进程锁；此处不在主线程自行操作网站目录。
            val prepared = HuaweiIdWebSession.prepare(cleanupContext, request.token)
            if (!owns(request)) return@LaunchedEffect
            if (!editable()) {
                abandonRequest()
                return@LaunchedEffect
            }
            if (!prepared) {
                acquisition.notify("无法准备隔离登录，请稍后重试或手动填写。")
                owner.request = null
                acquisition.busy = false
                HuaweiIdWebSession.release(request.token)
                return@LaunchedEffect
            }
            val intent = HuaweiIdWebActivity.launchIntent(context, request.token)
            // launch 抛异常也交给幂等 abandon 处理可能已启动的子页。
            request.launched = true
            acquisition.notify("请自行登录，将自动获取华为 ID，关闭并清理网站数据。")
            launcher.launch(intent)
        } catch (cancelled: CancellationException) {
            if (owns(request)) abandonRequest()
            throw cancelled
        } catch (_: Exception) {
            if (owns(request)) {
                acquisition.notify("无法打开隔离登录，请稍后重试或手动填写。")
                abandonRequest()
            }
        }
    }

    acquisition.start = start@{
        if (!owner.alive || !editable() || owner.request != null) return@start
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            acquisition.notify("当前系统不支持隔离网页登录，请手动填写华为 ID")
            return@start
        }
        val request = HuaweiIdRequest(UUID.randomUUID().toString())
        if (!HuaweiIdWebSession.reserve(request.token)) {
            acquisition.notify("已有登录获取正在进行，请稍后重试。")
            return@start
        }
        owner.request = request
        acquisition.busy = true
        acquisition.notify("正在准备隔离登录…")
    }
    acquisition
}
