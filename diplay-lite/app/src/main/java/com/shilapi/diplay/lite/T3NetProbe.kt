package com.shilapi.diplay.lite

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc271（T3-B 无线取证包）：用 root 在**设备本机**抓「iPhone 为什么不来连 7100」的现场证据。
 *
 * 为什么要这个包：T3-B 的现场没有电脑，没法用 adb/tcpdump 抓包；而现有日志只能证明
 * `JmDNS 广播失败（setsockopt ENODEV）`，仍缺两件事的决定性证据——
 *   ① iPhone 到底有没有发 mDNS 查询（有没有把这里当 CarPlay 网络）；
 *   ② 有没有 SYN 打到 7100（区分「没发现」和「发现了连不上」）。
 *
 * 设计约束：
 *  - **只读**：不写 /system、不改任何配置/路由/防火墙，只 cat 与 tcpdump；
 *  - T3 是 toybox 老 ROM，`su -c` 里的管道/重定向不可用（见 Fe6VoiceProbe 头注），
 *    所以每条命令都是单条、无管道，过滤/截断一律在 App 侧做；
 *  - 全部输出走 LiteLog（TAG=`t3probe`），随现有「运行日志」上传通道走，不另建通道；
 *  - 只在**本取证包**启用：正式包把 [ENABLED] 置 false 即整体失效。
 */
internal object T3NetProbe {

    /** 取证开关。正式包必须 false——root 命令与 tcpdump 只允许出现在测试包。 */
    private const val ENABLED = true

    private val running = AtomicBoolean(false)

    /**
     * 连接发起后延时抓一轮。默认 10s：足够覆盖 0x5703 下发 + iPhone 入网窗口，
     * 又不至于把整段建链日志冲乱。
     */
    fun schedule(context: Context, reason: String, delayMs: Long = 10_000L) {
        if (!ENABLED) return
        val app = context.applicationContext ?: context
        Thread({
            try {
                Thread.sleep(delayMs)
                capture(app, reason)
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                LiteLog.log("t3probe", "取证异常 ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "t3netprobe-schedule").apply { isDaemon = true; start() }
    }

    private fun capture(context: Context, reason: String) {
        if (!running.compareAndSet(false, true)) return
        try {
            val id = execAsRoot("id", 3_000).trim()
            if (!id.contains("uid=0")) {
                LiteLog.log("t3probe", "[$reason] 非 root（id=$id），跳过")
                return
            }
            LiteLog.log("t3probe", "[$reason] begin root=$id model=${android.os.Build.MODEL}")
            dump(reason, "arp", "cat /proc/net/arp")
            dump(reason, "igmp", "cat /proc/net/igmp")
            dump(reason, "maddr", "ip maddr show")
            dump(reason, "addr", "ip -4 addr show")
            dump(reason, "route", "ip route show table all")
            dump(reason, "rule", "ip rule")
            dump(reason, "udp", "cat /proc/net/udp")
            dump(reason, "tcp", "cat /proc/net/tcp")
            dump(reason, "iface-sta", "iw dev wlan0 station dump", limit = 20)
            dump(reason, "iptables-filter", "iptables -L -n -v", limit = 40)
            dump(reason, "iptables-nat", "iptables -t nat -L -n -v", limit = 40)
            // 抓包：5353 找 mDNS 查询/应答，7100 找 AirPlay SYN。各给 15s 窗口。
            dump(reason, "tcpdump-5353", "tcpdump -i wlan0 -n -c 60 udp port 5353", 15_000, 60)
            dump(reason, "tcpdump-7100", "tcpdump -i wlan0 -n -c 40 tcp port 7100", 15_000, 40)
            LiteLog.log("t3probe", "[$reason] end")
        } finally {
            running.set(false)
        }
    }

    /** 跑一条命令并把输出按行落日志；超时只记「超时」，不抛。 */
    private fun dump(
        reason: String,
        label: String,
        command: String,
        timeoutMs: Long = 6_000,
        limit: Int = 60,
    ) {
        val startedAt = System.currentTimeMillis()
        val output = execAsRoot(command, timeoutMs)
        val elapsed = System.currentTimeMillis() - startedAt
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            LiteLog.log("t3probe", "[$reason] $label empty (elapsed=${elapsed}ms)")
            return
        }
        LiteLog.log("t3probe", "[$reason] $label lines=${lines.size} elapsed=${elapsed}ms")
        lines.take(limit).forEach { line ->
            LiteLog.log("t3probe", "[$reason] $label | ${line.trim().take(220)}")
        }
    }

    /**
     * `su -c "<cmd>"`：整串必须是一个参数；用 `ProcessBuilder(vararg)` 避免 shell 转义问题。
     * 超时后 destroy，并在结尾尝试 `killall tcpdump` 收掉遗留子进程（仅测试包）。
     */
    private fun execAsRoot(command: String, timeoutMs: Long): String {
        val proc = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return "exec-failed ${t.javaClass.simpleName}: ${t.message}"
        }
        return try {
            val out = StringBuilder()
            val pump = Thread({
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (out.length < 200_000) out.appendLine(line)
                    }
                }
            }, "t3probe-pump").apply { isDaemon = true; start() }
            var waited = 0L
            while (waited < timeoutMs) {
                Thread.sleep(100)
                waited += 100
                try {
                    proc.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                }
            }
            if (waited >= timeoutMs) {
                runCatching { proc.destroy() }
                if (command.contains("tcpdump")) {
                    runCatching { ProcessBuilder("su", "-c", "killall tcpdump").start().waitFor() }
                }
            }
            pump.join(500)
            out.toString()
        } catch (t: Throwable) {
            "exec-failed ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { proc.destroy() }
        }
    }
}
