package io.github.escosis.hwtfaceswap

import io.github.escosis.hwtfaceswap.protocol.MiniJson
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** 纯发布解析器，复用有大小、深度、节点数和重复键限制的 JSON 解析器。 */
internal object UpdateReleaseParser {
    const val MAX_RESPONSE_BYTES = MiniJson.MAX_BYTES

    fun parse(body: ByteArray): UpdateResult {
        if (body.size > MAX_RESPONSE_BYTES) return failure("更新响应过大，已停止检查")
        val release = try {
            MiniJson.parseObject(body)
        } catch (_: IOException) {
            return failure("发布信息无效，请稍后重试")
        } catch (_: IllegalArgumentException) {
            return failure("发布信息无效，请稍后重试")
        }

        val draft = release["draft"] as? Boolean
            ?: return failure("发布信息缺少正式版本标记")
        val prerelease = release["prerelease"] as? Boolean
            ?: return failure("发布信息缺少正式版本标记")
        if (draft) return failure("该发布为草稿，未提供正式更新")
        if (prerelease) return failure("该发布为预发行版本，未提供正式更新")

        val tagName = release["tag_name"] as? String
            ?: return failure("发布信息缺少版本号")
        val version = UpdateVersion.parse(tagName)
            ?: return failure("发布版本号无效，已停止检查")
        val releaseUrl = release["html_url"] as? String
            ?: return failure("发布信息缺少发布页链接")
        if (!isSafeReleaseUrl(releaseUrl, tagName)) {
            return failure("发布页链接不安全，已停止检查")
        }

        val assets = release["assets"] as? List<*>
            ?: return failure("发布信息缺少下载列表")
        var downloadUrl = ""
        for (entry in assets) {
            val asset = entry as? Map<*, *>
                ?: return failure("发布下载信息无效，请稍后重试")
            val name = asset["name"] as? String
                ?: return failure("发布下载信息缺少文件名")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val candidate = asset["browser_download_url"] as? String
                ?: return failure("发布下载信息缺少下载链接")
            if (!isSafeDownloadUrl(candidate, tagName, name)) {
                return failure("下载链接不安全，已停止检查")
            }
            // 所有 APK 链接都必须通过校验，避免资产排序掩盖不安全链接。
            if (downloadUrl.isEmpty()) downloadUrl = candidate
        }

        return UpdateResult.Available(
            UpdateInfo(
                tagName = tagName,
                version = version.version,
                downloadUrl = downloadUrl,
                releaseUrl = releaseUrl,
            ),
        )
    }

    private fun isSafeReleaseUrl(value: String, tagName: String): Boolean {
        val uri = safeGithubUri(value) ?: return false
        return uri.rawPath == "/Escosis/WristMirror/releases/tag/$tagName"
    }

    private fun isSafeDownloadUrl(value: String, tagName: String, name: String): Boolean {
        val uri = safeGithubUri(value) ?: return false
        val prefix = "/Escosis/WristMirror/releases/download/$tagName/"
        val rawPath = uri.rawPath ?: return false
        if (!rawPath.startsWith(prefix)) return false
        val rawName = rawPath.removePrefix(prefix)
        if (rawName.isEmpty() || '/' in rawName || '\\' in rawName) return false
        val decodedPath = uri.path ?: return false
        if (!decodedPath.startsWith(prefix)) return false
        val decodedName = decodedPath.removePrefix(prefix)
        if (decodedName != name || !decodedName.endsWith(".apk", ignoreCase = true)) return false
        // 拒绝残留百分号，避免二次解码把文件名变成跳转或路径分隔符。
        return decodedName.none { it == '/' || it == '\\' || it == '%' || it.code < 32 || it.code == 127 || it == '\uFFFD' }
    }

    private fun safeGithubUri(value: String): URI? {
        if (value.length !in 1..2048) return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.rawQuery != null || uri.rawFragment != null || uri.isOpaque
        ) return null
        return uri
    }

    private fun failure(message: String) = UpdateResult.Failure(message)
}
