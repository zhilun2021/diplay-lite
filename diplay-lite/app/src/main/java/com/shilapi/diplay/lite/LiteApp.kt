package com.shilapi.diplay.lite

import android.app.Application
import android.os.Environment
import com.shilapi.xcertplay.diag.CarPlayStageLog
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
        val directory = logDirectory()
        // vc254：必须在挂载新文件之前判决上一会话，否则新文件会被当成"上一份"。
        val previousVerdict = LiteLog.classifyPreviousSession(directory)
        LiteLog.attachFileLog(directory)
        // vc250：共享层 USB/权限阶段直接落同一个日志出口（服务未起也能记）。
        CarPlayStageLog.install { stage, detail -> LiteLog.stage(stage, detail) }
        // vc250（VC248-10）：keepalive 判死等共享层失败也走同一 failure 出口。
        CarPlayStageLog.installFailure { stage, failureClass, detail ->
            LiteLog.failure(stage, failureClass, detail)
        }
        // vc250-05：凭据三态的源头事实（0x5703 下发瞬间是否已有 TCP / TCP 是否到达）。
        CarPlayStageLog.installCredential(
            credential = { beforeTcp -> LiteLog.credentialIssued(beforeTcp) },
            tcpAccepted = { LiteLog.airPlayTcpAccepted() },
            // vc253：静默轮判据命中 → summary failureKind=credential-no-auth。
            noAuth = { LiteLog.credentialNoAuth() },
        )
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
            // vc254：崩溃行只有这一条，常规 8 条批量 flush 等不到就已被杀——必须立刻落盘，
            // 否则下次启动的崩溃判决（[LiteLog.classifyPreviousSession]）永远判不出来。
            LiteLog.flushNow()
            previous?.uncaughtException(thread, throwable)
        }
        LiteLog.log("app", "DiPlay Lite 进程启动 versionName=${packageManager.getPackageInfo(packageName, 0).versionName}")
        // 本地日志也带归机码：用户直接拷日志时同样能分辨来自哪台机器。
        // 设备码是明文蓝牙 MAC + ROM 指纹（VC250-02）；describe 在取不到 MAC 时写明 unknown 的具体原因。
        LiteLog.log("app", "设备码 ${DeviceCode.describe()}")
        // vc254：上一会话退出方式（crash/clean/abnormal/none），上传时据此提示"上次异常"。
        LiteLog.log("app", "上会话判决：${LiteLog.verdictLabel(previousVerdict)}（$previousVerdict）")
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
