package com.shilapi.diplay.lite

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.usbbt.UsbBinderGate
import com.shilapi.xcertplay.usbbt.UsbBtBootstrapStream
import com.shilapi.xcertplay.usbbt.UsbBtStandby
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 外置 USB 蓝牙（dongle）兜底：有界自检 / 掉线感知。
 *
 * vc245 背景（T3 2026-11-05 现场）：dongle 掉线后重新拔插无反应。旧代码有两处盲区：
 * ① MainActivity 的 DETACHED 接收器只处理 Apple 设备（0x05ac），dongle（0x0a12）被忽略；
 * ② openDevice 没有超时，system_server 一旦 D 态卡在 usbdev_open，App 会无限等、也无日志。
 *
 * 本类只做两件可交付的事：
 * 1. 有界自检：openDevice 放独立线程 + 3s 看门狗，超时判 OPEN_TIMEOUT，不再无限等；
 * 2. 三态可区分：设备不在 / 未授权 / 打开超时 / 打开失败，各落一行可判别日志。
 *
 * 天花板（实测结论，别宣称已解决）：若 system_server 线程已经 D 态卡在 usbdev_open，
 * openDevice 与任何 sysfs 写都会跟着卡住，App 层无解，只能重启 system_server 或整机。
 */
object UsbDongleGuard {
    const val VENDOR_ID = 0x0a12
    const val PRODUCT_ID = 0x0001

    /** openDevice 看门狗：T3 正常 <300ms，3s 足够区分「慢」与「内核卡死」。 */
    const val OPEN_TIMEOUT_MS = 3_000L

    /**
     * vc246：整段自检看门狗。binder 段仍按 [OPEN_TIMEOUT_MS]=3s 单独判定（超时即置
     * D 态粘性门禁）；整段 25s 是为了容纳「等常驻广播 host 初始化」与 HCI 探针。
     */
    const val CHECK_TIMEOUT_MS = 25_000L

    /** vc246：等常驻广播 host 初始化完成的上限（实测 dongle 冷启 3–10s）。 */
    private const val STANDBY_READY_WAIT_MS = 15_000L

    // vc246（USB-246-03）：自检成功判据升级——新增「控制器不应答 HCI」。
    // vc247（V2）：SKIPPED = 代际交接期跳过，不增不减失败计数、不上 UI。
    enum class Status { OK, ABSENT, NO_PERMISSION, OPEN_TIMEOUT, OPEN_FAILED, HCI_NO_RESPONSE, SKIPPED }

    /** [alert] 供 UI 判断是否上屏：仅连续真失败达到阈值且尚未报过时为 true。 */
    data class Report(val status: Status, val detail: String, val alert: Boolean = false)

    /** vc247（V2）：连续真失败阈值（单次失败只进日志，达到才上 UI）。 */
    private const val ALERT_THRESHOLD = 2

    /** vc247（V2）：进程级计数，**不以代际编号作键**（交接会重置计数）。进程重启才清零。 */
    @Volatile private var consecutiveFailures = 0
    @Volatile private var alertShown = false

    /** vc247（V2）：单飞，防 Activity 重建/多处调用重复计数与并跑。 */
    private val checkRunning = AtomicBoolean(false)

    // vc310：在场判定的缓存参数。sysfs 只读、不走 binder（D 态下仍可读）；
    // 2s TTL 避免 renderState 的 500ms tick 每拍都扫一遍 /sys。
    private const val PRESENCE_TTL_MS = 2_000L
    @Volatile private var presenceCache = false
    @Volatile private var presenceCacheAt = 0L

