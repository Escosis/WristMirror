package io.github.escosis.hwtfaceswap

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** 已校验的正式发布；没有 APK 资产时下载链接为空，仍可打开发布页。 */
data class UpdateInfo(
    val tagName: String,
    val version: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

/** 只有 404 表示尚未发布；有效发布是否较新由调用者结合当前版本判断。 */
sealed class UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult()
    object NoRelease : UpdateResult()
    data class Failure(val message: String) : UpdateResult()
}

object UpdateChecker {
    const val REPOSITORY_URL = "https://github.com/Escosis/WristMirror"
    const val RELEASES_URL = "https://api.github.com/repos/Escosis/WristMirror/releases/latest"
    private const val TIMEOUT_MS = 15_000

    /** 阻塞检查，调用者应放在 IO 协程；连接、响应头和响应体共用十五秒预算。 */
    fun fetchLatest(): UpdateResult = fetchLatest(
        connectionFactory = { URI(RELEASES_URL).toURL().openConnection() as HttpsURLConnection },
    )

    /** 任一版本不符合三段数字格式时返回 false，不猜测或截断非法版本。 */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = UpdateVersion.parse(remoteVersion) ?: return false
        val current = UpdateVersion.parse(currentVersion) ?: return false
        return remote > current
    }

    /** 仅供离线测试注入连接与短时限，不改变公开接口或真实网络地址。 */
    internal fun fetchLatest(
        connectionFactory: () -> HttpsURLConnection,
        timeoutMillis: Int = TIMEOUT_MS,
    ): UpdateResult {
        require(timeoutMillis in 1..TIMEOUT_MS)
        if (Thread.currentThread().isInterrupted) return UpdateResult.Failure("检查更新已取消")
        val request = ReleaseRequest(connectionFactory, timeoutMillis)
        val task = FutureTask<UpdateResult> { request.fetch() }
        val worker = Thread(task, "腕镜更新检查").apply { isDaemon = true }
        return try {
            worker.start()
            task.get(request.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            request.cancel()
            UpdateResult.Failure("检查更新超时，请稍后重试")
        } catch (_: SocketTimeoutException) {
            task.cancel(true)
            request.cancel()
            UpdateResult.Failure("检查更新超时，请稍后重试")
        } catch (_: InterruptedException) {
            task.cancel(true)
            request.cancel()
            Thread.currentThread().interrupt()
            UpdateResult.Failure("检查更新已取消")
        } catch (_: ExecutionException) {
            UpdateResult.Failure("检查更新失败，请稍后重试")
        } catch (_: SecurityException) {
            task.cancel(true)
            request.cancel()
            UpdateResult.Failure("无法联网检查更新，请稍后重试")
        }
    }

    private class ReleaseRequest(
        private val connectionFactory: () -> HttpsURLConnection,
        private val timeoutMillis: Int,
    ) {
        private val startedAt = System.nanoTime()
        private val cancelled = AtomicBoolean(false)
        private val activeConnection = AtomicReference<HttpsURLConnection?>(null)
        private val activeInput = AtomicReference<InputStream?>(null)

        fun remainingMillis(): Int {
            val remaining = timeoutMillis * 1_000_000L - (System.nanoTime() - startedAt)
            if (cancelled.get() || Thread.currentThread().isInterrupted || remaining <= 0L) {
                throw SocketTimeoutException()
            }
            return ((remaining + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
        }

        fun fetch(): UpdateResult {
            var connection: HttpsURLConnection? = null
            return try {
                val opened = connectionFactory()
                connection = opened
                activeConnection.set(opened)
                remainingMillis()
                if (opened.url.toExternalForm() != RELEASES_URL) {
                    return UpdateResult.Failure("更新服务地址不安全，已停止检查")
                }
                opened.apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = remainingMillis()
                    readTimeout = remainingMillis()
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "WristMirror-UpdateChecker")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                opened.connect()
                opened.readTimeout = remainingMillis()
                val status = opened.responseCode
                remainingMillis()
                when (status) {
                    404 -> return UpdateResult.NoRelease
                    403, 429 -> return UpdateResult.Failure("更新服务访问受限，请稍后重试")
                    in 300..399 -> return UpdateResult.Failure("更新服务发生跳转，已停止检查")
                    in 500..599 -> return UpdateResult.Failure("更新服务暂时不可用，请稍后重试")
                    200 -> Unit
                    else -> return UpdateResult.Failure("检查更新失败（HTTP $status），请稍后重试")
                }
                val encoding = opened.contentEncoding
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                    return UpdateResult.Failure("更新服务返回了不支持的响应格式")
                }
                if (opened.contentLengthLong > UpdateReleaseParser.MAX_RESPONSE_BYTES) {
                    return UpdateResult.Failure("更新响应过大，已停止检查")
                }
                opened.readTimeout = remainingMillis()
                val body = opened.inputStream.use { input ->
                    activeInput.set(input)
                    try {
                        readLimited(opened, input)
                    } finally {
                        activeInput.compareAndSet(input, null)
                    }
                }
                remainingMillis()
                UpdateReleaseParser.parse(body)
            } catch (_: SocketTimeoutException) {
                UpdateResult.Failure("检查更新超时，请稍后重试")
            } catch (_: ResponseTooLargeException) {
                UpdateResult.Failure("更新响应过大，已停止检查")
            } catch (_: SSLException) {
                UpdateResult.Failure("更新服务的安全连接失败，请稍后重试")
            } catch (_: IOException) {
                UpdateResult.Failure("检查更新失败，请检查网络后重试")
            } catch (_: SecurityException) {
                UpdateResult.Failure("无法联网检查更新，请稍后重试")
            } catch (_: Exception) {
                UpdateResult.Failure("检查更新失败，请稍后重试")
            } finally {
                try {
                    closeQuietly(connection?.errorStream)
                } catch (_: Exception) {
                    // 错误响应也必须回收，清理失败不覆盖原始结果。
                }
                disconnectQuietly(connection)
                activeConnection.compareAndSet(connection, null)
            }
        }

        private fun readLimited(connection: HttpsURLConnection, input: InputStream): ByteArray {
            val limit = UpdateReleaseParser.MAX_RESPONSE_BYTES
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                connection.readTimeout = remainingMillis()
                // 最多额外读取一个字节来判断超限，不能无界读完响应。
                val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
                remainingMillis()
                if (count == -1) break
                if (count == 0) continue
                total += count
                if (total > limit) throw ResponseTooLargeException()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        fun cancel() {
            cancelled.set(true)
            val connection = activeConnection.getAndSet(null)
            val input = activeInput.getAndSet(null)
            if (connection == null && input == null) return
            // 部分平台的流关闭可能阻塞，连接回收不能拖延十五秒的返回时限。
            Thread({
                disconnectQuietly(connection)
                closeQuietly(input)
            }, "腕镜更新连接回收").apply { isDaemon = true }.start()
        }
    }

    private class ResponseTooLargeException : IOException()

    private fun closeQuietly(input: InputStream?) {
        try {
            input?.close()
        } catch (_: Exception) {
            // 清理异常不覆盖检查结果。
        }
    }

    private fun disconnectQuietly(connection: HttpsURLConnection?) {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
            // 清理异常不覆盖检查结果。
        }
    }
}
