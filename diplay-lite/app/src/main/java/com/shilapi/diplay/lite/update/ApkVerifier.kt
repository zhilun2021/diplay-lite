package com.shilapi.diplay.lite.update

import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.diplay.lite.BuildConfig
import com.shilapi.diplay.lite.LiteLog
import java.io.File
import java.security.MessageDigest

/**
 * vc248：安装前四道校验（硬约束 H2/H3/H4）。
 *
 * 顺序：SHA-256 完整性 → 包名 → 签名证书指纹（固定值）→ versionCode 严格大于当前 & 与发布值一致。
 * 任一不符：拒绝安装并删除 APK；提示直接进界面。
 */
internal object ApkVerifier {
    class Result(val ok: Boolean, val message: String)

    fun verify(context: Context, apk: File, expectedVersionCode: Int, expectedSha256: String): Result {
        if (expectedSha256.isBlank()) {
            return reject(apk, "发行信息不完整（缺少校验值），请到发行页手动下载：${UpdateConfig.RELEASE_PAGE}")
        }
        val actualSha = UpdateDownloader.sha256Of(apk)
        if (!expectedSha256.equals(actualSha, ignoreCase = true)) {
            LiteLog.log("update", "校验失败：sha=$actualSha expected=$expectedSha256")
            return reject(apk, "下载校验失败，文件可能损坏，请重试")
        }

        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        if (archive == null) return reject(apk, "无法解析安装包，已阻止安装")
        if (archive.packageName != UpdateConfig.APK_PACKAGE_NAME) {
            return reject(apk, "安装包不是 DiPlay Lite，已阻止安装")
        }

        @Suppress("DEPRECATION")
        val signatures = archive.signatures
        if (signatures == null || signatures.size != 1) {
            return reject(apk, "安装包签名校验失败，已阻止安装")
        }
        val certSha = MessageDigest.getInstance("SHA-256")
            .digest(signatures[0].toByteArray())
            .joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        if (certSha != UpdateConfig.EXPECTED_CERT_SHA256) {
            LiteLog.log("update", "证书指纹不符：$certSha")
            return reject(apk, "安装包签名校验失败，已阻止安装")
        }

        @Suppress("DEPRECATION")
        val archiveCode = archive.versionCode
        if (archiveCode != expectedVersionCode) {
            LiteLog.log("update", "版本不一致：apk=$archiveCode update=$expectedVersionCode")
            return reject(apk, "安装包版本与发布信息不一致，已阻止安装")
        }

        @Suppress("DEPRECATION")
        val installedCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            BuildConfig.VERSION_CODE
        }
        if (archiveCode <= installedCode) {
            return reject(apk, "服务器版本不高于当前版本，已取消")
        }
        LiteLog.log("update", "校验通过：vc=$archiveCode sha=$actualSha")
        return Result(true, "")
    }

    private fun reject(apk: File, message: String): Result {
        runCatching { apk.delete() }
        return Result(false, message)
    }
}
