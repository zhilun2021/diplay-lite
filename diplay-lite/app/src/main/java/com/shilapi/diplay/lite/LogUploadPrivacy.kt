package com.shilapi.diplay.lite

import java.util.Locale

/**
 * vc248（VC248-02）：上传副本脱敏。
 *
 * 只作用于**上传件**（落入磁盘待上传的那一份）；本地运行日志与设置页导出保持原文。
 * 幂等：已打过码的值再次经过不会二次变形（`#`、`***` 作为已处理标记）。
 * 哈希为 FNV-1a 32 位取低 16 位，同一文件内与跨文件均可对齐。
 */
internal object LogRedactor {

    private val NAME_FIELD = Regex(
        "(?i)(\\s|^)(name=)([^\\r\\n]+?)" +
            "(?=\\s+(?:deviceId|model|address|localBt|btMac|sole|wifiMac|bssid|ssid|rssi|ip)=" +
            "|\\s*$)",
    )
    private val DEVICE_ID_FIELD = Regex("(?i)(deviceId=)([^\\s,;&\"']+)")
    private val SSID_FIELD = Regex("(?i)(ssid=)([^\\s,;&\"']+)")
    private val PASSPHRASE_FIELD = Regex("(?i)(passphrase=)([^\\s,;&\"']+)")
    private val BT_FIELD = Regex(
        "(?i)(address|localBt|btMac|sole|btaddr)=" +
            "((?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{12})",
    )
    private val MAC_GENERIC = Regex("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

    /** 整体脱敏：对上传件正文调用一次即可。 */
    fun redact(text: String): String {
        var out = text
        out = replaceValues(BT_FIELD, out) { it }
        out = replaceValues(DEVICE_ID_FIELD, out) { value ->
            if (value.contains("#")) value else "dev#" + tag(value)
        }
        out = replaceValues(SSID_FIELD, out) { value ->
            if (value.contains("***")) value else prefix(value, 3) + "***"
        }
        out = replaceValues(PASSPHRASE_FIELD, out) { "<redacted>" }
        out = replaceValues(NAME_FIELD, out) { value ->
            if (value.contains("***")) value else prefix(value, 2) + "***#" + tag(value)
        }
        out = MAC_GENERIC.replace(out) { match ->
            val mac = match.value
            val sep = if (mac.contains(":")) ":" else "-"
            val parts = mac.split(if (sep == ":") ":" else "-")
            if (parts.size != 6) mac else {
                parts.take(3).joinToString(sep) + sep + "**" + sep + "**" + sep + "**#" +
                    tag(mac.replace("-", ":").lowercase(Locale.US))
            }
        }
        return out
    }

    /** 逐行脱敏（dongle 上传件在截断前调用，保证 -old.txt 与裁剪段同样被覆盖）。 */
    fun redactLines(lines: MutableList<String>) {
        for (index in lines.indices) lines[index] = redact(lines[index])
    }

    /** 蓝牙地址统一成 `bdaddr#xxxx`。 */
    private fun replaceValues(
        regex: Regex,
        text: String,
        transform: (String) -> String,
    ): String = regex.replace(text) { match ->
        val head = match.groupValues[1]
        val value = match.groupValues[2]
        head + transform(value)
    }

    private fun prefix(value: String, keep: Int): String =
        if (value.length <= keep) value else value.substring(0, keep)

    /** 稳定短哈希：FNV-1a 32 位 → 低 16 位 → 4 位十六进制。 */
    fun tag(value: String): String {
        var hash = 2166136261u.toInt() // FNV offset basis 0x811C9DC5
        for (ch in value.lowercase(Locale.US)) {
            hash = hash xor ch.code
            hash *= 16777619
        }
        return String.format(Locale.US, "%04x", hash and 0xFFFF)
    }
}

/**
 * vc248（VC248-13）：上传失败可诊断。
 *
 * 把 Gitee 返回的状态码翻译成可读原因，并附必要响应摘要；任何输出都不得包含
 * token 或 `access_token=` 原文。
 */
internal object UploadFailure {

    fun describe(code: Int, body: String, token: String): String {
        val reason = when (code) {
            400 -> "请求格式被拒绝"
            401 -> "令牌无效或已吊销，需更换 GITEE_LOG_TOKEN"
            403 -> "令牌无权访问该仓，或授权仓与代码目标仓不一致（需单仓 + projects）"
            404 -> "仓库或路径不存在（检查仓名与分支 master）"
            409 -> "同名文件已存在（已尝试 -1 重试）"
            413 -> "上传件超过仓库单文件限制"
            422 -> "参数不合法（检查文件名与分支）"
            429 -> "请求过于频繁，稍后重试"
            in 500..599 -> "Gitee 服务端错误，稍后重试"
            else -> "上传被拒绝"
        }
        val summary = scrub(body, token)
        return if (summary.isEmpty()) {
            "HTTP $code：$reason"
        } else {
            "HTTP $code：$reason｜$summary"
        }
    }

    /** 超时与网络异常同样需要清洗，避免把 URL 里的凭据带出。 */
    fun scrub(text: String, token: String): String {
        var out = text
        if (token.isNotEmpty()) out = out.replace(token, "***")
        out = out.replace(Regex("access_token=[^&\\s\"']*"), "access_token=***")
        out = out.replace(Regex("[\\r\\n]+"), " ").trim()
        return if (out.length > 160) out.take(160) + "…" else out
    }
}
