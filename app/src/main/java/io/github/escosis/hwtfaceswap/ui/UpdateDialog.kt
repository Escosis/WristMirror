package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escosis.hwtfaceswap.UpdateInfo
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 仅展示版本和发布页入口；父界面负责优先显示正在操作的确认弹窗。 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = {
            Text("发现新版本", fontSize = 20.sp, color = colors.onSurface)
        },
        text = {
            Column {
                Text("当前版本：$currentVersion", color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("最新版本：${info.version}", color = colors.onSurface)
                Spacer(Modifier.height(8.dp))
                Text("可在浏览器中打开发布页并下载 APK。", color = colors.onSurface)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenReleasePage) {
                Text("打开发布页", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后", color = colors.onSurfaceVariantSummary)
            }
        },
    )
}
