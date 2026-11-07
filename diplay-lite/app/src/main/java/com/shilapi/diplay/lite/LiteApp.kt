package com.shilapi.diplay.lite

import android.app.Application
import android.os.Environment
import com.shilapi.xcertplay.diag.boundedTrace
import java.io.File

/**
 * 进程入口：挂载文件日志 + 捕获未捕获异常（无 adb 实测时这是唯一观测通道）。
 * 日志优先落 /sdcard/DiPlayLite/（车机文件管理器/MTP 直接读取），
 * 外部存储不可用时降级到应用专属目录。
 */
class LiteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LiteLog.attachFileLog(logDirectory())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 兜 Throwable：若是 OutOfMemoryError，拼字符串本身也可能抛 Error，
            // 用 catch(Exception) 会让崩溃日志本身二次失败、日志里什么都留不下。
            try {
                LiteLog.log(
                    "crash",
                    "线程 $thread 未捕获异常：${throwable.boundedTrace(maxFrames = 12)}",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
        LiteLog.log("app", "DiPlay Lite 进程启动 versionName=${packageManager.getPackageInfo(packageName, 0).versionName}")
        // 本地日志也带归机码：用户直接拷日志时同样能分辨来自哪台机器。
        // 设备码是明文蓝牙 MAC（用户拍板）；describe 在取不到 MAC 时写明 unknown 的具体原因。
        LiteLog.log("app", "设备码 ${DeviceCode.describe()}")
    }

    /** targetSdk 19：外部存储写权限安装即授予，无需运行时申请。 */
    private fun logDirectory(): File {
        val external = Environment.getExternalStorageDirectory()
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val dir = File(external, "DiPlayLite")
            if (dir.mkdirs() || dir.isDirectory) return dir
        }
        return getExternalFilesDir(null) ?: File(filesDir, "logs")
    }
}
