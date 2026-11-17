package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** 全部连接都是本地替身，不访问真实发布仓库或任何设备。 */
class UpdateCheckerTest {
    @Test
    fun 更新地址固定为英文仓库名() {
        assertEquals("https://github.com/Escosis/WristMirror", UpdateChecker.REPOSITORY_URL)
        assertEquals("https://api.github.com/repos/Escosis/WristMirror/releases/latest", UpdateChecker.RELEASES_URL)
    }

    @Test
    fun 成功响应可区分新版本与当前最新版并关闭资源() {
        val connection = fake()
        val result = fetch(connection)
        assertTrue(result is UpdateResult.Available)
        val info = (result as UpdateResult.Available).info
        assertTrue(UpdateChecker.isNewer(info.version, "1.0.1"))
        assertFalse(UpdateChecker.isNewer(info.version, "1.0.10"))
        assertFalse(UpdateChecker.isNewer(info.version, "2.0.0"))
        assertTrue(connection.inputOpened)
        assertTrue((connection.input as TrackingInput).closed)
        assertClean(connection)
    }

    @Test
    fun 请求禁止跳转缓存与压缩且限时十五秒以内() {
        val connection = fake()
        fetch(connection)
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.connectTimeout in 1..15_000)
        assertTrue(connection.readTimeout in 1..15_000)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals("WristMirror-UpdateChecker", connection.getRequestProperty("User-Agent"))
    }

    @Test
    fun 未发布的404不误报网络失败() {
        val connection = fake(status = 404)
        assertSame(UpdateResult.NoRelease, fetch(connection))
        assertFalse(connection.inputOpened)
        assertClean(connection)
    }

    @Test
    fun 限流跳转及其他HTTP错误均为中文失败() {
        for (status in listOf(201, 301, 302, 307, 308, 401, 403, 429, 500, 503)) {
            val connection = fake(status = status)
            assertFailure(fetch(connection))
            assertFalse(connection.inputOpened)
            assertClean(connection)
        }
    }

    @Test
    fun 网络异常与超时不泄露原始异常信息() {
        for (error in listOf(IOException("原始异常不应展示"), SocketTimeoutException("原始异常不应展示"), SSLException("原始异常不应展示"))) {
            val connection = fake()
            connection.connectFailure = error
            val failure = assertFailure(fetch(connection))
            assertFalse(failure.message.contains("原始异常"))
            assertFalse(connection.inputOpened)
            assertClean(connection)
        }
    }

    @Test
    fun 无联网权限也返回中文失败() {
        val connection = fake()
        connection.connectFailure = SecurityException("模拟权限拒绝")
        assertFailure(fetch(connection))
        assertClean(connection)
    }

    @Test
    fun 非固定HTTPS地址在连接前被拒绝() {
        for (url in listOf(
            "https://example.com/releases/latest",
            "http://api.github.com/repos/Escosis/WristMirror/releases/latest",
            UpdateChecker.RELEASES_URL + "?redirect=1",
        )) {
            val connection = fake(url = url)
            assertFailure(fetch(connection))
            assertFalse(connection.didConnect)
            assertFalse(connection.inputOpened)
            assertClean(connection)
        }
    }

    @Test
    fun 声明过大响应在打开输入流前被拒绝() {
        val connection = fake()
        connection.declaredLength = UpdateReleaseParser.MAX_RESPONSE_BYTES.toLong() + 1L
        assertTrue(assertFailure(fetch(connection)).message.contains("过大"))
        assertFalse(connection.inputOpened)
        assertClean(connection)
    }

    @Test
    fun 未声明长度的响应最多读取限制加一个字节() {
        val input = TrackingInput(ByteArray(UpdateReleaseParser.MAX_RESPONSE_BYTES + 4096) { 0x20 })
        val connection = fake(input = input)
        connection.declaredLength = -1
        assertTrue(assertFailure(fetch(connection)).message.contains("过大"))
        assertEquals(UpdateReleaseParser.MAX_RESPONSE_BYTES + 1, input.bytesRead)
        assertTrue(input.closed)
        assertClean(connection)
    }

    @Test
    fun 刚好达到响应限制的合法JSON仍可解析() {
        val padded = validBody + ByteArray(UpdateReleaseParser.MAX_RESPONSE_BYTES - validBody.size) { 0x20 }
        val input = TrackingInput(padded)
        val connection = fake(input = input)
        connection.declaredLength = padded.size.toLong()
        assertTrue(fetch(connection) is UpdateResult.Available)
        assertEquals(padded.size, input.bytesRead)
        assertTrue(input.closed)
        assertClean(connection)
    }

    @Test
    fun 意外压缩响应不会被无界解压() {
        val connection = fake()
        connection.encoding = "gzip"
        assertFailure(fetch(connection))
        assertFalse(connection.inputOpened)
        assertClean(connection)
    }

    @Test
    fun 无效发布信息不会被当作当前最新版() {
        val connection = fake(input = TrackingInput("{\"tag_name\":\"v1.0.10-beta\",\"draft\":false,\"prerelease\":false}".toByteArray(Charsets.UTF_8)))
        assertTrue(assertFailure(fetch(connection)).message.contains("版本号"))
        assertTrue((connection.input as TrackingInput).closed)
        assertClean(connection)
    }

    @Test
    fun 响应读取失败仍关闭流和连接() {
        val input = TrackingInput(validBody, failRead = true)
        val connection = fake(input = input)
        assertFailure(fetch(connection))
        assertTrue(input.closed)
        assertClean(connection)
    }

    @Test
    fun 错误流关闭或断开异常不会覆盖成功结果() {
        val connection = fake()
        connection.errorInput = TrackingInput(byteArrayOf(), failClose = true)
        connection.failDisconnect = true
        assertTrue(fetch(connection) is UpdateResult.Available)
        assertTrue((connection.input as TrackingInput).closed)
        assertClean(connection)
    }

    @Test(timeout = 5_000)
    fun 整体时限涵盖阻塞连接且取消后回收连接() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = fake()
        connection.onConnect = {
            entered.countDown()
            awaitIgnoringInterrupts(release)
        }
        connection.onDisconnect = { release.countDown() }
        val started = System.nanoTime()
        try {
            assertTrue(assertFailure(fetch(connection, 200)).message.contains("超时"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000L)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(connection.disconnectedLatch.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 5_000)
    fun 整体时限同样涵盖阻塞响应头() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = fake()
        connection.onResponse = {
            entered.countDown()
            awaitIgnoringInterrupts(release)
        }
        connection.onDisconnect = { release.countDown() }
        try {
            assertTrue(assertFailure(fetch(connection, 200)).message.contains("超时"))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(connection.disconnectedLatch.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 5_000)
    fun 整体时限涵盖阻塞响应体且关闭活动流() {
        val input = BlockingInput()
        val connection = fake(input = input)
        try {
            assertTrue(assertFailure(fetch(connection, 200)).message.contains("超时"))
            assertTrue(input.entered.await(1, TimeUnit.SECONDS))
            assertTrue(input.closedLatch.await(1, TimeUnit.SECONDS))
            assertTrue(connection.disconnectedLatch.await(1, TimeUnit.SECONDS))
        } finally {
            input.close()
        }
    }

    @Test(timeout = 5_000)
    fun 调用线程中断时返回取消且保留中断状态() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = fake()
        connection.onConnect = {
            entered.countDown()
            awaitIgnoringInterrupts(release)
        }
        connection.onDisconnect = { release.countDown() }
        val result = AtomicReference<UpdateResult?>()
        val remainedInterrupted = AtomicBoolean(false)
        val caller = Thread({
            result.set(fetch(connection))
            remainedInterrupted.set(Thread.currentThread().isInterrupted)
        }, "更新取消测试").apply { isDaemon = true }
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertTrue(assertFailure(result.get()).message.contains("取消"))
            assertTrue(remainedInterrupted.get())
            assertTrue(connection.disconnectedLatch.await(1, TimeUnit.SECONDS))
        } finally {
            caller.interrupt()
            release.countDown()
        }
    }

    @Test(timeout = 5_000)
    fun 已中断的调用不会启动网络请求() {
        val connection = fake()
        val result = AtomicReference<UpdateResult?>()
        val remainedInterrupted = AtomicBoolean(false)
        val caller = Thread({
            Thread.currentThread().interrupt()
            result.set(fetch(connection))
            remainedInterrupted.set(Thread.currentThread().isInterrupted)
        }, "更新预取消测试").apply { isDaemon = true }
        caller.start()
        caller.join(2_000)
        assertFalse(caller.isAlive)
        assertTrue(assertFailure(result.get()).message.contains("取消"))
        assertTrue(remainedInterrupted.get())
        assertFalse(connection.didConnect)
    }

    private fun fake(
        status: Int = 200,
        input: InputStream = TrackingInput(validBody),
        url: String = UpdateChecker.RELEASES_URL,
    ) = FakeConnection(URI(url).toURL(), status, input)

    private fun fetch(connection: FakeConnection, timeoutMillis: Int = 15_000): UpdateResult =
        UpdateChecker.fetchLatest(connectionFactory = { connection }, timeoutMillis = timeoutMillis)

    private fun assertFailure(result: UpdateResult?): UpdateResult.Failure {
        assertTrue("预期中文失败，实际结果：$result", result is UpdateResult.Failure)
        val failure = result as UpdateResult.Failure
        assertTrue(failure.message.any { it in '\u4e00'..'\u9fff' })
        return failure
    }

    private fun assertClean(connection: FakeConnection) {
        assertTrue(connection.disconnected)
        assertTrue(connection.errorInput.closed)
    }

    private class FakeConnection(
        endpoint: URL,
        private val status: Int,
        val input: InputStream,
    ) : HttpsURLConnection(endpoint) {
        @Volatile var didConnect = false
        @Volatile var inputOpened = false
        @Volatile var disconnected = false
        val disconnectedLatch = CountDownLatch(1)
        var errorInput = TrackingInput("模拟服务错误".toByteArray(Charsets.UTF_8))
        var declaredLength = -1L
        var encoding: String? = null
        var connectFailure: Exception? = null
        var failDisconnect = false
        var onConnect: (() -> Unit)? = null
        var onResponse: (() -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null

        override fun connect() {
            didConnect = true
            onConnect?.invoke()
            connectFailure?.let { throw it }
        }

        override fun disconnect() {
            disconnected = true
            onDisconnect?.invoke()
            disconnectedLatch.countDown()
            if (failDisconnect) throw IllegalStateException("模拟断开失败")
        }

        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int {
            onResponse?.invoke()
            return status
        }

        override fun getInputStream(): InputStream {
            inputOpened = true
            return input
        }

        override fun getErrorStream(): InputStream = errorInput
        override fun getContentLengthLong(): Long = declaredLength
        override fun getContentEncoding(): String? = encoding
    }

    private class TrackingInput(
        data: ByteArray,
        private val failRead: Boolean = false,
        private val failClose: Boolean = false,
    ) : ByteArrayInputStream(data) {
        @Volatile var closed = false
        var bytesRead = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failRead) throw IOException("模拟读取失败")
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }

        override fun close() {
            closed = true
            super.close()
            if (failClose) throw IOException("模拟关闭失败")
        }
    }

    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            awaitIgnoringInterrupts(release)
            return -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            release.countDown()
            closedLatch.countDown()
        }
    }

    private companion object {
        val validBody = """{"tag_name":"v1.0.10","html_url":"https://github.com/Escosis/WristMirror/releases/tag/v1.0.10","draft":false,"prerelease":false,"assets":[{"name":"WristMirror.apk","browser_download_url":"https://github.com/Escosis/WristMirror/releases/download/v1.0.10/WristMirror.apk"}]}""".toByteArray(Charsets.UTF_8)

        fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                    // 模拟中断无法立即取消的底层连接，必须依靠连接或流回收。
                }
            }
        }
    }
}
