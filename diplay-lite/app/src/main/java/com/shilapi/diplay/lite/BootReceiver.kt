package com.shilapi.diplay.lite

import android.annotation.SuppressLint
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
                // vc318（BT-302-01）：上电后蓝牙栈可能处于「SDP 可见但 RFCOMM 全灭」半死态
                //（C8815 六轮实证：与谁拉蓝牙无关、66 分钟不自愈、复位必愈）。
                // vc319（BOOT-318-01）：改为**仅勾选自启蓝牙时**才复位——不勾时蓝牙交给系统恢复，
                // Lite 一律不碰（避免把本来正常的用户蓝牙打进 disable→enable 重建流程）；
                // 同时迟到的 BOOT_COMPLETED（T3 实测比出画晚 52s）也不会再触发复位打死实时链路。
                if (config.bootAutoBluetooth) ensureBluetoothReady(config.bootAutoBluetooth)
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

    /**
     * vc318（BT-302-01）：复位蓝牙并等待就绪（原 ensureBluetoothOn 的复位增强版）。
     * 流程：disable（若已开）→ 等 STATE_OFF（最长 8s）→ enable → 等 STATE_ON（最长 20s）
     * → 稳定期 10s（vc318 实证：enable 就绪后 secure 通道仍有约 2 轮 SDF 过渡期）。
     * [alsoEnable]=false 时只复位不负责拉起（autoBt=false 场景，蓝牙由系统恢复，
     * 复位后回到系统开启状态由 enable 步骤完成——disable 之后必须 enable，否则蓝牙没开）。
     * minSdk16 红线核对：disable()/enable()/getState() 均 API16 可用公开方法。
     */
    @SuppressLint("MissingPermission") // vc318：BLUETOOTH_ADMIN/CONNECT 已在 Manifest 声明；目标机型 API16-19 走旧权限模型
    private fun ensureBluetoothReady(alsoEnable: Boolean) {
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
        // ①复位：已开才 disable（未开没有复位对象，直接走 enable）
        var disabled = false
        if (adapter.state == BluetoothAdapter.STATE_ON) {
            LiteLog.log("boot", "蓝牙复位开始（BT-302-01：上电半死态例行规避）")
            val disableOk = try {
                adapter.disable()
            } catch (error: Throwable) {
                LiteLog.log("boot", "蓝牙 disable() 调用失败（${error.javaClass.name}）: ${error.message}")
                false
            }
            if (disableOk) {
                var waitedOff = 0
                while (waitedOff < 8_000 && adapter.state != BluetoothAdapter.STATE_OFF) {
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    waitedOff += 1_000
                }
                disabled = adapter.state == BluetoothAdapter.STATE_OFF
                LiteLog.log("boot", "蓝牙复位 disable 完成 waited=${waitedOff}ms off=$disabled")
            } else {
                LiteLog.log("boot", "蓝牙复位 disable 被拒绝（ROM 策略），继续拉起")
            }
        }
        // ②拉起：autoBt=false 时也要 enable（disable 过必须拉回来；没 disable 过且未开时同样拉起）
        if (!alsoEnable && !disabled && adapter.state == BluetoothAdapter.STATE_ON) {
            // 系统已恢复且本次未 disable：保持原 ensureBluetoothOn 的早退行为，但仍做稳定期
            //（系统自恢复的栈正是半死高发场景，复位才是本次的主目的——此分支只在
            // 「disable 被拒绝」时可达，稳定期照做，日志写明）。
            LiteLog.log("boot", "蓝牙未复位（disable 被拒），仅等待稳定期")
            stableSettle()
            return
        }
        val requested = try {
            adapter.enable()
        } catch (error: Throwable) {
            LiteLog.log("boot", "蓝牙 enable() 调用失败（${error.javaClass.name}）: ${error.message}")
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
        if (adapter.isEnabled) stableSettle()
    }

    /** vc318：复位后稳定期（栈收敛，规避 secure 通道过渡期 SDF）。 */
    private fun stableSettle() {
        try {
            Thread.sleep(10_000)
        } catch (_: InterruptedException) {
        }
        LiteLog.log("boot", "蓝牙复位稳定期 done stableMs=10000")
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
