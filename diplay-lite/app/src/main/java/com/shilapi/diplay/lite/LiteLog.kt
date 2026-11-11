package com.shilapi.diplay.lite

import android.util.Log
import com.shilapi.xcertplay.diag.AnchorLog
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形内存日志 + 可选文件持久化（无 adb 实测的观测通道，文件管理器/MTP 读取）。
 * 设计文档 §4.3：release 版可通过把 onTrace 回调置空整体关闭。
 */
object LiteLog {
    private const val MAX = 200
    // 每进程启动新建一个带时间戳的日志文件（diplay-log-<yyyyMMdd-HHmmss>.txt）：
    //  ① 文件名不同 → MTP/文件管理器必然刷新，不再"看不到更新"；
    //  ② 每份日志自成一体（含"构建标识"开头），不像轮转那样丢前半段（T29 就丢过）。
    private const val FILE_MAX_BYTES = 8L shl 20 // 8MB 上限，测试会话基本不会触发轮转
    private const val FILE_PREFIX = "diplay-log-"
    private const val OLD_SUFFIX = "-old.txt"

    /** vc250：CarPlay Host 自链路的阶段标记（不照搬 CarLife 盒子 S1–S6）。 */
    enum class Stage(val wire: String) {
        UsbEnum("usb-enum"),
        Permission("permission"),
        ConfigSwitch("config-switch"),
        Iap2Handshake("iap2-handshake"),
        Credential("credential"),
        // vc250-10：四个耗时边界重指——credential=0x5703 成功下发、wifi-join=首个
        // AirPlay TCP accept、airplay-establish=onSessionActive、first-frame=首帧上屏。
        // 键名沿用旧 string，历史验收 grep 不受影响；旧语义（credential 曾指起监听、
        // airplay-establish 曾指蓝牙控制环启动）已废弃，见 08 文档 §17。
        WifiJoin("wifi-join"),
        Tunnel("tunnel"),
        AirPlayEstablish("airplay-establish"),
        FirstFrame("first-frame");
    }

    /** vc250：失败分类；前五类为需求下限，允许按现场继续扩展。 */
    enum class FailureClass(val wire: String) {
        BtNoResponse("bt-no-response"),
        ClaimBusy("claim-busy"),
        CredTimeout("cred-timeout"),
        TunnelTimeout("tunnel-timeout"),
        KeyframeStall("keyframe-stall"),
        Iap2Timeout("iap2-timeout"),
        Unknown("unknown");
    }

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()
    private var writer: FileWriter? = null
    private var logDirectory: File? = null
    private var fileBytes = 0L
    private var unflushed = 0
    @Volatile private var currentFile: File? = null

    // ---------- vc250-03：阶段耗时（单进程内一次连接一个会话） ----------
    private val stageLock = Any()
    private var sessionActive = false
    private var sessionStartNanos = 0L
    private var lastStageNanos = 0L
    private val stageFirstNanos = LinkedHashMap<String, Long>()
    private var sessionFailureClass: String? = null
    // vc250-05：凭据三态的两个源头事实——0x5703 是否在「iPhone 尚无 AirPlay TCP」时
    // 成功下发、TCP 是否到达。summary 据此输出 credential=full|cached|none，
    // 事后 grep 不可靠（轮转/截断/跨会话/发送失败都没有关联）。
    private var credentialSentBeforeTcp = false
    private var airPlayTcpAcceptedOnce = false
    // vc250-10：credential/wifi-join 阶段只落第一次（后续重发/重连不刷屏、不改首入时刻）。
    private var credentialStageMarked = false
    private var wifiJoinStageMarked = false

    /** 挂载文件持久化；失败静默降级为仅内存日志，绝不影响主流程。 */
    fun attachFileLog(directory: File) {
        synchronized(fileLock) {
            try {
                directory.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(directory, "$FILE_PREFIX$stamp.txt")
                fileBytes = 0L
                writer = FileWriter(file, true)
                logDirectory = directory
                currentFile = file
            } catch (error: Exception) {
                writer = null
                logDirectory = null
                currentFile = null
                Log.w("LiteLog", "文件日志不可用: ${error.message}")
            }
        }
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        log("log", "文件日志挂载：${currentFile?.absolutePath ?: "(失败)"} " +
            "screen=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density}")
    }

    /** 当前日志目录；未挂载或失败时为 null。 */
    fun directory(): File? = logDirectory

    /** 当前日志文件（带启动时间戳）；未挂载时为 null。 */
    fun currentFile(): File? = currentFile

