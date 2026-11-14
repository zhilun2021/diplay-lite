package com.shilapi.xcertplay.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc272（TS7 热点多播下行失效兜底）：mDNS 单播通告器。
 *
 * 背景（20261114 TS7 vc271 取证）：热点模式下 JmDNS 绑 192.168.43.1 注册成功
 * （Bonjour services started mode=interface），但软 AP 不把自身发出的多播帧转给
 * 已关联 STA——tcpdump 本地能抓到 announce、iPhone 收不到，tcpAccepted 恒为 0，
 * 45s 判死 credential-no-join 循环。同代码中继模式成功：多播经上游路由器（真 AP）
 * 正常双向转发（vc271 17:39 tcpdump：announce 多播 → iPhone 9s 后 SYN 7100），
 * 且 iPhone 入网热点后其自身多播（mobdev2 宣告/companion-link 查询）车机也能收到
 * ——故障只定向在「软 AP 多播下行」。
 *
 * 修复：把 _carplay-ctrl/_airplay 的完整服务记录（PTR/SRV/TXT/A）手组 DNS response
 * 包（flags=0x8400 QR+AA，cache-flush class），UDP 单播直发已关联 iPhone 的 5353。
 * iPhone mDNSResponder 接受单播 announce，收到即解析并连 7100。仅热点模式启用。
 *
 * API16 兼容：纯 java.net（DatagramSocket/DatagramPacket）+ 手写 DNS 字节序，
 * 无任何高 API 依赖；IP 目标由调用方从 /proc/net/arp 观测注入。
 */
