package com.shilapi.xcertplay.network

import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

/**
 * vc262：网络地址族守卫。
 *
 * 目的：把「本机到底能不能用 IPv6」这件事在**软件启动时自检一次**并缓存下来，
 * 后续选址/广播/监听只查这个结论，不再各自猜：
 *   1 类 = 可 IPv6（栈可用；热点接口上也确实拿得到可用 v6 地址）
 *   2 类 = 仅 IPv4（IPv6 栈关闭 / 接口拿不到 v6 / 绑定失败）
 *
 * 注意：**这是车机侧的能力分类，不是 iPhone 侧**。iPhone 的地址族能力在 AirPlay
 * 会话建立前没有任何 Android API 可查；对端实际走哪一族只能看 `airplay connection
 * accepted from` 的源地址。因此本守卫只用于「本机不该走 v6 时别走」，不能用来推测
 * iPhone 的版本或快慢（见 工程文档/13-定案-无线冷启动35s因果与红线.md）。
 *
 * 判定口径（全部为 Android/Java 公开 API，API16 安全）：
 *   ① /proc/net/if_inet6 存在且非空 —— 内核 IPv6 栈启用；
 *   ② 存在 up、非回环接口上的可用 v6 地址（优先 fe80 link-local，且带 scope）；
 *   ③ IPv6 通配地址绑定探针（`ServerSocket` 绑 `::`）—— 排除「有地址但栈被裁剪」的 ROM。
 * ①③ 任一缺失判 2 类；热点接口分类额外要求 ②。
 */
enum class NetworkFamilyClass(val code: Int, val label: String) {
    IPV6_CAPABLE(1, "可IPv6"),
    IPV4_ONLY(2, "仅IPv4"),
}

data class NetworkFamilyVerdict(
    val familyClass: NetworkFamilyClass,
    val reason: String,
    val interfaceName: String? = null,
    val ipv6Address: String? = null,
    val ipv4Address: String? = null,
) {
    fun describe(): String = buildString {
        append("class=").append(familyClass.code).append('(').append(familyClass.label).append(')')
        append(" reason=").append(reason)
        interfaceName?.let { append(" iface=").append(it) }
        append(" ipv6=").append(ipv6Address ?: "none")
        append(" ipv4=").append(ipv4Address ?: "none")
    }
}

object NetworkFamilyGuard {

    @Volatile
    private var startup: NetworkFamilyVerdict? = null

    /** 启动自检（幂等）：只跑一次，结果缓存；失败一律降级为 2 类，不抛异常。 */
    @Synchronized
    fun selfCheckAtStartup(): NetworkFamilyVerdict {
        startup?.let { return it }
        val verdict = computeStartup()
        startup = verdict
        return verdict
    }

    fun startupVerdict(): NetworkFamilyVerdict? = startup

    /**
     * 热点接口级分类：接口存在、up、非回环，且拿得到可用 v6 地址才算 1 类。
     * 拿不到接口信息时回落到启动自检结论（避免把「还没起热点」误判成 2 类）。
     */
    fun classifyInterface(interfaceName: String?): NetworkFamilyVerdict {
        if (interfaceName.isNullOrBlank()) return startup ?: selfCheckAtStartup()
        val nif = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull()
            ?: return NetworkFamilyVerdict(
                NetworkFamilyClass.IPV4_ONLY,
                "iface-missing",
                interfaceName,
            )
        val scan = scan(interfaceName) ?: return NetworkFamilyVerdict(
            NetworkFamilyClass.IPV4_ONLY,
            "iface-unreadable",
            interfaceName,
        )
        if (!scan.up || scan.loopback) {
            return NetworkFamilyVerdict(NetworkFamilyClass.IPV4_ONLY, "iface-down", interfaceName)
        }
        if (scan.ipv6 == null) {
            return NetworkFamilyVerdict(
                NetworkFamilyClass.IPV4_ONLY,
                "iface-no-ipv6",
                interfaceName,
                ipv4Address = scan.ipv4?.hostAddress,
            )
        }
        return NetworkFamilyVerdict(
            NetworkFamilyClass.IPV6_CAPABLE,
            "iface-ok",
            interfaceName,
            scan.ipv6.hostAddress,
            scan.ipv4?.hostAddress,
        )
    }

    /** 组合结论：启动自检已判 2 类就保持 2 类；否则以传入接口的实际能力为准。 */
    fun effectiveVerdict(interfaceName: String? = null): NetworkFamilyVerdict {
        val boot = startup ?: selfCheckAtStartup()
        if (boot.familyClass == NetworkFamilyClass.IPV4_ONLY) return boot
        return if (interfaceName.isNullOrBlank()) boot else classifyInterface(interfaceName)
    }

