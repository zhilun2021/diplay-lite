package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * 2026-11-07 有线实验：内核 cdc_ncm 数据面探测（对齐 DiPlay 4.2 的 restoreKernelDriver 链路）。
 *
 * 与用户态 NCM + TUN 路径的根本差别：本机**不 claim** NCM control/data、不建 VpnService TUN、
 * 不建 Ipv6NcmBridge，数据面完全由内核 cdc_ncm 驱动创建的 USB 网卡承载。
 * 因此探测必须自己确认「驱动在 → 网卡 up → 拿到带 scope 的链路本地 IPv6 → 读到 MAC」，
 * 任一步失败都返回**具体原因**，由调用方结束本次有线会话（不做 TUN 回落、不自动重试）。
 */
enum class KernelNcmFailure(val reason: String, val failureClass: String) {
    /**
     * **构建问题**：native 库没加载 / 缺 `restoreKernelDriver` 符号 / 库与当前 ABI 不匹配。
     * 与内核能力无关——换一台机器也一样失败，必须重新出包或确认装的是含新 `.so` 的 APK。
     */
    NATIVE_LIBRARY_MISMATCH(
        "usb host native library missing or symbol mismatch (build problem)",
        "build",
    ),

    /**
     * **机器限制**：内核没有 `cdc_ncm` 驱动，或 usbfs 交还驱动的 ioctl 不被该内核支持。
     * 这是机器/内核能力问题，换 APK 也解决不了；只能放弃内核 NCM 路径。
     */
    KERNEL_DRIVER_MISSING(
        "kernel has no usable cdc_ncm driver (machine limitation)",
        "machine",
    ),

    /** 驱动在，但网卡始终没有 up。 */
    INTERFACE_NOT_UP("kernel NCM interface did not come up", "machine"),

    /** 网卡 up 了，但 /proc/net/if_inet6 里没有可用的链路本地地址。 */
    NO_IPV6("kernel NCM interface has no link-local IPv6 address", "machine"),

    /** 读不到 /sys、/proc 或 USB fd（SELinux/权限限制）。 */
    PERMISSION("cannot read kernel network state", "runtime"),

    /** 网卡状态异常（MAC 非法等）。 */
    INVALID_INTERFACE("kernel NCM interface state is invalid", "machine"),

    /** 限时窗口内没有出现内核 NCM 网卡（驱动探测/枚举没有完成）。 */
    TIMEOUT("timed out waiting for the kernel NCM interface", "machine"),
}

class KernelNcmException(
    val failure: KernelNcmFailure,
    val detail: String,
) : IOException("${failure.reason}: $detail") {
    /** 日志锚点 `failed reason=…` 与界面提示共用这一句。 */
    val reasonText: String get() = "${failure.reason}: $detail"
}

/** 探测成功的内核 NCM 网卡。 */
data class KernelNcmInterface(
    val name: String,
    /** 带 scope 的链路本地地址：AirPlay 监听/datagram 绑定必须用它。 */
    val address: Inet6Address,
    /** 小写冒号分隔 MAC。 */
    val mac: String,
) {
    /** 下发 iPhone 的地址：去掉 `%scope`（iPhone 按自己链路解析）。 */
    val addressWithoutScope: String = formatIpv6Literal(address.address)

    /** 日志锚点 `addr=…%iface` 用的带 scope 文本。 */
    val addressWithScope: String get() = "$addressWithoutScope%$name"

    val macBytes: ByteArray =
        mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
}

