package com.shilapi.diplay.lite.update

import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.shilapi.diplay.lite.LiteLog
import java.io.File

/**
 * vc248（FEAT-247-01 §7）：API16-23 用 `file://`，API24+ 用自定义 provider 的 `content://`；
 * 拉起系统安装器，不做静默安装。返回空串 = 已拉起；非空 = 需要用户先处理的提示（直接展示）。
 */
internal object UpdateInstaller {
    private const val MIME_APK = "application/vnd.android.package-archive"

    fun install(activity: Activity, apk: File): String {
        if (Build.VERSION.SDK_INT >= 26 && !canRequestPackageInstalls(activity)) {
            return guideUnknownSources(activity, apk)
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) {
            ApkContentProvider.uriFor(activity, apk)
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(apk)
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (tryStart(activity, view)) return ""
        val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (tryStart(activity, legacy)) return ""
        LiteLog.log("update", "未找到系统安装器：${apk.absolutePath}")
        return "未找到系统安装器，请用文件管理器手动安装：${apk.absolutePath}"
    }

    @TargetApi(26)
    private fun canRequestPackageInstalls(activity: Activity): Boolean =
        activity.packageManager.canRequestPackageInstalls()

    @TargetApi(26)
    private fun guideUnknownSources(activity: Activity, apk: File): String {
        val perApp = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        if (tryStart(activity, perApp)) {
            return "请允许本应用安装未知应用，然后重新点击安装。安装包已保存：${apk.absolutePath}"
        }
        val security = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        if (tryStart(activity, security)) {
            return "请在系统设置中开启「未知来源」，然后重新点击安装。安装包已保存：${apk.absolutePath}"
        }
        return "请在系统设置中允许安装未知应用，再用文件管理器安装：${apk.absolutePath}"
    }

    private fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        LiteLog.log("update", "安装意图不可用：${intent.action}")
        false
    } catch (e: Exception) {
        LiteLog.log("update", "安装意图失败：${e.message}")
        false
    }
}
