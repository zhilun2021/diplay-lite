package com.shilapi.diplay.lite

import com.shilapi.xcertplay.root.SuShell
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * vc96：有线会话 root 诊断探针——内核级 USB 取证。
 *
 * 背景（FE-6 20261017 断链）：wedge 看门狗判死后重枚举发现不到 iPhone，Android 层
 * 只能看到「设备不在列表」，无法回答「谁先动手」（iPhone 侧复位退出 CarPlay 配置 vs
 * 车机 host 端口楔死）。内核 dmesg 有完整 USB 事件线（disconnect/reset/枚举错误/
 * MUSB wedge print），root 可读——这是区分两者的唯一铁证。
 *
 * 设计约束：
 *  - su 可用才启动（SessionService 已有 root 探测）；无 root 静默关闭，零成本
 *  - 常驻仅增量读 dmesg 的 USB 相关行（每 2s，su -c dmesg 全量拉取后本地过滤增量，
 *    4.x 内核 dmesg 环形缓冲一般 ≤64KB，全量拉取成本可接受；有的老 ROM 无 -c/--read-clear）
 *  - wedge/断链事件时抓 sysfs 设备树快照（挂起状态+驱动绑定）
 *  - 所有输出走 LiteLog（TAG=usbfx），进文件日志可被 Gitee 采集
 *  - su 调用复用 vc94 的超时模式：pump 线程 + exitValue 轮询，防 su 弹框挂死线程
 */
internal object WiredUsbForensics {

    private const val TAG = "usbfx"
    private const val POLL_INTERVAL_MILLIS = 2_000L
    private const val SU_TIMEOUT_MILLIS = 5_000L
    /** dmesg 增量过滤关键词：USB 事件 + NCM/以太 + MUSB/chipidea/dwc3 host 控制器。 */
    private const val DMESG_FILTER =
        "usb|USB|cdc_ncm|cdc_ether|musb|chipidea|dwc3|ehci|reset|disconnect"
    /** vc97⑤：内核时间戳提取（形如 [ 248.509207]，含 dmesg -t 无前缀格式兜底）。 */
    private val TS_PATTERN = Regex("\\[\\s*(\\d+\\.\\d+)\\]")

    /** vc326：未退出后台任务上限（含卡在 API16 `Process.destroy()` 的滞留任务）。 */
    private const val MAX_LIVE_TASKS = 2

    private val running = AtomicBoolean(false)

    /** vc326：任务代次——`stop()` 时 +1，使全部在途任务立即过期（不依赖 interrupt 成功）。 */
    private val epoch = AtomicLong(0L)

    /** vc326：当前未退出任务数（含滞留任务）。 */
    private val liveTasks = AtomicInteger(0)

    /** vc326：在途线程（快照遍历，用于尽力 interrupt）。 */
    private val threads = Collections.synchronizedList(mutableListOf<Thread>())

    /**
     * vc326（40b §1.4）：状态跃迁临界区——`running` / `epoch` / `liveTasks` 的组合读写必须原子，
     * 否则 stop 与 start 交错会丢新会话探针（窗口 A/B/C）。
     * **慢调用（suExec/dmesg/sleep）一律在锁外**，否则重演 ANR。
     */
    private val stateLock = Any()

    @Volatile
    private var suOk = false