internal class WirelessMdnsAnnouncer(
    private val onDiagnostic: (String) -> Unit,
) {
    /** 通告参数（attach 时一次性注入；来自 CarPlayBonjour 同源注册数据）。 */
    class Params(
        /** A 记录地址 = 车机热点 IP（192.168.43.1）。 */
        val bindAddress: InetAddress,
        /** 服务实例名（与 JmDNS 注册同源，如 Honda）。 */
        val instanceName: String,
        /** AirPlay/CarPlay 监听端口（7100）。 */
        val port: Int,
        /** TXT 记录键值（airPlayTxtRecords 产物）。 */
        val txtRecords: Map<String, String>,
    )

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    @Volatile private var params: Params? = null

    /** 目标 iPhone IP（调用方线程安全注入；hostAddress → InetAddress 去重）。 */
    private val targets = LinkedHashMap<String, InetAddress>()

    fun attach(params: Params) {
        this.params = params
    }

    /** 注入一个单播目标（已关联 STA 的 IP）。重复注入自动去重。 */
    fun addTarget(address: InetAddress) {
        val key = address.hostAddress ?: return
        synchronized(targets) { targets[key] = address }
    }

    /** 会话 teardown：清目标（下一代会话重新积累）。 */
    fun clearTargets() {
        synchronized(targets) { targets.clear() }
    }

    /** 启动通告循环：每 [INTERVAL_MILLIS] 一轮，共 [ROUNDS] 轮（覆盖 45s 判死窗）。 */
    fun start() {
        if (!ENABLED) {
            onDiagnostic("mdns unicast announce disabled (vc275: iOS 不依赖广播，实测见设备档案)")
            return
        }
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            try {
                val s = openSocket(params?.bindAddress)
                socket = s
                var round = 0
                while (round < ROUNDS && running.get()) {
                    val p = params
                    val snapshot = synchronized(targets) { targets.values.toList() }
                    if (p != null && snapshot.isNotEmpty()) {
                        val packet = try {
                            buildAnnounce(p)
                        } catch (error: Throwable) {
                            onDiagnostic(
                                "mdns unicast announce build-failed " +
                                    "${error.javaClass.simpleName}: ${error.message}",
                            )
                            null
                        }
                        if (packet != null) {
                            var sent = 0
                            for (target in snapshot) {
                                try {
                                    s.send(DatagramPacket(packet, packet.size, target, MDNS_PORT))
                                    sent++
                                } catch (error: Throwable) {
                                    onDiagnostic(
                                        "mdns unicast announce send-failed to=" +
                                            "${target.hostAddress} ${error.message}",
                                    )
                                }
                            }
                            if (sent > 0) {
                                onDiagnostic(
                                    "mdns unicast announce round=${round + 1} " +
                                        "sent=$sent bytes=${packet.size} " +
                                        "instance=${p.instanceName} port=${p.port}",
                                )
                            }
                        }
                    }
                    round++
                    try {
                        Thread.sleep(INTERVAL_MILLIS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            } catch (error: Throwable) {
                onDiagnostic(
                    "mdns unicast announce loop-exit " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
            } finally {
                running.set(false)
            }
        }.also {
            it.isDaemon = true
            it.name = "mdns-unicast"
            it.start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.interrupt()
        socket?.close()
        thread = null
        socket = null
    }

    // ── DNS response 组包（无名字压缩，全展开；RFC 1035/6762） ──

    private fun buildAnnounce(p: Params): ByteArray {
        val out = java.io.ByteArrayOutputStream(1024)
        // Header：id=0，flags=0x8400（Response+Authoritative），qd=0，an=8
        // 8 条 answer：2 服务 × (PTR + SRV + TXT) + A ×2（两个服务宿主名各一条）
        writeU16(out, 0)
        writeU16(out, 0x8400)
        writeU16(out, 0)
        writeU16(out, 8)
        writeU16(out, 0)
        writeU16(out, 0)

        val ctrlInstance = "${p.instanceName}.${CARPLAY_CTRL_TYPE}.local"
        val airplayInstance = "${p.instanceName}.${AIRPLAY_TYPE}.local"
        val hostName = "${p.instanceName}.local"
        val txtBytes = encodeTxt(p.txtRecords)
        val ipv4 = p.bindAddress.address

        // PTR：服务类型 → 实例（rdata 必须是 DNS label 序列，不能是 ASCII 点分串）
        writeRecord(out, "$CARPLAY_CTRL_TYPE.local", TYPE_PTR, nameBytes(ctrlInstance))
        writeRecord(out, "$AIRPLAY_TYPE.local", TYPE_PTR, nameBytes(airplayInstance))
        // SRV：实例 → 宿主名+端口（cache-flush class=0x8001）
        writeSrv(out, ctrlInstance, hostName, p.port)
        writeSrv(out, airplayInstance, hostName, p.port)
        // TXT：实例 → 键值
        writeRecord(out, ctrlInstance, TYPE_TXT, txtBytes)
        writeRecord(out, airplayInstance, TYPE_TXT, txtBytes)
        // A：宿主名 → 热点 IP
        writeRecord(out, hostName, TYPE_A, ipv4)
        writeRecord(out, hostName, TYPE_A, ipv4)
        return out.toByteArray()
    }

    /** mDNS 单条 answer：name + type + cache-flush class + ttl + rdlength + rdata。 */
    private fun writeRecord(
        out: java.io.ByteArrayOutputStream,
        name: String,
        type: Int,
        rdata: ByteArray,
        ttl: Int = 120,
    ) {
        writeName(out, name)
        writeU16(out, type)
        writeU16(out, 0x8001) // IN + cache-flush
        writeU32(out, ttl)
        writeU16(out, rdata.size)
        out.write(rdata)
    }

    private fun writeSrv(
        out: java.io.ByteArrayOutputStream,
        instance: String,
        hostName: String,
        port: Int,
    ) {
        // SRV rdata：priority=0 weight=0 port target
        val rdata = java.io.ByteArrayOutputStream()
        writeU16(rdata, 0)
        writeU16(rdata, 0)
        writeU16(rdata, port)
        writeName(rdata, hostName)
        writeRecord(out, instance, TYPE_SRV, rdata.toByteArray())
    }

    /** 名字标签编码：按 "." 分段，每段前 1 字节长度，末尾 0；末尾 "." 视为根。 */
    private fun writeName(out: java.io.ByteArrayOutputStream, name: String) {
        val trimmed = name.removeSuffix(".")
        if (trimmed.isEmpty()) {
            out.write(0)
            return
        }
        for (label in trimmed.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    /** mDNS TXT：每条 "key=value" 前 1 字节长度；空 value 只写 key。 */
    private fun encodeTxt(records: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream(256)
        for ((key, value) in records) {
            val text = if (value.isEmpty()) key else "$key=$value"
            val bytes = text.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size.coerceAtMost(255))
            out.write(bytes.take(255).toByteArray())
        }
        if (out.size() == 0) out.write(0)
        return out.toByteArray()
    }

    /** dotted("a.b.local") 的 DNS label 编码产物（PTR rdata 用）。 */
    private fun nameBytes(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(64)
        writeName(out, name)
        return out.toByteArray()
    }

    /**
     * 打开发送 socket：**必须绑 5353 源端口**。
     *
     * RFC 6762 §6.7：接收方若发现 mDNS 包源端口不是 5353，必须静默丢弃。vc272 首包实测
     * （tcpdump 18:21:11 `192.168.43.1.52675 > 192.168.43.160.5353`）用了临时端口，
     * iPhone 全部忽略——这是「通告发出但 iPhone 不连」的第一层原因。
     *
     * JmDNS 已在同一地址占 5353，靠 SO_REUSEADDR 共存（UDP 组播入向仍会复制给两个
     * socket，iPhone 的组播查询 JmDNS 照常收到）。绑不上则降级临时端口并明确记日志。
     */
    private fun openSocket(bindAddress: InetAddress?): DatagramSocket {
        if (bindAddress != null) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(bindAddress, MDNS_PORT))
                onDiagnostic("mdns unicast announce socket bound src=${bindAddress.hostAddress}:$MDNS_PORT")
                return s
            } catch (error: Throwable) {
                onDiagnostic(
                    "mdns unicast announce socket bind-failed src=${bindAddress.hostAddress}:$MDNS_PORT " +
                        "${error.javaClass.simpleName}: ${error.message} (fallback ephemeral; RFC6762 §6.7 接收方可能丢弃)",
                )
            }
        }
        return DatagramSocket()
    }

    private fun writeU16(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeU32(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    companion object {
        /**
         * vc275：默认关闭。
         *
         * vc272/273 曾用「单播 mDNS 通告」兜底软 AP 多播下行，判定依据是 iPhone 收不到广播。
         * 2026-11-14 TS7 对照实测推翻该假设：vc245 的 Bonjour 比首个 TCP 晚 2 秒才 started，
         * iPhone 仍 0.2 秒建链——说明无线 CarPlay 的建链由 iAP2 0x4301 驱动，不依赖 mDNS。
         * 真正断点是 0x4301 缺 channel 字段（见 vc274）。代码保留供排障取证，正式包不启用。
         */
        const val ENABLED = false

        /** 与 CarPlayBonjour 同源服务类型（那边是 private companion，这里自持同值常量）。 */
        private const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val AIRPLAY_TYPE = "_airplay._tcp"
        private const val MDNS_PORT = 5353
        private const val TYPE_A = 1
        private const val TYPE_PTR = 12
        private const val TYPE_TXT = 16
        private const val TYPE_SRV = 33
        /** 每 2s 一轮 × 30 轮 = 60s，覆盖热点会话 45s 判死窗 + 重试余量。 */
        private const val INTERVAL_MILLIS = 2_000L
        private const val ROUNDS = 30
    }
}
