package com.shilapi.diplay.lite.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.shilapi.diplay.lite.LiteLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc248：单次 APK 下载（用户确认后才开始；无断点续传——Gitee Release asset 不支持 Range）。
 *
 * 约定：先写 `.part`，大小与 SHA-256 校验通过后才改名为最终 APK；校验不过绝不改名。
 * 取消/失败/中断都删 `.part`；下次启动由 [cleanupStale] 清理残留。
 */
internal object UpdateDownloader {
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    @Volatile
    private var active: HttpURLConnection? = null

    fun cleanupStale(context: Context) {
        UpdateConfig.updateDir(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(".part")) file.delete()
        }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { active?.disconnect() }
    }

    /**
     * 后台线程下载。
     * [onProgress]：(百分比 -1=未知, 已下载字节, 总字节)；[onResult]：(最终文件 或 null, 错误文案 或 null)。
     * 取消时 onResult(null, "已取消")。
     */
    fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Int, Long, Long) -> Unit,
        onResult: (File?, String?) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            onResult(null, "已有下载在进行中")
            return
        }
        cancelled.set(false)
        val appContext = context.applicationContext
        val target = File(UpdateConfig.updateDir(appContext), "DiPlay-${release.versionName}.apk")
        val part = File(target.absolutePath + ".part")
        Thread {
            var conn: HttpURLConnection? = null
            var input: BufferedInputStream? = null
            var output: FileOutputStream? = null
            var failure: String? = null
            try {
                if (target.exists()) target.delete()
                if (part.exists()) part.delete()

                conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = UpdateConfig.DOWNLOAD_CONNECT_TIMEOUT_MILLIS
                    readTimeout = UpdateConfig.DOWNLOAD_READ_TIMEOUT_MILLIS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                active = conn
                val code = conn.responseCode
                LiteLog.log("update", "asset GET -> $code")
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = conn.contentLength.toLong()
                if (total > UpdateConfig.MAX_APK_BYTES) throw IOException("安装包过大")
                if (total > 0) {
                    // API16：用已废弃的 int 版 StatFs（availableBlocksLong 是 API18）。
                    @Suppress("DEPRECATION")
                    val stat = StatFs(UpdateConfig.updateDir(appContext).absolutePath)
                    @Suppress("DEPRECATION")
                    val free = stat.availableBlocks.toLong() * stat.blockSize.toLong()
                    if (free < total + 8L * 1024 * 1024) {
                        throw IOException("存储空间不足：需要约 ${total / 1024 / 1024} MB，可用 ${free / 1024 / 1024} MB")
                    }
                }

                input = BufferedInputStream(conn.inputStream)
                output = FileOutputStream(part)
                val digest = MessageDigest.getInstance("SHA-256")
                val startedAt = System.currentTimeMillis()
                var lastDataAt = startedAt
                var downloaded = 0L
                var lastPct = -1
                val buf = ByteArray(8192)
                while (true) {
                    if (cancelled.get()) break
                    val now = System.currentTimeMillis()
                    if (now - startedAt > UpdateConfig.DOWNLOAD_TOTAL_MILLIS) throw IOException("下载超时")
                    if (now - lastDataAt > UpdateConfig.DOWNLOAD_STALL_MILLIS) throw IOException("下载中断")
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    downloaded += n
                    lastDataAt = System.currentTimeMillis()
                    if (downloaded > UpdateConfig.MAX_APK_BYTES) throw IOException("安装包过大")
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        main.post { onProgress(pct, downloaded, total) }
                    }
                }
                output.flush()
                output.close()
                output = null
                input.close()
                input = null

                if (cancelled.get()) {
                    failure = "已取消"
                } else {
                    val sha = digest.digest().toHex()
                    if (total > 0 && downloaded != total) {
                        failure = "下载校验失败，文件可能损坏，请重试"
                    } else if (release.sha256.isNotBlank() && !release.sha256.equals(sha, ignoreCase = true)) {
                        failure = "下载校验失败，文件可能损坏，请重试"
                    } else {
                        if (!part.renameTo(target)) failure = "下载校验失败，文件可能损坏，请重试"
                    }
                    LiteLog.log("update", "下载完成 $downloaded/$total sha=$sha")
                }
            } catch (e: IOException) {
                failure = when {
                    cancelled.get() -> "已取消"
                    e.message == "下载超时" -> "下载超时，请重试"
                    e.message == "下载中断" -> "下载中断，已清理临时文件，请重试"
                    e.message?.startsWith("存储空间不足") == true -> e.message
                    else -> "下载失败，请检查网络后重试"
                }
                LiteLog.log("update", "下载失败：${e.message}")
            } catch (e: Exception) {
                failure = if (cancelled.get()) "已取消" else "下载失败，请重试"
                LiteLog.log("update", "下载异常：${e.message}")
            } finally {
                runCatching { output?.close() }
                runCatching { input?.close() }
                runCatching { conn?.disconnect() }
                active = null
                if (failure != null && part.exists()) part.delete()
                running.set(false)
                val result = if (failure == null && target.isFile) target else null
                main.post { onResult(result, failure) }
            }
        }.apply { name = "update-download"; isDaemon = true }.start()
    }

    /** vc248：安装器拉起前再算一次 SHA-256，缩小“校验后文件被本地替换”的窗口。 */
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02X", b.toInt() and 0xFF))
        return sb.toString()
    }
}
