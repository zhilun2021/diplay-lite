package com.shilapi.diplay.lite

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.UUID

/**
 * 设备码：**ROM 指纹 + 8 位安装实例码**，用于「这台机器是谁」的日志归机与用户报号。
 *
 * vc325（DIAG-318-02）起格式（去 MAC）：
 * ```
 * t3p1-eng442kot49h201-a1b2c3d8
 * │───── ROM 指纹段（原算法冻结）─────│ └实例码┘
 * ```
 * - ROM 段：算法与长度与 vc250~324 完全一致（见 [romFingerprint]），保留它是为了新旧日志
 *   能用子串匹配对账——改算法/改长度会断掉 vc250~324 老日志的桥。
 * - 实例码：8 位十六进制小写，安装实例 ID，存 SharedPreferences，同一安装跨日志稳定。
 * - 整串不含 `:`、`#`、空格、`/`，可直接进日志头、日志文件名与剪贴板。
 *
 * 为什么去掉 MAC 段（vc325，用户 2026-11-18 08:54 拍板）：
 * P1/T3 平台出厂 MAC 是 `22:22:*` 这类固定值，多台车机完全相同；明文 MAC 既不能唯一归机，
 * 又让上传件/剪贴板长期携带蓝牙地址。去 MAC 后由实例码承担「同一安装可追踪」，
 * 由 ROM 段承担「同一型号可参考」。
 *
 * 三条语义边界（用户原话，禁止自行扩大解释）：
 * 1. **同型≠同机**：同分辨率 + 同 ROM = 同一「型」设备，日志可互相参考；不得当作物理唯一性
 *    对账（btMac 已因同类误判被机型总表 §1 降级）。
 * 2. **实例码 = 安装实例 ID**：刷机 / 卸载重装 / 恢复出厂后换新码是**预期行为、非缺陷**；
 *    它只保证「同一安装跨日志稳定可追踪」，对不上任何出厂清单。
 * 3. **实例码不用于对账**：与已否决的「哈希兜底」语义不同——哈希兜底想冒充物理号
 *    （对不上清单 = 假号码，已否），实例码明确只做实例追踪。
 *
 * 兼容：vc249 老日志是冒号纯 MAC；vc250~324 是「横杠 MAC + ROM 段」；vc325 起是「ROM 段 +
 * 实例码」。三代都能靠 ROM 段子串对账，[LogRedactor] 的 `DEVICE_CODE_MARK` 同时认三种写法。
 *
 * 不新增权限：本版不再读蓝牙地址，Manifest 不变。
 */
object DeviceCode {
    const val UNKNOWN = "unknown"

    /** 冻结：新旧日志靠本段做子串匹配对账（DIAG-318-02 vc325），禁止修改算法/长度。 */
    private const val ROM_FINGERPRINT_MAX = 20

    /** 归一化只保留 `a-z0-9` 与横杠：横杠是 ROM 短形里的分隔符（如 `t3p1-20181203`），其余一律去掉。 */
    private val ROM_UNSAFE = Regex("[^a-z0-9-]")

    /** 连续横杠压成一个，避免 `T3P1 - 20181203` 这类原串产生 `--`。 */
    private val ROM_DASH_RUN = Regex("-{2,}")

    /** vc325：安装实例码持久化位置（独立 SP，卸载即清，符合「安装实例」语义）。 */
    private const val PREFS_NAME = "diplay_device"
    private const val KEY_INSTANCE_ID = "install_instance_id"

    /** 合法实例码：8 位十六进制小写。 */
    private val INSTANCE_ID_PATTERN = Regex("[0-9a-f]{8}")

    /** vc325：首次生成实例码的并发保护（LiteApp.onCreate 与 UI 首帧可能同时触发）。 */
    private val INSTANCE_LOCK = Any()

    @Volatile
    private var appContext: Context? = null

    /** 是否已注入 applicationContext（[initialize]）。 */
    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    private var cachedInstanceId: String? = null

    /** 未 initialize 时的临时实例码（仅防御，正常路径不会走到；同一进程内保持稳定）。 */
    @Volatile
    private var ephemeralInstanceId: String? = null

    @Volatile
    private var warnedNotInitialized = false

    /**
     * vc325：注入 applicationContext。**只存引用，不生成实例码**——首次 [of] 惰性生成，
     * 保证「读 SP → 生成 → 落盘」只发生在真正需要设备码的那一刻。
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        initialized = true
    }

    /** 设备码本体：`t3p1-eng442kot49h201-a1b2c3d8`。 */
    fun of(): String = romFingerprint() + "-" + instanceId()

    /** vc325 起去 MAC，不再有 `unknown-` / `（bt=ok）` 降级分支；保留签名供既有调用点使用。 */
    fun describe(): String = of()

    /**
     * 8 位十六进制实例码：读 SP → 合法直接用 → 否则生成、写入（apply）、内存缓存。
     * 整段 synchronized：同一进程内只生成一次（Concurrent 首调不会写出两个码）。
     */
    private fun instanceId(): String {
        cachedInstanceId?.let { return it }
        synchronized(INSTANCE_LOCK) {
            cachedInstanceId?.let { return it }
            val context = appContext
            if (context == null) {
                val ephemeral = ephemeralInstanceId ?: newInstanceId().also { ephemeralInstanceId = it }
                if (!warnedNotInitialized) {
                    warnedNotInitialized = true
                    LiteLog.log("app", "DeviceCode warn: not initialized, ephemeral instance id")
                }
                return ephemeral
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_INSTANCE_ID, null)
            val instance = if (saved != null && INSTANCE_ID_PATTERN.matches(saved)) {
                saved
            } else {
                newInstanceId().also { prefs.edit().putString(KEY_INSTANCE_ID, it).apply() }
            }
            cachedInstanceId = instance
            return instance
        }
    }

    private fun newInstanceId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /**
     * ROM 指纹：`Build.DISPLAY` → `Build.VERSION.INCREMENTAL` → [UNKNOWN]。
     * 转小写 → 去掉 `a-z0-9-` 之外的字符（空格/下划线/斜杠等）→ 连续横杠压成一个 →
     * 去掉首尾横杠 → 截断到 [ROM_FINGERPRINT_MAX]。全程无随机因素，同一台机器每次结果一致。
     * 例：`T3P1-20181203` → `t3p1-20181203`；`T3P1_20181203` → `t3p120181203`。
     *
     * 冻结：本函数与 [ROM_FINGERPRINT_MAX] 是 vc250~324 与 vc325 之后日志的对账桥，
     * 任何算法/长度改动都会让老日志无法子串命中（DIAG-318-02 vc325）。
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
