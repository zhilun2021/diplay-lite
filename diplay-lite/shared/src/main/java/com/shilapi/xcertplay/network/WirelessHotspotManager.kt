package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.Closeable
import java.net.InetAddress

enum class WirelessHotspotBackend(val label: String) {
    WIFI_P2P("Wi-Fi P2P"),
    LOCAL_ONLY_HOTSPOT("LocalOnlyHotspot"),
    MANUAL_HOTSPOT("Manual hotspot"),
    RELAY_CLIENT("Relay (随身WiFi)"),
}

/** The live Wi-Fi credentials and interface details for one wireless CarPlay hotspot. */
class WirelessHotspotInfo(
    val ssid: String,
    val passphrase: String,
    val security: Iap2WirelessSecurity,
    val channel: Int,
    val frequencyMHz: Int?,
    val bssid: String?,
    val interfaceName: String?,
    val hostAddress: InetAddress?,
    val bandLabel: String,
    val backend: WirelessHotspotBackend,
    /**
     * vc256：channel / frequency 的来源标注（只用于诊断日志，不参与连接判断）。
     * 取值：softap-callback / ap-config / sta-connection / scan / user-config / memory /
     * p2p-group / unknown / unlabeled（后端未标注）。
     */
    val channelSource: String = "unlabeled",
    val frequencySource: String = "unlabeled",
) {
    override fun toString(): String =
        "WirelessHotspotInfo(backend=${backend.label}, ssid='$ssid', " +
            "passphrase=<redacted>, security=$security, channel=$channel, " +
            "channelSource=$channelSource, frequencyMHz=$frequencyMHz, " +
            "frequencySource=$frequencySource, bssid='$bssid', interfaceName=$interfaceName, " +
            "hostAddress=$hostAddress, bandLabel='$bandLabel')"
}

/** Owns one Android Wi-Fi group and all resources needed to keep it alive. */
interface WirelessHotspotManager : Closeable {
    /** vc264：热点侧 STA 客户端数；-1 = 未知（不支持/未注册回调）。 */
    fun staClientCount(): Int = -1
    /**
     * Starts a hotspot and waits up to [timeoutMillis] for its live configuration and AP
     * interface. Implementations must not be called on the main thread.
     */
    fun start(timeoutMillis: Long): WirelessHotspotInfo

    /** The authenticated wireless session has rendered CarPlay; AP creation alone is insufficient. */
    fun onCarPlayConfirmed() {}
}

/**
 * Converts IEEE 802.11 frequency in MHz to its channel number.
 *
 * Android's [android.net.wifi.p2p.WifiP2pGroup.getFrequency] reports MHz, while the iAP2
 * wireless CarPlay payload expects a channel number.
 */
internal fun wifiFrequencyMhzToChannel(frequencyMHz: Int): Int? = when {
    frequencyMHz in 2412..2472 && (frequencyMHz - 2407) % 5 == 0 ->
        (frequencyMHz - 2407) / 5
    frequencyMHz == 2484 -> 14
    frequencyMHz in 5160..5895 && (frequencyMHz - 5000) % 5 == 0 ->
        (frequencyMHz - 5000) / 5
    frequencyMHz in 5955..7115 && (frequencyMHz - 5950) % 5 == 0 ->
        (frequencyMHz - 5950) / 5
    else -> null
}

/** Converts an IEEE 802.11 channel number to MHz when its band is known. */
internal fun wifiChannelToFrequencyMhz(channel: Int, band: Int? = null): Int? = when {
    channel == 0 -> null
    band == 1 && channel in 1..13 -> 2407 + channel * 5
    band == 1 && channel == 14 -> 2484
    band == 2 && channel in 32..177 -> 5000 + channel * 5
    band == 4 && channel in 1..233 -> 5950 + channel * 5
    else -> null
}

/** Maps Android's [android.net.wifi.SoftApConfiguration] band constants to a user-facing label. */
internal fun wifiBandLabel(band: Int?): String? = when (band) {
    1 -> "2.4 GHz"
    2 -> "5 GHz"
    3 -> "2.4 / 5 GHz (auto)"
    4 -> "6 GHz"
    else -> null
}

/** WifiConfiguration uses 0/1/-1; SoftApConfiguration uses a 1/2/4 bit mask. */
internal fun legacyHotspotBandToSoftApBand(band: Int?): Int? = when (band) {
    0 -> 1
    1 -> 2
    -1 -> 3
    else -> null
}

/**
 * Returns only a channel observed from Android, together with where it came from.
 * An unknown channel stays 0 ("auto" / field omitted) instead of echoing the user's desired
 * channel back to the iPhone in 0x4301.
 */
internal class ObservedHotspotChannel(val channel: Int, val source: String)

internal fun observeManualHotspotChannel(
    apChannel: Int,
    softApFrequencyMHz: Int?,
    connectionFrequencyMHz: Int?,
    scanFrequencyMHz: Int?,
    apFrequencyMHz: Int?,
): ObservedHotspotChannel {
    // SoftApCallback 的 frequency 是热点当前真实频点，优先级高于配置值（配置可能是 auto）。
    softApFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let {
        return ObservedHotspotChannel(it, "softap-callback")
    }
    if (apChannel > 0) return ObservedHotspotChannel(apChannel, "ap-config")
    connectionFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let {
        return ObservedHotspotChannel(it, "sta-connection")
    }
    scanFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let {
        return ObservedHotspotChannel(it, "scan")
    }
    apFrequencyMHz?.let(::wifiFrequencyMhzToChannel)?.let {
        return ObservedHotspotChannel(it, "ap-config")
    }
    return ObservedHotspotChannel(0, "unknown")
}

/** 2.4 GHz 的三个非重叠标准信道。 */
private val STANDARD_CHANNELS_2G = intArrayOf(1, 6, 11)
/** 5 GHz 常用非 DFS 信道（车机热点场景足够覆盖）。 */
private val STANDARD_CHANNELS_5G = intArrayOf(36, 44, 149, 157)

/**
 * 把观测到的信道吸附到最近的标准信道后随 0x5703 下发，iPhone 据此定向扫描、加快 join。
 *
 * - 2.4 GHz（1..14）→ 最近 1/6/11（如 3→1、4→6、13→11）
 * - 5 GHz（32..177）→ 最近 36/44/149/157（如 38→36、48→44、161→157）
 * - 信道未知（0）→ 无法猜测实际信道，原样返回 0，由调用方决定兜底值
 */
internal fun snapHotspotChannel(channel: Int): Int {
    val table = when (channel) {
        in 1..14 -> STANDARD_CHANNELS_2G
        in 32..177 -> STANDARD_CHANNELS_5G
        else -> return channel
    }
    var best = table[0]
    var bestDistance = Math.abs(channel - best)
    for (candidate in table) {
        val distance = Math.abs(channel - candidate)
        if (distance < bestDistance) {
            best = candidate
            bestDistance = distance
        }
    }
    return best
}