    /** vc246（USB-246-04）：sysfs 旁证是否仍能看到 0a12（只读、不走 binder，D 态下仍可读）。 */
    val sysfsPresent: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            if (presenceCacheAt != 0L && now - presenceCacheAt < PRESENCE_TTL_MS) return presenceCache
            val present = runCatching { sysfsNode() != null }.getOrDefault(false)
            presenceCache = present
            presenceCacheAt = now
            return present
        }

    /** vc310：USB 插拔时强制失效，下一次读立即重扫。 */
    fun invalidatePresence() {
        presenceCacheAt = 0L
        presenceAnyAt = 0L      // vc311：新增缓存一并失效，插拔后下一读立即重算
    }

    // ── vc311：三路合一的在场判定 ────────────────────────────────────────────
    // 独立缓存字段：**不复用** presenceCache / presenceCacheAt（那两个是 sysfsPresent
    // 的私有缓存，混用会让 sysfs 旁证读回 binder 的结论，语义互相污染）。
    @Volatile private var presenceBinder = false
    @Volatile private var presenceSysfs = false
    @Volatile private var presenceAnyAt = 0L
    private var lastPresenceKey: String? = null

    /**
     * vc311（UI-310-03）：dongle 是否在场。
     * 任一路为真即在场；sysfs 只做老设备兜底，**不参与否决**。
     * ① standby 在跑 ⇒ 直接 true（纯内存，零 binder / 零 sysfs 扫描）
     * ② 否则事件驱动：presenceAnyAt == 0 时查一次（由 invalidatePresence 归零）
     */
    fun isPresent(context: Context): Boolean {
        // vc313（UI-312-01）：短路判据由 isRunning 改为 isHostReady。
        // isRunning 在 start() 里、open dongle **之前**就置 true ⇒ 冷启动无 dongle 的那个窗口
        // 会把「在场」误报成 true，使 externalBtGateBlocked() 放过自动连接（vc312 门② D 组实证）。
        // isHostReady = isRunning && hostReadyGeneration == generation && generation != 0，
        // 语义是「host 已初始化成功」＝ dongle 真的被打开了，且带代次校验。
        if (UsbBtStandby.isHostReady) {
            // host 已就绪就必然在场（零 binder / 零 sysfs 扫描）。
            notePresence(true, null, null)
            return true
        }
        // vc311：**事件驱动**，不是 TTL。presenceAnyAt 只由 invalidatePresence()
        //（USB 插拔 / onResume / 重连前）归零 ⇒ binder 只在插拔后查一次，
        // 绝不在 renderState 的 500ms tick 里周期性查（deviceList 是到 system_server
        // 的 binder，system_server 一旦 D 态卡在 usbdev_open 就会阻塞主线程，vc245 教训）。
        if (presenceAnyAt == 0L) {
            // getSystemService(String) 是 API1；**禁**用 getSystemService(Class)（API23+，minSdk16 会崩）
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            presenceBinder = manager != null && findDongle(manager) != null
            presenceSysfs = sysfsPresent          // 复用其自带 2s 缓存，不重复扫 /sys
            presenceAnyAt = SystemClock.elapsedRealtime()
        }
        notePresence(false, presenceBinder, presenceSysfs)
        return presenceBinder || presenceSysfs
    }

    /** B-104：四元组变化才打，避免 renderState 的 500ms tick 刷屏。null = 该路本轮未查。 */
    private fun notePresence(running: Boolean, binder: Boolean?, sysfs: Boolean?) {
        val key = "$running/$binder/$sysfs"
        if (key == lastPresenceKey) return
        lastPresenceKey = key
        val src = when {
            running -> "standby"
            binder == true -> "binder"
            sysfs == true -> "sysfs"
            else -> "none"
        }
        LiteLog.log("bt", "B-104 src=$src running=$running binder=$binder sysfs=$sysfs | 在场判定")
    }

    /**
     * vc250：sysfs 旁证看到的 dongle 身份（"vid:pid"，任意 0x0a12 PID，判据与 [findDongle] 一致）。
     * 无设备或读不到 idVendor/idProduct 时返回 null。
     */
    fun sysfsId(): String? {
        val dir = sysfsNode() ?: return null
        val vendor = readHex(File(dir, "idVendor")) ?: return null
        val product = readHex(File(dir, "idProduct")) ?: return null
        return "%04x:%04x".format(vendor, product)
    }

    fun findDongle(manager: UsbManager): UsbDevice? {
        val list = manager.deviceList?.values ?: return null
        return list.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }
            ?: list.firstOrNull { it.vendorId == VENDOR_ID }
    }

    /**
     * 有界自检：**整段**（deviceList / hasPermission / openDevice / close）放独立线程，
     * [timeoutMs] 内未返回 → OPEN_TIMEOUT。
     *
     * vc245：deviceList 与 hasPermission 都是到 system_server 的 binder 调用；system_server
     * 一旦 D 态卡在 usbdev_open，这两步本身就会无限阻塞（此前它们放在看门狗外面，
     * 等于没有超时）。因此整段移入 worker，看门狗只看 latch。
     *
     * 必须在后台线程调用（内部会阻塞最多 timeoutMs）。
     */
    fun check(
        context: Context,
        tag: String,
        timeoutMs: Long = CHECK_TIMEOUT_MS,
        expectStandby: Boolean = false,
    ): Report {
        // vc247（V2）：单飞 —— 已有自检在跑时直接跳过，不重复计数。
        if (!checkRunning.compareAndSet(false, true)) {
            val skipped = Report(Status.SKIPPED, "自检已跳过（已有自检在跑）")
            log(tag, skipped)
            val generation = UsbBtStandby.generationSnapshot
            logStage(tag, skipped, generation, generation)
            return skipped
        }
        try {
            val generationAtStart = UsbBtStandby.generationSnapshot
            val latch = CountDownLatch(1)
            val result = AtomicReference<Report?>(null)
            val abandoned = AtomicReference(false)
            val worker = Thread {
                val report = try {
                    boundedOpen(context.applicationContext, abandoned, expectStandby)
                } catch (error: Throwable) {
                    Report(Status.OPEN_FAILED, "自检异常 ${error.javaClass.simpleName}: ${error.message}")
                }
                result.set(report)
                latch.countDown()
            }
            worker.isDaemon = true
            worker.start()
            val finished = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!finished) {
                abandoned.set(true)
                val report = Report(
                    Status.OPEN_TIMEOUT,
                    "自检 ${timeoutMs}ms 未返回：可能内核态 usbdev_open 卡住，App 层无解，需重启车机",
                )
                return finish(tag, report, generationAtStart, expectStandby)
            }
            val report = result.get() ?: Report(Status.OPEN_FAILED, "自检未产出结果")
            return finish(tag, report, generationAtStart, expectStandby)
        } finally {
            checkRunning.set(false)
        }
    }

    /**
     * vc247（V2）：交接期判定 + 计数 + 日志的统一出口。
     * 只有「非 OK、检查期间代际变了、结束时 standby 仍在跑」才降级为 SKIPPED —— 这是
     * 交接还没完成导致的中间态，既不是失败也不是成功，不参与计数。
     */
    private fun finish(tag: String, report: Report, generationAtStart: Int, tracked: Boolean): Report {
        val generationNow = UsbBtStandby.generationSnapshot
        val effective = if (
            report.status != Status.OK &&
            generationNow != generationAtStart &&
            UsbBtStandby.isRunning
        ) {
            Report(Status.SKIPPED, "自检已跳过（代际交接中）gen=$generationAtStart->$generationNow")
        } else {
            report
        }
        val out = if (tracked) track(effective) else effective
        log(tag, out)
        logStage(tag, out, generationAtStart, generationNow)
        return out
    }

    /**
     * vc250：自检结论落 usb-enum，只记录、不改控制流。
     * 三档结论：ok（HCI 正常应答）/ no-response（HCI 没回话）/ unavailable（su 不可用、
     * 无权限或环境不满足）；status 保留原始细分，gen 记录自检起止代际供 T3-D 对照。
     */
    private fun logStage(tag: String, report: Report, generationAtStart: Int, generationNow: Int) {
        val outcome = when (report.status) {
            Status.OK -> "ok"
            Status.HCI_NO_RESPONSE -> "no-response"
            else -> "unavailable"
        }
        runCatching {
            LiteLog.stage(
                LiteLog.Stage.UsbEnum,
                "role=dongle check=$outcome status=${report.status} context=$tag " +
                    "gen=$generationAtStart->$generationNow",
            )
        }
    }

    /** vc247（V2）：OK 清零、真失败 +1、SKIPPED 不增不减；达到阈值只上屏一次。 */
    private fun track(report: Report): Report {
        when (report.status) {
            Status.OK -> {
                consecutiveFailures = 0
                alertShown = false
                return report
            }
            Status.SKIPPED -> return report
            else -> Unit
        }
        consecutiveFailures += 1
        val alert = consecutiveFailures >= ALERT_THRESHOLD && !alertShown
        if (alert) alertShown = true
        LiteLog.log(
            "usbdongle",
            "self-check fail streak=$consecutiveFailures threshold=$ALERT_THRESHOLD alert=$alert",
        )
        return report.copy(alert = alert)
    }

    private fun boundedOpen(
        context: Context,
        abandoned: AtomicReference<Boolean>,
        expectStandby: Boolean,
    ): Report {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Report(Status.ABSENT, "UsbManager 不可用")
        // vc246（USB-246-03）：三段 binder 分别计时，>3s 即置进程级粘性 D 态门禁。
        val listStarted = System.currentTimeMillis()
        val device = findDongle(manager)
            ?: return Report(Status.ABSENT, "设备列表未发现 vendor 0x0a12 的 dongle")
        noteBinderSpan("getDeviceList", listStarted)
        val permissionStarted = System.currentTimeMillis()
        val permitted = manager.hasPermission(device)
        noteBinderSpan("hasPermission", permissionStarted)
        if (!permitted) return Report(Status.NO_PERMISSION, "设备在列表但无 USB 授权")
        // vc246：成功判据从「openDevice 非 null」升级为「HCI 真实应答」。
        // **铁律：绝不与常驻广播抢端口。** 常驻广播在跑（或即将启动，如开机自检）时，
        // 另开一个驱动 forceClaim 会把正在初始化的 host 顶掉（vc246 K60 实测：自检探针
        // 把刚启动的 host 打断，20s 后才恢复）。此路径只等 host 初始化结果，不 openDevice。
        if (expectStandby || UsbBtStandby.isRunning) {
            val deadline = System.currentTimeMillis() + STANDBY_READY_WAIT_MS
            while (System.currentTimeMillis() < deadline && !UsbBtStandby.isHostReady) {
                if (abandoned.get()) return Report(Status.OPEN_TIMEOUT, "自检超时（已放弃）")
                if (!UsbBtStandby.isRunning && !expectStandby) break
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return if (UsbBtStandby.isHostReady) {
                Report(Status.OK, "常驻广播 host 已初始化（HCI 已应答）${device.deviceName}")
            } else {
                Report(
                    Status.HCI_NO_RESPONSE,
                    "常驻广播 ${STANDBY_READY_WAIT_MS / 1000}s 内未完成 HCI 初始化（${UsbBtStandby.describe()}）",
                )
            }
        }
        val openStarted = System.currentTimeMillis()
        val connection = manager.openDevice(device)
        noteBinderSpan("openDevice", openStarted)
        if (connection == null) return Report(Status.OPEN_FAILED, "openDevice 返回 null")
        runCatching { connection.close() }
        // 超时后 worker 才返回：结果作废（已 close），由看门狗那侧报 OPEN_TIMEOUT。
        if (abandoned.get()) return Report(Status.OPEN_TIMEOUT, "open 在超时后才返回（已 close）")

        val hciError = UsbBtBootstrapStream.probeHci(context)
        return if (hciError == null) {
            Report(Status.OK, "HCI 初始化成功（ReadLocalVersion 有应答）${device.deviceName}")
        } else {
            Report(Status.HCI_NO_RESPONSE, hciError)
        }
    }

    /** vc246（USB-246-03）：binder 调用耗时采样，超 3s 置粘性门禁并落六字段日志。 */
    private fun noteBinderSpan(source: String, startedMs: Long) {
        val elapsed = System.currentTimeMillis() - startedMs
        if (UsbBinderGate.noteCall(source, elapsed)) {
            val line = "usb_246_03: layer=usb trigger=binder-slow holder=none" +
                " gen=${UsbBtStandby.generationSnapshot} present=${sysfsId() ?: "absent"}" +
                " next=forbid-open detail=$source ${elapsed}ms"
            LiteLog.log("usbdongle", line)
            Log.w("usbdongle", line)
        }
    }

    /** dongle 对应的 sysfs 节点（只读扫描，不需要 root 通常也能读 idVendor/idProduct）。 */
    fun sysfsNode(): File? {
        val entries = File("/sys/bus/usb/devices").listFiles() ?: return null
        for (dir in entries) {
            val vendor = readHex(File(dir, "idVendor"))
            // vc250：判据与 findDongle 一致——任意 0x0a12 PID 都算 dongle，不再限定 0x0001。
            if (vendor == VENDOR_ID) return dir
        }
        return null
    }

    private fun log(tag: String, report: Report) {
        val line = "usbdongle [$tag] ${report.status} ${report.detail}"
        LiteLog.log("usbdongle", line)
        Log.i("usbdongle", line)
    }

    private fun readHex(file: File): Int? = runCatching {
        file.readText().trim().toInt(16)
    }.getOrNull()
}
