package com.shilapi.xcertplay.network

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        // Before 29, fromSocket owns the original descriptor rather than a duplicate.
        if (Build.VERSION.SDK_INT < 29) return
        try {
            ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                val fd = duplicate.fileDescriptor
                // vc307：断链感知压缩。K60 11-16 13:15 实测：关 iPhone WiFi 后控制面
                // 拖到 22.5s 才报 Connection timed out（TCP_USER_TIMEOUT 20s + 内核给 hang
                // 而非 abort），而重连只需 10.9s —— 感知是恢复总耗时的 2 倍。
                // 取 10s：CarPlay 控制面有持续心跳，10s 无 ACK 已可判死；
                // 再低（<8s）会把 WiFi 信道扫描/短暂抖动误判成掉线，反触发无谓重连。
                // 已知限制：上方 SDK_INT < 29 早退不变，本段只对 API29+ 生效；
                // C8815（API16）/ at8317（API17）仍走默认 keepalive，不受本改动影响。
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 4)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 2)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, 10_000)
                diagnostic("TCP peer health enabled idle=4s interval=2s count=3 deadline=10000ms; no video-idle timeout (vc307)")
            }
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        }
    }
}
