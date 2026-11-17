package io.github.escosis.hwtfaceswap.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.NearbyWatch
import io.github.escosis.hwtfaceswap.R
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold

private enum class DetailsPage { ABOUT, LOGS }

private fun tabNavigationRequestCurrent(
    ownerActive: Boolean,
    request: Long,
    latestRequest: Long,
    detailsVisible: Boolean,
    sourceRequestCurrent: Boolean,
): Boolean = ownerActive && request == latestRequest && !detailsVisible && sourceRequestCurrent

/** 共用 Miuix 页面框架，保留参考项目的分页时长、缓动与关于页动画。 */
@Composable
fun WanJingApp(
    state: AppUiState,
    actions: AppActions,
    platformMessage: String,
    onDismissPlatformMessage: () -> Unit,
    onScan: () -> Unit,
    onConnect: (NearbyWatch, String) -> Unit,
    onPickHwt: () -> Unit,
    onExportLogs: () -> Unit,
) {
    WanJingTheme(state.theme) {
        val context = LocalContext.current
        val pagerState = rememberPagerState(pageCount = { 4 })
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        var selectedPage by remember { mutableIntStateOf(0) }
        var showDetails by rememberSaveable { mutableStateOf(false) }
        var detailsPage by rememberSaveable { mutableStateOf(DetailsPage.ABOUT) }
        val detailsTransition = remember { MutableTransitionState(false) }
        detailsTransition.targetState = showDetails
        val detailsVisible = detailsTransition.currentState || detailsTransition.targetState
        val latestDetailsVisible by rememberUpdatedState(detailsVisible)
        val latestReplacementDialogPresent by rememberUpdatedState(state.replacementDialog != null)
        var isOverlayExpanded by remember { mutableStateOf(false) }
        var deviceWindowExpanded by remember { mutableStateOf(false) }
        var shownNotice by remember { mutableLongStateOf(state.noticeSerial) }
        val tabs = remember { listOf("主页", "设备", "表盘", "设置") }
        val homeIcon = ImageVector.vectorResource(R.drawable.ic_home)
        val settingsIcon = ImageVector.vectorResource(R.drawable.ic_settings)
        val icons = listOf(homeIcon, WatchIcons.Watch, WatchIcons.Faces, settingsIcon)

        // 操作反馈短暂显示，不再将全局状态提示重复放入各页。
        LaunchedEffect(state.noticeSerial) {
            if(shownNotice != state.noticeSerial) {
                shownNotice = state.noticeSerial
                if(state.noticeMessage.isNotBlank()) Toast.makeText(context, state.noticeMessage.uiText(), Toast.LENGTH_LONG).show()
            }
        }
        LaunchedEffect(platformMessage) {
            if(platformMessage.isNotBlank()) {
                Toast.makeText(context, platformMessage.uiText(), Toast.LENGTH_LONG).show()
                onDismissPlatformMessage()
            }
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                .collect { (page, scrolling) -> if(!scrolling) selectedPage = page }
        }
        // 连接和设备操作的确认窗口优先于关于页与更新提示。
        LaunchedEffect(state.prompt?.token, state.needsPin, state.replacementDialog?.token) {
            if(state.prompt != null || state.needsPin || state.replacementDialog != null) showDetails = false
            if(state.replacementDialog != null) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
        LaunchedEffect(pagerState.currentPage, showDetails) {
            if(pagerState.currentPage != 1 || showDetails) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
        val animationJob = remember { arrayOfNulls<Job>(1) }
        val navigationOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
        val navigationRequest = remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val pendingNavigationGuard = remember { arrayOfNulls<() -> Boolean>(1) }
        DisposableEffect(navigationOwner) {
            onDispose {
                navigationOwner.set(false)
                navigationRequest.incrementAndGet()
                pendingNavigationGuard[0] = null
                animationJob[0]?.cancel()
            }
        }
        // 若主页来源帧在分页动画期间失效，停止旧请求，而不是继续执行过期目标。
        LaunchedEffect(state, detailsVisible) {
            if(pendingNavigationGuard[0]?.invoke() == false) {
                navigationRequest.incrementAndGet()
                pendingNavigationGuard[0] = null
                animationJob[0]?.cancel()
                selectedPage = pagerState.currentPage
            }
        }
        fun navigateToTab(index: Int, sourceRequestCurrent: () -> Boolean = { true }) {
            if(!navigationOwner.get() || latestDetailsVisible || latestReplacementDialogPresent || !sourceRequestCurrent()) return
            if(index != 1 || showDetails) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            val request = navigationRequest.incrementAndGet()
            animationJob[0]?.cancel()
            val requestCurrent = {
                tabNavigationRequestCurrent(
                    navigationOwner.get(), request, navigationRequest.get(), latestDetailsVisible,
                    sourceRequestCurrent() && !latestReplacementDialogPresent,
                )
            }
            pendingNavigationGuard[0] = requestCurrent
            animationJob[0] = scope.launch {
                try {
                    // 必须先复核 owner、请求代次和最新来源状态，才能高亮或实际切页。
                    if(!requestCurrent()) return@launch
                    selectedPage = index
                    val pageSize = pagerState.layoutInfo.pageSize.toFloat()
                    val pageSpacing = pagerState.layoutInfo.pageSpacing.toFloat()
                    if(pageSize <= 0f) {
                        pagerState.scrollToPage(index)
                        return@launch
                    }
                    val currentPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    val delta = index - currentPosition
                    if(kotlin.math.abs(delta) < 0.001f) return@launch
                    val scrollPixels = delta * (pageSize + pageSpacing)
                    val duration = (kotlin.math.abs(delta) * 150f).coerceAtLeast(200f).toInt()
                    pagerState.animateScrollBy(
                        value = scrollPixels,
                        animationSpec = tween<Float>(durationMillis = duration, easing = FastOutSlowInEasing),
                    )
                } finally {
                    if(navigationRequest.get() == request) pendingNavigationGuard[0] = null
                }
            }
        }
        val onTabClick: (Int) -> Unit = { index -> navigateToTab(index) }
        val overlayExpandedOnSettings = isOverlayExpanded && pagerState.currentPage == 3 && !detailsVisible
        val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = (detailsVisible || pagerState.currentPage != 0) &&
                !overlayExpandedOnSettings && !deviceWindowExpanded && state.replacementDialog == null && state.prompt == null && !state.needsPin,
            onBackCompleted = {
                if(!latestReplacementDialogPresent) {
                    if(detailsVisible) showDetails = false else onTabClick(0)
                }
            },
        )

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedPage == index,
                                onClick = { onTabClick(index) },
                                icon = icons[index],
                                label = label,
                            )
                        }
                    }
                },
            ) { paddingValues ->
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 3,
                    modifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues),
                ) { page ->
                    when(page) {
                        0 -> CollapsingPage("腕镜") { insets, scrollModifier ->
                            HomeScreen(
                                state, insets,
                                onOpenStatus = { destination, requestCurrent -> navigateToTab(destination, requestCurrent) },
                                scrollModifier = scrollModifier,
                            )
                        }
                        1 -> CollapsingPage("设备") { insets, scrollModifier ->
                            DeviceScreen(
                                state, actions, insets, onScan, onConnect,
                                onOpenFaces = { onTabClick(2) },
                                onWindowExpandedChange = { deviceWindowExpanded = it },
                                scrollModifier = scrollModifier,
                            )
                        }
                        2 -> CollapsingPage("表盘") { insets, scrollModifier ->
                            FacesScreen(state, actions, insets, onPickHwt, scrollModifier)
                        }
                        3 -> CollapsingPage("设置") { insets, scrollModifier ->
                            SettingsScreen(
                                state, actions, insets,
                                onNavigateToAbout = { if(!latestReplacementDialogPresent) { detailsPage = DetailsPage.ABOUT; showDetails = true } },
                                onNavigateToLogs = { if(!latestReplacementDialogPresent) { detailsPage = DetailsPage.LOGS; showDetails = true } },
                                onOverlayExpandedChange = { isOverlayExpanded = it },
                                scrollModifier = scrollModifier,
                            )
                        }
                    }
                }
                RuntimeDialogs(state = state, actions = actions)
            }
            AnimatedVisibility(
                visibleState = detailsTransition,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            ) {
                when(detailsPage) {
                    DetailsPage.ABOUT -> AboutScreen(onBack = { showDetails = false })
                    DetailsPage.LOGS -> LogsScreen(state, actions, onExportLogs, onBack = { showDetails = false })
                }
            }
            state.pendingUpdate?.takeIf {
                !detailsVisible && !overlayExpandedOnSettings && !deviceWindowExpanded && state.replacementDialog == null && !state.operation.active &&
                    !state.connecting && !state.pickingHwt && !state.scanning && !state.listRefreshing && state.prompt == null && !state.needsPin
            }?.let { info ->
                UpdateDialog(
                    info = info,
                    currentVersion = state.appVersion,
                    onDismiss = {
                        if(navigationOwner.get() && !latestReplacementDialogPresent) actions.dismissUpdate()
                    },
                    onOpenReleasePage = {
                        if(navigationOwner.get() && !latestReplacementDialogPresent) {
                            actions.dismissUpdate()
                            openProjectPage(context, info.releaseUrl.ifBlank { info.downloadUrl })
                        }
                    },
                )
            }
        }
    }
}

internal fun openProjectPage(context: Context, url: String) {
    if(url.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val browser = context.packageManager.queryIntentActivities(intent, 0).firstOrNull {
            val pkg = it.activityInfo.packageName
            pkg != "com.github.android" && !pkg.contains("github", ignoreCase = true)
        }
        if(browser != null) intent.setPackage(browser.activityInfo.packageName)
        context.startActivity(intent)
    } catch(_: Exception) {
        Toast.makeText(context, "打开链接失败，请检查浏览器", Toast.LENGTH_SHORT).show()
    }
}
