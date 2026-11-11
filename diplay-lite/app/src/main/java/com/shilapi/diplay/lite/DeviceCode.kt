package com.shilapi.diplay.lite

import android.bluetooth.BluetoothAdapter
import android.os.Build
import java.util.Locale

/**
 * 设备唯一码：**明文蓝牙 MAC + ROM 指纹**，用于「这台机器是谁」的日志归机与用户报号。
 *
 * 格式（2026-11-07，VC250-02 拍板）：
 * ```
 * 22-22-0B-E8-E6-92-t3p1-20181203
 * │────────── MAC 段 ──────────│ └ROM 指纹┘
 * ```
 * - MAC 段：大写、**横杠分隔**的蓝牙 MAC（12 位十六进制）。
 * - ROM 段：优先 `ro.build.display.id`（[Build.DISPLAY]），取不到退
 *   `ro.build.version.incremental`（[Build.VERSION.INCREMENTAL]），都取不到为 [UNKNOWN]。
 *   原串转小写、去掉 `a-z0-9-` 之外的字符（空格/下划线/斜杠等）、连续横杠压成一个，
 *   再截断到 [ROM_FINGERPRINT_MAX] 字符；同一台机器每次结果一致。
 * - 整串不含 `:`、`#`、空格、`/`，可直接进日志头、日志文件名与剪贴板。
 *
 * 为什么加 ROM 指纹：vc249 起设备码是明文 MAC，但实机日志汇总发现 P1/T3 平台出厂 MAC
 * 是 `22:22:*` 这类固定值，多台车机完全相同（`22:22:0B:E8:E6:92` 同时对应 1024×500 与
 * 1280×720 两台不同机器），只靠 MAC 会让日志仓库里两台机器共用一个设备码。ROM 指纹是
 * 同一固件版本的稳定值，正好用来区分同 MAC 的不同机器。
 *
 * 兼容：vc249 的老日志只有纯 MAC（冒号分隔、无指纹），对账时按 MAC 前缀匹配即可；
 * 历史日志不做改写。[LogRedactor] 的 `DEVICE_CODE_MARK` 同时认新旧两种写法。
 *
 * 取不到 MAC 时的降级（**不使用哈希兜底**，用户已否）：MAC 段写 [UNKNOWN]，整串仍是
 * `unknown-<ROM 指纹>`，并在 [describe] 里写明具体原因（无适配器 / 权限拒绝 / 占位地址 /
 * 格式非法）。理由：明文方案的价值是「拿这串数直接对账」，哈希兜底值对不上任何清单，
 * 等于制造一个无法核对的假号码。
 * ROM 取不到时同理降级为 `22-22-0B-E8-E6-92-unknown`。
 * 已知会取不到 MAC 的真实场景：API 31+ 未授予 BLUETOOTH_CONNECT（`getAddress` 抛
 * SecurityException）；Android 6+ 对普通应用返回占位地址 `02:00:00:00:00:00`。
 * 不新增权限：复用 Manifest 里已有的 BLUETOOTH / BLUETOOTH_CONNECT。
 */
object DeviceCode {
    const val UNKNOWN = "unknown"

    /** ROM 指纹长度上限：足够区分固件版本，又不让文件名过长。 */
    private const val ROM_FINGERPRINT_MAX = 20

    /** 归一化只保留 `a-z0-9` 与横杠：横杠是 ROM 短形里的分隔符（如 `t3p1-20181203`），其余一律去掉。 */
    private val ROM_UNSAFE = Regex("[^a-z0-9-]")

    /** 连续横杠压成一个，避免 `T3P1 - 20181203` 这类原串产生 `--`。 */
    private val ROM_DASH_RUN = Regex("-{2,}")

    /** 设备码本体：`22-22-0B-E8-E6-92-t3p1-20181203` / `unknown-<ROM 指纹>`。 */
    fun of(): String = resolve().code

    /**
     * `22-22-0B-E8-E6-92-t3p1-20181203（bt=ok）` /
     * `unknown-t3p1-20181203（bt=miss: <原因>）`：启动日志标明降级原因。
     */
    fun describe(): String {
        val resolved = resolve()
        return if (resolved.macKnown) {
            "${resolved.code}（bt=ok）"
        } else {
            "${resolved.code}（bt=miss: ${resolved.reason}）"
        }
    }

    private class Resolved(val code: String, val macKnown: Boolean, val reason: String)

    private fun resolve(): Resolved {
        val fingerprint = romFingerprint()
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (error: Exception) {
            return Resolved("$UNKNOWN-$fingerprint", false, "BT adapter exception ${error.javaClass.simpleName}")
        } ?: return Resolved("$UNKNOWN-$fingerprint", false, "no BT adapter")

        val raw = try {
            adapter.address
        } catch (error: SecurityException) {
            return Resolved(
                "$UNKNOWN-$fingerprint",
                false,
                "BT address denied (BLUETOOTH_CONNECT not granted)",
            )
        } catch (error: Exception) {
            return Resolved(
                "$UNKNOWN-$fingerprint",
                false,
                "BT address exception ${error.javaClass.simpleName}",
            )
        }.orEmpty().trim()

        val hex = raw.replace(":", "").replace("-", "").uppercase(Locale.US)
        if (hex.isEmpty()) return Resolved("$UNKNOWN-$fingerprint", false, "BT address empty")
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            return Resolved("$UNKNOWN-$fingerprint", false, "BT address malformed '$raw'")
        }
        if (hex.all { it == '0' }) {
            return Resolved("$UNKNOWN-$fingerprint", false, "BT address all-zero")
        }
        if (hex == "020000000000") {
            return Resolved(
                "$UNKNOWN-$fingerprint",
                false,
                "BT address placeholder 02:00:00:00:00:00",
            )
        }
        return Resolved(hex.chunked(2).joinToString("-") + "-" + fingerprint, true, "ok")
    }

    /**
     * ROM 指纹：`Build.DISPLAY` → `Build.VERSION.INCREMENTAL` → [UNKNOWN]。
     * 转小写 → 去掉 `a-z0-9-` 之外的字符（空格/下划线/斜杠等）→ 连续横杠压成一个 →
     * 去掉首尾横杠 → 截断到 [ROM_FINGERPRINT_MAX]。全程无随机因素，同一台机器每次结果一致。
     * 例：`T3P1-20181203` → `t3p1-20181203`；`T3P1_20181203` → `t3p120181203`。
     */
    private fun romFingerprint(): String {
        val display = runCatching { Build.DISPLAY }.getOrNull().orEmpty()
        val incremental = runCatching { Build.VERSION.INCREMENTAL }.getOrNull().orEmpty()
        val raw = if (isUsableRomValue(display)) display else incremental
        val normalized = raw.lowercase(Locale.US)
            .replace(ROM_UNSAFE, "")
            .replace(ROM_DASH_RUN, "-")
            .trim('-')
        return normalized.take(ROM_FINGERPRINT_MAX).trimEnd('-').ifEmpty { UNKNOWN }
    }

    /** 系统对未设置的 build 属性返回字面量 `unknown`，等同取不到，继续往下一级退。 */
    private fun isUsableRomValue(value: String): Boolean =
        value.isNotBlank() && value.trim().lowercase(Locale.US) != UNKNOWN
}
