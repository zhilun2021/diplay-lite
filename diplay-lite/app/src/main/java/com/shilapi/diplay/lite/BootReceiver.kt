package com.shilapi.diplay.lite

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.diag.boundedTrace

/**
 * 开机自启：
 * - 勾选"开机自启" → 拉起主页（连接与否交给主页的"自动连接"逻辑）；
 * - 1.0.11：勾选"开机自动开启蓝牙" → BluetoothAdapter.enable()（API16-23 公开方法，静默执行），
 *   轮询等待 STATE_ON；
 * - 1.0.11：勾选"开机自动开启热点" → 蓝牙就绪后反射 setWifiApEnabled(null, true)
 *   （沿用系统已有 AP 配置；STA/AP 互斥，先关 Wi-Fi 再起 AP）。
 *
 * 实测注意：部分 ROM（EMUI 等）自启动管理可能拦截 BOOT_COMPLETED，属系统行为，本程序无法绕过。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = LiteConfig.load(context)
        // vc303（BOOT-300-01）：日志前移到 early-return 之前——原实现全未勾选时静默 return，
        // 「未勾选」与「ROM 未投递 BOOT_COMPLETED」在日志上都表现为「没有 [boot] 行」，无法区分。
        val anyAuto = config.bootAutostart || config.bootAutoBluetooth ||
            config.bootAutoHotspot || config.bootAutoWifi
        LiteLog.log(
            "boot",
            "BOOT_COMPLETED 收到 autostart=${config.bootAutostart} autoBt=${config.bootAutoBluetooth} " +
                "autoAp=${config.bootAutoHotspot} autoWifi=${config.bootAutoWifi} " +
                "autoConnect=${config.autoConnect} 执行=${if (anyAuto) "是" else "否（四项均未勾选）"}",
        )
        if (!anyAuto) return
        // enable()/setWifiApEnabled() + 轮询等待耗时超过 onReceive 上限，投后台线程 + goAsync。
        // 1.0.16（方案四②）：主页先拉起并弹「等待勾选项启动」弹窗（等待/滤波/超时都在 UI 层），
        // 再在后台跑勾选项启动——避免蓝牙轮询 20s 期间主页迟迟不出现。
        val pending = goAsync()
        Thread {
            try {
                if (config.bootAutostart || config.autoConnect) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    LiteLog.log("boot", "开机自启：拉起主页（自启等待弹窗由主页接管）")
                }
                if (config.bootAutoBluetooth) ensureBluetoothOn()
                // STA/AP 互斥：勾了热点自启时热点流程会先关 Wi-Fi，Wi-Fi 自启让位。
                if (config.bootAutoWifi && !config.bootAutoHotspot) ensureWifiOn(context)
                if (config.bootAutoHotspot) ensureHotspotOn(context)
                LiteLog.log("boot", "开机自启流程完成")
            } catch (error: Throwable) {
                LiteLog.log("boot", "开机自启流程失败: ${error.boundedTrace()}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** 开启蓝牙并等待就绪（最长 20 秒）。部分 ROM 拒绝静默 enable()，记日志继续。 */
    private fun ensureBluetoothOn() {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (error: Throwable) {
            LiteLog.log("boot", "蓝牙适配器获取失败: ${error.message}")
            return
        }
        if (adapter == null) {
            LiteLog.log("boot", "蓝牙适配器不可用")
            return
        }
        if (adapter.isEnabled) {
            LiteLog.log("boot", "蓝牙已开启")
            return
        }
        val requested = try {
            adapter.enable()
        } catch (error: Throwable) {
            LiteLog.log("boot", "蓝牙 enable() 调用失败: ${error.message}")
            false
        }
        if (!requested) {
            LiteLog.log("boot", "蓝牙 enable() 被拒绝（ROM 自启动管理/权限策略），跳过等待")
            return
        }
        var waited = 0
        while (waited < 20_000 && !adapter.isEnabled) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                break
            }
            waited += 1_000
        }
        LiteLog.log("boot", "蓝牙开启${if (adapter.isEnabled) "就绪" else "超时"} waited=${waited}ms")
    }

    /**
     * 开启 Wi-Fi（STA 模式）并等待就绪（最长 15 秒）。setWifiEnabled 为公开 API（API16-26 有效，
     * 27+ 对后台调用收窄——Lite 目标机型 api16-23 不受影响）。
     */
    private fun ensureWifiOn(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            LiteLog.log("boot", "WifiManager 不可用，跳过 Wi-Fi 自启")
            return
        }
        if (wifi.isWifiEnabled) {
            LiteLog.log("boot", "Wi-Fi 已开启")
            return
        }
        val started = try {
            wifi.setWifiEnabled(true)
        } catch (error: Throwable) {
            // vc303：显式捕获并记录类名（Error 不是 Exception，runCatching 会把它吞成 false）。
            LiteLog.log("boot", "Wi-Fi setWifiEnabled(true) 异常（${error.javaClass.name}）: ${error.message}")
            false
        }
        if (!started) {
            LiteLog.log("boot", "Wi-Fi setWifiEnabled(true) 被拒绝（ROM 策略），跳过等待")
            return
        }
        var waited = 0
        while (waited < 15_000 && !wifi.isWifiEnabled) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                break
            }
            waited += 1_000
        }
        LiteLog.log("boot", "Wi-Fi 开启${if (wifi.isWifiEnabled) "就绪" else "超时"} waited=${waited}ms")
    }

    /**
     * 开启热点（沿用系统已有 AP 配置）。反射调用隐藏 API setWifiApEnabled（API16-25 可用）。
     * T57 教训：低版本兼容对方法存在性一律反射 + runCatching，不信任白牌 ROM。
     */
    private fun ensureHotspotOn(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            LiteLog.log("boot", "WifiManager 不可用，跳过热点自启")
            return
        }
        val beforeState = wifiApState(wifi)
        LiteLog.log("boot", "热点自启前 AP state=$beforeState（13=已开启）")
        if (beforeState == 13) {
            LiteLog.log("boot", "热点已开启")
            return
        }
        // STA/AP 模式互斥：先关 Wi-Fi 再起 AP
        try {
            WifiManager::class.java
                .getMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
                .invoke(wifi, false)
        } catch (error: Throwable) {
            LiteLog.log("boot", "关闭 Wi-Fi 失败（${error.javaClass.name}）: ${error.message}")
        }
        try {
            Thread.sleep(800)
        } catch (_: InterruptedException) {
        }
        val started = try {
            WifiManager::class.java
                .getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                .invoke(wifi, null, true) as? Boolean ?: false
        } catch (error: Throwable) {
            // vc303：显式捕获并把类名记进日志（NoSuchMethodError 是 Error，runCatching 会吞成 false）。
            LiteLog.log("boot", "setWifiApEnabled 调用异常（${error.javaClass.name}）: ${error.message}")
            false
        }
        if (!started) {
            LiteLog.log("boot", "热点自启被拒绝 setWifiApEnabled=false AP state=${wifiApState(wifi)}")
            return
        }
        // vc303（BOOT-300-01）：setWifiApEnabled 返回 true 不代表 AP 已起——轮询 AP state，
        // 把「已执行但失败」与「已执行且成功」区分开（原实现只调一次、不校验结果）。
        val apReadyWaitMs = 10_000
        var waited = 0
        var state = wifiApState(wifi)
        while (waited < apReadyWaitMs && state != 13) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                break
            }
            waited += 500
            state = wifiApState(wifi)
        }
        LiteLog.log(
            "boot",
            "热点自启结果=${if (state == 13) "ready" else "timeout"} AP state=$state waited=${waited}ms",
        )
    }

    /** 反射读取 AP 状态（13=ENABLED）；取不到返回 -1 并记录异常类名。 */
    private fun wifiApState(wifi: WifiManager): Int = try {
        WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as? Int ?: -1
    } catch (error: Throwable) {
        LiteLog.log("boot", "getWifiApState 调用失败（${error.javaClass.name}）: ${error.message}")
        -1
    }
}
