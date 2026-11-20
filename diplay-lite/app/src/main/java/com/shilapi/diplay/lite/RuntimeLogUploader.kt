package com.shilapi.diplay.lite

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Base64
import com.shilapi.xcertplay.diag.AnchorLog
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * vc240：运行日志上传（含 dongle 链路）——四段式统一上传件。
 *
 * 文件结构（用户拍板 vc240-upload-merge；vc254 扩为多会话）：
 * ① 头区：元信息 + 会话清单 + 采集范围说明；
 * ② 每份会话三段：骨架区（上限 160KB，含 -old.txt，old 在前）/
 *    滚动区（**该会话自身**最后 20 分钟，整段丢弃 `TRACE airplay`，`bulk read fail` 仅首尾取样+计数）/
 *    尾区（**该会话自身**最后 30 秒 + 收尾锚点 + 裁剪说明）。
 *
 * vc254：闪退现场往往落在上一个进程的日志文件里（重启后 currentFile 已换新），因此默认打包
 * 最近 3 个进程会话（当前 + 上一次 + 再上一次）。每个会话的滚动/尾窗口以**该文件最后一条
 * 日志行的时刻**为锚点，不再用"当前时间"——否则旧会话会得到空窗口。
 * 崩溃/异常会话的尾区优先占该会话配额的 1/2（栈与最终状态都在末尾）。
 *
 * 体积上限 768KB；裁剪优先级：TRACE → bulk 噪声 → 非关键杂项 → 周期 stats 只留最近 2 分钟
 * → 骨架明细。骨架超 160KB 时按 gen 裁剪，但**每个出现过的 gen 至少保留一条关键锚点行**
 * （vc242 修 vc241 缺陷：旧策略「保首个 gen + 最近 3 个 gen」会让中间 gen 整段消失），
 * 每个 gen 内优先省略 HCI init 明细并落一行计数说明；每次裁剪都在文件里留明确的省略说明。
 *
 * 失败兜底：四段式组装异常/为空时回落到 [DongleLogUploader]（skeleton 模式，文件名与头部
 * 字段格式保持 `dongle-*.txt` 兼容，`ANCHOR` 前缀不变）。
 */
object RuntimeLogUploader {
    private const val MAX_BYTES = 768 * 1024
    private const val SKELETON_CAP_BYTES = 160 * 1024
    private const val TAIL_CAP_BYTES = 96 * 1024
    private const val ROLLING_WINDOW_MILLIS = 20 * 60_000L
    private const val TAIL_WINDOW_MILLIS = 30_000L
    private const val STATS_RECENT_MILLIS = 2 * 60_000L
    private const val TIMEOUT_MILLIS = 60_000L
    private const val RESERVE_BYTES = 2048
    /** vc254：默认打包最近 3 个进程会话。 */
    private const val SESSION_LIMIT = 3
    /** 每个会话块自身的标题行（会话名/时段/体积/判决/版本）。 */
    private const val BLOCK_TITLE_RESERVE = 512
    /**
     * vc255：flush 后到取快照边界之间的短暂兜底（300ms）。
     * 只覆盖「其它线程刚写完、还没进文件」的极小窗口；在 worker 线程执行，不阻塞 UI。
     */
    private const val SNAPSHOT_SETTLE_MILLIS = 300L

    /** 骨架裁剪时给统计行/每段省略标记预留的字节。 */
    private const val SKELETON_NOTE_RESERVE = 2048

    private const val TRACE_MARK = "TRACE airplay"
    private const val BULK_MARK = "bulk read fail"
    private const val ANCHOR_MARK = "ANCHOR "

    internal const val SKELETON_SEP = "──── 骨架区（每个 gen 至少保留锚点行） ────"
    internal const val ROLLING_SEP = "──── 滚动区（最近 20 分钟） ────"
    internal const val TAIL_SEP = "──── 尾区（最后 30 秒 + 收尾锚点） ────"

