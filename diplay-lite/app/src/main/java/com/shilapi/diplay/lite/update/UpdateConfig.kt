package com.shilapi.diplay.lite.update

import android.content.Context
import com.shilapi.diplay.lite.BuildConfig

/**
 * vc248（FEAT-247-01 最小可用版）：在线升级固定常量。
 *
 * 口径（方案-FEAT-247-01-在线升级评估-20261106.md）：
 * - 更新源 = Gitee 开源公开仓的 Release，检查/下载全程匿名，APK 内零凭据；
 * - 检查更新只由用户点击触发，一次点击=一次请求，不排队、不重试、不后台探测；
 * - 信任根是固定签名证书指纹，SHA-256 只证明传输完整。
 */
internal object UpdateConfig {
    const val REPO_OWNER = "wangliunian"
    const val REPO_NAME = "diplay-lite"

    const val API_LATEST = "https://gitee.com/api/v5/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASE_PAGE = "https://gitee.com/$REPO_OWNER/$REPO_NAME/releases"

    /** 全项目固定签名证书 SHA-256（大写，无冒号）。发布侧与 App 侧共用同一值。 */
    const val EXPECTED_CERT_SHA256 = "21703B447A96BCB57D30EFFA7E80910795631DD70525BD35D5F6B4A91F2201AE"

    /** 检查更新：连接 10s、读取 20s、总 20s。 */
    const val CONNECT_TIMEOUT_MILLIS = 10_000
    const val READ_TIMEOUT_MILLIS = 20_000
    const val CHECK_TOTAL_MILLIS = 20_000L
    const val MAX_JSON_BYTES = 64 * 1024

    /** 下载：连接 10s、单次读取 30s、总 10min；连续 30s 无新增字节按中断处理。 */
    const val DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 10_000
    const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000
    const val DOWNLOAD_TOTAL_MILLIS = 10 * 60_000L
    const val DOWNLOAD_STALL_MILLIS = 30_000L
    const val MAX_APK_BYTES = 100L * 1024 * 1024

    /** 安装包自身包名（与 applicationId 一致；用于拒绝“装错包”）。 */
    const val APK_PACKAGE_NAME = BuildConfig.APPLICATION_ID

    /** 下载目录：外部私有目录优先（API16-23 安装器需要 file:// 可读路径）。 */
    fun updateDir(context: Context): java.io.File =
        (context.getExternalFilesDir("update") ?: java.io.File(context.filesDir, "update")).apply { mkdirs() }
}
