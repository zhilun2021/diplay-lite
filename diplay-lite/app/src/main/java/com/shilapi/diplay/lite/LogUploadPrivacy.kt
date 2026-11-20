package com.shilapi.diplay.lite

import java.util.Locale

/**
 * vc277（1.5.5 发布）：恢复上传件脱敏。
 *
 * vc269 为 MX6 无线取证临时置 false（要看清 Bonjour 广播的 A 记录是哪个网卡的地址），
 * 取证已于 2026-11-14 结束（结论：iPhone 不依赖广播，断点是 0x4301 缺 channel 字段），
 * 发布包必须恢复打码。
 */
internal const val UPLOAD_REDACTION_ENABLED = true

/**
 * vc248（VC248-02）：上传副本脱敏。
 *
 * 只作用于**上传件**（落入磁盘待上传的那一份）；本地运行日志与设置页导出保持原文。
 * 幂等：已打过码的值再次经过不会二次变形（`#`、`***` 作为已处理标记）。
 * 哈希为 FNV-1a 32 位取低 16 位，同一文件内与跨文件均可对齐。
 */
internal object LogRedactor {

    // vc250（VC250-04）：键名 `name=` 收进 group1 一并原样输出，只对值打码（此前 value 组把
    // 键名也吃进去，打成 `na***#xxxx`）。
    private val NAME_FIELD = Regex(
        "(?i)((?:\\s|^)name=)([^\\r\\n]+?)" +
            "(?=\\s+(?:deviceId|model|address|localBt|btMac|sole|wifiMac|bssid|ssid|rssi|ip)=" +
            "|\\s*$)",
    )
    private val DEVICE_ID_FIELD = Regex("(?i)(deviceId=)([^\\s,;&\"']+)")
    /**
     * 设备码落点（vc249 冒号纯 MAC；vc250~324 横杠 MAC + ROM 指纹；vc325 起 ROM 指纹 + 8 位实例码）。
     *
     * 只放行「设备码」这一处：日志头 `设备码：t3p1-eng442kot49h201-a1b2c3d8`、启动日志
     * `设备码 t3p1-eng442kot49h201-a1b2c3d8`。做法是先把这处整串换成不可命中的占位符，
     * 跑完其余脱敏规则后还原——这样**只有设备码保留原文**，同一份日志里其它位置的 MAC
     * （`btMac=`、`address=`、任何裸 MAC）仍按原规则打码，不会因为这次放开而整份外露。
     *
     * 三个分支都要保留（三代日志通吃）：
     * - 分支 1：vc250~324 `22-22-0B-E8-E6-92-t3p1-20181203`（横杠 MAC + 可选 ROM 段）；
     * - 分支 2：vc249 `22:22:0B:E8:E6:92`（冒号纯 MAC，按前缀匹配对账）；
     * - 分支 3：vc325 起 `t3p1-eng442kot49h201-a1b2c3d8`（无 MAC 段，小写字母数字 + 至少一段
     *   横杠），同时兼容未来 ROM 段横杠数变化。
     *
     * 三种写法都要求到行尾（允许末尾带「（bt=ok）」这类来源标注）。
     *
     * 为什么开这个白名单：用户明确选择明文设备码以便与清单直接对账；vc325 去 MAC 后，
     * 上传件里的设备码段本就不含蓝牙地址，这是预期副收益，不因此额外收紧其它规则。
     * `deviceId=` 是安装身份（重装即变），不在放行范围，照常打码。
     */
    private val DEVICE_CODE_MARK = Regex(
        "(?m)(设备码[：:]?\\s*)" +
            // 分支 1：VC250-02 横杠 MAC + 可选 `-<ROM 指纹>`。
            "((?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}(?:-[A-Za-z0-9-]+)?" +
            // 分支 2：vc249 冒号纯 MAC。
            "|(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" +
            // 分支 3：vc325 起 ROM 指纹 + 8 位实例码（无 MAC）。
            "|[a-z0-9]+(?:-[a-z0-9]+)+)" +
            // 必须到行尾（允许末尾带「（bt=ok）」这类来源标注）才算设备码落点；
            // 句中顺势写出「设备码 MAC 其他内容」不豁免，避免这条白名单被当成通用漏口。
            "(?=\\s*(?:（[^）]*）)?\\s*$)",
    )
    private const val GUARD_OPEN = "\u0001dc"
    private const val GUARD_CLOSE = "\u0001"
    private val SSID_FIELD = Regex("(?i)(ssid=)([^\\s,;&\"']+)")
    // vc254：带引号写法（如 `ssid='7205'`、`name="iPhone"`）此前整体漏过——上面的通用规则把
    // 引号排除在值字符集外，导致这类值根本匹配不上。这里先按引号分支打码，再走通用分支。
    private val SSID_QUOTED = Regex("(?i)(ssid=)([\"'])([^\"'\\r\\n]{0,64})([\"'])")
    private val NAME_QUOTED = Regex("(?i)((?:\\s|^)name=)([\"'])([^\"'\\r\\n]{0,64})([\"'])")
    // vc255：全角引号（“…” / ‘…’）同样是成对写法（如 `name=“华”的 iPhone`），此前整段落进
    // 通用分支、右引号被吞导致引号不配对。全角开闭是「异形」字符，无法像 ASCII 那样用同一个
    // 字符类兜住，这里按「开引号 + 引号内值 + 闭引号」整体匹配：开闭引号原样保留，只替换值；
    // ssid / name 共用一条（键名仍从 group1 取，按 3 段/2 段前缀分别打码）。
    private val QUOTED_FIELD_FULLWIDTH = Regex(
        "(?i)((?:\\s|^)(?:ssid|name)=)([“‘])([^“‘”’\\r\\n]{0,64})([”’])",
    )
    // vc254：IPv4 保留前两段（`192.168.*.*`），避免上传件暴露内网主机位；已打码的值不再命中。
    private val IPV4 = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")
    private val PASSPHRASE_FIELD = Regex("(?i)(passphrase=)([^\\s,;&\"']+)")
    // vc250（VC250-04）：`=` 收进 group1，键名与等号原样保留（此前等号落在分组外被吞）。
    private val BT_FIELD = Regex(
        "(?i)((?:address|localBt|btMac|sole|btaddr)=)" +
            "((?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{12})",
    )
    private val MAC_GENERIC = Regex("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

    /** 整体脱敏：对上传件正文调用一次即可。 */
    fun redact(text: String): String {
        // vc269：取证包直接放行原文（见 UPLOAD_REDACTION_ENABLED 注释）。
        if (!UPLOAD_REDACTION_ENABLED) return text
        // 先给「设备码」落点的整串（MAC + 可选 ROM 指纹）打上占位符，全量脱敏后再还原
        // （原因见 DEVICE_CODE_MARK 注释）。
        val deviceCodes = ArrayList<String>()
        var out = DEVICE_CODE_MARK.replace(text) { match ->
            deviceCodes.add(match.groupValues[2])
            match.groupValues[1] + GUARD_OPEN + (deviceCodes.size - 1) + GUARD_CLOSE
        }
        out = replaceValues(BT_FIELD, out) { it }
        out = replaceValues(DEVICE_ID_FIELD, out) { value ->
            if (value.contains("#")) value else "dev#" + tag(value)
        }
        // vc254：引号写法先处理（通用分支的字符集排除了引号，之前整类漏网）。
        out = replaceQuoted(SSID_QUOTED, out) { value ->
            if (value.contains("***")) value else prefix(value, 3) + "***"
        }
        out = replaceQuoted(NAME_QUOTED, out) { value ->
            if (value.contains("***")) value else prefix(value, 2) + "***#" + tag(value)
        }
        // vc255：全角引号分支（“…”/‘…’），保留成对引号，只替换引号内的值。
        out = QUOTED_FIELD_FULLWIDTH.replace(out) { match ->
            val head = match.groupValues[1]
            val value = match.groupValues[3]
            val masked = if (head.contains("ssid=", ignoreCase = true)) {
                if (value.contains("***")) value else prefix(value, 3) + "***"
            } else {
                if (value.contains("***")) value else prefix(value, 2) + "***#" + tag(value)
            }
            head + match.groupValues[2] + masked + match.groupValues[4]
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
        // vc254：IPv4 只保留前两段，避免暴露内网主机位（`192.168.*.*`）；非法/已打码的值原样返回。
        out = IPV4.replace(out) { match ->
            val parts = (1..4).map { match.groupValues[it].toIntOrNull() ?: -1 }
            if (parts.any { it < 0 || it > 255 }) {
                match.value
            } else {
                match.groupValues[1] + "." + match.groupValues[2] + ".*.*"
            }
        }
        // 还原设备码落点原文（MAC 段 + 可选 ROM 指纹）：整份日志里只有这一处豁免。
        for (index in deviceCodes.indices) {
            out = out.replace(GUARD_OPEN + index + GUARD_CLOSE, deviceCodes[index])
        }
        return out
    }

    /** 逐行脱敏（dongle 上传件在截断前调用，保证 -old.txt 与裁剪段同样被覆盖）。 */
    fun redactLines(lines: MutableList<String>) {
        if (!UPLOAD_REDACTION_ENABLED) return
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

    /** 引号分支：保留键名 + 引号，只对引号内的值打码。 */
    private fun replaceQuoted(
        regex: Regex,
        text: String,
        transform: (String) -> String,
    ): String = regex.replace(text) { match ->
        match.groupValues[1] + match.groupValues[2] + transform(match.groupValues[3]) +
            match.groupValues[4]
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
