package com.shilapi.diplay.lite

import android.bluetooth.BluetoothAdapter
import java.util.Locale

/**
 * 设备唯一码：**明文蓝牙 MAC**，用于「这台机器是谁」的日志归机与用户报号。
 *
 * 这是用户 2026-11-07 明确拍板的选择：撤销此前的 SHA-256 分级派生短码（`dev-xxxxxxxx`），
 * 设备码直接就是蓝牙 MAC 原文（12 位十六进制、冒号分隔，如 `22:22:37:4D:5C:0D`）。
 * 选它的理由是可核对：车机蓝牙清单（系统设置/配对列表）、路由器与蓝牙扫描记录里看到的
 * 就是同一串，人工对账不需要再查表换算。
 *
 * **代价（用户已知并接受）**：
 * - 上传件会含明文 MAC —— 日志头「设备码：<MAC>」与日志文件名（`diplay-…-<MAC>-…`）
 *   都会随上传落到仓库；MAC 是可长期关联的硬件标识，同型号车机之间也因此可被区分。
 * - 因此 [LogRedactor] 只对「设备码」落点精确放行（见 LogUploadPrivacy.kt 的
 *   DEVICE_CODE_MARK 注释段），**其余位置的 MAC 仍按原规则打码**，不能因为这次放开
 *   就把整份日志里的 MAC 全露出来。
 *
 * 稳定性：MAC 是硬件标识，App 重装/升级/清数据不变，换机必变；不掺 versionName/
 * versionCode，也不掺安装身份（`deviceId` 是 SHA-256(公钥) 派生的安装身份，重装即变，
 * 不能当设备码）。
 *
 * 取不到 MAC 时的降级（**不使用哈希兜底**，用户已否）：统一输出 `unknown`，并在
 * [describe] 里写明具体原因（无适配器 / 权限拒绝 / 占位地址 / 格式非法）。理由：
 * 明文方案的价值是「拿这串数直接对账」，哈希兜底值对不上任何清单，等于制造一个无法
 * 核对的假号码，反而会让人误判「这台机器换过了」；`unknown` 语义明确——看到它就知道
 * 该去查权限或机型，而不是怀疑日志归属。
 * 已知会取不到的真实场景：API 31+ 未授予 BLUETOOTH_CONNECT（`getAddress` 抛
 * SecurityException）；Android 6+ 对普通应用返回占位地址 `02:00:00:00:00:00`。
 * 老车机（如 T3-D / API19）安装期授权、无运行时授权变化，通常能拿到真实 MAC。
 * 不新增权限：复用 Manifest 里已有的 BLUETOOTH / BLUETOOTH_CONNECT。
 */
object DeviceCode {
    const val UNKNOWN = "unknown"

    /** 设备码本体：`22:22:37:4D:5C:0D` 或 `unknown`。 */
    fun of(): String = resolve().code

    /** `22:22:37:4D:5C:0D（bt=ok）` / `unknown（bt=miss: <原因>）`：启动日志标明降级原因。 */
    fun describe(): String {
        val resolved = resolve()
        return if (resolved.code == UNKNOWN) {
            "$UNKNOWN（bt=miss: ${resolved.reason}）"
        } else {
            "${resolved.code}（bt=ok）"
        }
    }

    private class Resolved(val code: String, val reason: String)

    private fun resolve(): Resolved {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (error: Exception) {
            return Resolved(UNKNOWN, "BT adapter exception ${error.javaClass.simpleName}")
        } ?: return Resolved(UNKNOWN, "no BT adapter")

        val raw = try {
            adapter.address
        } catch (error: SecurityException) {
            return Resolved(UNKNOWN, "BT address denied (BLUETOOTH_CONNECT not granted)")
        } catch (error: Exception) {
            return Resolved(UNKNOWN, "BT address exception ${error.javaClass.simpleName}")
        }.orEmpty().trim()

        val hex = raw.replace(":", "").replace("-", "").uppercase(Locale.US)
        if (hex.isEmpty()) return Resolved(UNKNOWN, "BT address empty")
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            return Resolved(UNKNOWN, "BT address malformed '$raw'")
        }
        if (hex.all { it == '0' }) return Resolved(UNKNOWN, "BT address all-zero")
        if (hex == "020000000000") {
            return Resolved(UNKNOWN, "BT address placeholder 02:00:00:00:00:00")
        }
        return Resolved(hex.chunked(2).joinToString(":"), "ok")
    }
}