    fun log(tag: String, message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "$stamp [$tag] $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        synchronized(fileLock) {
            val w = writer ?: return
            try {
                w.write(line)
                w.write("\n")
                // vc136 审查节流：逐条 flush 在 KK eMMC 上单条可拖几十 ms（iPhone 高频
                // mDNS 查询时日志风暴直接拖卡 UI 线程）——改攒 8 条或轮转时统一 flush
                fileBytes += line.length + 1
                if (++unflushed >= 8 || fileBytes > FILE_MAX_BYTES) {
                    unflushed = 0
                    w.flush()
                }
                if (fileBytes > FILE_MAX_BYTES) rotateLocked()
            } catch (_: Exception) {
                // 写入失败不影响主流程
            }
        }
    }

    /** vc250：阶段标记。只加结构化字段，不改变既有日志行格式。 */
    fun stage(stage: Stage, detail: String) = stage(stage.wire, detail)

    /**
     * vc250：供共享层桥接使用；与 [stage] 输出完全同一格式，可被同一条 grep 命中。
     *
     * vc250-03：行尾追加 `t=<距会话起点毫秒> dt=<距上一条阶段行毫秒>`；同一阶段再次进入不改写
     * 首入时刻，另打一行 `[stage] reenter stage=<id> t=<距会话起点毫秒>`（首条 `dt=0`）。
     */
    fun stage(stageWire: String, detail: String) {
        val nowNanos = System.nanoTime()
        var reenterMs = -1L
        val timing = synchronized(stageLock) {
            // 共享层的 usb-enum 早于服务启动时也能起一个会话；服务侧发起连接会显式 beginSession() 重置。
            if (!sessionActive) {
                sessionActive = true
                sessionStartNanos = nowNanos
                lastStageNanos = nowNanos
            }
            val elapsed = (nowNanos - sessionStartNanos) / 1_000_000L
            val delta = (nowNanos - lastStageNanos) / 1_000_000L
            lastStageNanos = nowNanos
            if (stageFirstNanos.containsKey(stageWire)) {
                // 重复进入：耗时并入首次进入那段，汇总不出现重复键；本行仍带 t/dt。
                reenterMs = elapsed
            } else {
                stageFirstNanos[stageWire] = nowNanos
            }
            " t=$elapsed dt=$delta"
        }
        log("stage", "stage=$stageWire $detail$timing")
        if (reenterMs >= 0) log("stage", "reenter stage=$stageWire t=$reenterMs")
    }

    /** vc250：失败分类。detail 走既有脱敏链路，不额外携带 MAC/SSID/密钥。 */
    fun failure(stage: Stage, failureClass: FailureClass, detail: String) =
        failure(stage.wire, failureClass.wire, detail)

    /** vc250：供共享层桥接使用；与 [failure] 输出完全同一格式，可被同一条 grep 命中。 */
    fun failure(stageWire: String, failureClassWire: String, detail: String) {
        synchronized(stageLock) {
            // 只记当前会话的失败类；已在会话结束后到达的迟到失败不污染下一会话。
            if (sessionActive) sessionFailureClass = failureClassWire
        }
        log("fail", "stage=$stageWire failureClass=$failureClassWire $detail")
    }

    /** vc250-03：服务侧发起连接时显式开启会话，重复调用以最新时刻为起点并清空上一会话。 */
    fun beginSession() {
        val nowNanos = System.nanoTime()
        synchronized(stageLock) {
            sessionActive = true
            sessionStartNanos = nowNanos
            lastStageNanos = nowNanos
            stageFirstNanos.clear()
            sessionFailureClass = null
            credentialSentBeforeTcp = false
            airPlayTcpAcceptedOnce = false
            credentialStageMarked = false
            wifiJoinStageMarked = false
        }
    }

    /** vc250-05/10：真凭证 0x5703 成功写出；[beforeAirPlayTcp]=发送瞬间尚无 AirPlay TCP。 */
    fun credentialIssued(beforeAirPlayTcp: Boolean) {
        val first = synchronized(stageLock) {
            if (!sessionActive) return@synchronized false
            if (beforeAirPlayTcp) credentialSentBeforeTcp = true
            if (credentialStageMarked) return@synchronized false
            credentialStageMarked = true
            true
        }
        if (first) {
            stage(
                Stage.Credential,
                "credential 0x5703 sent beforeAirPlayTcp=$beforeAirPlayTcp",
            )
        }
    }

    /** vc250-05/10：首个 AirPlay TCP accept（缓存凭证入网也会打点）。 */
    fun airPlayTcpAccepted() {
        val first = synchronized(stageLock) {
            if (!sessionActive) return@synchronized false
            airPlayTcpAcceptedOnce = true
            if (wifiJoinStageMarked) return@synchronized false
            wifiJoinStageMarked = true
            true
        }
        if (first) stage(Stage.WifiJoin, "first AirPlay TCP accepted")
    }

    /**
     * vc250-03：会话结束汇总，一行看全；幂等（未开启/已结束时 no-op，不重复刷屏）。
     * 各段耗时 = 该阶段首次进入到下一阶段首次进入，最后一段算到会话结束；
     * 没走过的阶段写 `-`（不填 0）。未走到 `first-frame` 或已记录失败类时 `result=failed`。
     */
    fun endSession() {
        val summary = synchronized(stageLock) {
            if (!sessionActive) return
            val nowNanos = System.nanoTime()
            val text = buildSummaryLocked(nowNanos)
            sessionActive = false
            stageFirstNanos.clear()
            sessionFailureClass = null
            credentialSentBeforeTcp = false
            airPlayTcpAcceptedOnce = false
            credentialStageMarked = false
            wifiJoinStageMarked = false
            text
        }
        log("stage", summary)
    }

    private fun buildSummaryLocked(nowNanos: Long): String {
        val entries = stageFirstNanos.entries.toList()
        val builder = StringBuilder("summary total=")
        builder.append(elapsedMs(nowNanos - sessionStartNanos))
        for (stage in Stage.values()) {
            builder.append(' ').append(stage.wire).append('=')
            val start = stageFirstNanos[stage.wire]
            if (start == null) {
                builder.append('-')
            } else {
                val index = entries.indexOfFirst { it.key == stage.wire }
                val nextStart = entries.getOrNull(index + 1)?.value ?: nowNanos
                builder.append(elapsedMs(nextStart - start))
            }
        }
        val failed = sessionFailureClass != null || stageFirstNanos[Stage.FirstFrame.wire] == null
        // vc250-07：出过画=首帧上屏（与 06 的媒体面事实同源），用于 LONG_OUTAGE /
        // SLOW_FIRST_DISPLAY 分型；慢轮节奏（60s vs 5min）在 shared 层按同一事实决定。
        val rendered = stageFirstNanos[Stage.FirstFrame.wire] != null
        builder.append(" result=").append(if (failed) "failed" else "ok")
        builder.append(" failureClass=").append(sessionFailureClass ?: if (failed) "unknown" else "-")
        builder.append(" rendered=").append(if (rendered) "yes" else "no")
        builder.append(" failureKind=").append(
            when {
                !failed -> "-"
                rendered -> "long-outage"
                else -> "slow-first-display"
            },
        )
        // vc250-05：凭据三态。full=入网前已下发新凭证且 TCP 到达；cached=未下发新凭证
        // 但 TCP 到达（iPhone 用缓存凭证入网）；none=两者都不成立。
        builder.append(" credential=").append(
            when {
                airPlayTcpAcceptedOnce && credentialSentBeforeTcp -> "full"
                airPlayTcpAcceptedOnce -> "cached"
                else -> "none"
            },
        )
        // vc250-11：绝对锚——与 ANCHOR 行的 session=<进程短 id>、上传骨架标签同一口径。
        builder.append(" session=").append(AnchorLog.sessionId())
        return builder.toString()
    }

    private fun elapsedMs(nanos: Long): Long = if (nanos <= 0L) 0L else nanos / 1_000_000L

    private fun rotateLocked() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        val file = currentFile ?: run {
            writer = null
            return
        }
        val old = File(file.parentFile, file.nameWithoutExtension + OLD_SUFFIX)
        if (old.exists()) old.delete()
        if (!file.renameTo(old)) {
            // 轮转失败：截断重开，保证后续日志不丢
            file.delete()
        }
        fileBytes = 0L
        writer = try {
            FileWriter(file, true)
        } catch (_: Exception) {
            null
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * 把当前日志复制成同目录下的**新文件**（带时间戳），供"无 adb 实测"时手动取日志。
     *
     * 为什么不用原名：MTP / 车机文件管理器对**已存在的同名文件**常按旧缓存显示（"看不到更新"）。
     * 每次导出生成一个新文件名，文件管理器必然能看到。
     *
     * @return 导出的文件；未挂载、无内容或失败时返回 null。
     */
    fun exportSnapshot(): File? {
        val directory = logDirectory ?: return null
        synchronized(fileLock) {
            return try {
                writer?.flush()
                val source = currentFile ?: return null
                if (!source.exists() || source.length() == 0L) return null
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val target = File(directory, "diplay-log-export-$stamp.txt")
                source.copyTo(target, overwrite = true)
                target
            } catch (error: Exception) {
                Log.w("LiteLog", "日志导出失败: ${error.message}")
                null
            }
        }
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
