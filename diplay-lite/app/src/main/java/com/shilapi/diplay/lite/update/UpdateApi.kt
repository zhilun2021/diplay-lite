package com.shilapi.diplay.lite.update

import com.shilapi.diplay.lite.LiteLog
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** vc248：一次检查更新的结果（来源 = Gitee 公开仓 `releases/latest`，单次请求）。 */
internal class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val sha256: String,
)

/** vc248：检查更新的用户可见失败（message 直接进界面，一屏一句人话 + 下一步）。 */
internal class UpdateCheckException(val userMessage: String) : Exception(userMessage)

internal object UpdateApi {
    /**
     * vc250（VC250-04）：版本名提取，容忍 `1.5.0-vc249`、`1.5.0（vc249）`、`1.5.0(vc249)`
     * 三种写法；命中的统一写成 `1.5.0-vc249`（落盘名/弹窗都不再出现全角括号）。
     * 三段数字 + `vc<code>` 的旧写法结果与 vc248 完全一致；纯 `vc249` 标题不命中，仍回落 tag。
     */
    private val VERSION_NAME = Regex("""(\d+\.\d+\.\d+)[\s\-（(]*vc(\d+)""")
    /**
     * 单次匿名 GET `releases/latest`：解析版本号、更新内容、APK 资产直链。
     * 失败抛 [UpdateCheckException]；调用方只呈现一次结果，不重试。
     */
    fun fetchLatest(): ReleaseInfo {
        val startedAt = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(UpdateConfig.API_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MILLIS
                readTimeout = UpdateConfig.READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DiPlayLite-updater")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val body = readLimited(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (System.currentTimeMillis() - startedAt > UpdateConfig.CHECK_TOTAL_MILLIS) {
                throw UpdateCheckException("检查更新超时，请稍后重试")
            }
            LiteLog.log("update", "releases/latest -> $code, ${body.length} bytes")
            return when {
                code == 200 -> parse(body)
                code == 404 -> throw UpdateCheckException(
                    "更新通道暂无发行版，请到发行页手动下载：${UpdateConfig.RELEASE_PAGE}",
                )
                code == 403 || code == 429 -> throw UpdateCheckException(
                    "更新服务繁忙，请稍后再试；也可到发行页手动下载：${UpdateConfig.RELEASE_PAGE}",
                )
                else -> {
                    LiteLog.log("update", "releases/latest 异常响应 $code")
                    throw UpdateCheckException(
                        "更新服务暂时不可用，请稍后重试；也可到发行页手动下载：${UpdateConfig.RELEASE_PAGE}",
                    )
                }
            }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: SocketTimeoutException) {
            LiteLog.log("update", "检查更新超时：${e.message}")
            throw UpdateCheckException("检查更新超时，请稍后重试")
        } catch (e: IOException) {
            LiteLog.log("update", "检查更新网络失败：${e.message}")
            throw UpdateCheckException(
                "无法连接更新服务器：请检查网络，或到发行页手动下载：${UpdateConfig.RELEASE_PAGE}",
            )
        } catch (e: Exception) {
            LiteLog.log("update", "检查更新异常：${e.message}")
            throw UpdateCheckException("检查更新失败，请稍后重试")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        input.use { stream ->
            val buf = ByteArray(4096)
            val out = StringBuilder()
            var total = 0
            while (total < UpdateConfig.MAX_JSON_BYTES) {
                val n = stream.read(buf)
                if (n <= 0) break
                out.append(String(buf, 0, n, Charsets.UTF_8))
                total += n
            }
            return out.toString()
        }
    }

    private fun parse(body: String): ReleaseInfo {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw UpdateCheckException("更新服务返回异常，请稍后重试")
        }
        val tag = json.optString("tag_name", "")
        val name = json.optString("name", "")
        val versionCode = versionCodeOf(tag) ?: versionCodeOf(name)
            ?: throw UpdateCheckException("发行信息不完整，请到发行页手动下载：${UpdateConfig.RELEASE_PAGE}")
        // vc250（VC250-04）：比较逻辑不变（仍比 versionCode）；仅版本名提取放宽分隔符。
        val versionName = VERSION_NAME.find("$name $tag")?.let {
            "${it.groupValues[1]}-vc${it.groupValues[2]}"
        } ?: tag
        val changelog = json.optString("body", "").trim().let {
            if (it.length > 2000) it.substring(0, 2000) else it
        }
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets?.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isBlank()) {
            throw UpdateCheckException("发行信息不完整（缺少安装包），请到发行页手动下载：${UpdateConfig.RELEASE_PAGE}")
        }
        val sha256 = Regex("""SHA-256[：:\s]*([0-9A-Fa-f]{64})""")
            .find(changelog)?.groupValues?.get(1)?.uppercase() ?: ""
        return ReleaseInfo(versionCode, versionName, changelog, apkUrl, sha256)
    }

    private fun versionCodeOf(text: String): Int? =
        Regex("""vc(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
}