    private val TIME_REGEX = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})")
    private val GEN_REGEX = Regex("gen=(\\d+)")
    private val TAG_REGEX = Regex("\\[([a-z]+)\\]")

    /** 骨架关键词：dongle 链路 / 配对 / 锚点 / 总闸。`bulk read fail` 在判定前先排除。 */
    private val SKELETON_KEYWORDS = arrayOf(
        "usbbt", "dongle", "host:", "standby", "inquiry", "pair", "hci", "外置蓝牙",
        "scan=", "gate-off", "gate-on", "link-ready", "connect-begin", "handoff",
        "session_active", "acl",
    )

    /**
     * 骨架关键锚点行（小写匹配）：任何裁剪下每个 gen 至少保留一条，
     * 保证「某个 gen 存在过」始终可查。配对事件只收中文/显式 pair 事件，
     * 避免把 HCI init 的 `WriteSimplePairingMode` 也算成锚点。
     */
    private val SKELETON_KEY_MARKS = arrayOf(
        "anchor ", "session_active", "scan mode ->", "gate-off", "gate-on",
        "acl 链路建立", "pair-begin", "pair-done", "配对",
    )

    /** 骨架里优先省略的 HCI 初始化明细。 */
    private val SKELETON_HCI_MARKS = arrayOf(
        "hci cmd", "writeelapsedms", "init sequence", "enqueue first command",
        "host: start entered",
    )

    /** 非关键杂项 tag：滚动区超预算时优先省略（[ui]/[media]/[log]/配对与锚点不在此列）。 */
    private val MISC_TAGS = setOf("statusbar", "cluster", "app", "service", "usbfx", "pcap", "vpn")

    /** 本地已备好的上传件。 */
    class Prepared(
        val file: File,
        val fileName: String,
        val lineCount: Int,
        val sizeBytes: Long,
        /** vc254：包内会话份数（1 = 单会话，与旧行为一致）。 */
        val sessions: Int = 1,
        /** vc254：上一会话判决（crash/clean/abnormal/none），供界面提示复用。 */
        val verdict: String? = null,
    )

    /** 上传结果：ok=true 时 remoteName=仓库内真实文件名（409 冲突可能加 -1 后缀）。 */
    class Result(val ok: Boolean, val remoteName: String?, val error: String?)

    internal class Entry(val line: String, val tod: Long?)

    private class GenGroup(val gen: Int?, val lines: ArrayList<String>)

    /** bulk read fail 取样：前 2 行 + 后 2 行 + 总计数。 */
    private class Noise {
        private val head = ArrayList<String>(2)
        private val tail = ArrayDeque<String>()
        var count = 0
            private set

        fun add(line: String) {
            count++
            if (head.size < 2) head.add(line)
            tail.addLast(line)
            while (tail.size > 2) tail.removeFirst()
        }

        fun samples(): List<String> = (head + tail).distinct()
    }

    /**
     * 组装四段式上传件；异常或为空时回落 [DongleLogUploader] 的 skeleton 模式。
     *
     * vc255：读取发生在组包阶段，所以落盘必须在这里、而不是只在上传返回时——否则
     * 末尾几行仍在 8 条批量缓冲里，上传件永远缺最后一段；300ms 兜底后再取快照边界。
     * 本方法在 worker 线程调用（见 SettingsPage.uploadLogToGitee）。
     */
    fun prepare(context: Context): Prepared? {
        LiteLog.flushNow()
        try {
            Thread.sleep(SNAPSHOT_SETTLE_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val full = try {
            buildBundle(context)
        } catch (error: Exception) {
            LiteLog.log(
                "log",
                "四段式日志组装失败 ${error.javaClass.simpleName} ${error.message.orEmpty()}，回落 skeleton 模式",
            )
            null
        }
        if (full != null) return full
        val skeleton = DongleLogUploader.prepare(context) ?: return null
        LiteLog.log("log", "运行日志上传：回落 skeleton 模式（${skeleton.fileName}）")
        return Prepared(
            skeleton.file,
            skeleton.fileName,
            skeleton.lineCount,
            skeleton.file.length(),
            sessions = 1,
            verdict = LiteLog.previousVerdict().takeIf { it != "none" },
        )
    }

    // ---------- 组装 ----------

    private fun buildBundle(context: Context): Prepared? {
        val current = LiteLog.currentFile() ?: return null
        val directory = current.parentFile ?: return null

        // vc254：最近 3 个进程会话（新 → 旧）。目录枚举失败时退回单会话，保持旧行为。
        val sessions = LiteLog.recentSessions(SESSION_LIMIT).ifEmpty {
            listOfNotNull(singleSession(current))
        }
        if (sessions.isEmpty()) return null

        // vc255：组包这一刻为每个文件取一次长度，之后所有读取都只到该位置——
        // 上传过程中继续追加的日志不会混进本次上传件，也不会读到正在写的半行。
        // 多会话共用一个快照时刻：各会话的 current/-old 各自记边界，互不影响。
        val boundaries = HashMap<File, Long>(sessions.size * 2)
        for (session in sessions) {
            for (file in session.files) boundaries[file] = file.length()
        }

        val previousVerdict = LiteLog.previousVerdict()
        // 只有"紧邻当前进程的那一份"有启动判决；更早的会话判别不了（标"未知"）。
        val verdictIndex = sessions.indexOfFirst { it.current != current }
        val scans = ArrayList<SessionScan>(sessions.size)
        for (index in sessions.indices) {
            val session = sessions[index]
            val verdict = if (index == verdictIndex) {
                previousVerdict.takeIf { it != "none" }
            } else {
                null
            }
            scans.add(scanSession(session, verdict, boundaries))
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val deviceCode = DeviceCode.of()
        val headerText = buildHeader(context, scans, stamp, deviceCode)
        val headerBytes = sectionBytes(headerText)
        val budget = (MAX_BYTES - headerBytes - RESERVE_BYTES).coerceAtLeast(64 * 1024)
        // 每会话配额均分：3 份 ≈255KB、2 份 ≈382KB、1 份 ≈765KB（与旧的单会话上限接近）。
        val share = budget / scans.size

        var skeletonLines = 0
        var rollingLines = 0
        var tailLines = 0
        val body = StringBuilder()
        for (scan in scans) {
            val block = buildSessionBlock(scan, share)
            body.append(block.text).append('\n')
            skeletonLines += block.skeletonLines
            rollingLines += block.rollingLines
            tailLines += block.tailLines
        }

        val text = LogRedactor.redact(headerText + body.toString())
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            LiteLog.log("log", "运行日志上传件超出 768KB（${bytes.size} 字节），已拒绝落盘")
            return null
        }
        val totalLines = text.count { it == '\n' }
        val fileName = buildFileName(context, stamp, deviceCode, scans.size)
        val target = File(directory, fileName)
        target.outputStream().use { it.write(bytes) }
        LiteLog.log(
            "log",
            "运行日志上传件已生成：$fileName（${bytes.size} 字节，${scans.size} 份会话，" +
                "骨架 $skeletonLines 行 / 滚动 $rollingLines 行 / 尾区 $tailLines 行）",
        )
        return Prepared(
            target,
            fileName,
            totalLines,
            bytes.size.toLong(),
            sessions = scans.size,
            verdict = previousVerdict.takeIf { it != "none" },
        )
    }

    /** 目录枚举不可用时的单会话兜底（current + 其 -old）。 */
    private fun singleSession(current: File): LiteLog.Session? {
        if (!current.isFile || current.length() == 0L) return null
        val old = File(current.parentFile, current.nameWithoutExtension + "-old.txt")
        val files = ArrayList<File>(2)
        if (old.isFile && old.length() > 0L) files.add(old)
        files.add(current)
        return LiteLog.Session(current.name, files)
    }

    /** vc254：一个会话的扫描结果（骨架/窗口/噪声 + 元信息）。 */
    private class SessionScan(
        val session: LiteLog.Session,
        val verdict: String?,
        val anchorTod: Long,
        val firstStamp: String,
        val lastStamp: String,
        val version: String,
        val skeleton: List<Entry>,
        val window: List<Entry>,
        val noise: Noise,
        val traceDropped: Int,
    )

    private class BlockResult(
        val text: String,
        val skeletonLines: Int,
        val rollingLines: Int,
        val tailLines: Int,
    )

    /**
     * vc254：扫描单个会话（old 在前、本体在后）。
     * 滚动/尾窗口以**该文件最后一条日志行的时刻**为锚点——旧会话跨天、跨重启同样成立。
     */
    private fun scanSession(
        session: LiteLog.Session,
        verdict: String?,
        boundaries: Map<File, Long>,
    ): SessionScan {
        val anchorTod = findAnchorTod(session, boundaries)
        val rollingStart = anchorTod - ROLLING_WINDOW_MILLIS
        val skeleton = ArrayList<Entry>()
        val window = ArrayList<Entry>()
        val noise = Noise()
        var traceDropped = 0
        var firstStamp = "-"
        var lastStamp = "-"
        var version = "未记录"
        for (file in session.files) {
            val boundary = boundaries[file] ?: file.length()
            try {
                readLinesUpTo(file, boundary) { line ->
                    if (line.isEmpty()) return@readLinesUpTo
                    val match = TIME_REGEX.find(line)
                    if (match != null) {
                        val text = "${match.groupValues[1]}-${match.groupValues[2]} " +
                            "${match.groupValues[3]}:${match.groupValues[4]}:${match.groupValues[5]}"
                        if (firstStamp == "-") firstStamp = text
                        lastStamp = text
                    }
                    if (version == "未记录" && line.contains("进程启动 versionName=")) {
                        val parsed = line.substringAfter("进程启动 versionName=").trim()
                        if (parsed.isNotEmpty()) version = parsed.take(24)
                    }
                    val tod = parseTod(line, anchorTod)
                    val inWindow = tod != null && tod >= rollingStart && tod <= anchorTod
                    val lower = line.lowercase(Locale.US)
                    if (inWindow && lower.contains(TRACE_MARK.lowercase(Locale.US))) {
                        traceDropped++
                        return@readLinesUpTo
                    }
                    if (inWindow && lower.contains(BULK_MARK)) {
                        noise.add(line)
                        return@readLinesUpTo
                    }
                    if (isSkeletonLine(lower)) {
                        skeleton.add(Entry(line, tod))
                        return@readLinesUpTo
                    }
                    if (inWindow) window.add(Entry(line, tod))
                }
            } catch (_: Exception) {
            }
        }
        return SessionScan(
            session,
            verdict,
            anchorTod,
            firstStamp,
            lastStamp,
            version,
            skeleton,
            window,
            noise,
            traceDropped,
        )
    }

    /** 会话时间锚点 = 该会话最后一条能解析出时刻的日志行（先本体、后 old；只到快照边界）。 */
    private fun findAnchorTod(session: LiteLog.Session, boundaries: Map<File, Long>): Long {
        val files = session.files
        for (index in files.indices.reversed()) {
            val file = files[index]
            val lines = LiteLog.readTail(file, 64 * 1024, boundaries[file] ?: file.length())
                .split('\n')
            for (lineIndex in lines.indices.reversed()) {
                val tod = rawTod(lines[lineIndex])
                if (tod != null) return tod
            }
        }
        return todOfNow()
    }

    /**
     * vc255：按快照边界逐行读取（只读 [boundary] 之前的字节，流式不整文件载入）。
     *
     * 边界恰好落在半行上时丢弃最后一个不完整行：flush 之后仍可能有其它线程的写入
     * 把缓冲区整块刷出，块尾可能切在行中间——读取侧不能把半行当成一条日志。
     */
    private fun readLinesUpTo(file: File, boundary: Long, action: (String) -> Unit) {
        val length = minOf(boundary, runCatching { file.length() }.getOrDefault(0L))
        if (length <= 0L) return
        val endsWithNewline = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - 1)
                raf.read() == '\n'.code
            }
        }.getOrDefault(true)
        BoundedInputStream(file.inputStream(), length).bufferedReader().use { reader ->
            var line = reader.readLine() ?: return
            while (true) {
                val next = reader.readLine()
                if (next == null) {
                    if (endsWithNewline) action(line)
                    return
                }
                action(line)
                line = next
            }
        }
    }

    /** 只放行前 [limit] 字节的输入流包装（超出部分视为文件结束）。 */
    private class BoundedInputStream(
        private val inner: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = inner.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val request = minOf(length.toLong(), remaining).toInt()
            val read = inner.read(buffer, offset, request)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() = inner.close()
    }

    /** 单个会话块：标题 + 骨架 / 滚动 / 尾 三段。 */
    private fun buildSessionBlock(scan: SessionScan, shareBytes: Int): BlockResult {
        val tailStart = scan.anchorTod - TAIL_WINDOW_MILLIS
        val crashed = scan.verdict == "crash" || scan.verdict == "abnormal"
        val title = buildSessionTitle(scan)
        val usable = (shareBytes - sectionBytes(title) - BLOCK_TITLE_RESERVE).coerceAtLeast(2048)
        val skeletonCap = minOf(SKELETON_CAP_BYTES, usable / 3)
        // 崩溃/异常会话：尾区优先占 1/2（栈与最终状态在末尾），其余 1/3。
        val tailCap = minOf(TAIL_CAP_BYTES, usable * (if (crashed) 3 else 2) / 6)
            .coerceAtLeast(1024)
        val skeletonFit = fitSkeleton(scan.skeleton, skeletonCap)
        val skeletonBytes = sectionBytes(skeletonFit.first)
        val tailEntries = scan.window.filter { it.tod != null && it.tod >= tailStart }
        val anchorEntries = scan.skeleton
            .filter { it.line.contains(ANCHOR_MARK, ignoreCase = true) }
            .takeLast(6)
        val tailFit = fitTail(tailEntries, anchorEntries, tailCap, tailStart)
        val tailBytes = sectionBytes(tailFit.first)
        val rollingBudget = (usable - skeletonBytes - tailBytes).coerceAtLeast(1024)
        val rollingEntries = scan.window.filter { it.tod != null && it.tod < tailStart }
        val rollingFit = fitRolling(
            rollingEntries,
            rollingBudget,
            scan.anchorTod,
            scan.traceDropped,
            scan.noise,
        )
        val text = buildString {
            append(title)
            append(SKELETON_SEP).append('\n')
            append(skeletonFit.first).append('\n')
            append(ROLLING_SEP).append('\n')
            append(rollingFit.first).append('\n')
            append(TAIL_SEP).append('\n')
            append(tailFit.first)
        }
        return BlockResult(text, skeletonFit.second, rollingFit.second, tailFit.second)
    }

    private fun buildSessionTitle(scan: SessionScan): String = buildString {
        append("════ ").append(scan.session.name)
        if (scan.session.hasOld) append("（含 -old.txt，old 在前）")
        append("  ").append(scan.firstStamp).append(" ~ ").append(scan.lastStamp)
        append("  ").append(kbText(scan.session.sizeBytes.toInt()))
        append("  判决=").append(scan.verdict?.let { LiteLog.verdictLabel(it) } ?: "未知")
        append("  版本=").append(scan.version)
        append(" ════\n")
    }

    internal fun isSkeletonLine(lower: String): Boolean {
        if (lower.contains(BULK_MARK)) return false
        if (lower.contains(ANCHOR_MARK.lowercase(Locale.US))) return true
        for (keyword in SKELETON_KEYWORDS) {
            if (lower.contains(keyword)) return true
        }
        return false
    }

    /**
     * 骨架区：不超 160KB 时原样保留；超限时按 gen 分组裁剪，但**每个出现过的 gen 至少保留
     * 一条关键锚点行**（vc242）。每个 gen 内优先省略 HCI init 明细，保留头尾若干行，
     * 每段落一行省略计数；顶部落总体统计（保留 gen 数、每段行数、省略行数、抽样规则）。
     */
    internal fun fitSkeleton(entries: List<Entry>, cap: Int): Pair<String, Int> {
        if (entries.isEmpty()) return "（无 dongle/链路骨架行）\n" to 0
        val groups = groupByGen(entries)
        val fullBytes = bytesOfGroups(groups)
        if (fullBytes <= cap) {
            val text = buildString {
                groups.forEach { group -> group.lines.forEach { append(it).append('\n') } }
            }
            return text to entries.size
        }

        // 关键锚点行必须保留；某段完全没有关键行时用该段首行代表，保证 gen 存在过可查。
        val keySets = groups.map { group ->
            group.lines.indices.filter { isKeyAnchorLine(group.lines[it]) }.toMutableSet()
        }
        keySets.forEachIndexed { i, set ->
            if (set.isEmpty() && groups[i].lines.isNotEmpty()) set.add(0)
        }

        val mustBytes = bytesOfIndices(groups, keySets)
        val contextBudget = (cap - SKELETON_NOTE_RESERVE - mustBytes).coerceAtLeast(0)
        var keep = buildKeep(groups, keySets, contextBudget)
        var text = renderSkeleton(groups, keep, entries.size, fullBytes)
        if (sectionBytes(text) > cap) {
            // 极端兜底：关键锚点行本身超额 → 每段只留首条锚点，仍保证 gen 连续可查。
            val minimal = keySets.map { set ->
                if (set.isEmpty()) mutableSetOf() else mutableSetOf(set.minOrNull()!!)
            }
            keep = buildKeep(groups, minimal, 0)
            text = renderSkeleton(groups, keep, entries.size, fullBytes)
        }
        val kept = keep.sumOf { arr -> arr.count { it } }
        return text to kept
    }

    /** 按行内 `gen=` 分组；无 gen 的前导行单独成段（gen=null）。 */
    private fun groupByGen(entries: List<Entry>): List<GenGroup> {
        val groups = ArrayList<GenGroup>()
        var current: GenGroup? = null
        for (entry in entries) {
            val gen = GEN_REGEX.find(entry.line)?.groupValues?.get(1)?.toIntOrNull()
            val group = current
            if (group == null || (gen != null && group.gen != gen)) {
                val next = GenGroup(gen, ArrayList())
                current = next
                groups.add(next)
                next.lines.add(entry.line)
            } else {
                group.lines.add(entry.line)
            }
        }
        return groups
    }

    /** 关键锚点：ANCHOR / SESSION_ACTIVE / scan mode -> / gate-off|on / ACL 建立 / 配对事件。 */
    private fun isKeyAnchorLine(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        for (mark in SKELETON_KEY_MARKS) {
            if (lower.contains(mark)) return true
        }
        return false
    }

    private fun isHciDetailLine(line: String): Boolean {
        if (isKeyAnchorLine(line)) return false
        val lower = line.lowercase(Locale.US)
        for (mark in SKELETON_HCI_MARKS) {
            if (lower.contains(mark)) return true
        }
        return false
    }

    /** 在必须保留的锚点之外，按段均分预算补头尾上下文行。 */
    private fun buildKeep(
        groups: List<GenGroup>,
        must: List<Set<Int>>,
        budget: Int,
    ): List<BooleanArray> {
        val keep = groups.map { group -> BooleanArray(group.lines.size) }
        groups.indices.forEach { i -> must[i].forEach { keep[i][it] = true } }
        if (budget <= 0 || groups.isEmpty()) return keep
        val share = budget / groups.size
        groups.forEachIndexed { i, group ->
            val set = keep[i]
            val half = (share / 2).coerceAtLeast(64)
            var used = 0
            var head = 0
            while (head < group.lines.size) {
                if (!set[head]) {
                    val next = lineBytes(group.lines[head])
                    if (used + next > half) break
                    set[head] = true
                    used += next
                }
                head++
            }
            used = 0
            var tail = group.lines.size - 1
            while (tail >= 0) {
                if (!set[tail]) {
                    val next = lineBytes(group.lines[tail])
                    if (used + next > half) break
                    set[tail] = true
                    used += next
                }
                tail--
            }
        }
        return keep
    }

    /** 渲染骨架区：顶部统计行 + 每段保留行 + 每段省略计数。 */
    private fun renderSkeleton(
        groups: List<GenGroup>,
        keep: List<BooleanArray>,
        totalLines: Int,
        totalBytes: Int,
    ): String {
        var keptLines = 0
        var keptBytes = 0
        var omittedLines = 0
        var omittedHci = 0
        groups.forEachIndexed { i, group ->
            keep[i].forEachIndexed { idx, on ->
                if (on) {
                    keptLines++
                    keptBytes += lineBytes(group.lines[idx])
                } else {
                    omittedLines++
                    if (isHciDetailLine(group.lines[idx])) omittedHci++
                }
            }
        }
        val gens = groups.mapNotNull { it.gen }.distinct()
        // vc250-11：骨架标签带 session=<进程短 id>，与 ANCHOR 行/结构化 summary 绝对对齐。
        val sessionId = runCatching { AnchorLog.sessionId() }.getOrDefault("?")
        val stats = buildString {
            append("（骨架区：session=$sessionId 完整 $totalLines 行 / ${kbText(totalBytes)} 超 160KB 上限；")
            append("按「每个 gen 强制保留关键锚点行 + 该 gen 头尾各若干行、优先省略 HCI 明细」裁剪：")
            append("保留 ${gens.size} 个 gen")
            if (gens.isNotEmpty()) {
                append("（").append(gens.joinToString("、") { "gen=$it" }).append("）")
            }
            append("，保留 $keptLines 行 / ${kbText(keptBytes)}，")
            append("省略 $omittedLines 行（其中 HCI 明细 $omittedHci 行）)\n")
            val perGroup = groups.indices.joinToString("、") { i ->
                val label = groups[i].gen?.let { "session=$sessionId gen=$it" }
                    ?: "session=$sessionId / 无 gen 段"
                "$label:${keep[i].count { it }}"
            }
            if (perGroup.isNotEmpty()) append("（骨架区每段保留行数：$perGroup）\n")
        }
        val body = buildString {
            groups.forEachIndexed { i, group ->
                val label = group.gen?.let { "session=$sessionId gen=$it" }
                    ?: "session=$sessionId / 无 gen 段"
                var idx = 0
                while (idx < group.lines.size) {
                    if (keep[i][idx]) {
                        append(group.lines[idx]).append('\n')
                        idx++
                    } else {
                        val start = idx
                        var hci = 0
                        while (idx < group.lines.size && !keep[i][idx]) {
                            if (isHciDetailLine(group.lines[idx])) hci++
                            idx++
                        }
                        append("… 骨架裁剪：$label 已省略 ${idx - start} 行（其中 HCI 明细 $hci 行）…\n")
                    }
                }
            }
        }
        return stats + body
    }

    /** 关键锚点行 + 上下文行合计字节。 */
    private fun bytesOfIndices(groups: List<GenGroup>, sets: List<Set<Int>>): Int {
        var total = 0
        groups.indices.forEach { i -> sets[i].forEach { total += lineBytes(groups[i].lines[it]) } }
        return total
    }

    private fun kbText(bytes: Int): String =
        String.format(Locale.US, "%.1fKB", bytes / 1024.0)

    /** 滚动区：已过滤 TRACE/bulk；超预算按 非关键杂项 → 周期 stats(2min) → 硬裁 依次省略。 */
    private fun fitRolling(
        entries: List<Entry>,
        budget: Int,
        nowTod: Long,
        traceDropped: Int,
        noise: Noise,
    ): Pair<String, Int> {
        val notes = ArrayList<String>()
        if (traceDropped > 0) {
            notes.add("（滚动区：已整段丢弃 TRACE airplay 共 $traceDropped 行）\n")
        }
        if (noise.count > 0) {
            notes.add("（空闲噪声 bulk read fail：共 ${noise.count} 行，未逐行进正文；首尾取样如下）\n")
            for (sample in noise.samples()) notes.add("  $sample\n")
        }
        var current = entries
        if (bytesOfEntries(current) + bytesOfNotes(notes) > budget) {
            var removed = 0
            current = current.filter {
                val misc = isMiscTag(it.line)
                if (misc) removed++
                !misc
            }
            if (removed > 0) notes.add("（滚动区：非关键杂项 $removed 行已省略）\n")
        }
        if (bytesOfEntries(current) + bytesOfNotes(notes) > budget) {
            val cutoff = nowTod - STATS_RECENT_MILLIS
            var removed = 0
            current = current.filter {
                val drop = isPeriodicStats(it.line) && (it.tod ?: nowTod) < cutoff
                if (drop) removed++
                !drop
            }
            if (removed > 0) notes.add("（滚动区：周期 stats 仅保留最近 2 分钟，省略 $removed 行）\n")
        }
        var lines = current.map { it.line }
        val lineBudget = (budget - bytesOfNotes(notes) - 256).coerceAtLeast(256)
        if (bytesOfLines(lines) > lineBudget) {
            var start = lines.size
            var used = 0
            while (start > 0) {
                val next = lineBytes(lines[start - 1])
                if (used + next > lineBudget) break
                used += next
                start--
            }
            if (start > 0) {
                notes.add("（骨架完整、滚动自 ${lineTime(lines[start])} 起被裁，省略 $start 行）\n")
            }
            lines = lines.subList(start, lines.size)
        }
        if (lines.isEmpty()) {
            notes.add("（滚动区无日志行）\n")
        }
        val text = buildString {
            notes.forEach { append(it) }
            lines.forEach { append(it).append('\n') }
        }
        return text to lines.size
    }

    /** 尾区：最后 30 秒 + 收尾锚点；超 96KB 从最早处裁并留说明。 */
    private fun fitTail(
        entries: List<Entry>,
        anchors: List<Entry>,
        cap: Int,
        tailStart: Long,
    ): Pair<String, Int> {
        var lines = entries.map { it.line }
        val anchorLines = anchors.map { it.line }
        var note = ""
        val anchorBytes = bytesOfLines(anchorLines) + (if (anchorLines.isEmpty()) 0 else 64)
        if (bytesOfLines(lines) + anchorBytes > cap) {
            val lineBudget = (cap - anchorBytes - 256).coerceAtLeast(256)
            var start = lines.size
            var used = 0
            while (start > 0) {
                val next = lineBytes(lines[start - 1])
                if (used + next > lineBudget) break
                used += next
                start--
            }
            if (start > 0) {
                note = "（尾区：最后 30 秒内省略 $start 行，自 ${lineTime(lines[start])} 起被裁）\n"
            }
            lines = lines.subList(start, lines.size)
        }
        val text = buildString {
            append(note)
            lines.forEach { append(it).append('\n') }
            if (anchorLines.isNotEmpty()) {
                append("（收尾锚点：最近 ${anchorLines.size} 条，自 ${todText(tailStart)} 前）\n")
                anchorLines.forEach { append(it).append('\n') }
            }
            if (lines.isEmpty() && anchorLines.isEmpty()) {
                append("（最后 30 秒无日志行）\n")
            }
        }
        return text to (lines.size + anchorLines.size)
    }

    // ---------- 头区 / 文件名 ----------

    private fun buildHeader(
        context: Context,
        scans: List<SessionScan>,
        stamp: String,
        deviceCode: String,
    ): String = buildString {
        append("运行日志（含 dongle 链路）— 最近 ").append(scans.size).append(" 份会话\n\n")
        append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        // vc325：设备码 = ROM 指纹 + 8 位安装实例码（已去蓝牙 MAC）；上传脱敏只放行这一处
        // 「设备码」行（见 LogUploadPrivacy.DEVICE_CODE_MARK，三代格式通吃）。
        append("设备码：").append(deviceCode).append('\n')
        val metrics = Resources.getSystem().displayMetrics
        append("分辨率：").append(metrics.widthPixels).append('x').append(metrics.heightPixels)
            .append(" density=").append(metrics.density).append('\n')
        append("系统版本：Android").append(Build.VERSION.RELEASE)
            .append("-api").append(Build.VERSION.SDK_INT).append('\n')
        val romDisplay = Build.DISPLAY?.trim().orEmpty().ifEmpty { Build.ID }
        append("ROM版本：").append(romDisplay)
            .append("（built ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(Build.TIME)))
            .append("）\n")
        append("软件版本：").append(versionName(context)).append('\n')
        append("上传时间：").append(stamp).append('\n')
        append("──── 会话清单（新 → 旧） ────\n")
        scans.forEachIndexed { index, scan ->
            append('[').append(index + 1).append("] ").append(scan.session.name)
            if (scan.session.hasOld) append("（含 -old.txt）")
            append("  ").append(scan.firstStamp).append(" ~ ").append(scan.lastStamp)
            append("  ").append(kbText(scan.session.sizeBytes.toInt()))
            append("  判决=").append(scan.verdict?.let { LiteLog.verdictLabel(it) } ?: "未知")
            append("  版本=").append(scan.version)
            append('\n')
        }
        append("──── 采集范围 ────\n")
        append("每份会话独立三段；时间锚点 = 该文件最后一条日志行的时刻（不按当前时间推算，旧会话同样成立）\n")
        append("骨架区：dongle 检测/初始化/配对/锚点/总闸链路（每会话上限 160KB，含 -old.txt，old 在前；")
        append("超限时每个 gen 至少保留一条锚点行，优先省略 HCI 明细）\n")
        append("滚动区：该会话最后 20 分钟，整段丢弃 TRACE airplay\n")
        append("尾区：该会话最后 30 秒 + 收尾锚点；崩溃/异常会话尾区优先占该会话配额 1/2，其余 1/3\n")
        append("总上限：768KB；bulk read fail 仅首尾取样+计数\n")
    }

    private fun buildFileName(
        context: Context,
        stamp: String,
        deviceCode: String,
        sessions: Int,
    ): String {
        val systemVersion = "Android${Build.VERSION.RELEASE}-api${Build.VERSION.SDK_INT}"
        val safeModel = Build.MODEL.trim().replace(Regex("[^0-9A-Za-z._-]"), "-")
        // vc254：多会话批次标记 -s3（含 3 份会话）；单会话保持旧命名，便于历史检索不割裂。
        val batch = if (sessions > 1) "-s$sessions" else ""
        // 设备码紧跟时间戳：仓库里同一秒上传的多台机器也能一眼区分归属。
        return "diplay-$stamp-$deviceCode-$systemVersion-v${versionName(context)}-$safeModel$batch.txt"
            .replace(Regex("[\\s]+"), "")
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    // ---------- 工具 ----------

    private fun isMiscTag(line: String): Boolean {
        val tag = TAG_REGEX.find(line)?.groupValues?.get(1)?.lowercase(Locale.US) ?: return false
        return MISC_TAGS.contains(tag)
    }

    private fun isPeriodicStats(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        return lower.contains("video stats") || lower.contains("touch stats") ||
            lower.contains("microphone gain window")
    }

    /** 解析 `MM-dd HH:mm:ss.SSS` → 当日毫秒（跨零点按锚点 ±12h 归一）。 */
    private fun parseTod(line: String, anchorTod: Long): Long? {
        val tod = rawTod(line) ?: return null
        return if (tod > anchorTod + 12 * 3_600_000L) tod - 24 * 3_600_000L else tod
    }

    /** 不做跨零点归一的当日毫秒（用于确定会话锚点）。 */
    private fun rawTod(line: String): Long? {
        val m = TIME_REGEX.find(line) ?: return null
        val hour = m.groupValues[3].toIntOrNull() ?: return null
        val minute = m.groupValues[4].toIntOrNull() ?: return null
        val second = m.groupValues[5].toIntOrNull() ?: return null
        val millis = m.groupValues[6].toIntOrNull() ?: return null
        return hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    }

    private fun todOfNow(): Long {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 3_600_000L +
            calendar.get(Calendar.MINUTE) * 60_000L +
            calendar.get(Calendar.SECOND) * 1_000L +
            calendar.get(Calendar.MILLISECOND)
    }

    private fun todText(tod: Long): String {
        val day = 24 * 3_600_000L
        val t = ((tod % day) + day) % day
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            t / 3_600_000L,
            (t / 60_000L) % 60,
            (t / 1_000L) % 60,
        )
    }

    private fun lineTime(line: String): String {
        val m = TIME_REGEX.find(line) ?: return "-"
        return "${m.groupValues[3]}:${m.groupValues[4]}:${m.groupValues[5]}"
    }

    private fun lineBytes(line: String): Int = line.toByteArray(Charsets.UTF_8).size + 1

    private fun bytesOfLines(lines: List<String>): Int {
        var total = 0
        for (line in lines) total += lineBytes(line)
        return total
    }

    private fun bytesOfGroups(groups: List<GenGroup>): Int {
        var total = 0
        for (group in groups) total += bytesOfLines(group.lines)
        return total
    }

    private fun bytesOfEntries(entries: List<Entry>): Int {
        var total = 0
        for (entry in entries) total += lineBytes(entry.line)
        return total
    }

    private fun bytesOfNotes(notes: List<String>): Int {
        var total = 0
        for (note in notes) total += note.toByteArray(Charsets.UTF_8).size
        return total
    }

    private fun sectionBytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    // ---------- 上传（60s 总超时，409 同名加 -1 重试一次） ----------

    /**
     * vc255：上传流程收口必落盘。
     *
     * 现场反馈：上传本身成功，但「运行日志上传件已生成 / 运行日志上传成功」两行停在
     * LiteLog 的 8 条批量缓冲里没写盘，日志恰好断在上传动作之前，无法取证。
     * 这里只在流程结束时（成功/失败/提前返回）补一次 flush，不改 LiteLog 的常规节流。
     */
    fun upload(prepared: Prepared): Result {
        try {
            return uploadInternal(prepared)
        } finally {
            LiteLog.flushNow()
        }
    }

    private fun uploadInternal(prepared: Prepared): Result {
        val token = BuildConfig.GITEE_LOG_TOKEN
        if (token.isEmpty()) return Result(false, null, "GITEE_LOG_TOKEN 未配置")
        val encoded = try {
            Base64.encodeToString(prepared.file.readBytes(), Base64.NO_WRAP)
        } catch (error: Exception) {
            return Result(false, null, "读取本地文件失败：${error.message.orEmpty()}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var attemptName = prepared.fileName
        var lastError = "未知错误"
        for (attempt in 0..1) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return Result(false, null, timeoutText())
            val timedOut = AtomicBoolean(false)
            val outcome = try {
                postGiteeContents(
                    token,
                    attemptName,
                    stamp,
                    prepared.sessions,
                    encoded,
                    remaining,
                    timedOut,
                )
            } catch (error: Exception) {
                if (timedOut.get()) return Result(false, null, timeoutText())
                val detail = UploadFailure.scrub(error.message.orEmpty(), token)
                LiteLog.log("log", "运行日志上传异常：${error.javaClass.simpleName} $detail")
                return Result(false, null, "网络不通：$detail")
            }
            if (timedOut.get()) {
                LiteLog.log("log", "运行日志上传超时（60s）")
                return Result(false, null, timeoutText())
            }
            val (code, body) = outcome
            if (code in 200..299) {
                val path = runCatching {
                    JSONObject(body).optJSONObject("content")?.optString("path").orEmpty()
                }.getOrDefault("")
                val remote = path.ifEmpty { attemptName }
                LiteLog.log("log", "运行日志上传成功：$remote")
                return Result(true, remote, null)
            }
            lastError = UploadFailure.describe(code, body, token)
            LiteLog.log("log", "运行日志上传失败：$lastError")
            if ((code == 409 || body.contains("already exists", ignoreCase = true)) && attempt == 0) {
                attemptName = prepared.fileName.removeSuffix(".txt") + "-1.txt"
                continue
            }
            break
        }
        return Result(false, null, lastError)
    }

    private fun timeoutText(): String = "上传超时（${TIMEOUT_MILLIS / 1000}s）"

    private fun postGiteeContents(
        token: String,
        name: String,
        stamp: String,
        sessions: Int,
        encoded: String,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
    ): Pair<Int, String> {
        val url = URL(
            "https://gitee.com/api/v5/repos/wangliunian/display--log-collection/contents/" +
                URLEncoder.encode(name, "UTF-8") +
                "?access_token=" + URLEncoder.encode(token, "UTF-8"),
        )
        val body = JSONObject()
            .put("content", encoded)
            .put("branch", "master")
            .put("message", "DiPlay Lite 运行日志（含 dongle 链路，${sessions} 份会话）$stamp")
            .toString()
        val connection = url.openConnection() as HttpURLConnection
        val perCallTimeout = if (timeoutMs > Int.MAX_VALUE) Int.MAX_VALUE else timeoutMs.toInt()
        val watchdog = Timer(true)
        try {
            watchdog.schedule(object : TimerTask() {
                override fun run() {
                    timedOut.set(true)
                    runCatching { connection.disconnect() }
                }
            }, timeoutMs)
            connection.requestMethod = "POST"
            connection.connectTimeout = perCallTimeout
            connection.readTimeout = perCallTimeout
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            enableTls12ForPreLollipop(connection)
            connection.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            watchdog.cancel()
            connection.disconnect()
        }
    }

    /** Android 4.x（API16-20）默认不启用 TLSv1.2，Gitee 需要：显式换 SSLContext 兜底。 */
    private fun enableTls12ForPreLollipop(connection: HttpURLConnection) {
        if (Build.VERSION.SDK_INT > 20 || connection !is HttpsURLConnection) return
        try {
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, null, null)
            connection.sslSocketFactory = context.socketFactory
        } catch (_: Exception) {
        }
    }
}
