package io.github.escosis.hwtfaceswap.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.FaceItem
import io.github.escosis.hwtfaceswap.SourceInfo
import io.github.escosis.hwtfaceswap.UiPolicy
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FacesScreen(
    state: AppUiState,
    actions: AppActions,
    insets: PaddingValues,
    onPickHwt: () -> Unit,
    scrollModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestState by rememberUpdatedState(state)
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(owner) { onDispose { owner.set(false) } }
    // 只随同一连接的刷新开始触发一次；型号回显、进度等普通重组不会重复提示。
    LaunchedEffect(state.connectionEpoch, state.listRefreshing) {
        val current = latestState
        if (owner.get() && current.connectionEpoch == state.connectionEpoch && UiPolicy.showFaceRefreshNotice(current)) {
            Toast.makeText(context, "正在刷新表盘列表，请稍候", Toast.LENGTH_SHORT).show()
        }
    }
    val selectedFace = state.faces.singleOrNull { it.id == state.selectedId }
    val available = UiPolicy.canOperateFaces(state)
    val canChange = available && selectedFace?.selectable == true
    val busy = state.operation.active || state.operationDialog != null || state.pickingHwt || state.connecting || state.prompt != null || state.needsPin
    LazyColumn(
        modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets).selectableGroup(),
        contentPadding = pageContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "face_and_file_actions") {
            Card(
                Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(vertical = 16.dp),
            ) {
                Text(
                    "目标表盘：${(selectedFace?.title ?: "未选择").uiText()}",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                // 标题与动画详情共用完整源快照。换源即销毁旧子树，不把旧退出内容挂在新文件标题下。
                key(state.source) {
                    val sourceSnapshot = remember { state.source }
                    val sourceOwner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
                    DisposableEffect(sourceOwner) { onDispose { sourceOwner.set(false) } }
                    var showSource by rememberSaveable(sourceSnapshot) { mutableStateOf(false) }
                    val renderedExpanded = showSource
                    val sourceTransition = remember { MutableTransitionState(false) }
                    sourceTransition.targetState = showSource && sourceSnapshot != null
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if(sourceSnapshot == null) "HWT 文件：未选择" else "HWT 文件：", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            sourceSnapshot?.let { file ->
                                Text(file.fileName.uiText(), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                MutedText(formatBytes(file.payloadBytes.toLong()))
                            }
                        }
                        if(sourceSnapshot != null) {
                            ExpandButton(showSource, "文件信息", onClick = {
                                if(owner.get() && sourceOwner.get() && sourceToggleCurrent(
                                    sourceSnapshot, latestState.source, renderedExpanded, showSource,
                                )) showSource = !renderedExpanded
                            })
                        }
                    }
                    AnimatedVisibility(
                        visibleState = sourceTransition,
                        enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(160, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                        exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                            shrinkVertically(tween(160, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            sourceSnapshot?.let { SourceDetails(it, Modifier.padding(horizontal = 16.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        text = "选择 HWT", onClick = onPickHwt,
                        modifier = Modifier.weight(1f), enabled = UiPolicy.canChooseHwt(state),
                    )
                    ActionButton(
                        text = "替换表盘", onClick = actions::requestReplacement,
                        modifier = Modifier.weight(1f), primary = true, enabled = canChange && state.source != null,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        text = if(state.listRefreshing) "正在刷新" else "刷新列表",
                        onClick = actions::refreshFaces, modifier = Modifier.weight(1f),
                        enabled = state.connected && !state.listRefreshing && !state.quarantine && !busy,
                    )
                    ActionButton(
                        text = "删除表盘", onClick = actions::requestDeletion,
                        modifier = Modifier.weight(1f), destructive = true, enabled = canChange,
                    )
                }
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    text = "清除导入缓存", onClick = actions::clearCache,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = !busy,
                )
            }
        }
        if(pageOperationCardVisible(state.operation)) {
            item(key = "operation") { OperationCard(state.operation) }
        }
        item(key = "list_heading") { SectionHeading("全部表盘（${state.faces.size}）") }
        if(state.faces.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    title = when {
                        state.listRefreshing -> "正在读取表盘"
                        state.listReady -> "手表返回了空列表"
                        else -> "等待读取表盘"
                    },
                    message = if(state.connected) "点击刷新，等待手表返回表盘列表。" else "请先前往设备页连接手表。",
                    icon = WatchIcons.Faces,
                )
            }
        } else {
            itemsIndexed(state.faces, key = { index, face -> "$index:${face.id}:${face.version}" }) { _, face ->
                val renderedEpoch = state.connectionEpoch
                FaceRow(
                    face = face, epoch = renderedEpoch, selected = state.selectedId == face.id,
                    enabled = available && face.selectable,
                    onSelect = {
                        if(owner.get()) {
                            val current = latestState
                            val feedback = faceSelectionFeedback(state) ?: faceSelectionFeedback(current)
                                ?: if(!face.selectable) "该类型表盘不可替换或删除"
                                else if(current.connectionEpoch != renderedEpoch || current.faces.count {
                                    it.id == face.id && it.version == face.version && it.selectable
                                } != 1 || current.faces.count { it.id == face.id } != 1) "请刷新表盘列表"
                                else null
                            if(feedback != null) Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                            else actions.selectFace(face.id)
                        }
                    },
                )
            }
        }
    }
}

/** 同一源的旧展开帧不能再次翻转状态；完整元数据改变也视为换源。 */
private fun sourceToggleCurrent(
    renderedSource: SourceInfo?,
    latestSource: SourceInfo?,
    renderedExpanded: Boolean,
    latestExpanded: Boolean,
): Boolean = renderedSource != null && renderedSource == latestSource && renderedExpanded == latestExpanded

/** 过期列表仍可点击查看原因，但此路径不会选择条目、刷新或提交表盘操作。 */
private fun faceSelectionFeedback(state: AppUiState): String? = when {
    state.unpairWorking || UiPolicy.isUnpairTarget(state, state.deviceAddress) -> "解除配对处理中，请稍候"
    !state.connected -> "请先前往设备页连接手表"
    state.quarantine -> "请重新连接后刷新表盘列表"
    state.operation.active || state.operationDialog != null || state.pickingHwt || state.connecting || state.prompt != null || state.needsPin -> "操作进行中，请保持连接并等待。"
    state.listRefreshing -> "正在刷新表盘列表，请稍候"
    !state.listReady -> "请刷新表盘列表"
    else -> null
}

@Composable
private fun FaceRow(face: FaceItem, epoch: Long, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    var expanded by rememberSaveable(epoch, face.id, face.version) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val selectedVisible = selected && enabled
    Card(
        modifier = Modifier.fillMaxWidth()
            .animateContentSize(tween(160, easing = FastOutSlowInEasing))
            .squircleClip(cornerRadius = 16.dp)
            // 禁止选择时保留点击解释，不把灰色卡片偷偷切为选中。
            .selectable(selectedVisible, enabled = true, role = Role.RadioButton, onClick = onSelect)
            .semantics { if(!enabled) stateDescription = "不可选择" },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(vertical = 8.dp),
        colors = CardDefaults.defaultColors(color = if(selectedVisible) colors.tertiaryContainer else colors.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                face.title.uiText(), modifier = Modifier.weight(1f),
                fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if(enabled) colors.onSurfaceContainer else colors.onSurfaceVariantSummary.copy(alpha = 0.6f),
            )
            if(face.current) {
                Spacer(Modifier.width(8.dp))
                Text("正在使用", fontSize = 12.sp, color = if(enabled) colors.primary else colors.onSurfaceVariantSummary.copy(alpha = 0.6f))
            }
            ExpandButton(expanded, "表盘信息", onClick = { expanded = !expanded })
        }
        if(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                ValueRow("版本", face.version.ifBlank { "手表未提供" })
                ValueRow("ID", face.id)
                ValueRow("类型", face.kind)
                if(!face.selectable) MutedText("该类型表盘不可替换或删除")
            }
        }
    }
}

@Composable
private fun ExpandButton(expanded: Boolean, label: String, onClick: () -> Unit) {
    val angle by animateFloatAsState(if(expanded) 180f else 0f, tween(160), label = "展开箭头")
    IconButton(onClick = onClick) {
        Icon(
            imageVector = WatchIcons.Expand,
            contentDescription = (if(expanded) "收起" else "展开") + label,
            modifier = Modifier.size(24.dp).rotate(angle),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SourceDetails(source: SourceInfo, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueRow("源文件大小", formatBytes(source.sourceBytes))
        ValueRow("待传输内容", formatBytes(source.payloadBytes.toLong()))
        ValueRow("文件版本", source.xmlVersion.ifBlank { "未提供" })
        ValueRow("SHA-256", source.sha256.take(16) + "…")
    }
}