    fun isIpv6Capable(interfaceName: String? = null): Boolean =
        effectiveVerdict(interfaceName).familyClass == NetworkFamilyClass.IPV6_CAPABLE

    private fun computeStartup(): NetworkFamilyVerdict = try {
        val procEnabled = runCatching {
            val file = File("/proc/net/if_inet6")
            file.exists() && file.readLines().any { it.isNotBlank() }
        }.getOrDefault(false)
        // API16 兼容：ServerSocket 到 API19 才实现 Closeable，`use {}` 在 API16 上会类转换失败。
        // 这里手动 try/finally 关闭，语义与 use 一致。
        val bindOk = runCatching {
            val socket = ServerSocket()
            try {
                socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
                true
            } finally {
                runCatching { socket.close() }
            }
        }.getOrDefault(false)
        val scan = scan(null)
        val ipv6 = scan?.ipv6
        if (!procEnabled && !bindOk) {
            NetworkFamilyVerdict(
                NetworkFamilyClass.IPV4_ONLY,
                "stack-disabled(proc=$procEnabled bind=$bindOk)",
                ipv4Address = scan?.ipv4?.hostAddress,
            )
        } else if (!bindOk) {
            NetworkFamilyVerdict(
                NetworkFamilyClass.IPV4_ONLY,
                "bind-failed",
                ipv6Address = ipv6?.hostAddress,
                ipv4Address = scan?.ipv4?.hostAddress,
            )
        } else {
            NetworkFamilyVerdict(
                NetworkFamilyClass.IPV6_CAPABLE,
                if (ipv6 != null) "stack-ok+addr" else "stack-ok(no-iface-addr)",
                ipv6Address = ipv6?.hostAddress,
                ipv4Address = scan?.ipv4?.hostAddress,
            )
        }
    } catch (_: Throwable) {
        // 自检失败绝不影响启动/建链，按最保守的 2 类处理。
        NetworkFamilyVerdict(NetworkFamilyClass.IPV4_ONLY, "self-check-error")
    }

    private class InterfaceScan(
        val up: Boolean,
        val loopback: Boolean,
        val ipv6: Inet6Address?,
        val ipv4: Inet4Address?,
    )

    private fun scan(onlyInterface: String?): InterfaceScan? {
        return try {
            val enumeration = NetworkInterface.getNetworkInterfaces() ?: return null
            val interfaces = Collections.list(enumeration)
            var firstIpv6: Inet6Address? = null
            var firstIpv4: Inet4Address? = null
            var sawUp = false
            var sawLoopback = false
            // 优先看 Wi-Fi/AP/以太网，移动数据（rmnet/ccmni/ppp）排最后：
            // 手机上有蜂窝 v6 不代表热点接口也能走 v6，日志里也不该先报蜂窝地址。
            for (nif in interfaces.sortedByDescending { interfacePriority(it.name) }) {
                if (onlyInterface != null && nif.name != onlyInterface) continue
                val up = runCatching { nif.isUp }.getOrDefault(false)
                val loopback = runCatching { nif.isLoopback }.getOrDefault(false)
                if (onlyInterface != null) {
                    // 指定接口：如实返回 up/loopback，由调用方判定
                    val addresses = runCatching {
                        Collections.list(nif.inetAddresses)
                    }.getOrDefault(emptyList<InetAddress>())
                    return InterfaceScan(
                        up,
                        loopback,
                        addresses.filterIsInstance<Inet6Address>().firstOrNull { usableIpv6(it) },
                        addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress },
                    )
                }
                if (!up) continue
                sawUp = true
                if (loopback) {
                    sawLoopback = true
                    continue
                }
                val addresses = runCatching {
                    Collections.list(nif.inetAddresses)
                }.getOrDefault(emptyList<InetAddress>())
                if (firstIpv6 == null) {
                    firstIpv6 = addresses.filterIsInstance<Inet6Address>().firstOrNull { usableIpv6(it) }
                }
                if (firstIpv4 == null) {
                    firstIpv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
                }
                if (firstIpv6 != null && firstIpv4 != null) break
            }
            InterfaceScan(sawUp, sawLoopback && !sawUp, firstIpv6, firstIpv4)
        } catch (_: Throwable) {
            null
        }
    }

    private fun interfacePriority(name: String): Int = when {
        name.startsWith("ap") || name.startsWith("wlan") ||
            name.startsWith("p2p") || name.startsWith("eth") -> 3
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("ppp") -> 0
        else -> 1
    }

    private fun usableIpv6(address: Inet6Address): Boolean = try {
        when {
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress -> false
            // fe80 link-local 在 Android 上必须带 scope（接口索引），否则不可用于绑定。
            address.isLinkLocalAddress -> runCatching { address.scopeId }.getOrDefault(0) != 0
            else -> true
        }
    } catch (_: Throwable) {
        false
    }
}