object KernelNcmProbe {
    private const val POLL_MILLIS = 250L
    private const val DEFAULT_TIMEOUT_MILLIS = 20_000L
    private const val CDC_NCM_DRIVER = "cdc_ncm"
    private val MAC_PATTERN = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    /**
     * 恢复内核驱动绑定并等待网卡就绪。
     *
     * @param controlInterfaceId 当前活动配置里的 NCM control 接口号（内核 cdc_ncm 绑定它）。
     * @throws KernelNcmException 任一步失败，[KernelNcmException.reasonText] 可直接上屏。
     */
    fun restoreAndAwait(
        connection: UsbDeviceConnection,
        controlInterfaceId: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onDiagnostic: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): KernelNcmInterface {
        val driverDir = File("/sys/bus/usb/drivers/$CDC_NCM_DRIVER")
        if (!driverDir.exists()) {
            throw KernelNcmException(
                KernelNcmFailure.KERNEL_DRIVER_MISSING,
                "${driverDir.path} is not present (this kernel has no $CDC_NCM_DRIVER)",
            )
        }
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) {
            throw KernelNcmException(KernelNcmFailure.PERMISSION, "usb connection file descriptor unavailable")
        }
        if (!LegacyUsbHostNative.available) {
            throw KernelNcmException(
                KernelNcmFailure.NATIVE_LIBRARY_MISMATCH,
                "libusb_host_compat.so could not be loaded",
            )
        }
        onDiagnostic("wired kernel-ncm probe restore driver ctrl-iface=$controlInterfaceId")
        // 符号缺失/库不匹配必须和「内核没有 cdc_ncm」分开：前者是构建问题（换包可救），
        // 后者是机器限制（换包无解）。UnsatisfiedLinkError 明确归构建问题。
        val errno = try {
            LegacyUsbHostNative.restoreKernelDriver(fd, controlInterfaceId)
        } catch (error: UnsatisfiedLinkError) {
            throw KernelNcmException(
                KernelNcmFailure.NATIVE_LIBRARY_MISMATCH,
                "restoreKernelDriver symbol missing: ${error.message ?: "-"}",
            )
        } catch (error: Throwable) {
            throw KernelNcmException(
                KernelNcmFailure.NATIVE_LIBRARY_MISMATCH,
                "restoreKernelDriver call failed: ${error.javaClass.simpleName}: ${error.message ?: "-"}",
            )
        }
        when (errno) {
            0 -> Unit
            // EBUSY = 内核驱动本来就绑在该接口上（无需恢复）。
            16 -> onDiagnostic("wired kernel-ncm probe driver already bound ctrl-iface=$controlInterfaceId")
            else -> throw KernelNcmException(
                KernelNcmFailure.KERNEL_DRIVER_MISSING,
                "restoreKernelDriver ctrl-iface=$controlInterfaceId errno=$errno (kernel refused USBDEVFS_CONNECT)",
            )
        }

        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var sawAppleInterface = false
        var notUpInterface: String? = null
        var noIpv6Interface: String? = null
        while (true) {
            if (isCancelled()) {
                throw KernelNcmException(KernelNcmFailure.TIMEOUT, "session closed while probing")
            }
            val names = listNetInterfaceNames()
            for (name in names) {
                if (isCancelled()) {
                    throw KernelNcmException(KernelNcmFailure.TIMEOUT, "session closed while probing")
                }
                val driver = driverNameOf(name)
                if (driver != null && driver != CDC_NCM_DRIVER) {
                    // 同一个 Apple 设备存在网卡但绑的是别的驱动 → 明确报驱动不存在。
                    if (isAppleUsbInterface(name)) {
                        throw KernelNcmException(
                            KernelNcmFailure.KERNEL_DRIVER_MISSING,
                            "iface=$name driver=$driver (expected $CDC_NCM_DRIVER)",
                        )
                    }
                    continue
                }
                if (driver == null) continue
                if (!isAppleUsbInterface(name)) continue
                sawAppleInterface = true
                val nif = runCatching { NetworkInterface.getByName(name) }.getOrNull()
                if (nif == null || !nif.isUp) {
                    notUpInterface = name
                    continue
                }
                notUpInterface = null
                val mac = readMac(name)
                val address = findLinkLocal(name) ?: run {
                    noIpv6Interface = name
                    null
                }
                if (address != null) {
                    return KernelNcmInterface(name, address, mac)
                }
            }
            if (System.nanoTime() >= deadline) {
                throw when {
                    noIpv6Interface != null -> KernelNcmException(
                        KernelNcmFailure.NO_IPV6,
                        "iface=$noIpv6Interface has no usable link-local address in /proc/net/if_inet6",
                    )
                    notUpInterface != null -> KernelNcmException(
                        KernelNcmFailure.INTERFACE_NOT_UP,
                        "iface=$notUpInterface is not up",
                    )
                    sawAppleInterface -> KernelNcmException(
                        KernelNcmFailure.NO_IPV6,
                        "Apple NCM interface appeared but no link-local address was published",
                    )
                    else -> KernelNcmException(
                        KernelNcmFailure.TIMEOUT,
                        "no $CDC_NCM_DRIVER network interface appeared within ${timeoutMillis}ms",
                    )
                }
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun listNetInterfaceNames(): List<String> {
        val root = File("/sys/class/net")
        return runCatching { root.list()?.toList() }.getOrNull()
            ?: throw KernelNcmException(
                KernelNcmFailure.PERMISSION,
                "cannot list ${root.path}",
            )
    }

    private fun driverNameOf(name: String): String? =
        runCatching { File("/sys/class/net/$name/device/driver").canonicalFile.name }.getOrNull()

    private fun isAppleUsbInterface(name: String): Boolean {
        val uevent = runCatching { File("/sys/class/net/$name/device/uevent").readText() }.getOrNull()
        // uevent 读不到时按「可能是」处理，避免把可用的内核网卡漏判成超时。
        return uevent == null || uevent.contains("PRODUCT=5ac/", ignoreCase = true)
    }

    private fun readMac(name: String): String {
        val file = File("/sys/class/net/$name/address")
        val mac = runCatching { file.readText().trim().lowercase(Locale.US) }.getOrElse {
            throw KernelNcmException(
                KernelNcmFailure.PERMISSION,
                "cannot read ${file.path}: ${it.javaClass.simpleName}",
            )
        }
        if (!MAC_PATTERN.matches(mac)) {
            throw KernelNcmException(KernelNcmFailure.INVALID_INTERFACE, "iface=$name has invalid MAC '$mac'")
        }
        return mac
    }

    private fun findLinkLocal(name: String): Inet6Address? {
        val file = File("/proc/net/if_inet6")
        val lines = runCatching { file.readLines() }.getOrElse {
            throw KernelNcmException(
                KernelNcmFailure.PERMISSION,
                "cannot read ${file.path}: ${it.javaClass.simpleName}",
            )
        }
        for (line in lines) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 6 || fields[5] != name) continue
            // 列：address ifindex prefixlen scope flags ifname；scope=0x20 即链路本地。
            if (fields[3].toIntOrNull(16) != 0x20) continue
            // 与 4.2 一致：排除非稳定地址（flag 位含 0x40/0x08）。
            val flags = fields[4].toIntOrNull(16) ?: continue
            if (flags and 72 != 0) continue
            val bytes = parseHexAddress(fields[0]) ?: continue
            val nif = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: return null
            // API16~18 没有 NetworkInterface#getIndex（API19+），退回 sysfs ifindex；
            // 链路本地地址必须带 scope，否则 bind/connect 会拒绝（"link-local without scope id"）。
            val scopeId = runCatching { nif.index }.getOrNull()
                ?: runCatching { File("/sys/class/net/$name/ifindex").readText().trim().toInt() }.getOrNull()
            return if (scopeId != null && scopeId > 0) {
                Inet6Address.getByAddress(null, bytes, scopeId)
            } else {
                Inet6Address.getByAddress(null, bytes, nif)
            }
        }
        return null
    }

    private fun parseHexAddress(text: String): ByteArray? {
        val compact = text.trim()
        if (compact.length != 32) return null
        return try {
            ByteArray(16) { index -> compact.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

private fun formatIpv6Literal(bytes: ByteArray): String {
    val builder = StringBuilder(39)
    for (index in 0 until 16 step 2) {
        if (index > 0) builder.append(':')
        builder.append(String.format(Locale.US, "%02x%02x", bytes[index], bytes[index + 1]))
    }
    return builder.toString()
}
