package com.shilapi.xcertplay.vehicle

import android.util.Log

/**
 * 2026-11-14（汽水音乐两行歌词/媒体卡两行）：CarPlay iAP2 NowPlayingUpdate(0x5001) 的
 * 轻量解析与共享状态。
 *
 * 背景：ECARX 媒体卡（车机 Dock/音乐卡片）通过 [EcarxMediaCenterBridge] 读取本应用的
 * IMusicPlaybackInfo；此前桥只发布空歌名，卡片只有一行空标题。这里把 iPhone 推来的
 * NowPlayingUpdate 解出「标题/艺人/专辑」，桥再发布给车机，卡片即显示两行
 * （第一行歌名、第二行艺人），汽水音乐等第三方 App 一并生效。
 *
 * 解析为启发式：0x5001 的 TLV 里参数 ID 与编码在不同 iOS 版本上不完全一致，因此
 * 优先按已知 ID（0x0000 标题 / 0x0001 艺人 / 0x0002 专辑）取值，取不到时按 TLV 中
 * 字符串出现顺序回退为 标题/艺人/专辑。字符串同时兼容 UTF-8(NUL 结尾) 与 UTF-16LE。
 * 任何异常都只记日志，绝不影响控制面主链路。
 */
object CarPlayNowPlaying {
    const val MESSAGE_NOW_PLAYING_UPDATE = 0x5001
    private const val TAG = "CarPlayNowPlaying"

    @Volatile var title: String = ""
        private set
    @Volatile var artist: String = ""
        private set
    @Volatile var album: String = ""
        private set

    /** 元数据变化回调（EcarxMediaCenterBridge 注册后即向其车机媒体卡重新发布）。 */
    @Volatile var listener: (() -> Unit)? = null

    fun update(newTitle: String, newArtist: String, newAlbum: String) {
        val t = newTitle.trim()
        val a = newArtist.trim()
        val al = newAlbum.trim()
        if (t == title && a == artist && al == album) return
        title = t
        artist = a
        album = al
        runCatching { listener?.invoke() }
    }

    fun clear() = update("", "", "")

    /** 当前「两行歌词」文本：第一行歌名、第二行艺人（无艺人时退回专辑）。 */
    fun twoLineText(): String {
        val second = artist.ifEmpty { album }
        return when {
            title.isEmpty() && second.isEmpty() -> ""
            second.isEmpty() -> title
            title.isEmpty() -> second
            else -> title + "\n" + second
        }
    }

    /** 解析一条 0x5001 NowPlayingUpdate 的 payload；失败/非 TLV 时静默忽略。 */
    fun onIap2NowPlayingUpdate(payload: ByteArray) {
        val parsed = runCatching { parse(payload) }.getOrNull() ?: return
        update(parsed.title, parsed.artist, parsed.album)
    }

    private data class NowPlaying(val title: String, val artist: String, val album: String)

    private fun parse(data: ByteArray): NowPlaying? {
        if (data.size < 4) return null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        val strings = ArrayList<String>()

        var offset = 0
        while (offset + 4 <= data.size) {
            val totalLength = u16(data, offset)
            if (totalLength < 4 || totalLength > data.size - offset) return null
            val type = u16(data, offset + 2)
            val valueOffset = offset + 4
            val valueLength = totalLength - 4
            val value = if (valueLength > 0) decodeString(data, valueOffset, valueLength) else ""
            if (value.isNotEmpty()) {
                when (type) {
                    0x0000 -> title = value
                    0x0001 -> artist = value
                    0x0002 -> album = value
                }
                strings.add(value)
            }
            offset += totalLength
        }
        if (offset != data.size) return null

        // 已知 ID 全缺失时按出现顺序回退（多数 App：标题、艺人、专辑）。
        if (title.isNullOrEmpty() && strings.isNotEmpty()) {
            title = strings.getOrNull(0)
            if (artist.isNullOrEmpty()) artist = strings.getOrNull(1)
            if (album.isNullOrEmpty()) album = strings.getOrNull(2)
        }
        if (title.isNullOrEmpty()) return null
        return NowPlaying(title.orEmpty(), artist.orEmpty(), album.orEmpty())
    }

    private fun decodeString(data: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        // UTF-16LE 启发式：高字节为 0 的码元占多数即按 UTF-16LE 解。
        var highZero = 0
        var pairs = 0
        var i = offset
        while (i + 1 < offset + length) {
            if (data[i + 1] == 0.toByte()) highZero++
            pairs++
            i += 2
        }
        if (pairs > 0 && highZero * 2 >= pairs) {
            var end = offset + length
            while (end - 2 >= offset && data[end - 2] == 0.toByte() && data[end - 1] == 0.toByte()) end -= 2
            return runCatching { String(data, offset, end - offset, Charsets.UTF_16LE) }
                .getOrDefault("").trim()
        }
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return runCatching { String(data, offset, end - offset, Charsets.UTF_8) }
            .getOrDefault("").trim()
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    fun logState(prefix: String) {
        runCatching {
            Log.d(TAG, "$prefix title=$title artist=$artist album=$album")
        }
    }
}
