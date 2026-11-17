package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import io.github.escosis.hwtfaceswap.OperationInfo
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 只规范界面文字，不改变 ID、文件地址、配对码或协议输入。 */
internal fun String.uiText(): String = replace('\u00B7', ' ')

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun SectionHeading(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
        Text(
            text = title.uiText(),
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            MutedText(subtitle)
        }
    }
}

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uiText(),
        modifier = modifier,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 使用参考项目的透明边框输入框与 Miuix 配色。 */
@Composable
internal fun MiuixField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        visualTransformation = visualTransformation,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        textStyle = TextStyle(color = scheme.onSurface, fontSize = 16.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface,
            disabledTextColor = scheme.disabledOnSurface,
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.onSurfaceVariantSummary,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariantSummary,
            cursorColor = scheme.primary,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        content = content,
    )
}

@Composable
internal fun Badge(text: String, highlighted: Boolean = false, danger: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val background = when {
        danger -> colors.errorContainer
        highlighted -> colors.tertiaryContainer
        else -> colors.secondaryVariant
    }
    val foreground = when {
        danger -> colors.error
        highlighted -> colors.onTertiaryContainer
        else -> colors.onSurfaceVariantSummary
    }
    Text(
        text = text.uiText(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
internal fun IconTile(icon: ImageVector, size: Int = 24, highlighted: Boolean = true) {
    val colors = MiuixTheme.colorScheme
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(size.dp),
        tint = if (highlighted) colors.onSurface else colors.onSurfaceVariantSummary,
    )
}

/** 危险按钮使用原始 Error 色和白字；禁用由整层透明度处理，不预混背景。 */
internal fun destructiveButtonColors(primaryColors: ButtonColors, scheme: Colors): ButtonColors = primaryColors.copy(
    color = scheme.error,
    disabledColor = scheme.error,
    contentColor = Color.White,
    disabledContentColor = Color.White,
)

/** Primary 保留组件专用禁用语义，只有危险按钮禁用时整层使用 38% 不透明度。 */
internal fun actionButtonOpacity(enabled: Boolean, destructive: Boolean): Float =
    if (destructive && !enabled) 0.38f else 1f

@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val colors = when {
        destructive -> destructiveButtonColors(ButtonDefaults.buttonColorsPrimary(), scheme)
        primary -> ButtonDefaults.buttonColorsPrimary()
        else -> ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        modifier = modifier.alpha(actionButtonOpacity(enabled, destructive)),
        enabled = enabled,
        colors = colors,
        cornerRadius = 16.dp,
        minHeight = ButtonDefaults.MinHeight,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(text = text.uiText(), style = MiuixTheme.textStyles.button)
    }
}

@Composable
internal fun NoticeCard(
    text: String,
    danger: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = if (danger) colors.errorContainer else colors.tertiaryContainer,
            contentColor = if (danger) colors.error else colors.onTertiaryContainer,
        ),
    ) {
        Text(text.uiText(), fontSize = 13.sp, lineHeight = 20.sp)
        if (onDismiss != null) {
            Spacer(Modifier.height(10.dp))
            ActionButton("关闭提示", onDismiss)
        }
    }
}

@Composable
internal fun EmptyCard(title: String, message: String, icon: ImageVector = WatchIcons.Watch) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(icon, size = 56, highlighted = false)
            Text(title.uiText(), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            MutedText(message)
        }
    }
}

@Composable
internal fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MutedText(label, Modifier.weight(0.4f))
        Text(
            value.uiText(),
            modifier = Modifier.weight(0.6f),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun OperationCard(operation: OperationInfo) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = when (operation.kind) {
                    "source" -> "准备 HWT 文件"
                    "replacement" -> "替换表盘"
                    "deletion" -> "删除表盘"
                    else -> "操作状态"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (operation.progress != null) Badge("${operation.progress.coerceIn(0, 100)}%", highlighted = true)
        }
        val stageMessage = operation.detail.ifBlank { operation.stage }
        if (stageMessage.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                stageMessage.uiText(),
                color = if (operation.failed) MiuixTheme.colorScheme.error else Color.Unspecified,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        if (operation.active) {
            Spacer(Modifier.height(5.dp))
            MutedText("操作进行中，请保持连接并等待。")
        }
        if (operation.active) {
            Spacer(Modifier.height(14.dp))
            val progress = operation.progress?.coerceIn(0, 100)?.div(100f)
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.semantics {
                    progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate
                    else ProgressBarRangeInfo(progress, 0f..1f)
                },
            )
        }
    }
}
