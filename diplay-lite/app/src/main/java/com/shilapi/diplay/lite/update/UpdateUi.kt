package com.shilapi.diplay.lite.update

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.shilapi.diplay.lite.AppDialog
import com.shilapi.diplay.lite.BuildConfig
import com.shilapi.diplay.lite.LiteLog
import com.shilapi.diplay.lite.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc248（FEAT-247-01 §5.3）：检查更新的三态呈现与下载/验签/安装编排。
 *
 * 三态：有新版本（当前/最新/更新内容 + 下载）｜已是最新（一句 + 检查时间）｜检查失败（人话 + 下一步）。
 * 硬约束：只在用户点击时请求；连点只发一次；失败不自动重试；下载确认后才开始。
 */
internal object UpdateUi {
    private val main = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    fun check(activity: Activity, button: Button) {
        if (!checking.compareAndSet(false, true)) return
        button.isEnabled = false
        button.text = "检查中…"
        UpdateDownloader.cleanupStale(activity)
        Thread {
            val result = runCatching { UpdateApi.fetchLatest() }
            main.post {
                checking.set(false)
                if (activity.isFinishing) return@post
                button.isEnabled = true
                button.text = "检查更新"
                result.fold(
                    onSuccess = { release -> onRelease(activity, release) },
                    onFailure = { error ->
                        LiteLog.log("update", "检查失败：${error.message}")
                        val message = (error as? UpdateCheckException)?.userMessage
                            ?: "检查更新失败，请稍后重试"
                        showInfo(activity, "检查更新失败", message)
                    },
                )
            }
        }.apply { name = "update-check"; isDaemon = true }.start()
    }

    private fun onRelease(activity: Activity, release: ReleaseInfo) {
        val currentCode = BuildConfig.VERSION_CODE
        val currentName = BuildConfig.VERSION_NAME
        when {
            release.versionCode > currentCode -> showUpdate(activity, currentName, release)
            release.versionCode == currentCode -> showInfo(
                activity,
                "已是最新版本",
                "当前已是最新版本（$currentName）\n检查时间：${timeNow()}",
            )
            else -> showInfo(
                activity,
                "无需更新",
                "当前版本高于服务器版本（可能是测试包），不提供降级。\n" +
                    "当前 $currentName / 服务器 ${release.versionName}",
            )
        }
    }

    private fun showUpdate(activity: Activity, currentName: String, release: ReleaseInfo) {
        val changelog = release.changelog.ifBlank { "发行说明未提供" }
        AppDialog.Builder(activity)
            .setTitle("发现新版本 ${release.versionName}")
            .setMessage(
                "当前版本：$currentName\n最新版本：${release.versionName}\n\n更新内容\n$changelog",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("下载并安装") { dialog, _ ->
                dialog.dismiss()
                startDownload(activity, release)
            }
            .create()
            .show()
    }

    private fun startDownload(activity: Activity, release: ReleaseInfo) {
        if (!downloading.compareAndSet(false, true)) return
        if (release.sha256.isBlank()) {
            downloading.set(false)
            showInfo(
                activity,
                "无法下载",
                "发行信息不完整（缺少校验值），请到发行页手动下载：${UpdateConfig.RELEASE_PAGE}",
            )
            return
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val label = TextView(activity).apply {
            text = "准备下载…"
            textSize = 13f
            setTextColor(Ui.SUB)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * activity.resources.displayMetrics.density).toInt()
            },
        )
        val dialog = AppDialog.Builder(activity)
            .setTitle("正在下载 ${release.versionName}")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("取消") { _, _ -> UpdateDownloader.cancel() }
            .create()
        dialog.show()

        UpdateDownloader.download(
            activity,
            release,
            onProgress = { pct, done, total ->
                if (activity.isFinishing) return@download
                label.text = if (pct >= 0) {
                    "已下载 $pct%（${mb(done)} / ${mb(total)}）"
                } else {
                    "已下载 ${mb(done)}"
                }
                if (pct >= 0) bar.progress = pct
            },
            onResult = { file, error ->
                downloading.set(false)
                runCatching { dialog.dismiss() }
                if (activity.isFinishing) return@download
                if (error != null) {
                    if (error != "已取消") showInfo(activity, "下载失败", error)
                    return@download
                }
                if (file == null) {
                    showInfo(activity, "下载失败", "下载失败，请稍后重试")
                    return@download
                }
                val result = ApkVerifier.verify(activity, file, release.versionCode, release.sha256)
                if (!result.ok) {
                    showInfo(activity, "已阻止安装", result.message)
                    return@download
                }
                val guide = UpdateInstaller.install(activity, file)
                if (guide.isNotBlank()) showInfo(activity, "需要先允许安装", guide)
            },
        )
    }

    private fun showInfo(activity: Activity, title: String, message: String) {
        AppDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("好", null)
            .create()
            .show()
    }

    private fun timeNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
