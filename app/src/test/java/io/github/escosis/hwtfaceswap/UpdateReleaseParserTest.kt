package io.github.escosis.hwtfaceswap

import io.github.escosis.hwtfaceswap.protocol.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseParserTest {
    @Test
    fun 正式发布解析版本及首个安全的APK资产() {
        val release = release()
        release["body"] = "不应在更新弹窗展示的修改日志"
        release["assets"] = listOf(
            asset("source.zip", "https://github.com/Escosis/WristMirror/releases/download/v1.0.10/source.zip"),
            asset("WristMirror.apk", apkUrl),
            asset("WristMirror-arm64.apk", apkUrl.replace("WristMirror.apk", "WristMirror-arm64.apk")),
        )
        assertEquals(
            UpdateInfo("v1.0.10", "1.0.10", apkUrl, releaseUrl),
            available(release).info,
        )
    }

    @Test
    fun 无APK资产仍提供正式发布页() {
        val release = release()
        release["assets"] = emptyList<Any>()
        val info = available(release).info
        assertEquals("", info.downloadUrl)
        assertEquals(releaseUrl, info.releaseUrl)
    }

    @Test
    fun 大写版本前缀及APK扩展名可以解析() {
        val release = release()
        release["tag_name"] = "V1.0.10"
        release["html_url"] = releaseUrl.replace("v1.0.10", "V1.0.10")
        release["assets"] = listOf(
            asset("WristMirror.APK", apkUrl.replace("v1.0.10", "V1.0.10").replace(".apk", ".APK")),
        )
        assertEquals("1.0.10", available(release).info.version)
    }

    @Test
    fun 编码后的中文及空格文件名可以安全下载() {
        val release = release()
        val url = "https://github.com/Escosis/WristMirror/releases/download/v1.0.10/%E8%85%95%E9%95%9C%201.0.10.apk"
        release["assets"] = listOf(asset("腕镜 1.0.10.apk", url))
        assertEquals(url, available(release).info.downloadUrl)
    }

    @Test
    fun 不带前缀的正式发布标签同样可以解析() {
        val release = release()
        release["tag_name"] = "1.0.10"
        release["html_url"] = releaseUrl.replace("v1.0.10", "1.0.10")
        release["assets"] = listOf(asset("WristMirror.apk", apkUrl.replace("v1.0.10", "1.0.10")))
        assertEquals("1.0.10", available(release).info.version)
    }

    @Test
    fun 标准HTTPS端口和主机名大小写不改变安全来源() {
        val release = release()
        release["html_url"] = releaseUrl.replace("github.com", "GITHUB.COM:443")
        assertEquals(release["html_url"], available(release).info.releaseUrl)
    }

    @Test
    fun 草稿和预发行均不能视为正式发布() {
        for (key in listOf("draft", "prerelease")) {
            val release = release()
            release[key] = true
            assertFailure(encode(release))
        }
    }

    @Test
    fun 非法版本与版本后缀被完整拒绝() {
        for (tag in listOf("", "1", "v1.0", "v1.0.10-rc.1", "1.0.10+2", "01.0.10", "v2147483648.0.0", "1.0.0\n")) {
            val release = release()
            release["tag_name"] = tag
            assertFailure(encode(release))
        }
    }

    @Test
    fun 缺失或错误类型的必要发布字段被拒绝() {
        for (key in listOf("tag_name", "html_url", "draft", "prerelease", "assets")) {
            val missing = release()
            missing.remove(key)
            assertFailure(encode(missing))
            val wrongType = release()
            wrongType[key] = 1L
            assertFailure(encode(wrongType))
            val nullValue = release()
            nullValue[key] = null
            assertFailure(encode(nullValue))
        }
    }

    @Test
    fun 外部及不安全发布页均被拒绝() {
        val unsafe = listOf(
            "http://github.com/Escosis/WristMirror/releases/tag/v1.0.10",
            "javascript:alert(1)",
            "file:///Escosis/WristMirror/releases/tag/v1.0.10",
            "https://example.com/Escosis/WristMirror/releases/tag/v1.0.10",
            "https://github.com.example.com/Escosis/WristMirror/releases/tag/v1.0.10",
            "https://github.com@evil.example/Escosis/WristMirror/releases/tag/v1.0.10",
            "https://evil@github.com/Escosis/WristMirror/releases/tag/v1.0.10",
            "https://github.com:8443/Escosis/WristMirror/releases/tag/v1.0.10",
            "https://github.com/Escosis/Other/releases/tag/v1.0.10",
            "https://github.com/Other/WristMirror/releases/tag/v1.0.10",
            "https://github.com/Escosis/WristMirror/releases/tag/v1.0.9",
            "https://github.com/Escosis/WristMirror/releases/latest",
            "$releaseUrl?redirect=https://evil.example",
            "$releaseUrl#fragment",
            "$releaseUrl/../v1.0.9",
            releaseUrl.replace("/tag/", "/tag/%2e%2e/"),
            " $releaseUrl",
        )
        for (url in unsafe) {
            val release = release()
            release["html_url"] = url
            assertFailure(encode(release))
        }
    }

    @Test
    fun 外部及不安全APK链接均被拒绝() {
        val unsafe = listOf(
            apkUrl.replace("https://", "http://"),
            apkUrl.replace("github.com", "example.com"),
            apkUrl.replace("github.com", "github.com.evil.example"),
            apkUrl.replace("github.com", "github.com@evil.example"),
            apkUrl.replace("github.com", "user@github.com"),
            apkUrl.replace("github.com", "github.com:8443"),
            apkUrl.replace("WristMirror/releases", "Other/releases"),
            apkUrl.replace("v1.0.10", "v1.0.9"),
            apkUrl.replace("/download/", "/tag/"),
            apkUrl.replace("WristMirror.apk", "../WristMirror.apk"),
            apkUrl.replace("WristMirror.apk", "%2e%2e%2fWristMirror.apk"),
            apkUrl.replace("WristMirror.apk", "%5cWristMirror.apk"),
            apkUrl.replace("WristMirror.apk", "WristMirror.exe"),
            apkUrl.replace("WristMirror.apk", "Other.apk"),
            apkUrl.replace("WristMirror.apk", "WristMirror%00.apk"),
            "$apkUrl?download=1",
            "$apkUrl#fragment",
            "javascript:alert(1)",
            "",
        )
        for (url in unsafe) {
            val release = release()
            release["assets"] = listOf(asset("WristMirror.apk", url))
            assertFailure(encode(release))
        }
    }

    @Test
    fun 文件名匹配也不能放行编码分隔符和二次解码() {
        for ((name, encodedName) in listOf(
            "../WristMirror.apk" to "%2e%2e%2fWristMirror.apk",
            "\\WristMirror.apk" to "%5cWristMirror.apk",
            "%2e%2e%2fWristMirror.apk" to "%252e%252e%252fWristMirror.apk",
        )) {
            val release = release()
            release["assets"] = listOf(asset(name, apkUrl.replace("WristMirror.apk", encodedName)))
            assertFailure(encode(release))
        }
    }

    @Test
    fun 安全首资产不能掩盖后续不安全APK() {
        val release = release()
        release["assets"] = listOf(
            asset("WristMirror.apk", apkUrl),
            asset("unsafe.apk", "https://evil.example/unsafe.apk"),
        )
        assertFailure(encode(release))
    }

    @Test
    fun APK资产缺字段或类型错误被拒绝() {
        for (assets in listOf(
            listOf(null),
            listOf(7L),
            listOf(mapOf("name" to null)),
            listOf(mapOf("name" to "WristMirror.apk")),
            listOf(mapOf("name" to "WristMirror.apk", "browser_download_url" to 7L)),
        )) {
            val release = release()
            release["assets"] = assets
            assertFailure(encode(release))
        }
    }

    @Test
    fun 格式无效重复键及非整数JSON被拒绝() {
        for (json in listOf(
            "", "[]", "null", "{", "{} trailing", "{\"draft\":false,\"draft\":false}",
            "{\"number\":1.5}", "{\"number\":1e3}", "{\"bad\":\"\\uD800\"}",
        )) {
            assertFailure(json.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun 非法UTF8不能通过替换字符假解析() {
        assertFailure(byteArrayOf(0x7B, 0x22, 0xC3.toByte(), 0x28, 0x22, 0x3A, 0x30, 0x7D))
    }

    @Test
    fun 响应大小边界有明确限制() {
        val body = encode(release())
        val exact = body + ByteArray(UpdateReleaseParser.MAX_RESPONSE_BYTES - body.size) { 0x20 }
        assertTrue(UpdateReleaseParser.parse(exact) is UpdateResult.Available)
        assertFailure(exact + byteArrayOf(0x20))
    }

    @Test
    fun JSON深度和节点预算不会因无关字段被绕过() {
        val original = String(encode(release()), Charsets.UTF_8)
        val depthJson = original.dropLast(1) + ",\"extra\":" + "[".repeat(MiniJson.MAX_DEPTH + 1) + "0" + "]".repeat(MiniJson.MAX_DEPTH + 1) + "}"
        assertFailure(depthJson.toByteArray(Charsets.UTF_8))
        val nodeJson = original.dropLast(1) + ",\"extra\":[" + List(MiniJson.MAX_NODES + 1) { "0" }.joinToString(",") + "]}"
        assertFailure(nodeJson.toByteArray(Charsets.UTF_8))
    }

    private fun release(): MutableMap<String, Any?> = linkedMapOf(
        "tag_name" to "v1.0.10",
        "html_url" to releaseUrl,
        "draft" to false,
        "prerelease" to false,
        "assets" to listOf(asset("WristMirror.apk", apkUrl)),
    )

    private fun asset(name: String, url: String): Map<String, Any> = mapOf(
        "name" to name,
        "browser_download_url" to url,
    )

    private fun encode(release: Map<String, Any?>): ByteArray = MiniJson.encode(release).toByteArray(Charsets.UTF_8)

    private fun available(release: Map<String, Any?>): UpdateResult.Available {
        val result = UpdateReleaseParser.parse(encode(release))
        assertTrue("预期正式发布，实际结果：$result", result is UpdateResult.Available)
        return result as UpdateResult.Available
    }

    private fun assertFailure(body: ByteArray) {
        val result = UpdateReleaseParser.parse(body)
        assertTrue("预期拒绝不安全发布，实际结果：$result", result is UpdateResult.Failure)
        assertTrue((result as UpdateResult.Failure).message.any { it in '\u4e00'..'\u9fff' })
    }

    private val releaseUrl = "https://github.com/Escosis/WristMirror/releases/tag/v1.0.10"
    private val apkUrl = "https://github.com/Escosis/WristMirror/releases/download/v1.0.10/WristMirror.apk"
}
