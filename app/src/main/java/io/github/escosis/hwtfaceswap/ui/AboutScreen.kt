package io.github.escosis.hwtfaceswap.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escosis.hwtfaceswap.R
import io.github.escosis.hwtfaceswap.UpdateChecker
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val githubUrl = UpdateChecker.REPOSITORY_URL

    CollapsingPage(
        title = "关于",
        fullscreen = true,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_backspace),
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    ) { padding, scrollModifier ->
        Column(
            modifier = scrollModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .consumeWindowInsets(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "腕镜应用图标",
                modifier = Modifier.size(128.dp),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "腕镜",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = versionName?.let { "v$it" } ?: "版本未知",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "利用替换法为华为手表安装第三方 HWT 表盘的工具，面向绝大多数采用兼容表盘协议的 GT、FIT 等设备，也为直接传输失灵的新鸿蒙版本提供既有资源替换路径。支持连接、读取表盘、替换与删除；具体兼容性以设备型号和固件实现为准。华为 ID 与认证资料按设备加密保存在本机，更新检查通过 GitHub 完成。",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            Spacer(Modifier.height(32.dp))

            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            ) {
                ArrowPreference(
                    title = "项目仓库",
                    summary = "Escosis/WristMirror",
                    startAction = {
                        Icon(
                            painter = painterResource(R.drawable.ic_github_mono),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp).size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                        } catch (_: Exception) {
                            Toast.makeText(context, "打开链接失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