    /**
     * 会话建立时调用：仅有线通道启动增量探针；重复调用幂等。
     *
     * vc326（C8815/API16 ANR）：su 探测与 dmesg 轮询全部移入后台线程。原实现把
     * `suExec("id")` 放在调用线程（= 服务主线程），API16 上 `SuShell` 超时后的
     * `Process.destroy()` 会等 `sushell-out` 输出流锁，直接把会话启动卡成 ANR。
     */
    fun start(
        onlyUsb: Boolean,
        slowSuMillis: Long = 0L,
        report: (String) -> Unit = { LiteLog.log(TAG, it) },
    ) {
        if (!onlyUsb) {
            report("probe skipped: channel not usb")
            return
        }
        // vc326：running 幂等门 → liveTasks 上限 → epoch++ → liveTasks++ 必须整体原子（40b 窗口 A/B/C）。
        val myEpoch = synchronized(stateLock) {
            if (!running.compareAndSet(false, true)) return
            if (liveTasks.get() >= MAX_LIVE_TASKS) { // 防堆积：旧任务卡住不无限新建
                running.set(false)
                return@synchronized -1L
            }
            liveTasks.incrementAndGet()
            epoch.incrementAndGet()
        }
        if (myEpoch < 0L) {
            report("probe skipped: too many live tasks")
            return
        }
        val worker = Thread({
            var stale = false
            try {
                if (slowSuMillis > 0L) {
                    // vc326：门② 慢 su 注入（仅 DEBUG 测试入口会传非 0），模拟 su 卡住；
                    // 仍在后台线程、锁外，验证主线程与会话不被阻塞。
                    try {
                        Thread.sleep(slowSuMillis)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                // su 探测：5s 超时（防 su 弹框挂死），失败即静默关闭。慢 su 只可能卡在本后台线程。
                val id = suExec("id")
                val rootGranted = synchronized(stateLock) {
                    if (myEpoch != epoch.get()) {
                        stale = true
                        false
                    } else {
                        suOk = id.contains("uid=0")
                        suOk
                    }
                }
                if (stale) return@Thread
                if (!rootGranted) {
                    report("probe disabled: su unavailable")
                    return@Thread
                }
                if (myEpoch != epoch.get()) {
                    stale = true
                    return@Thread
                }
                report("probe started (epoch=$myEpoch dmesg usb filter every ${POLL_INTERVAL_MILLIS / 1000}s)")
                // vc97⑤：内核时间戳增量去重——旧 lastLine 尾行匹配在部分 ROM（T3 实锤）上
                // 因输出顺序/格式抖动匹配失败，每拍全量重放启动期行（212K 日志中 263 行全是
                // 0.x 秒老行）。dmesg 时间戳单调递增，按 [sec.millis] 维护水位：ts ≤ 水位丢弃。
                var maxTsMillis = -1.0
                var polls = 0
                while (running.get() && myEpoch == epoch.get()) {
                    try {
                        val dmesg = suExec("dmesg")
                        if (myEpoch != epoch.get()) {
                            stale = true
                            break
                        }
                        if (dmesg.isNotEmpty()) {
                            val fresh = dmesg.lines()
                                .filter { it.isNotBlank() && Regex(DMESG_FILTER).containsMatchIn(it) }
                                .filter { line ->
                                    val ts = TS_PATTERN.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                                    if (ts == null) return@filter true
                                    if (ts <= maxTsMillis) return@filter false
                                    maxTsMillis = ts
                                    true
                                }
                            if (fresh.isNotEmpty()) {
                                if (myEpoch != epoch.get()) {
                                    stale = true
                                    break
                                }
                                fresh.take(60).forEach { report("dmesg: ${it.trim()}") }
                                if (fresh.size > 60) report("dmesg: ... ${fresh.size - 60} more lines suppressed")
                            }
                        }
                        polls++
                        // 每 30 轮（约 1 分钟）心跳一次，确认探针活着（环形缓冲全 USB 静默时 dmesg 无新增行）
                        if (polls % 30 == 0) {
                            if (myEpoch != epoch.get()) {
                                stale = true
                                break
                            }
                            report("heartbeat polls=$polls")
                        }
                    } catch (error: Throwable) {
                        if (myEpoch == epoch.get()) report("poll error: ${error.javaClass.simpleName}: ${error.message}")
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MILLIS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            } finally {
                synchronized(stateLock) {
                    // 关键：旧代次任务迟到返回，不得清掉新一代的 running/suOk（40b 窗口 A/C）。
                    if (myEpoch == epoch.get()) {
                        suOk = false
                        running.set(false)
                    }
                    // liveTasks 语义：**未退出任务数（含滞留）**。每个任务在 start 时只 +1 一次，
                    // 故这里无条件 -1 释放的是自己的名额，永远不会误减新一代任务的名额。
                    liveTasks.decrementAndGet()
                    threads.remove(Thread.currentThread())
                }
                if (stale) report("probe stale task exited epoch=$myEpoch")
            }
        }, "usbfx-boot-$myEpoch").apply { isDaemon = true }
        threads.add(worker)
        worker.start()
    }

    /** 断链/wedge 事件时调用：抓 sysfs 设备树快照（拓扑+挂起态+驱动绑定）+ dmesg 尾部。 */
    fun snapshot(reason: String, report: (String) -> Unit = { LiteLog.log(TAG, it) }) {
        if (!suOk) return
        try {
            report("snapshot begin reason=$reason")
            val devices = suExec(
                "for d in /sys/bus/usb/devices/*; do " +
                    "n=\${d##*/}; " +
                    "v=\$(cat \$d/idVendor 2>/dev/null); p=\$(cat \$d/idProduct 2>/dev/null); " +
                    "s=\$(cat \$d/power/runtime_status 2>/dev/null); " +
                    "drv=\$(readlink \$d/driver 2>/dev/null); " +
                    "echo \"\$n vid=\$v pid=\$p drv=\${drv##*/}\"; done",
            )
            devices.lines().filter { it.isNotBlank() }.take(30).forEach { report("sysfs: $it") }
            // vc97⑤：su -c 内管道在部分 ROM（T3 toybox）不可用（无 tail + 管道失效），
            // 改全量拉取后 Kotlin 侧截尾。
            val tail = suExec("dmesg").lines()
                .filter { it.isNotBlank() }
                .takeLast(120)
            val usbTail = tail.filter { Regex(DMESG_FILTER).containsMatchIn(it) }
            report("snapshot dmesg-tail lines=${usbTail.size}")
            usbTail.take(80).forEach { report("tail: ${it.trim()}") }
            report("snapshot end")
        } catch (error: Throwable) {
            report("snapshot error: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 会话 teardown 时调用：使全部在途任务失效（不依赖 interrupt 成功）。 */
    fun stop() {
        // vc326：epoch++ 与 running=false 必须原子（40b 窗口 A），否则中间插入 start 会丢新探针。
        val toInterrupt = synchronized(stateLock) {
            epoch.incrementAndGet() // 关键：所有在途任务 myEpoch 立即过期
            if (!running.getAndSet(false)) return // 非活跃周期：epoch 已 +1，过期仍生效
            threads.toList() // 快照，避免与 finally 的 threads.remove 并发
        }
        // interrupt 仅加速 sleep 中的正常任务；卡在 API16 destroy 的线程打不断，但已因 epoch 过期被隔离
        toInterrupt.forEach { it.interrupt() }
        LiteLog.log(TAG, "probe stopped")
    }

    /**
     * vc199：转调 [SuShell]（全项目唯一 su 实现）。
     * 本文件原先是「pump 线程 + exitValue 轮询」的成熟写法，也是本次日志里
     * 成功抓到 450 行 root dmesg 的那条通道；保留语义（stderr 并入 stdout）不变，
     * 只是不再单独维护一份。
     */
    private fun suExec(command: String): String =
        SuShell.su(command, SU_TIMEOUT_MILLIS, mergeError = true).stdout
}
