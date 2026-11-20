package com.shilapi.diplay.lite

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.diag.AnchorLog
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.usbbt.UsbBinderGate
import com.shilapi.xcertplay.usbbt.UsbBtRetryPolicy
import com.shilapi.xcertplay.usbbt.UsbBtStandby
import com.shilapi.xcertplay.network.WiredVpnService
import kotlin.math.abs

/**
 * DiPlay Lite 主界面（原版式视觉重构）：视觉语言对齐上游原版 DiPlayActivity（DiAuto 设计）。
 *
 * 结构（原版式）：page 字符串路由 + render() 整页重建，构建代码收敛为原版式辅助函数
 * （dip/label/button/card/column/row/space/matchButton/rounded）。M6k25 单 Activity 化：
 * page = "home" | "settings" | "caricon" | "hotspot-car" | "hotspot-relay" | "steering"，原
 * SettingsActivity/CarIconActivity/HotspotConfigActivity 收编为同包页面对象
 * （SettingsPage/CarIconPage/HotspotPage），根除子 Activity 新窗口出生方向闪转问题。
 *
 * home 页形态（M6k21，对齐原版）：
 * - 「CARPLAY 状态」大状态卡（原版「无线 CARPLAY」式）：眉题 + 实时状态大字 + 主按钮
 *   （空闲=按默认通道连接 / 会话中=断开）+ 默认通道提示；
 *   （hero 文案区已删除，横屏右列改为与状态卡顶底对齐）
 * - USB 直连键（原版右列位）：竖屏在状态卡下方独立成行；横屏在右列 USB+设置。
 *   通道卡取消：热点/中继网络配置入口移入设置页（「车机热点网络配置」「中继热点网络配置」）。
 *
 * 必须保留的既有能力（勿破坏）：
 * - USB 通道连接路径：USB 设备探测（Apple VID）→ VpnService.prepare 授权 → startSession；
 * - 视频层：SurfaceView 铺满 + forwardTouch 触摸转发，ACTIVE 时面板收起、悬浮球唤回；
 * - 悬浮球：初始右对齐上下居中，拖拽后吸附最近左/右边（attachBallDrag）；
 * - errorCard：FAILED 态居中小卡片（≤300dp，标题 + 3 行省略 + 重试）；vc300 起热点未开
 *   等待态也复用该卡片，额外给出「去打开热点」直达键（不停会话）。
 * - 旋转：onConfigurationChanged 分屏处理；布局方向用 Display 实际宽高判定
 *   （isLandscapeDisplay，勿信 configuration.orientation）；ACTIVE/CONNECTING 仅换面板
 *   不动 SurfaceView；空闲态 render() 整页重建。
 *
 * API16 红线：letterSpacing/stateListAnimator/RippleDrawable 均 ≥API21，全部显式护栏；
 * clipToOutline（API18）沿用既有护栏。零 XML，不改 Manifest。
 */
class MainActivity : Activity() {

    /**
     * 1.1.1 UI：全局字体放大 1.2 倍 —— 全部文字均为 sp 单位，覆盖 fontScale 一次生效
     * （含 AlertDialog/弹窗/输入框）。createConfigurationContext 是 API 17+，API 16 回落不放大。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val config = Configuration(newBase.resources.configuration)
            config.fontScale = 1.2f
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private val handler = Handler()

    /** 原版式 page 字符串路由（M6k25 单 Activity 化：全部页面均在本 Activity 内重建）。 */
    private var page = "home"

    // 子页对象：与 page 一一对应，离开页面时 cleanup()（persist/向导注销/线程池关闭等）。
    private var settingsPage: SettingsPage? = null
    // vc320：缓存页面对象与根 View，离页清理临时资源，Activity 销毁时释放。
    private var settingsCachedRoot: FrameLayout? = null
    private var carIconPage: CarIconPage? = null
    private var hotspotPage: HotspotPage? = null
    private var steeringPage: SteeringPage? = null

    // 视图引用：render()/homePanel() 程序化构建并在其中完成全部监听挂接与引用赋值
    private lateinit var video: SurfaceView
    // vc250（VC248-07）：常驻根容器——切页复用同一个 root 与 SurfaceView，只换覆盖层。
    private var rootView: FrameLayout? = null

    // 1.0.29：分辨率设定=自动（拉伸）时，视频不做等比 letterbox，直接铺满容器。
    // onCreate 初始化、onResume 刷新（设置页可能改过）。
    private var videoStretchMode = false
    private lateinit var panel: View
    private lateinit var ballRoot: View
    // vc163：全局悬浮球位置（跨页面保留；null = 尚未拖动过，用默认初始位）。
    // 拖拽结束时由 attachBallDrag 写入；render() 重建球时读回，切页不再复位。
    private var ballTopMargin: Int? = null
    private var ballRightMargin: Int? = null
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView
    // FAILED 态的居中小卡片（标题 + 3 行省略 + 结束）。
    private lateinit var errorCard: View
    private lateinit var errorTextView: TextView
    private lateinit var errorTitleView: TextView
    // vc300：等待热点态（CONNECTING + 「热点未开启」）的直达按钮——只跳系统热点页，不停会话。
    private lateinit var hotspotJumpButton: Button
    /** vc314（INV-308-01）：失败卡的可执行动作按钮（文案与行为按 failureClass 决定）。 */
    private lateinit var errorActionButton: Button
    // M6k24：状态卡三键 —— 立即连接（常驻，连接中置灰）/ 选择 iPhone（常驻）/ 断开连接（仅连接中出现）。
    // 大状态卡主按钮不再做 0/1 状态切换（对齐原版交互）。
    private lateinit var primaryActionButton: Button
    private lateinit var pickIphoneButton: Button
    private lateinit var disconnectButton: Button
    // 默认通道提示行：设置页改默认通道后回主页实时刷新。
    private lateinit var hintLabel: TextView
    // vc184：主页状态卡底部「当前蓝牙」行（车机蓝牙 / 外置蓝牙 + 名称）。
    private lateinit var btModeLabel: TextView
    // vc206：外置蓝牙未配对门槛警示行（warnline，设计稿 §04 G1）。
    private lateinit var pairGateLabel: TextView

    private var panelVisible = true
    private var lastActive = false
    // 与设置页同步：记录当前渲染出的默认通道，tick 里比对 LiteConfig.activeChannel
    private var shownActiveChannel: String? = null
    /** vc310：外置蓝牙三态哨兵（三态互切但文案需刷新时用）。 */
    private var shownExtBtState: ExtBtState? = null
    // vc145（C8815 实测 23:54 定案）：refreshTask 每 500ms 全量重绘，dalvik 低端机
    // 每 tick 主线程 ~11ms 且持续制造分配——状态未变时短路全部 UI 写，降低空闲
    // 主线程负载与 GC 压力（未连接期按钮响应的 app 侧放大器）。
    private var shownPhase: LiteState.Phase? = null
    private var shownRunning: Boolean? = null
    private var shownStatus: String? = null
    // vc308 INV-1：C-3（已连接）后锁定粗状态，晚到的 C-2 不得把界面打回「连接中」。
    // 解锁集合 = 会话终态七码（S-01/S-02/S-12/S-14/S-15/S-16/S-27），见 renderState()。
    private var coarseLocked = false
    // vc308 INV-2：进入 C-2 的时刻（elapsedRealtime；0 = 当前不在 C-2）。
    private var c2SinceElapsed = 0L
    // vc308 INV-2：状态行实际显示文本的哨兵（含「· 已等待 N 秒」后缀）。
    private var shownDisplayText: String? = null
    // 自动连接一个进程生命周期只发起一次，避免用户断开后再被拉起
    private var autoConnectTried = false
    // vc245：手动重连的退避状态（运行中 = 已在重试链里，防重复点击叠加）。
    private var dongleRetryRunning = false
    private var dongleRetryAttempt = 0
    // vc247（V3）：用户主动「断开连接」后抑制 iPhone 回连自动拉起（进程内有效）。
    private var userDisconnected = false
    // vc247（V3）：用户主动「停止外置蓝牙」后抑制回前台/重连自动拉起，重新打开配对页才恢复。
    private var usbBtUserStopped = false

    /**
     * vc319（BT-318-01 方案B）：「已配对即拉起」每 Activity 实例只做一次。
     * Activity 每次 onCreate 都是新实例 ⇒ 天然覆盖「冷启」与「销毁重建」
     * （11-17 21:19 那次 HCI 假报警正发生在重建后的 onResume）；
     * 而单纯回前台（设置页返回、无重建）不重复拉起，保住省电档（`keepAlive=false` 时不反复启动）。
     */
    @Volatile
    private var usbBtAutoBroughtUp = false

    // vc279（ac8317「点下去没反应」取证）：UI 侧最小埋点，只打日志、不改交互逻辑。
    // ① 触摸排队 = uptime - ev.eventTime（主线程被占时输入事件在队列里积压）
    // ② dispatchTouchEvent 分发耗时（含点击回调同步执行）
    // ③ render() 整页重建耗时
    // ④ Choreographer 帧间隔（主线程掉帧）
    // 阈值以下不打日志：LiteLog 是同步写盘，高频记录会反过来拖慢主线程。
    private val slowTouchQueueMs = 100L
    private val slowTouchDispatchMs = 50L
    private val slowRenderMs = 120L
    private val slowFrameGapMs = 200L
    // vc320（UI-318-01 修正）：探针改「双基准」——帧回调 + 主线程心跳。
    // 帧与心跳由主线程写、后台线程读；冷却由后台维护 ⇒ 全部 @Volatile（Kotlin/JVM 对 volatile Long
    // 的读写是原子的，不需要 AtomicLong）。
    @Volatile private var lastUiFrameNs = 0L        // 最近一次 Choreographer 帧回调
    @Volatile private var lastUiHeartbeatNs = 0L    // 最近一次主线程心跳
    @Volatile private var lastFrameLogNs = 0L       // 3s 冷却
    @Volatile private var uiProbePaused = true     // onPause 置真：后台阶段一律不采样
    // vc319 起的采样线程（vc320 补齐销毁时的清理，见 A-4）
    private val uiStackSampler = android.os.HandlerThread("xcertplay-ui-stack").apply { start() }
    private val uiStackHandler = android.os.Handler(uiStackSampler.looper)
    @Volatile private var uiStackSampling = false
    /**
     * vc320：帧回调引用。**必须持有**——vc319 用的是匿名对象 + 无条件自我续订，
     * onPause/onDestroy 都无法移除，等于探针永不停止（后台、息屏后仍在排队）。
     * 由 [installUiFrameMonitor] 写入、[removeUiFrameMonitor] 清空。
     * ⚠️ 它属于本块「改后」，vc319 原文没有这一行。
     */
    private var uiFrameCallback: Choreographer.FrameCallback? = null
    /** 后台轮询周期。100ms 对 200ms 阈值足够（最坏多等 100ms 才发现），且空转只读两个 long。 */
    private val uiStackPollMs = 100L
    /** 心跳周期。必须 < slowFrameGapMs，否则自己就会触发误判。 */
    private val uiHeartbeatMs = 100L
    /** 原始栈保留帧数。24 帧足够覆盖「自家卡点 + 框架调用链」，再长日志无收益。 */
    private val uiStackDepth = 24

    // 1.0.16（方案四②）：开机自启等待弹窗——上电后等勾选的蓝牙/热点就绪并稳定 10s 再自动连接。
    private var autostartWaitActive = false
    private var autostartDialog: AppDialog? = null
    private var autostartStableSecs = 0
    private var autostartDeadline = 0L

    private val autostartPoll = object : Runnable {
        override fun run() {
            // vc177（20261023）：此处原为 `!isFinishing`——Activity 存活时 isFinishing=false
            // ⇒ !isFinishing 恒真 ⇒ 轮询体**从不执行**、也不再 postDelayed，自启等待永远停在
            // 「等待中」直到 60s 超时弹窗，实际从未发起自动连接（「上车即连」形同虚设）。
            // 正确语义是「等待未激活 或 页面正在结束 ⇒ 退出」，与下方 refreshTask 的
            // `if (!isFinishing)` 同一写法。改动仅去掉一个 `!`。
            if (!autostartWaitActive || isFinishing) return
            val config = LiteConfig.load(this@MainActivity)
            // 就绪判定：勾了蓝牙自启 → 蓝牙已开；勾了热点自启 → AP state=13（AOSP ENABLED）。
            val btReady = !config.bootAutoBluetooth || runCatching {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            }.getOrDefault(false)
            val apReady = !config.bootAutoHotspot || runCatching {
                val wifi = getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
                val state = wifi?.let {
                    android.net.wifi.WifiManager::class.java.getMethod("getWifiApState").invoke(it) as Int
                } ?: -1
                state == 13
            }.getOrDefault(false)
            if (btReady && apReady) {
                autostartStableSecs += 1
            } else {
                autostartStableSecs = 0
            }
            autostartDialog?.setMessage(
                "等待勾选的蓝牙或WIFI热点启动…" +
                    (if (autostartStableSecs > 0) "（已就绪，稳定中 $autostartStableSecs/10s）" else ""),
            )
            if (autostartStableSecs >= AUTOSTART_STABLE_SECS) {
                LiteLog.log("ui", "自启等待：勾选项就绪且稳定 ${AUTOSTART_STABLE_SECS}s，发起自动连接")
                finishAutostartWait()
                maybeAutoConnect()
                return
            }
            if (System.currentTimeMillis() >= autostartDeadline) {
                LiteLog.log("ui", "自启等待：超时 ${AUTOSTART_WAIT_TIMEOUT_MILLIS / 1000}s，自动连接已取消")
                autostartWaitActive = false
                autostartDialog?.apply {
                    // vc303（BOOT-303-01）：ROM 拦截 BOOT_COMPLETED 或拒绝静默开启时不再静默失败，
                    // 给可执行出口（直达热点/网络共享页；系统自启动白名单需用户在本机设置里放行）。
                    setMessage(
                        "等待超时：勾选的蓝牙/热点未就绪，自动连接已取消。" +
                            "若每次开机都如此，请在系统「自启动管理」中允许本应用，或手动打开热点/蓝牙后重试。",
                    )
                    setPositiveButton("去打开热点") {
                        SystemSettingsJumps.openHotspotSettings(this@MainActivity)
                    }
                    show()
                }
                autostartDialog = null
                return
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            renderState()
            if (!isFinishing) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // vc248（VC248-03）：配对窗内配对成功 → 降回「仅可连接」并在界面明示。
        UsbBtStandby.onPairSuccess = {
            if (LiteConfig.usbBtPowerModeOf(this) == LiteConfig.USB_BT_MODE_DISCOVERABLE) {
                LiteConfig.setUsbBtPowerMode(this, LiteConfig.USB_BT_MODE_CONNECTABLE)
                UsbBtStandby.setDiscoverable(false)
                LiteLog.log("ui", "配对成功，档位已切回仅可连接")
                runOnUiThread { toast("配对成功，已切回仅可连接") }
            }
        }
        // 车机形态：状态栏也收掉，投屏画面占满整块屏。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        // vc101（at8317 用户反馈）：会话期系统栏常驻占用画面顶部（AC822X ROM 定制
        // 导航/状态合一栏，FLAG_FULLSCREEN 压不住）。挂 systemUiVisibility 三标志：
        // HIDE_NAVIGATION 收导航栏、FULLSCREEN 收状态栏、LOW_PROFILE 弱化残余控件。
        // GS1/AC822X 实测 systemUiVisibility 无法隐藏顶栏；原“监听变化 + 1.5s 后自动
        // 重藏”已移除（无效且形成无限重设循环），只保留一次性隐藏尝试。
        hideSystemBars()
        // M4l：车机屏绝不能休眠（API16 无 setOutputSurface，Surface 重建 = 解码器重配）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientationMode(this, LiteConfig.load(this).orientationMode)
        videoStretchMode = LiteConfig.load(this).resolutionMode == "stretch"
        render()
        installUsbBtPairConfirmHook()
        // vc326（40b §2.2 方案1）：门② 测试入口——仅 DEBUG 测试构建。由可导出的 MainActivity
        // 接收 forceUsbProbe/forceSlowSu，再由 App 自己启动**未导出**的 LiteSessionService
        // （绝不把 Service 改成 exported）。release 包 BuildConfig.DEBUG=false，整段不生效。
        if (BuildConfig.DEBUG &&
            intent?.getBooleanExtra(LiteSessionService.EXTRA_FORCE_USB_PROBE, false) == true
        ) {
            val slowSu = intent?.getIntExtra(LiteSessionService.EXTRA_FORCE_SLOW_SU, 0) ?: 0
            LiteLog.log("ui", "测试入口：forceUsbProbe=true slowSu=${slowSu}ms（仅 DEBUG 构建）")
            startService(
                Intent(this, LiteSessionService::class.java)
                    .putExtra(LiteSessionService.EXTRA_FORCE_USB_PROBE, true)
                    .putExtra(LiteSessionService.EXTRA_FORCE_SLOW_SU, slowSu),
            )
        }
        // 1.0.16（方案四②）：开机自启拉起（BootReceiver 带 EXTRA_AUTOSTART）→ 等待弹窗。
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            beginAutostartWait()
        }
        // 1.0.11（FE-6 拉伸）：解码器实际输出尺寸 ≠ 面板尺寸时等比 letterbox，不硬拉伸。
        LiteVideoBridge.setVideoSizeListener { width, height ->
            runOnUiThread { applyVideoFit(width, height) }
        }
        LiteLog.log("ui", "主界面就绪（原版式视觉：page 路由 + render 整页重建）")
        installUiFrameMonitor()
        startUiStackSampler()
        // vc102 副屏探测：仪表/HUD 投射路线判生死，默认日志检测（无按钮），结果写 LiteLog cluster 标签。
        ClusterDisplayProbe.probe(this)
        // vc270：状态栏探针已摘除（VC270-02）——GS1 顶栏压不住已成定论，留它每次进页面都要
        // spawn `sh -c dumpsys window`，低端车机上是纯开销。
        // vc148（启动阶段 VPN 状态检测）：先清理上次会话可能残留的 TUN fd/服务，
        // 再进入自动连接——残留的异常 VPN 连接若不清，establish 会在旧 fd 上叠新会话。
        ensureCleanVpnState()
        // vc149（USB 拔插彻底清理）：此前拔线 app 零感知——旧 controller/解码器/7000
        // 监听/VPN 全部残留，重插后 iPhone 对旧地基叠会话（实测 shown=0、触摸延迟 190ms
        // 的脏会话）。拔线即执行「用户断开等价清理」（LiteSessionService.onDestroy 全拆
        // controller 栈 + mediaSink + VPN），重插 ATTACHED 经 onNewIntent 触发全新建链。
        registerUsbDetachReceiver()
        // vc250：冷启动由 ATTACHED intent 直接拉起时不会走 onNewIntent，这里补一次 usb-enum。
        logUsbAttach(intent)
        // vc245：外置蓝牙兜底自检（有界 3s、三态可判别）。只挂 dongle 链路，
        // 车机蓝牙链路零变化。
        if (LiteConfig.load(this).usbBtDongle) startDongleSelfCheck()
        // vc151（targetSdk 34）：targetSdk 19→34 后 RECORD_AUDIO（语音上行）/
        // ACCESS_FINE_LOCATION（无线 B3 信道）/POST_NOTIFICATIONS（33+ 通知）变运行时权限。
        // 启动即批量申请（系统弹一次窗），拒绝则对应功能降级、不阻塞连接主流程；
        // API 23- 机型安装即授予，本函数零操作，老车机零回归。
        requestRequiredRuntimePermissions()
        bootstrapNetworkAdb()
    }

    /**
     * vc151（targetSdk 34）：运行时权限批量申请。仅对「未授予且需要运行时申请」的权限
     * 发起 requestPermissions；重复启动（旋转/重建）时已授予项自动跳过，弹窗只出现一次。
     * 拒绝的后果：RECORD_AUDIO → CarPlay 语音上行不可用（单向听）；定位 → 无线链路
     * 信道读取降级；POST_NOTIFICATIONS → 前台服务通知不显示（会话不受影响）。
     */
    private fun requestRequiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val wanted = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        // vc151：API 31+ 蓝牙新权限模型（K60 实测 getBondedDevices 无 CONNECT 直接闪退）
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            wanted.add(android.Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(android.Manifest.permission.POST_NOTIFICATIONS)
            // vc157（Wi-Fi Direct 一级模式）：API 33+ P2P 建组前置权限；拒绝只影响
            // 选了 Wi-Fi Direct 承载方式的场景（提示去系统设置授予），不影响车机热点/中继主路径。
            wanted.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            LiteLog.log("ui", "运行时权限申请（targetSdk 34）：${missing.joinToString()}")
            requestPermissions(missing.toTypedArray(), 0x151)
        }
    }

    private var usbDetachReceiver: BroadcastReceiver? = null

    /**
     * vc149：监听 USB_DEVICE_DETACHED。Apple 设备拔出 → 等价用户断开：
     * 停 LiteSessionService（onDestroy 按序拆 controller/AirPlay 栈/VPN），
     * 并重置 autoConnectTried 让重插后自动连接可再次发起。幂等，未连接时无害。
     */
    private fun registerUsbDetachReceiver() {
        usbDetachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                // vc245：外置蓝牙（0a12）此前被完全忽略——掉线/拔插后 App 零感知，
                // 这是 T3「重新拔插没反应」的直接原因之一。这里置为待重连并上屏提示。
                if (device.vendorId == UsbDongleGuard.VENDOR_ID) {
                    // vc310：拔出即让在场缓存失效，三态立刻从「就绪/未配对」翻到「未检测到」。
                    UsbDongleGuard.invalidatePresence()
                    LiteLog.log("usbdongle", "USB 拔出：外置蓝牙 detach ${device.deviceName}，进入掉线重连")
                    autoConnectTried = false
                    // vc311（BT-310-04）：设备已物理不在总线上，重试必然全 ABSENT；会话活着时
                    // 只会把 failStreak 推到 5（→ extBtState 判 NOT_READY）并弹一条误报 Toast。
                    if (LiteSessionService.current?.isWirelessSessionAlive() == true) {
                        usbSixFields("usb", "detach", "receiver", "session-alive-skip-retry")
                        LiteLog.log("bt", "B-101 running=${UsbBtStandby.isRunning} | 拔出：会话存活，跳过重连")
                        // vc245 的教训：旧句柄必须释放（已交出的流归控制器所有，stop 不拆会话）
                        runCatching { UsbBtStandby.stop() }
                        return
                    }
                    // ↓ 以下为无活会话时的既有逻辑，原样保留（Toast + 1s 后 5 次退避）
                    usbSixFields("usb", "detach", "receiver", "reconnect-after-1.0s")
                    Toast.makeText(
                        this@MainActivity,
                        "外置蓝牙已掉线：正在释放旧句柄并自动重连（失败 5 次后停止）",
                        Toast.LENGTH_LONG,
                    ).show()
                    // vc246（USB-246-03）：DETACHED 后 1.0s 静默期（原 500ms 太短，重枚举未收尾
                    // 就 open 会撞上内核重建端点表窗口）。插回 dongle 后无需手动点键。
                    handler.postDelayed({ reconnectDongle() }, 1_000)
                    return
                }
                if (device.vendorId != APPLE_USB_VENDOR_ID) return
                // vc176（K60 实测）：0x52 重枚举等待期内旧实例的 detach 是必经步骤、
                // 不是用户拔线——此时停服务会杀掉重枚举兜底、误弹「控制通道已结束」，
                // 表象是「首次授权后弹结束提示，必须再点一次连接」。忽略并继续等待。
                if (LiteState.reenumerating) {
                    LiteLog.log("ui", "USB 拔出：重枚举等待期，忽略（等待新实例出现）")
                    return
                }
                LiteLog.log("ui", "USB 拔出：按用户断开等价清理会话与 VPN")
                autoConnectTried = false
                stopService(Intent(this@MainActivity, LiteSessionService::class.java))
            }
        }
        // vc151（targetSdk 34）：动态 receiver 必须带 EXPORTED 旗标；DETACHED 是 protected
        // broadcast（仅系统可发），NOT_EXPORTED 不影响接收。注意三参 registerReceiver 是
        // API 26+ 才有——else 必须走两参重载（C8815 API16 实测传 0 也会 NoSuchMethodError）。
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                usbDetachReceiver,
                IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerReceiver(usbDetachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        }
    }

    /**
     * vc149：重插触发。singleTask Activity 已在前台时 ATTACHED intent 走 onNewIntent
     * （此前默认空实现，拔插重连只能靠 iPhone 主动连旧残留监听——脏会话根源）。
     * 延迟 1.5s 等 detach 清理拆栈完成，再走标准自动连接（auto=true 跳确认弹窗）。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // vc310：插入即让在场缓存失效，三态立刻重判（否则最长 2s 仍显示「未检测到」）。
        UsbDongleGuard.invalidatePresence()
        // vc250：USB 插入侧直接落 usb-enum，不依赖会话服务是否已起。
        logUsbAttach(intent)
        autoConnectTried = false
        usbSixFields("usb", "attach-settle", "receiver", "auto-connect-after-1.5s")
        // vc311（BT-310-04）：物理插拔不推翻用户手动「停止外置蓝牙」的决定；force 会绕过
        // syncUsbBtStandby 内部的 usbBtUserStopped 抑制，所以必须先判。
        // 蓝牙来源=车机蓝牙时不碰外置（syncUsbBtStandby 会自行 stop 并返回，不该打「恢复广播」）。
        if (LiteConfig.usbBtDongleOf(this)) {
            if (usbBtUserStopped) {
                LiteLog.log("bt", "B-103 running=${UsbBtStandby.isRunning} | 插回：用户已停止外置蓝牙，不拉起")
            } else {
                syncUsbBtStandby(force = true)
                LiteLog.log("bt", "B-102 running=${UsbBtStandby.isRunning} | 插回：恢复常驻广播")
            }
        }
        // vc246（USB-246-03）：ATTACHED 后 1.5s 静默期（与 DETACHED 后 1.0s 对称）。
        handler.postDelayed({ maybeAutoConnect() }, 1_500)
    }

    /**
     * vc67（TS7 调试自举）：App 启动即用 root 把 adbd 拉到 TCP 5555。TS7 车机重启后
     * 网络 adb 不持久（展锐 ROM adbd 不读 persist），USB adb 又被编译期阉割，调试链路
     * 反复断——DiPlay 已有记住的 Superuser 授权，静默自举，失败无感（未 root/被拒绝均忽略）。
     * 仅诊断版本启用，稳定版可移除。
     */
    private fun bootstrapNetworkAdb() {
        Thread {
            try {
                val su = Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "setprop service.adb.tcp.port 5555; stop adbd; start adbd"))
                su.waitFor()
                val check = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getprop service.adb.tcp.port"))
                val value = check.inputStream.bufferedReader().readText().trim()
                LiteLog.log("ui", "adb 自举完成 tcp.port=$value")
            } catch (error: Exception) {
                LiteLog.log("ui", "adb 自举跳过：${error.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        LiteLog.log("ui", "onResume")
        // vc101（at8317）：每次回前台重新压系统栏（设置页等场景会唤回）。
        hideSystemBars()
        // M6k24：设置页可能改过横竖屏 —— 回主页立即按最新设置强制窗口方向。
        applyOrientationMode(this, LiteConfig.load(this).orientationMode)
        // 1.0.29：设置页可能改过分辨率设定 —— 回主页刷新拉伸模式标志。
        videoStretchMode = LiteConfig.load(this).resolutionMode == "stretch"
        handler.post(refreshTask)
        // vc319（BT-318-01）：invalidate 必须先于 sync —— 改动 4b 的放行判据要读 isPresent，
        // 而 isPresent 走「事件驱动缓存」（presenceAnyAt==0 才重查）。若先 sync 后 invalidate，
        // sync 读到的是上一轮陈旧在场值：dongle 已拔但缓存为 true ⇒ 误拉起 ⇒ open 失败
        // ⇒ failStreak>0 ⇒ 反而落 NOT_READY 黄字。invalidate 只归零、无副作用，前置安全。
        UsbDongleGuard.invalidatePresence()
        syncUsbBtStandby()
        usbSixFields("bt", "resume", if (UsbBtStandby.isRunning) "standby-running" else "standby-stopped", "auto-connect")
        maybeAutoConnect()
        // 1.1.4 系统诊断：新装无检测记录时，主界面就绪后延迟 5 秒触发首启快检（可跳过）。
        Diagnostics.quickCheckIfDue(this)
        // vc297：车机「汽车」键是系统 HOME（实测 mResumedActivity 直接变 huawei Launcher，
        // 窗口收不到任何 KeyEvent，拦不住）。退而求其次：从桌面点图标回到前台时，若仍在
        // 激活态且面板收起，直接亮出面板——「断开连接」必须始终可达。
        if (LiteState.phase == LiteState.Phase.ACTIVE && page == "home" && !panelVisible &&
            ::ballRoot.isInitialized
        ) {
            setPanelVisible(true)
            LiteLog.log("ui", "回前台：激活态自动亮面板（汽车键兜底）")
        }
        // vc320：先重置基线、恢复产生者，再允许后台采样；保留跨前后台冷却。
        lastUiFrameNs = 0L
        lastUiHeartbeatNs = System.nanoTime()
        installUiFrameMonitor()
        handler.removeCallbacks(uiHeartbeat)
        handler.post(uiHeartbeat)
        uiProbePaused = false
    }

    override fun onPause() {
        uiProbePaused = true
        removeUiFrameMonitor()
        handler.removeCallbacks(uiHeartbeat)
        super.onPause()
        LiteLog.log("ui", "onPause")
        handler.removeCallbacks(refreshTask)
    }

    override fun onStop() {
        super.onStop()
        // vc247（V12）：切后台/被覆盖 —— 配对页若开着，必须落关窗（不能只靠 dismiss 回调）。
        ExternalBtPairingDialog.onHostBackgrounded()
    }

    /** vc101（at8317）：API17 兼容的系统栏隐藏——HIDE_NAVIGATION+FULLSCREEN+LOW_PROFILE。 */
    private fun hideSystemBars() {
        // vc104（AC822X/at8317 反编译定案）：高德「系统状态栏」开关的实现就是
        // FLAG_FULLSCREEN 的动态 add/clear + 每次焦点回归重放（无特权接口）。
        // 我们 onCreate 一次性 setFlags 后若被 FORCE_NOT_FULLSCREEN（权限弹窗等）
        // 盖过或被系统清掉就回不来——这里幂等重放：清 FORCE 标志 + 重设 FULLSCREEN。
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LOW_PROFILE or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        setVendorFullScreen(true)
    }

    /**
     * vc108（AC822X 正版高德反编译定案，tmp-analysis/amap-decomp2/）：该 ROM 的厂商
     * launcher/systemui 监听 `sys.force_full_screen` 系统属性自行藏栏——正版高德的
     * setFullScreenState 就是反射 SystemProperties.set 写它（SDK<=19 另发云OS广播，
     * extras：properties=属性名、value=true/false），本机实锤高德在前台时栏消失。
     * 1:1 抄作业：进前台写 true，退出写 false 还原车机栏。写失败（sepolicy 拦截）
     * 静默降级——标准 flag 仍在，行为不劣于 vc107。
     */
    private fun setVendorFullScreen(enabled: Boolean) {
        val value = if (enabled) "true" else "false"
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("set", String::class.java, String::class.java)
                .invoke(null, "sys.force_full_screen", value)
            Log.i("StatusBarProbe", "vc108 sys.force_full_screen=$value set ok")
        } catch (error: Throwable) {
            Log.i("StatusBarProbe", "vc108 sys.force_full_screen set failed: $error")
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            try {
                sendBroadcast(
                    Intent("com.yunos.service.ACTION_SET_SYSTEM_PROPERTY")
                        .putExtra("properties", "sys.force_full_screen")
                        .putExtra("value", value),
                )
            } catch (_: Throwable) {
                // 非 YunOS ROM 无接收方，静默
            }
        }
    }

    override fun onDestroy() {
        uiProbePaused = true
        removeUiFrameMonitor()
        handler.removeCallbacks(uiHeartbeat)
        uiStackHandler.removeCallbacksAndMessages(null)
        uiStackSampler.quit()
        LiteLog.log("ui", "onDestroy（Activity 被销毁，下一次进入会重建 Surface 与解码器）")
        // vc247（V12）：页面被回收/销毁时兜底关配对窗（dismiss 回调不保证一定走）。
        ExternalBtPairingDialog.onHostDestroyed()
        // vc149：注销 USB 拔出监听
        usbDetachReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbDetachReceiver = null
        LiteVideoBridge.setVideoSizeListener(null)
        // vc192：退出页面释放 dongle（不留在后台常驻广播；下次进前台 onResume 会重新拉起）。
        // vc246（自动连接撞常驻广播窗口，候选 (c)）：仅 Activity **真正结束**（finish）时才停；
        // 配置变更 / 低内存重建 / 从设置页返回一律保留本代际 host，避免每次重建都重新
        // open dongle（3–10s 初始化窗口正是 auto-connect「踢空」的来源）。
        if (isFinishing) {
            LiteLog.log("usbdongle", "Activity 结束：停止常驻广播并释放 dongle")
            UsbBtStandby.stop()
        } else {
            LiteLog.log("usbdongle", "Activity 重建：保留常驻广播（g=${UsbBtStandby.generationSnapshot}）")
        }
        detachCurrentPage()
        settingsPage = null
        settingsCachedRoot = null
        // vc108：退出时还原车机栏（写回 false），否则车机桌面栏被永久藏住
        setVendorFullScreen(false)
        super.onDestroy()
    }

    /**
     * 1.0.11（FE-6 拉伸）：按解码器实际输出尺寸把 SurfaceView 等比适配到容器内并居中。
     * width/height<=0 表示流已结束，恢复铺满。触摸映射（forwardTouch）基于 video 视图
     * 尺寸，等比适配后映射自动正确。vc111：底部失灵区已由系统层（1024x500+触摸上限）
     * 全局规避，画面直接按系统给定分辨率渲染，App 内不再做缩进映射。
     */
    private fun applyVideoFit(width: Int, height: Int) {
        val parent = video.parent as? android.view.ViewGroup ?: return
        // 1.0.29：分辨率设定=自动（拉伸）时跳过等比 letterbox，恢复铺满容器。
        if (videoStretchMode) {
            if (lpIsFullMatch()) return
            video.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            video.requestLayout()
            return
        }
        if (width <= 0 || height <= 0) {
            if (lpIsFullMatch()) return
            video.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            video.requestLayout()
            return
        }
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0) return
        val scale = minOf(pw.toDouble() / width, ph.toDouble() / height)
        val fitW = Math.max(1, Math.round(width * scale).toInt())
        val fitH = Math.max(1, Math.round(height * scale).toInt())
        val lp = video.layoutParams
        if (lp.width == fitW && lp.height == fitH) return
        lp.width = fitW
        lp.height = fitH
        if (parent is FrameLayout) {
            (lp as FrameLayout.LayoutParams).gravity = android.view.Gravity.CENTER
        }
        video.layoutParams = lp
        video.requestLayout()
        LiteLog.log(
            "ui",
            "视频等比适配 coded=${width}x${height} → view=${fitW}x${fitH} panel=${pw}x${ph}",
        )
    }

    /** video 当前 LayoutParams 是否已是全铺（MATCH_PARENT x MATCH_PARENT）。 */
    private fun lpIsFullMatch(): Boolean {
        val lp = video.layoutParams ?: return false
        return lp.width == FrameLayout.LayoutParams.MATCH_PARENT &&
            lp.height == FrameLayout.LayoutParams.MATCH_PARENT
    }

    // ---------- M6k25：单 Activity 页面路由 ----------

    /** 切页：离开旧页先清理（persist/向导注销/试听关闭等），再整页重建。 */
    fun setPage(target: String) {
        if (target == page) return
        detachCurrentPage()
        page = target
        render()
    }

    /** 返回主页（原各子 Activity 的 finish()）。 */
    fun goHome() = setPage("home")

    /** 设置页热点配置入口（原 HotspotConfigActivity.intent(this, mode)）。 */
    fun openHotspotPage(mode: String) = setPage("hotspot-$mode")

    /** 离开当前子页并释放其资源；home 页无资源需要释放。 */
    private fun detachCurrentPage() {
        when (page) {
            "settings" -> settingsPage?.cleanup()
            "caricon" -> carIconPage?.cleanup()
            "hotspot-car", "hotspot-relay" -> hotspotPage?.cleanup()
            "steering" -> steeringPage?.cleanup()
        }
        // vc320：离开设置页仍 cleanup，但保留对象供重新进入时回显。
        carIconPage = null
        hotspotPage = null
        steeringPage = null
    }

    /**
     * CarIconPage 图库选择：统一由 MainActivity 发起（原 CarIconActivity.pickImage 的
     * startActivityForResult；单 Activity 后子页不能再自行启动结果回调）。
     */
    fun launchGalleryPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择图标图片"), REQUEST_GALLERY_PICK)
        } catch (_: Exception) {
            toast("未找到图片选择器")
        }
    }

    /**
     * 方向盘学习页的本地 KeyEvent 捕获（原 SettingsActivity.onKeyDown）：
     * 仅学习页且监听态（钩子非空）时消费。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (page == "steering") {
            steeringPage?.let { if (it.consumeKeyDown(keyCode, event)) return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * fe6t2 方控监听（FE-6「方控上/下一首无效」取证）：全量 KeyEvent 落日志，
     * 只观察不改分发。MediaSession/logcat/广播三条通路日志里零触发记录，先确认
     * 车机方控按键是否以窗口 KeyEvent 形态进 App、键码/来源为何，再定映射修复。
     *
     * vc87（fe6t5）方控派发接通：fe6t3 日志实锤按键确实以窗口 KeyEvent 形态进 App
     * （code=87/88 dev=-1 src=0），学习绑定能建立但标准派发只有 MediaSession 与
     * logcat/广播两条通路，窗口 KeyEvent 永不派发——「方控按键 sent=」零记录。
     * 观察侧升级为观察+派发：非学习页把事件转发给 LiteSteeringKeys 窗口派发入口，
     * 仍不改变 super 分发（返回键/页面导航行为不变）。
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            (event.action == KeyEvent.ACTION_UP || (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0))
        ) {
            LiteLog.log(
                "ui",
                "方控监听 code=${event.keyCode} action=${event.action} scan=${event.scanCode} " +
                    "repeat=${event.repeatCount} dev=${event.deviceId} src=${event.source}",
            )
            // 学习页自己消费 onKeyDown 捕获，转发会造成双重上报；其余页面统一派发。
            // DOWN(非 repeat) 命中学习绑定（event=ACTION_DOWN 存储），UP 命中标准媒体键。
            if (page != "steering") {
                val forward = (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ||
                    event.action == KeyEvent.ACTION_UP
                if (forward) LiteSteeringKeys.onWindowKeyEvent(event.keyCode, event.action)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 系统返回键层级（1.1.1 UI）：
     * 图标页裁剪态 → 回图标主页；三级页（图标/方向盘/热点配置）→ 设置页；
     * 设置页 → 主页；主页 → 弹窗确认退出。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // vc322：先取消设置页互斥覆盖窗，再处理页面层级。
        if (page == "settings" && settingsPage?.cancelBootConflict() == true) return
        when {
            // vc297：激活态视频铺满整屏、C8815 上悬浮球被视频层吞掉（层级里在、画面上不显示），
            // 用户没有任何"断开"入口。返回键先亮面板，再按一次才走原有层级（含退出确认）。
            LiteState.phase == LiteState.Phase.ACTIVE && page == "home" && !panelVisible -> {
                setPanelVisible(true)
                LiteLog.log("ui", "返回键：激活态亮面板（断开入口）")
            }
            page == "caricon" && carIconPage?.onBackKey() == true -> return
            page != "home" && page != "settings" -> setPage("settings")
            page == "settings" -> goHome()
            else -> confirmExit()
        }
    }

    /** 主页返回：确认退出弹窗（防止误触直接杀会话）。 */
    private fun confirmExit() {
        AppDialog.Builder(this)
            .setTitle("退出 DiPlay Lite")
            .setMessage("确认退出软件吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认退出") { _, _ -> finish() }
            .show()
    }

    /**
     * 旋转重建（M6k24 起仅兜底用）：方向已是强制二选一，系统旋转不再发生在会话中；
     * 设置页切换横竖屏回主页时窗口方向变化会走到这里。空闲态 render() 整页重建。
     * ACTIVE/CONNECTING 拒绝整页重建 —— Surface 换了会冻结画面，仅换面板不动 SurfaceView。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val phase = LiteState.phase
        // M6k25：子页。会话中不整页重建（与原独立子页行为一致，视图随 configChanges 自动重排）；
        // 空闲态重建当前页（render() 按 page 分发，天然重建正确）。
        if (page != "home") {
            if (phase == LiteState.Phase.IDLE) {
                // vc320：设置页走「保留输入」路径 —— 不 render、不重建、不重读配置。
                // 原 render() 会命中缓存 → attach(REENTER) → loadIntoForm()，
                // 而这条路径不经过 setPage/cleanup，磁盘配置是旧的 ⇒ 用户刚敲的值会被覆盖。
                val settings = settingsPage
                val cachedRoot = settingsCachedRoot
                if (page == "settings" && settings != null && cachedRoot != null) {
                    settings.attach(SettingsPage.AttachMode.CONFIG_CHANGE)
                    cachedRoot.requestLayout()
                    LiteLog.log("ui", "旋转：设置页保留输入并重排（不重建）")
                } else {
                    render()
                    LiteLog.log("ui", "旋转：空闲态整页重建（page=$page）")
                }
            } else {
                LiteLog.log("ui", "旋转：子页保持（page=$page phase=$phase）")
            }
            return
        }
        if (phase == LiteState.Phase.ACTIVE || phase == LiteState.Phase.CONNECTING) {
            val root = video.parent as? FrameLayout
            if (root != null) {
                root.removeView(panel)
                panel = homePanel()
                root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                // vc298：面板是新实例，statusLine/disconnectButton 等字段全部指向新控件，必须清掉
                // renderState() 的变更哨兵；否则 phase/running/statusText 未变 → 直接短路，新面板
                // 停在默认态。C8815 实测（17:19 回前台触发一次配置变化）表现为「状态写着已激活、
                // 主按钮还是立即连接、没有断开连接」。
                shownPhase = null
                shownRunning = null
                shownStatus = null
                shownDisplayText = null
                shownExtBtState = null
                // vc297/vc298：面板被追加到 root 末尾，同层 z 序会盖住悬浮球（球在 render() 里先加）；
                // 若球已被摘掉（parent 为空）按上次边距补回，避免重建后面板在、球没了。
                if (::ballRoot.isInitialized) {
                    if (ballRoot.parent == null) {
                        val size = dip(46)
                        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
                        val dmH = resources.displayMetrics.heightPixels
                        lp.topMargin = ballTopMargin ?: (dmH - size - dip(28))
                        lp.rightMargin = ballRightMargin ?: dip(12)
                        ballRoot.layoutParams = lp
                        root.addView(ballRoot, lp)
                        LiteLog.log("ui", "旋转：悬浮球已摘掉，按边距补回")
                    } else {
                        ballRoot.bringToFront()
                    }
                }
                // 旋转后让视频 SurfaceView 跟随新窗口尺寸重排（MediaCodec 输出自动缩放到 Surface）。
                video.requestLayout()
                setPanelVisible(panelVisible)
                renderState()
            }
            LiteLog.log("ui", "旋转：会话进行中，仅重建面板（视频 Surface 保持）")
            return
        }
        render()
        LiteLog.log("ui", "旋转：空闲态整页重建（page=$page）")
    }

    // ---------- 原版式构建辅助（对齐 DiPlayActivity 的写法） ----------

    private fun dip(value: Int): Int = Ui.dip(this, value)

    /**
     * 面板布局方向改用 Display 实际宽高判定，不再信 configuration.orientation ——
     * C8815（4.1 dalvik）实测强制横屏后 config 仍报 portrait，竖屏布局被画进横屏窗口，
     * 整版文字转 90°（"背景错位"）。Display 宽高与窗口真实形状同源，不受该 ROM bug 影响。
     */
    /**
     * M6k24：布局方向完全跟随设置（强制横屏/强制竖屏二选一，自动旋转已取消）。
     * 不再信 Display/configuration —— 8815 实测两者都会漂移（540x960↔960x540 翻转），
     * 导致竖屏设置下主页偶尔画成横版。窗口方向由 applyOrientationMode 强制，与设定一致。
     */
    private fun isLandscapeUi(): Boolean = LiteConfig.load(this).orientationMode != "portrait"

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = Ui.font(bold)
        setLineSpacing(dip(3).toFloat(), 1f)
    }

    /**
     * 原版式按钮：primary = ACCENT 实心 + BG 文字；secondary = CARD 面 + BORDER 描边 + TEXT 文字。
     * RippleDrawable/stateListAnimator 是 API21+，必须护栏（API16 红线）。
     */
    private fun button(title: String, primary: Boolean, height: Int = 46, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Ui.BG else Ui.TEXT)
        typeface = Ui.FONT_MEDIUM
        val fill = if (primary) Ui.ACCENT else Ui.CARD
        val stroke = if (primary) Ui.ACCENT else Ui.BORDER
        background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(fill, stroke), null)
        } else {
            rounded(fill, stroke)
        }
        setPadding(dip(12), 0, dip(12), 0); minHeight = dip(height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun card() = column().apply {
        background = rounded(Ui.CARD, Ui.BORDER)
        setPadding(dip(16), dip(14), dip(16), dip(16))
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dip(20).toFloat(); setStroke(dip(1), stroke)
    }

    private fun matchButton(top: Int = 0, height: Int = 50) =
        LinearLayout.LayoutParams(-1, dip(height)).apply { topMargin = dip(top) }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dip(height)) }

    /** 眉题字距：TextView.letterSpacing 是 API21+，护栏后应用（原版 .12f/.16f）。 */
    private fun TextView.applyLetterSpacing(spacing: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = spacing
    }

    // ---------- 原版式 render：整页重建 ----------

    /**
     * vc250（VC248-07）：首次建根容器与 SurfaceView 并挂上 contentView；
     * 之后所有 render() 复用同一 root/Surface，切页不再销毁重建 Surface。
     */
    private fun ensureRenderRoot(): FrameLayout {
        rootView?.let { return it }
        val root = FrameLayout(this).apply { background = ColorDrawable(Ui.BG) }
        // M3：CarPlay 画面镜像到该 SurfaceView；M4k：铺满整屏 + 接手势转发。
        // 子页时铺在页面底下（子页根布局不透明遮住），Surface 常驻避免切页反复换 Surface。
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                LiteVideoBridge.updateSurface(holder.surface)
                LiteLog.log("ui", "视频 Surface 就绪")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                LiteVideoBridge.updateSurface(null)
                LiteLog.log("ui", "视频 Surface 销毁")
            }
        })
        video.setOnTouchListener { _, event -> forwardTouch(event) }
        root.addView(
            video,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        rootView = root
        return root
    }

    /**
     * vc279（ac8317 取证）：触摸排队与分发耗时。
     *
     * `ev.eventTime` 是手指按下的系统时刻，当前 uptime 与它之差就是事件在 InputDispatcher
     * 队列里等主线程的时长。主线程被占死时该值会明显抬升——这正是用户感知的「点下去没反应」；
     * 该延迟只在事件真正分发到 App 时才可测，所以必须在这一层取。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val queueMs = SystemClock.uptimeMillis() - ev.eventTime
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && queueMs >= slowTouchQueueMs) {
            LiteLog.log("ui", "触摸排队: ${queueMs}ms page=$page at=(${ev.x.toInt()},${ev.y.toInt()})")
        }
        val startedNs = System.nanoTime()
        val handled = super.dispatchTouchEvent(ev)
        val costMs = (System.nanoTime() - startedNs) / 1_000_000
        if (costMs >= slowTouchDispatchMs) {
            LiteLog.log("ui", "触摸分发: ${costMs}ms action=${ev.actionMasked} page=$page")
        }
        return handled
    }

    /**
     * vc320：主线程心跳。仅用于回答一个问题——**主线程还在不在跑消息**。
     *
     * 为什么需要它：只看帧回调无法区分「主线程卡住」和「根本没有重绘」（静止画面、
     * 息屏、Activity 在后台）。后者 gap 可以无穷大但不是卡顿，必须排除，否则每次
     * 息屏回来都会误报一条几百秒的掉帧。
     */
    private val uiHeartbeat = object : Runnable {
        override fun run() {
            // vc320 v4：这里**不判断 uiProbePaused**。
            // 判断会让「onResume 里先 post 心跳还是先解暂停」互相牵制（先 post 会被
            // paused=true 挡掉一拍、先解暂停又会让后台立刻读到旧基线）。
            // 心跳的启停只由 onResume 的 post / onPause 的 removeCallbacks 控制。
            lastUiHeartbeatNs = System.nanoTime()
            handler.postDelayed(this, uiHeartbeatMs)
        }
    }

    /**
     * vc320（UI-318-01）：安装帧监听。**幂等** —— 已在监听时直接返回，
     * 因此 onResume 可以无条件调用而不会重复注册（重复注册会让每个回调续订出一份，
     * 帧数指数上涨）。
     */
    private fun installUiFrameMonitor() {
        if (uiFrameCallback != null) return
        val choreographer = runCatching { Choreographer.getInstance() }.getOrNull() ?: return
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // 本回调只写一个时间戳。判断与抓栈全部交给后台 ——
                // 在 doFrame 里抓栈只能抓到 doFrame 自己，卡点早已返回、不在栈上。
                lastUiFrameNs = System.nanoTime()
                // 只有「仍是当前回调」才续订：removeUiFrameMonitor() 会把字段置 null，
                // 已在管道里的那一帧返回后即自断，不会复活。
                if (uiFrameCallback === this) {
                    runCatching { choreographer.postFrameCallback(this) }
                }
            }
        }
        uiFrameCallback = callback
        runCatching { choreographer.postFrameCallback(callback) }
    }

    /**
     * vc320：移除帧监听。`Choreographer.removeFrameCallback` 是 **API 16+**（minSdk=16 恰好可用）。
     * 先置 null 再 remove —— 顺序反了会让 doFrame 里的 `=== this` 判断仍为真、再续订一帧。
     */
    private fun removeUiFrameMonitor() {
        val callback = uiFrameCallback ?: return
        uiFrameCallback = null
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    /**
     * vc320（UI-318-01）：后台轮询起点。**只调一次**，内部自我续订。
     *
     * 与 vc319 的关键差别：抓栈发生在「主线程仍卡着」的时刻，而不是「卡完之后」。
     */
    private fun startUiStackSampler() {
        uiStackHandler.post(object : Runnable {
            override fun run() {
                if (!uiProbePaused) {
                    try {
                        sampleMainThreadIfStuck()
                    } catch (error: Throwable) {
                        // 红线：异常必须记类名（NoSuchMethodError 是 Error 不是 Exception，
                        // 吞掉会被误读成业务结论）。
                        LiteLog.log("ui", "主线程阻塞采样失败（${error.javaClass.name}）: ${error.message}")
                    }
                }
                // 暂停期本循环仍会空转（每 100ms 读一次 uiProbePaused），开销可忽略；
                // 由 onDestroy 的 removeCallbacksAndMessages(null) + quit() 统一终止。
                uiStackHandler.postDelayed(this, uiStackPollMs)
            }
        })
    }

    /**
     * vc320（UI-318-01）：判定 + 抓栈 + 写日志（**先抓栈，再写日志**）。
     *
     * 判定用双基准，缺一不可：
     *  - [lastUiHeartbeatNs] 停 ⇒ 主线程**真的不响应**；
     *  - [lastUiFrameNs] 停但心跳正常 ⇒ 只是没重绘，**不是卡顿**，直接返回。
     */
    private fun sampleMainThreadIfStuck() {
        val now = System.nanoTime()
        val heartbeatNs = lastUiHeartbeatNs
        if (heartbeatNs == 0L) return                              // 心跳未起（onResume 未走完）
        val heartbeatGapMs = (now - heartbeatNs) / 1_000_000
        if (heartbeatGapMs < slowFrameGapMs) return                // 主线程活着
        if (uiStackSampling) return                                // 上一轮还在抓，防重入
        if (now - lastFrameLogNs < 3_000_000_000L) return           // 3s 冷却（沿用 vc319）
        uiStackSampling = true
        try {
            val frameNs = lastUiFrameNs
            val frameGapMs = if (frameNs == 0L) heartbeatGapMs else (now - frameNs) / 1_000_000
            // 完整原始栈：Thread.getStackTrace() 是当前时刻的快照，采样与检测存在调度间隙，
            // 主线程可能已恢复，需结合调用链判读。
            val raw = android.os.Looper.getMainLooper().thread.stackTrace
            // 保留完整 className（**禁止 substringAfterLast**——vc319 就是截短之后再用
            // startsWith("com.shilapi") 过滤，导致自家帧恒不匹配、全部被丢弃）。
            val frames = raw.take(uiStackDepth)
                .map { "${it.className}.${it.methodName}:${it.lineNumber}" }
            val own = frames.filter { it.startsWith("com.shilapi.") }
            // vc320 v4：输出前**复核暂停态**。抓栈这几百微秒里可能刚进后台（onPause 已置 true），
            // 这类样本是「暂停瞬间的快照」，判读价值低且会污染后台回归测试的判据。
            // 放在这里而不是函数入口：入口已判过一次，但状态可能在抓栈期间变化。
            if (uiProbePaused) return
            // 冷却从「真正输出」开始计时 —— 作废的样本不占 3s 窗口。
            lastFrameLogNs = System.nanoTime()
            // 先抓栈、后写日志：两条紧邻写出，降低 logd 丢 I 级行时「有头无栈」的概率。
            LiteLog.log(
                "ui",
                "主线程停顿: heartbeat=${heartbeatGapMs}ms gap=${frameGapMs}ms page=$page",
            )
            LiteLog.log(
                "ui",
                "主线程阻塞采样 gap=${frameGapMs}ms own=${own.size} top=${frames.joinToString(" | ")}",
            )
            if (own.isEmpty()) {
                // 只陈述事实：**打印的前 ${uiStackDepth} 帧里没有应用帧**。
                // 不能据此断言「问题在系统侧」——卡点可能深于第 N 帧，也可能在 native。
                // 单独一条便于判读，不污染 top= 的原始栈。
                LiteLog.log(
                    "ui",
                    "主线程阻塞采样：前 ${frames.size} 帧无应用帧（原始栈 ${raw.size} 帧，未覆盖部分需加大 uiStackDepth 再看）",
                )
            }
        } finally {
            uiStackSampling = false
        }
    }

    /**
     * 根视图：Surface 铺满 → home 面板 → 悬浮球 → errorCard。
     * 面板布局方向用 Display 实际宽高判定（isLandscapeDisplay），勿信 configuration.orientation。
     */
    // 🔴 可见性保持 internal —— CarIconPage 仍在调 activity.render()（L390 / L465），
    // 改成 private 会直接编译失败。
    internal fun render() {
        val startedNs = System.nanoTime()
        // vc320：必须在**入口**捕获。renderPanel 首次进入时会给 settingsCachedRoot 赋值，
        // 在末尾再读就恒为 true，首次与复用无法区分。
        val cachedAtEntry = settingsCachedRoot != null
        try {
            renderPanel()
        } finally {
            val costMs = (System.nanoTime() - startedNs) / 1_000_000
            // 原有：仅 ≥slowRenderMs(120) 才打。**保留不动** —— 历史判据依赖这一行，
            // 改了口径会让 vc318/vc319 的「整页重建」样本无法对比。
            if (costMs >= slowRenderMs) LiteLog.log("ui", "整页重建: ${costMs}ms page=$page")
            // vc320 新增：每次 render 无条件记录耗时与缓存命中，用于首次/复用对比。
            // 慢重建时会与上一行同时出现，属预期（两行用途不同，勿合并）。
            LiteLog.log("ui", "render 耗时=${costMs}ms page=$page cached=$cachedAtEntry")
        }
    }

    private fun renderPanel() {
        val root = ensureRenderRoot()

        // vc250（VC248-07）：切页只换面板/悬浮球/失败卡，SurfaceView 常驻不重建——
        // 原实现每次 render 都 new SurfaceView + setContentView，切页会销毁再创建 Surface，
        // 导致 1~5s 冻结与黑屏竞态。
        for (index in root.childCount - 1 downTo 0) {
            if (root.getChildAt(index) !== video) root.removeViewAt(index)
        }

        panel = when (page) {
            "settings" -> {
                val cachedRoot = settingsCachedRoot
                val cachedPage = settingsPage
                if (cachedRoot != null && cachedPage != null) {
                    // 防御：若该 View 还挂在某个 parent 上（正常不会），先摘下再交给本次 addView。
                    (cachedRoot.parent as? android.view.ViewGroup)?.removeView(cachedRoot)
                    cachedPage.attach(SettingsPage.AttachMode.REENTER)
                    cachedRoot
                } else {
                    val newPage = SettingsPage(this)
                    settingsPage = newPage
                    newPage.buildRoot().also { settingsCachedRoot = it }
                }
            }
            "caricon" -> {
                val newPage = CarIconPage(this)
                carIconPage = newPage
                newPage.buildRoot()
            }
            "hotspot-car", "hotspot-relay" -> {
                val newPage = HotspotPage(this, page.removePrefix("hotspot-"))
                hotspotPage = newPage
                newPage.buildRoot()
            }
            "steering" -> {
                // M6k26：方控学习页（原全屏 Dialog 在部分 ROM 内容区不渲染，改页面跳转）。
                val newPage = SteeringPage(this)
                steeringPage = newPage
                newPage.buildRoot()
            }
            else -> homePanel()
        }
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // vc163：**全局悬浮球**——所有页面常驻，不再由各子页各自 attachPageBall 建一个。
        // 原先是两套球：home 用这里可拖拽的，子页各自 attachPageBall 建一个不可拖的，
        // 行为与位置都不统一，切页还会闪。现合并成一个，位置跨页面保留。
        val ball = FrameLayout(this).apply {
            background = Ui.pillSolid(this@MainActivity, 0x66FFFFFF).apply { cornerRadius = dip(23).toFloat() }
            // setClipToOutline 是 API 18+；部分白牌 ROM（山海等）谎报 SDK_INT≥18 但 framework 缺方法，
            // SDK_INT 护栏拦不住 → 改反射探测，方法不存在时静默跳过。
            runCatching {
                FrameLayout::class.java
                    .getMethod("setClipToOutline", Boolean::class.javaPrimitiveType)
                    .invoke(this, true)
            }
        }
        val ballIcon = ImageView(this).apply {
            setImageDrawable(IconPainter.arrows(this@MainActivity, 20, Ui.TEXT))
        }
        ball.addView(
            ballIcon,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        // 点击语义集中在此一处（不是按页各写一遍）：home=收起/唤出面板，子页=逐级返回。
        ball.setOnClickListener {
            when (page) {
                "home" -> setPanelVisible(!panelVisible)
                "settings" -> goHome()
                else -> setPage("settings")
            }
        }
        // 悬浮球初始位置 = 右对齐 + 上下居中；拖拽后吸附最近左/右边。
        // 1.1.1 UI：横屏（1024x600 车机）右列按键加高后与球重叠 → 初始位改右下角空区；
        // 竖屏右列窄，保持垂直居中。vc163：拖过就沿用上次的边距，不再每次重建都复位。
        val ballSize = dip(46)
        val ballParams = FrameLayout.LayoutParams(ballSize, ballSize, Gravity.TOP or Gravity.END)
        val dmH = resources.displayMetrics.heightPixels
        ballParams.topMargin = ballTopMargin ?: if (isLandscapeUi()) {
            dmH - ballSize - dip(28)
        } else {
            ((dmH - ballSize) / 2).coerceAtLeast(0)
        }
        ballParams.rightMargin = ballRightMargin ?: dip(12)
        ball.layoutParams = ballParams
        attachBallDrag(ball, ballParams)
        ballRoot = ball
        root.addView(ball, ballParams)
        // vc146（T3 实测）：wm overscan 车机（1024x600 屏、可视仅 500）上
        // displayMetrics.heightPixels=600 会把球初始位算到可视区外（y≈526>500），
        // 球被系统黑条吞掉。布局完成后按 root 实际高度校正一次。
        // vc163：仅在从未拖动过时校正，否则会把用户拖好的位置又改回默认。
        if (ballTopMargin == null) {
            ball.post {
                val rh = root.height
                if (rh > 0 && rh < dmH) {
                    ballParams.topMargin = if (isLandscapeUi()) {
                        rh - ballSize - dip(28)
                    } else {
                        ((rh - ballSize) / 2).coerceAtLeast(0)
                    }
                    ballTopMargin = ballParams.topMargin
                    ball.layoutParams = ballParams
                }
            }
        }

        // FAILED 态：居中小卡片（宽度 ≤300dp：标题 + 3 行省略 + 结束按钮）。
        // M6k24：「重试」改「结束」—— 点击只结束当前会话回空闲，由用户自己再点连接。
        val errorCardView = FrameLayout(this).apply {
            background = rounded(Ui.CARD, Ui.BORDER)
            setPadding(dip(18), dip(14), dip(18), dip(16))
        }
        val errorColumn = column()
        errorTitleView = label("连接失败", 14, Ui.WARNING, true)
        errorColumn.addView(errorTitleView)
        errorTextView = label("", 13, Ui.TEXT).apply {
            maxWidth = dip(264)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dip(2).toFloat(), 1f)
        }
        errorColumn.addView(
            errorTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dip(6) },
        )
        // vc300：热点未开等待态提供直达入口（不停会话）；失败态隐藏。
        hotspotJumpButton = button("去打开热点", true, height = 40) {
            SystemSettingsJumps.openHotspotSettings(this@MainActivity)
        }.apply { visibility = View.GONE }
        errorColumn.addView(hotspotJumpButton, matchButton(top = 12, height = 40))
        // vc314（INV-308-01）：C-4 的可执行动作。文案与点击行为都在 renderState 里按
        // LiteState.failureClass 决定，见下方分型表。
        errorActionButton = button("重试", true, height = 40) {
            errorCard.visibility = View.GONE
            when (LiteState.failureClass) {
                LiteLog.FailureClass.CredTimeout -> SystemSettingsJumps.openHotspotSettings(this@MainActivity)
                LiteLog.FailureClass.BtNoResponse, LiteLog.FailureClass.Iap2Timeout ->
                    if (LiteConfig.usbBtDongleOf(this@MainActivity)) {
                        ExternalBtPairingDialog.show(this@MainActivity)
                    } else {
                        retryFromFailedCard()
                    }
                else -> retryFromFailedCard()
            }
        }.apply { visibility = View.GONE }
        errorColumn.addView(errorActionButton, matchButton(top = 8, height = 40))
        errorColumn.addView(
            button("结束", false, height = 40) {
                errorCardView.visibility = View.GONE
                disconnect()
            },
            matchButton(top = 8, height = 40),
        )
        errorCardView.addView(errorColumn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        errorCard = errorCardView
        errorCardView.setOnClickListener { errorCardView.visibility = View.GONE }
        errorCardView.visibility = View.GONE
        // errorCard 仅在 home 页展示（与原版一致：子 Activity 时代卡片只存在于 MainActivity）。
        if (page == "home") {
            root.addView(
                errorCardView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    leftMargin = dip(24); rightMargin = dip(24)
                },
            )
        }
        setPanelVisible(panelVisible)
        // M6k24k 方向诊断：每个 page 构建完成后记录一次（tag=当前 page）。
        logOrientation(this, page)
        // vc157：页面重建会创建一批新视图（statusDot 未着色、disconnectButton 默认 GONE、
        // 主按钮可用态未设）。若当前状态与上次相同，renderState() 的变化检测会整块短路，
        // 新视图停在灰色/隐藏态且不自愈（500ms tick 一直判定"状态未变"）——实测症状即
        // 「切回主页指示灯变灰、断开按钮消失，直到状态再次变化才恢复」。置空哨兵强制
        // 本 tick 走完整渲染（与"哨兵 null 保证首 tick 必跑"的既有约定一致）。
        shownPhase = null
        shownRunning = null
        shownStatus = null
        shownDisplayText = null
        shownActiveChannel = null
        shownExtBtState = null
        renderState()
    }

    // ---------- home 页 ----------

    /**
     * home 面板（M6k21）：竖屏 = 状态大卡 → USB 直连（按钮+说明）；
     * 横屏 = 状态卡（左）｜USB 直连+设置（右），右列与左卡垂直对齐。
     */
    private fun homePanel(): View {
        // M6k24：布局方向完全跟随设置（强制横屏/强制竖屏），不再读 Display。
        val wide = isLandscapeUi()
        val config = LiteConfig.load(this)
        shownActiveChannel = config.activeChannel

        val content = column().apply { setPadding(dip(16), dip(10), dip(16), dip(20)) }
        content.addView(buildHeader())
        content.addView(space(12))

        val statusCard = buildStatusCard(config)

        if (wide) {
            // 右列（原版式）：USB 直连 + 设置入口。
            // 1.1.1 UI：按键加高（52dp）+ 行间距拉开（12dp），1024x600 车机横屏不再拥挤。
            // 8815 版式：hero 文案区删除，右列 MATCH_PARENT 与左侧状态大卡垂直对齐
            //（USB 直连贴卡顶、设置贴卡底，中段弹性居中）。
            val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            right.addView(
                button("USB 直连", false) { connect("usb") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(52)),
            )
            right.addView(
                label("将 iPhone 插入车机 USB 数据接口，\n当 iPhone 提示时允许 CarPlay。", 12, Ui.MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(dip(8), dip(10), dip(8), dip(12))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // M6k24：右列同样提供 3 个系统设置快捷入口 + 设置键。
            right.addView(
                quickSettingsRow(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dip(12) },
            )
            right.addView(
                button("设置", false) { setPage("settings") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(52)).apply { topMargin = dip(12) },
            )

            val topRow = row().apply { gravity = Gravity.TOP }
            topRow.addView(
                statusCard,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f),
            )
            topRow.addView(space(16), LinearLayout.LayoutParams(dip(16), 1))
            // vc155：右列 WRAP_CONTENT 而非 MATCH_PARENT——右列自身内容总高（USB 键+说明+
            // 快捷行+设置键）在断开连接按钮 GONE、左卡变矮后可能超过左卡高度，MATCH_PARENT
            // 会把右列压到左卡同高导致设置键底部溢出被裁（K60 实测：断开后设置键圆角被切）。
            // WRAP_CONTENT 让右列反撑 topRow 行高（行高=左右较大者），任何状态都不裁切；
            // 连接中右列内容从顶排、位置与之前一致，视觉零变化。
            topRow.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(topRow)
        } else {
            content.addView(statusCard)
            // 竖屏：USB 直连键独立成行（原版右列键位）。
            content.addView(
                button("USB 直连", false) { connect("usb") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(50)).apply { topMargin = dip(12) },
            )
            content.addView(
                label("将 iPhone 插入车机 USB 数据接口，当 iPhone 提示时允许 CarPlay。", 12, Ui.MUTED).apply {
                    setPadding(dip(4), dip(8), dip(4), 0)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // M6k23：竖屏最底部加「设置」入口（USB 直连同款样式按钮；齿轮已从头部删除）。
            // M6k24：设置键上方加 3 个系统设置快捷入口。
            content.addView(
                quickSettingsRow(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dip(12) },
            )
            content.addView(
                button("设置", false) { setPage("settings") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(50)).apply { topMargin = dip(8) },
            )
        }
        return ScrollView(this).apply {
            // 面板底色不透明 —— 悬浮球唤回菜单后不再透出 CarPlay 画面（8815 实测反馈）。
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 头部：DiPlay Lite + 版本。M6k23：右上角齿轮删除（设置入口走竖屏底部/横屏右列按钮）。 */
    private fun buildHeader(): View {
        val left = column()
        left.addView(label("DiPlay Lite", 18, Ui.TEXT, true))
        left.addView(
            label("LITE · ${readVersionName()}", 10, Ui.MUTED).apply { setPadding(0, dip(2), 0, 0); applyLetterSpacing(0.08f) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return left
    }

    /**
     * 「CARPLAY 状态」大状态卡（原版「无线 CARPLAY」式）：
     * ACCENT 眉题 + 实时状态点 + 状态大字 + 主按钮（按默认通道连接/断开）+ 默认通道提示。
     */
    private fun buildStatusCard(config: LiteConfig): View {
        val card = card()
        val eyebrowRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        eyebrowRow.addView(
            label("CARPLAY 状态", 12, Ui.ACCENT, true).apply { applyLetterSpacing(0.12f) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.GRAY)
            }
        }
        eyebrowRow.addView(statusDot, LinearLayout.LayoutParams(dip(10), dip(10)))
        card.addView(eyebrowRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusLine = label(LiteState.statusText, 20, Ui.TEXT, true).apply {
            setPadding(0, dip(10), 0, dip(12))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(statusLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // M6k24：三键纵排（对齐原版交互）—— 立即连接（常驻，连接中置灰）
        // → 选择 iPhone（常驻，弹热点配置页同款蓝牙选择对话框）→ 断开连接（仅连接中出现）。
        // vc206：外置蓝牙未配对门槛（设计稿 §04 G1）——保持可点、点击拦截弹引导层；
        // 视觉灰化在 renderState 按门槛态切换（连接中的置灰仍归 phase 块管）。
        primaryActionButton = button("立即连接", primary = true, height = 50) {
            if (externalBtGateBlocked()) {
                showExternalBtPairGateDialog()
            } else {
                connect(LiteConfig.load(this@MainActivity).activeChannel)
            }
        }
        card.addView(primaryActionButton, matchButton())

        // vc206：门槛警示行（warnline：WARN 色 12sp，贴主按钮下方，设计稿 §04 G1）。
        // vc310：初始文案留空，真值由 renderState() 按外置蓝牙三态写入。
        pairGateLabel = label("", 12, Ui.WARNING).apply {
            setPadding(0, dip(8), 0, 0)
            visibility = View.GONE
        }
        card.addView(
            pairGateLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        pickIphoneButton = button("选择 iPhone", primary = false, height = 46) {
            // vc206：蓝牙来源=外置 → 整层替换为配对窗（设计稿 §02，三态读 UsbBtStandby 快照）；
            // 内置 → 原有系统已配对设备列表完全不变。
            if (LiteConfig.usbBtDongleOf(this@MainActivity)) {
                ExternalBtPairingDialog.show(this@MainActivity)
            } else {
                SystemSettingsJumps.showIphonePickerDialog(this@MainActivity) { address, name ->
                    LiteConfig.saveBluetoothPick(this@MainActivity, address, name)
                    toast(
                        if (address.isBlank()) "已清除选择：连接时自动匹配已配对 iPhone"
                        else "已选择 $name $address",
                    )
                    LiteLog.log("ui", "主页选择 iPhone：address=$address name=$name")
                }
            }
        }
        card.addView(pickIphoneButton, matchButton(top = 8, height = 46))

        disconnectButton = button("断开连接", primary = false, height = 46) { disconnect() }
        disconnectButton.visibility = View.GONE
        card.addView(disconnectButton, matchButton(top = 8, height = 46))

        hintLabel = label(defaultChannelHint(config.activeChannel), 12, Ui.MUTED).apply { setPadding(0, dip(12), 0, 0) }
        card.addView(
            hintLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        // vc184：「当前蓝牙」行 —— 与默认通道行同款灰字、不加亮不变色。
        btModeLabel = label(btModeText(config), 12, Ui.MUTED).apply { setPadding(0, dip(2), 0, 0) }
        card.addView(
            btModeLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return card
    }

    private fun defaultChannelHint(channel: String): String = when (channel) {
        "relay" -> "默认通道：中继热点。车机与手机同连随身WIFI后自动建立 CarPlay。"
        // vc158：Wi-Fi Direct 成为自动连接方式第四项。
        "p2p" -> "默认通道：Wi-Fi Direct。车机自建点对点组，iPhone 加入后自动建立 CarPlay。"
        "usb" -> "默认通道：USB 连接。数据线直连，当 iPhone 提示时允许 CarPlay。"
        else -> "默认通道：车机热点。手机连上车机热点后自动建立 CarPlay。"
    }

    /** vc184：主页状态卡底部「当前蓝牙」文案 —— 选外置蓝牙时附自定义名称，选车机蓝牙则不显示名称段。 */
    private fun btModeText(config: LiteConfig): String = if (config.usbBtDongle) {
        "当前蓝牙：外置蓝牙　蓝牙名称：${config.usbBtDongleName}"
    } else {
        "当前蓝牙：车机蓝牙"
    }

    /** vc310：外置蓝牙五态（NONE=内置蓝牙，不参与门槛）。 */
    private enum class ExtBtState { NONE, DONGLE_ABSENT, NOT_READY, UNPAIRED, USER_STOPPED, READY }

    /**
     * vc310：外置蓝牙三态的唯一判定出口。
     *
     * 顺序不可换：① 先看在场（不在场时 isRunning 仍可能为 true，重试循环还在跑）；
     * ② 再看失败（failStreak>0 = 插了但连不上，不是未配对）；③ 最后看配对。
     * 已知过渡态：刚插入、常驻广播初始化窗口内（failStreak==0 && !isUsable）会短暂显示
     * 「未配对」，与 vc309 现状一致，不新增第四句文案。
     */
    private fun extBtState(): ExtBtState {
        // NONE = 内置蓝牙，不参与门槛（与现状一致）
        if (!LiteConfig.usbBtDongleOf(this)) return ExtBtState.NONE
        if (!UsbDongleGuard.isPresent(this)) return ExtBtState.DONGLE_ABSENT
        // vc314（UI-311-01）：用户主动「停止外置蓝牙」后，dongle 仍在总线上 ⇒ isPresent 为真、
        // isUsable 为假，会落到 UNPAIRED 报「未配对」。先判这个抑制位，落第 4 态。
        if (usbBtUserStopped) return ExtBtState.USER_STOPPED
        if (UsbBtStandby.failStreak > 0) return ExtBtState.NOT_READY
        if (!UsbBtStandby.isUsable) return ExtBtState.UNPAIRED
        return ExtBtState.READY
    }

    /** vc310：外置蓝牙门槛 —— 外置来源 + 无线通道（USB 直连不经蓝牙，不拦）+ 未就绪。 */
    private fun externalBtGateBlocked(): Boolean {
        if (LiteConfig.activeChannelOf(this) == "usb") return false
        val state = extBtState()
        return state != ExtBtState.NONE && state != ExtBtState.READY
    }

    /** vc314：失败卡的「重试」——与主按钮走同一门槛出口，不允许绕过。 */
    private fun retryFromFailedCard() {
        if (externalBtGateBlocked()) {
            showExternalBtPairGateDialog()
            return
        }
        connect(LiteConfig.load(this).activeChannel)
    }

    /** vc206：门槛引导层（设计稿 §04 G1 可选「去配对」跳转；配对窗内完成配对后自动解锁）。 */
    private fun showExternalBtPairGateDialog() {
        when (extBtState()) {
            ExtBtState.DONGLE_ABSENT -> AppDialog.Builder(this)
                .setTitle("未检测到外置蓝牙")
                .setMessage("请把 USB 蓝牙 dongle 插到车机 USB 口。插入后会自动重连，无需手动操作。")
                .setPositiveButton("知道了", null)
                .show()

            ExtBtState.NOT_READY -> AppDialog.Builder(this)
                .setTitle("外置蓝牙连接失败")
                .setMessage("${UsbBtStandby.describe()}。可尝试拔插 dongle，或重启车机后再连。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重试") { _, _ -> reconnectDongle() }
                .show()

            ExtBtState.USER_STOPPED -> AppDialog.Builder(this)
                .setTitle("外置蓝牙已停止")
                .setMessage("你手动停止了外置蓝牙。车机热点连接不受影响，只是外置蓝牙的配对与常驻广播暂停了。点「去开启」会重新启动广播并打开配对窗口。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去开启") { _, _ ->
                    // vc315（UI-314-01）：stopExternalBt() 会把档位写成 USB_BT_MODE_OFF，而
                    // ensureUsbBtStandby() 的第一道门就是「档位=关闭则不拉起」（vc248 的
                    // 「关闭 > 页面临时升档」优先级）⇒ 只开页面不会恢复，usbBtUserStopped 也
                    // 不会被清。用户既然点了「去开启」，就先把档位拨回可被发现，再开页面；
                    // 之后的 ensureUsbBtStandby() 会清抑制并 syncUsbBtStandby(force=true)。
                    LiteConfig.setUsbBtPowerMode(this, LiteConfig.USB_BT_MODE_DISCOVERABLE)
                    ExternalBtPairingDialog.show(this)
                }
                .show()

            else -> AppDialog.Builder(this)   // UNPAIRED：现状文案，一个字不改
                .setTitle("先完成配对")
                // vc247（V11）：单台语义文案 —— 同一时间只服务一台，配对新手机会顶替旧设备。
                .setMessage("外置蓝牙还没和 iPhone 配对。同一时间只服务一台设备，配对新手机会顶替旧设备。点「去配对」打开配对窗口，完成后再回来连接。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去配对") { _, _ -> ExternalBtPairingDialog.show(this) }
                .show()
        }
    }

    /**
     * M6k24：主页快捷入口行（WiFi / 蓝牙 / 热点），放在设置键上方，一行三列等宽。
     * 跳转逻辑与热点配置页共用 SystemSettingsJumps（三级降级）。
     */
    private fun quickSettingsRow(): View {
        val rowView = row()
        val entries = listOf(
            "WiFi" to { SystemSettingsJumps.openWifiSettings(this) },
            "蓝牙" to { SystemSettingsJumps.openBluetoothSettings(this) },
            "热点" to { SystemSettingsJumps.openHotspotSettings(this) },
            // vc246（USB-246-05）：摘除「重连蓝牙」入口，掉线自动重连（DETACHED）已覆盖。
        )
        entries.forEachIndexed { index, (title, onClick) ->
            val entry = button(title, primary = false, height = 48, click = onClick)
            entry.layoutParams = LinearLayout.LayoutParams(0, dip(48), 1f).apply {
                if (index > 0) leftMargin = dip(8)
            }
            rowView.addView(entry)
        }
        return rowView
    }

    /**
     * vc245：启动自检（后台 + 10s 看门狗）。只落日志、异常时上屏，不改连接状态机。
     * vc246（USB-246-03 配套）：成功判据升级为 HCI 初始化真实应答（见 UsbDongleGuard）。
     */
    private fun startDongleSelfCheck() {
        LiteLog.log("usbdongle", "启动自检：已挂载（usbBtDongle=true）")
        Thread {
            val report = UsbDongleGuard.check(this, "启动自检", expectStandby = true)
            usbSixFields("usb", "self-check", "gate", report.status.toString())
            // vc247（V2）：SKIPPED 与单次失败只写日志；连续真失败达到阈值（report.alert=true）才上屏。
            if (report.alert) {
                val dstate = if (UsbBinderGate.suspect) "；D 态疑似（${UsbBinderGate.reason}），请重启车机" else ""
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "外置蓝牙自检异常：${report.status}$dstate（详见日志 usbdongle）",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    /**
     * vc245：手动「重连蓝牙」入口 —— 不依赖拔插。
     *
     * 顺序（对齐验收标准）：
     * 1. 先彻底释放旧句柄：`UsbBtStandby.stop()` 会 close 常驻广播持有的 driver/interface/fd，
     *    并让旧 open 循环的 generation 失效（否则第二次 open 会排在卡住的旧调用后面）；
     * 2. 留 500ms 静默窗（沿用 vc233 实测的 release 收尾时间）；
     * 3. 有界自检；正常 → 重正常驻广播 + 重进自动连接；异常 → 退避重试，封顶后上屏并停。
     */
    private fun reconnectDongle() {
        if (UsbBinderGate.suspect) {
            // vc246（USB-246-03）：粘性门禁 —— 已判定 D 态疑似，再 open 只会把线程堆进内核卡死。
            LiteLog.log("usbdongle", "重连被 D 态门禁拒绝：${UsbBinderGate.describe()}")
            usbSixFields("usb", "reconnect-blocked", "gate", "reboot-required")
            Toast.makeText(this, "D 态疑似（${UsbBinderGate.reason}），已禁止重开，请重启车机", Toast.LENGTH_LONG).show()
            return
        }
        if (dongleRetryRunning) {
            Toast.makeText(this, "重连已在进行中…", Toast.LENGTH_SHORT).show()
            return
        }
        dongleRetryRunning = true
        dongleRetryAttempt = 0
        usbSixFields("usb", "reconnect-begin", "none", "release-then-check")
        Toast.makeText(this, "正在释放旧句柄并检测外置蓝牙…", Toast.LENGTH_SHORT).show()
        Thread {
            // 第一步：释放旧句柄（这是「拔插没反应」的关键：旧 connection/interface/fd 未放，
            // 后续 open 会排在被卡住的内核调用后面）。
            LiteLog.log("usbdongle", "手动重连：先释放旧句柄 UsbBtStandby.stop()")
            runCatching { UsbBtStandby.stop() }
            runCatching { Thread.sleep(1_000) }
            dongleRetryStep()
        }.start()
    }

    /** vc245：退避 1s/2s/4s/8s…（封顶 [UsbBtRetryPolicy.MAX_DELAY_MILLIS]），连续 [DONGLE_RETRY_MAX] 次失败即停。 */
    private fun dongleRetryStep() {
        dongleRetryAttempt += 1
        val attempt = dongleRetryAttempt
        Thread {
            val report = UsbDongleGuard.check(this, "手动重连#$attempt")
            usbSixFields("usb", "retry-step-$attempt", "none", report.status.toString())
            runOnUiThread {
                when {
                    report.status == UsbDongleGuard.Status.OK -> {
                        dongleRetryRunning = false
                        Toast.makeText(
                            this, "外置蓝牙已恢复：${report.detail}", Toast.LENGTH_SHORT,
                        ).show()
                        autoConnectTried = false
                        // 重新拉常驻广播（iPhone 回连后既有 onLinkReady 会自动发起连接）。
                        syncUsbBtStandby(force = true)
                    }
                    attempt >= DONGLE_RETRY_MAX -> {
                        dongleRetryRunning = false
                        usbSixFields("usb", "retry-exhausted", "none", "stopped")
                        LiteLog.log(
                            "usbdongle",
                            "手动重连：连续 $attempt 次失败（${report.status}），停止自动重试" +
                                if (UsbBinderGate.suspect) "；D 态疑似（${UsbBinderGate.reason}）" else "",
                        )
                        Toast.makeText(
                            this,
                            "外置蓝牙连续 $attempt 次重连失败（${report.status}）：" +
                                (if (UsbBinderGate.suspect) "D 态疑似，请重启车机" else "已停止自动重试，可插拔 dongle 或重启车机"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    else -> {
                        // 退避：与常驻广播共用 UsbBtRetryPolicy（1s/2s/4s/8s，封顶 8s）。
                        val delay = UsbBtRetryPolicy.delayAfterFailure(attempt)
                        LiteLog.log(
                            "usbdongle",
                            "手动重连：第 $attempt 次 ${report.status}（${report.detail}），" +
                                "${delay}ms 后重试",
                        )
                        handler.postDelayed({ dongleRetryStep() }, delay)
                    }
                }
            }
        }.start()
    }

    // ---------- 视图状态 ----------

    /** 配置面板的显隐：收起后整屏都是 CarPlay 画面与触摸区（home 页专属机制）。 */
    private fun setPanelVisible(visible: Boolean) {
        panelVisible = visible
        // M6k25：子页没有面板显隐机制 —— 只记录状态，回主页 render 时按状态生效。
        // （避免 refreshTask 的 setPanelVisible(!active) 把正在看的子页整个隐藏。）
        if (page != "home") {
            // vc163：子页面板常显，全局悬浮球保持醒目（不再淡化）。
            ballRoot.alpha = 1f
            return
        }
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        // 面板展开时悬浮球淡化（避免和标题栏抢视觉），收起时恢复醒目。
        ballRoot.alpha = if (visible) 0.5f else 1f
    }

    /**
     * 悬浮球可拖拽。按下记位、移动改 right/top margin、抬起几乎未动则视为点击。
     * TOP|END 定位：向右拖 = rightMargin 减小；范围钳在父容器内；松手吸边。
     */
    private fun attachBallDrag(ball: View, params: FrameLayout.LayoutParams) {
        val slop = dip(6)
        var downRawX = 0f
        var downRawY = 0f
        var downRight = 0
        var downTop = 0
        var dragging = false
        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downRight = params.rightMargin
                    downTop = params.topMargin
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val parent = view.parent as? View
                        val maxRight = ((parent?.width ?: 0) - view.width).coerceAtLeast(0)
                        val maxTop = ((parent?.height ?: 0) - view.height).coerceAtLeast(0)
                        params.rightMargin = (downRight - dx).coerceIn(0, maxRight)
                        params.topMargin = (downTop + dy).coerceIn(0, maxTop)
                        // vc163：全局球位置持久化，切页重建时沿用
                        ballRightMargin = params.rightMargin
                        ballTopMargin = params.topMargin
                        view.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // 松手吸边 —— 球心在左半屏贴左、右半屏贴右，纵向保持松手位置。
                        val parent = view.parent as? View
                        val parentWidth = parent?.width ?: 0
                        if (parentWidth > 0) {
                            val centerX = parentWidth - params.rightMargin - view.width / 2
                            params.rightMargin = if (centerX > parentWidth / 2) {
                                dip(12)
                            } else {
                                (parentWidth - view.width - dip(12)).coerceAtLeast(0)
                            }
                            // vc163：吸边后的最终位置也要记住
                            ballRightMargin = params.rightMargin
                            ballTopMargin = params.topMargin
                            view.layoutParams = params
                        }
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * 触摸转发：屏幕坐标归一化成 0..1 的接触点，交给当前会话发 HID 报告。
     * 未连接时返回 false（不消费事件）。
     */
    private var lastMoveForwardMs = 0L

    private fun forwardTouch(event: MotionEvent): Boolean {
        val service = LiteSessionService.current ?: return false
        val width = video.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = video.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return false
        // 只有 ACTION_MOVE 是可合并的中间态；DOWN/UP/POINTER_*/CANCEL 都必须逐条送达。
        val sticky = event.actionMasked != MotionEvent.ACTION_MOVE
        // 1.0.7：MOVE 节流——手指按屏抖动会以 60Hz 洪灌 HID 通道
        // （3e 实测 15 秒 310 条 POST），DOWN/UP 不受影响。
        // vc278（跟手度）：33ms → 8ms（约 120Hz 采样）。洪灌由 sendTouch 的
        // 「MOVE 队尾就地覆盖 + 单泵串行排空」兜住（见 CarPlayController.enqueueTouchLocked），
        // 真正上行的条数与采样率无关；放宽采样只让滑动中间态更完整。
        if (!sticky) {
            val now = System.currentTimeMillis()
            if (now - lastMoveForwardMs < 8) return true
            lastMoveForwardMs = now
        }
        return service.sendTouch(CarPlayTouchMapper.contacts(event, width, height), sticky)
    }

    // ---------- 连接动作 ----------

    /**
     * 按通道发起连接。中继/USB 底层未接入前给出明确提示（避免误认为故障）。
     * auto=true 表示自动连接路径（开机自启/onResume 自动连接）：
     * USB 通道在 VPN 已授权（prepare 返回 null）时跳过 app 内确认框静默建链，
     * 避免无人值守场景被确认弹窗阻塞；手动入口（auto 缺省 false）行为不变。
     */
    private fun connect(channel: String, auto: Boolean = false) {
        // vc247（V3）：任何一次真实连接都视为用户回来了，解除断开抑制。
        userDisconnected = false
        // vc250：连接入口总线扫描——dongle 在场时也落 usb-enum。
        logUsbDonglePresence()
        val config = LiteConfig.load(this)
        when (channel) {
            "car" -> {
                if (config.ssid.isBlank()) {
                    toast("请先在「设置 → 车机热点网络配置」里填写热点名称")
                    return
                }
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps)
                // M6d：实测改分辨率后不重启+重连会出现渲染异常，这里拦一道二次确认。
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                startSession(config, key)
            }
            // vc158：Wi-Fi Direct 独立通道。与 car 同为无线，但凭据由系统随机生成，
            // 因此不需要（也不应）校验手填的热点名称/密码。
            "p2p" -> {
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps)
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                startSession(config, key, "p2p")
            }
            "relay" -> {
                if (config.relaySsid.isBlank()) {
                    toast("请先在「设置 → 中继热点网络配置」里填写随身WIFI信息")
                    return
                }
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps)
                // M6d：实测改分辨率后不重启+重连会出现渲染异常，这里拦一道二次确认。
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                startSession(config, key, "relay")
            }
            "usb" -> {
                // vc200：「清残留」从 onCreate 的一次性动作下移到**每次连接入口**。
                // 原有三处清理（onCreate:ensureCleanVpnState / closeWiredStack / USB 拔出
                // receiver）里，onCreate 那条在长驻车机上是真空档：Activity 为 singleTask +
                // configChanges 全覆盖，onCreate 只在进程首启跑一次。于是「上次异常结束、
                // 没拔线、closeWiredStack 也没走完」的残留 TUN fd 在下次连接前无人复查。
                // 有会话在跑时不碰（TUN 归当前会话所有）；无残留时 ensureCleanVpnState 内部
                // 直接跳过，零成本。
                if (!LiteState.running) ensureCleanVpnState("连接入口")
                // vc153（K60）：有线连接前的无线互斥——无线会话活跃（真交接/降级态/本代
                // 曾建立过）时先 stopService 拆无线栈（onDestroy 全拆 controller/AirPlay
                // 栈/VPN）。vc154：不再用固定 2.5s 延迟——拆栈完成回调驱动（teardown
                // 线程在闭锁放行后触发，即无线栈已彻底拆干净、iPhone 已收到断开信号），
                // 回调即重入本流程走有线 bring-up；8s 兜底防回调丢失（服务销毁竞态等）。
                // 无线未连接（无服务/未建立过会话）时零影响直走原路径。
                if (LiteSessionService.current?.isWirelessSessionAlive() == true) {
                    LiteLog.log("ui", "USB 通道：无线会话活跃，先拆无线栈，拆栈完成回调驱动有线连接（8s 兜底）")
                    val entered = java.util.concurrent.atomic.AtomicBoolean(false)
                    val handler = Handler(mainLooper)
                    // vc154 回调：onDestroy 转挂到 closingController，拆栈线程触发；
                    // post 回主线程后重入 connect（AtomicBoolean 防与兜底双触发）。
                    LiteSessionService.armTeardownCallback {
                        handler.post {
                            if (entered.compareAndSet(false, true)) {
                                LiteLog.log("ui", "USB 通道：无线栈拆除完成（回调驱动），进入有线连接")
                                connect(channel, auto)
                            }
                        }
                    }
                    stopService(Intent(this, LiteSessionService::class.java))
                    handler.postDelayed({
                        if (entered.compareAndSet(false, true)) {
                            LiteLog.log("ui", "USB 通道：拆栈完成回调 8s 未到（兜底触发），直接进入有线连接")
                            connect(channel, auto)
                        }
                    }, WIRED_SWITCH_FALLBACK_MS)
                    return
                }
                // U4 USB 有线：数据面 TUN 需要 VPN 授权（一次性系统对话框）；
                // 已授权（prepare 返回 null）则直接进入连接流程。
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps)
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                if (!hasAppleUsbDevice()) {
                    toast("未检测到 iPhone：请用数据线把 iPhone 连到车机 USB 口")
                    LiteLog.log("ui", "USB 通道连接请求：未发现 Apple VID(0x05ac) USB 设备")
                    return
                }
                // vc93：三段式授权。部分 OEM ROM（235521 日志实锤）裁剪了
                // com.android.vpndialogs，startActivityForResult 直接抛
                // ActivityNotFoundException 崩溃主线程。顺序：标准对话框 →
                // root appops 免对话框授权 → 明确报错，任何一步都不许崩。
                var consent: Intent? = null
                var prepareBroken = false
                try {
                    consent = VpnService.prepare(this)
                } catch (error: Exception) {
                    prepareBroken = true
                    LiteLog.log("ui", "USB 通道：VpnService.prepare 异常 ${error.message}")
                }
                if (!prepareBroken && consent != null) {
                    // vc148：自动连接优先 root appops 静默授权（仅 auto 路径——手动路径
                    // 不吃这 4s 超时代价）。授权成功则跳过系统对话框，无人值守全自动；
                    // 失败（无 root）回落系统对话框，首装首次仍需一次人工授权。
                    if (auto && grantVpnConsentViaRoot()) {
                        val after = try {
                            VpnService.prepare(this)
                        } catch (error: Exception) {
                            null
                        }
                        if (after == null) {
                            LiteLog.log("ui", "USB 通道：自动连接 root appops 静默授权成功，跳过系统对话框")
                            startSession(config, key, "usb")
                            return
                        }
                    }
                    var launched = false
                    try {
                        startActivityForResult(consent, REQUEST_VPN_PERMISSION)
                        LiteLog.log("ui", "USB 通道：请求 VPN 授权")
                        launched = true
                    } catch (error: Exception) {
                        LiteLog.log("ui", "USB 通道：VPN 授权对话框无法启动（ROM 疑似裁剪 vpndialogs）：${error.message}")
                    }
                    if (launched) return
                    // 对话框路走不通 → root 兜底：免对话框写 appops 后复检。
                    if (grantVpnConsentViaRoot()) {
                        val after = try {
                            VpnService.prepare(this)
                        } catch (error: Exception) {
                            null
                        }
                        if (after == null) {
                            LiteLog.log("ui", "USB 通道：root appops 免对话框授权成功")
                            startSession(config, key, "usb")
                            return
                        }
                    }
                    // vc154：授权链路全失败不再只弹 toast（无行动路径），升级引导页。
                    LiteLog.log("ui", "USB 通道：VPN 授权链路全失败（无 root 或 appops 未生效），弹引导页")
                    showVpnConsentGuideDialog(prepareBroken = false)
                    return
                }
                if (!prepareBroken && consent == null) {
                    // vc111（T3 排查定案）：KK 的 VPN 授权=mPackage 内存态，异常断开
                    // （interfaceRemoved）后残留 → VpnService.prepare 返回 null，系统
                    // 授权框被跳过。mPackage 无公开 API 可清（无 root 不可清）。
                    //
                    // vc200 定案（删除 app 内「VPN 连接确认」框）：这个框自 vc111 起存在，
                    // 但**从未承载它声称的职责**——
                    // ① 「清残留」实际发生在 onCreate / closeWiredStack / USB 拔出三处，
                    //    而框的「确定」只是 `startSession()`，零清理动作；
                    // ② establish() 返回 null（mPackage 残留 / UID 不匹配）时，用户点
                    //    「确定」改变不了 mPackage，照样失败 ⇒ 框是提示，不是解法。
                    // 实测代价：1.0s + 一次人工点击（22:06:09.986→22:06:11.002）。
                    // 现在：清理已在连接入口幂等执行（vc200），establish 失败由 Service 侧
                    // 「prepare 复核 + 同参重开一次」兜住（WiredVpnService），失败还会由
                    // 控制器整轮重开兜一次。失败文案由 establish 的真实原因分级给出，
                    // 比一个只会说「是否继续？」的框有信息量。
                    val mode = if (auto) "自动连接" else "手动连接"
                    LiteLog.log("ui", "USB 通道：$mode prepare 放行（系统 VPN 授权已存在），直接建链")
                    startSession(config, key, "usb")
                    return
                }
                // prepare 本身异常 → 也尝试 root 兜底后复检。
                if (grantVpnConsentViaRoot()) {
                    val after = try {
                        VpnService.prepare(this)
                    } catch (error: Exception) {
                        null
                    }
                    if (after == null) {
                        LiteLog.log("ui", "USB 通道：root appops 授权成功（prepare 异常路径）")
                        startSession(config, key, "usb")
                        return
                    }
                }
                // vc154：prepare 异常且 root 兜底未生效 → 引导页（原只弹 toast）。
                LiteLog.log("ui", "USB 通道：VpnService.prepare 异常且 root 兜底未生效，弹引导页")
                showVpnConsentGuideDialog(prepareBroken = true)
            }
            else -> {
                toast("未知连接通道")
                LiteLog.log("ui", "未知通道连接请求：$channel")
            }
        }
    }

    /**
     * vc154：VPN 授权失败引导页（场景：无 root + 车机 ROM 缺失/裁剪授权组件）。

     * 原两个失败终点只弹 toast 无行动路径，升级为弹窗引导：
     * ① 已 root → 授权后点「重试授权」自动写 appops + 复检建链；
     * ② 无 root → 提示改用无线连接（车机热点/中继），此类车机有线结构性不可用。
     * 重试的 su 写入放后台线程（grantVpnConsentViaRoot 内部 4s 轮询），结果 post 回
     * 主线程；API16 兼容：仅用自绘 AppDialog（应用内覆盖层），无新依赖。
     */
    private fun showVpnConsentGuideDialog(prepareBroken: Boolean) {
        val reason = if (prepareBroken) {
            "车机 VPN 授权组件异常（VpnService.prepare 抛异常）"
        } else {
            "车机系统缺失 VPN 授权组件（vpndialogs 被裁剪）"
        }
        LiteLog.log("ui", "USB 通道：VPN 授权失败引导页展示（$reason）")
        AppDialog.Builder(this)
            .setTitle("USB 有线模式需要 VPN 授权")
            .setMessage(
                "$reason，且当前未获取 root，无法完成一次性授权。\n\n" +
                    "USB 有线模式需建立 VPN 网络通道（数据面必需），此类车机可选：\n\n" +
                    "① 若车机已 root：请先给本 app 授权 root，再点「重试授权」自动放行并连接；\n" +
                    "② 改用无线连接（车机热点/中继模式），不依赖 VPN 授权组件。",
            )
            .setPositiveButton("重试授权（已 root）") { _, _ ->
                LiteLog.log("ui", "VPN 授权引导页：点重试授权")
                Thread {
                    val granted = grantVpnConsentViaRoot() && runCatching {
                        VpnService.prepare(this) == null
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (granted) {
                            LiteLog.log("ui", "VPN 授权引导页：重试成功，进入有线连接")
                            val config = LiteConfig.load(this)
                            startSession(
                                config,
                                LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps),
                                "usb",
                            )
                        } else {
                            toast("授权仍未生效：root 未授予或未生效，请改用无线连接")
                            LiteLog.log("ui", "VPN 授权引导页：重试仍失败")
                        }
                    }
                }.start()
            }
            .setNegativeButton("改用无线连接") { _, _ -> switchToWirelessFromGuide() }
            .setNeutralButton("取消", null)
            .show()
    }

    /** vc154：引导页「改用无线连接」动作——沿用最近一次用过的无线通道（无记录走车机热点）。 */
    private fun switchToWirelessFromGuide() {
        val saved = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).getString(SESSION_CHANNEL_KEY, null)
        // vc158：沿用最近一次用过的无线通道（car / p2p / relay），无记录走车机热点。
        val channel = when (saved) {
            "relay" -> "relay"
            "p2p" -> "p2p"
            else -> "car"
        }
        LiteLog.log("ui", "VPN 授权引导页：用户选择改用无线连接（channel=$channel）")
        connect(channel)
    }

    /**
     * vc148：VPN 状态检测与残留清理（**幂等**，无残留时为 no-op）。
     * - 存量 TUN fd（上次会话异常退出未关）→ shutdownTun() 关闭；
     * - WiredVpnService 实例残留 → stopService 强制停止；
     * - 系统侧其他 app 的 VPN 不属于本 app 管辖，不触碰（VpnService 无跨 app 清理 API）。
     *
     * vc200 两处升级：
     * ① **判定先行**：先问 `WiredVpnService.hasResidual()`，无残留整段跳过。旧版无条件
     *    `stopService()`，而低 API / EMUI ROM 上 `stopService` 紧跟 `startService` 有被
     *    合并/丢弃的先例 —— 没有残留时那次 `stopService` 是白冒风险。
     * ② **调用点下移**（见 `connect("usb")` 入口）：车机常年不关机，本 Activity 是
     *    `singleTask` + `configChanges` 全覆盖 ⇒ `onCreate` 只在进程首启执行一次，
     *    「上次异常结束但没拔线、`closeWiredStack` 也没走完」的残留 TUN fd 在下次连接前
     *    根本不会被复查。`origin` 参数用于区分调用来源，日志可追溯。
     */
    private fun ensureCleanVpnState(origin: String = "启动") {
        if (!WiredVpnService.hasResidual()) {
            LiteLog.log("ui", "VPN 状态检测[$origin]：无残留（无存量 TUN fd、服务未在世），跳过清理")
            return
        }
        try {
            WiredVpnService.shutdownTun()
        } catch (error: Throwable) {
            LiteLog.log("ui", "VPN 状态检测[$origin]：TUN 残留清理异常（忽略）${error.message}")
        }
        try {
            stopService(Intent(this, WiredVpnService::class.java))
            LiteLog.log("ui", "VPN 状态检测[$origin]：检测到残留，已强制清理（TUN fd + 服务）")
        } catch (error: Throwable) {
            LiteLog.log("ui", "VPN 状态检测[$origin]：服务残留清理异常（忽略）${error.message}")
        }
    }

    /** vc250：把 ATTACHED intent 里的 Apple / dongle 设备落到 usb-enum；只带 VID/PID（不含序列号）。 */
    private fun logUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        val role = when (device.vendorId) {
            APPLE_USB_VENDOR_ID -> "iphone"
            UsbDongleGuard.VENDOR_ID -> "dongle"
            else -> return
        }
        LiteLog.stage(
            LiteLog.Stage.UsbEnum,
            "attach-intent vid=0x%04x pid=0x%04x role=$role".format(
                device.vendorId, device.productId,
            ),
        )
    }

    /** vc250：连接入口的总线扫描——dongle 在场时落 usb-enum；复用 UsbDongleGuard 的 VID 判定。 */
    private fun logUsbDonglePresence() {
        val manager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        val dongle = UsbDongleGuard.findDongle(manager) ?: return
        LiteLog.stage(
            LiteLog.Stage.UsbEnum,
            "bus-check vid=0x%04x pid=0x%04x role=dongle".format(
                dongle.vendorId, dongle.productId,
            ),
        )
    }

    /** 枚举 USB 设备看是否有 Apple VID（iPhone/iPad），无需任何权限。 */
    private fun hasAppleUsbDevice(): Boolean {
        val apple = (getSystemService(USB_SERVICE) as? UsbManager)?.deviceList?.values
            ?.firstOrNull { it.vendorId == APPLE_USB_VENDOR_ID }
        // vc250：总线枚举结果直接落 usb-enum（服务未起时也能记；只带 VID/PID）。
        LiteLog.stage(
            LiteLog.Stage.UsbEnum,
            if (apple != null) {
                "bus-check appleUsbPresent=true vid=0x%04x pid=0x%04x role=iphone".format(
                    apple.vendorId, apple.productId,
                )
            } else {
                "bus-check appleUsbPresent=false role=iphone"
            },
        )
        return apple != null
    }

    /** vc93：root 免对话框写 appops 放行 ACTIVATE_VPN（授权组件被裁的 ROM 上绕过对话框）。
     *  只负责尝试写 op，最终是否生效由调用方复检 VpnService.prepare 判定。
     *  vc94：原 waitFor() 无超时——su 弹交互授权框无人应答会永久阻塞主线程直至 ANR；
     *  Process.waitFor(timeout) 是 API24+（minSdk16 不可用），改轮询 exitValue，4s 无果即
     *  destroy 走失败（低于 5s ANR 线；预授权车机 su 秒回不受影响）。 */
    private fun grantVpnConsentViaRoot(): Boolean = try {
        val su = Runtime.getRuntime().exec(arrayOf("su", "-c",
            "appops set $packageName ACTIVATE_VPN allow"))
        var exit: Int? = null
        var waited = 0
        while (exit == null && waited < 4_000) {
            Thread.sleep(100)
            waited += 100
            try {
                exit = su.exitValue()
            } catch (_: IllegalThreadStateException) {
            }
        }
        if (exit == null) {
            su.destroy()
            LiteLog.log("ui", "USB 通道：root 免对话框授权超时（4s）")
            false
        } else {
            true
        }
    } catch (error: Exception) {
        LiteLog.log("ui", "USB 通道：root 免对话框授权失败：${error.message}")
        false
    }

    /** VpnService 授权 / CarIconPage 图库选择的系统回调。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // M6k25：图库选择结果转发给 CarIconPage（requestCode=1001）。
        if (requestCode == REQUEST_GALLERY_PICK) {
            carIconPage?.onGalleryResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_VPN_PERMISSION) return
        if (resultCode == RESULT_OK) {
            startSession(LiteConfig.load(this), LiteConfig.videoKeyOf(LiteConfig.load(this).videoScalePercent, LiteConfig.load(this).videoFps), "usb")
        } else {
            toast("未授予 VPN 权限，USB 连接无法建立")
            LiteLog.log("ui", "USB 通道：VPN 授权被拒绝")
        }
    }

    private fun startSession(config: LiteConfig, videoKey: String, channel: String = "car") {
        // 日志头段锚点：固定文案、可 grep，供 dongle 日志头尾双保留定位连接起点。
        // vc239（LOG-238-02）：同一轮 1.5s 内的重复调用只打第一条；带 session/gen 归属，
        // 若常驻广播的 link-ready 先到，本行沿用那一代（消除 vc238「晚 2.6s」错配）。
        AnchorLog.beginConnect()?.let { LiteLog.log("anchor", it) }
        LiteConfig.saveAppliedVideoKey(this, videoKey)
        // 记录本轮会话通道：每卡绿点按它判定"哪张卡在连/已连"（UI 层自有键，不动 LiteConfig）。
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(SESSION_CHANNEL_KEY, channel)
            .apply()
        if (channel == "relay") {
            LiteLog.log(
                "ui",
                "发起连接（relay）：ssid=${config.relaySsid} videoKey=$videoKey duck=${config.navDuckPercent}%",
            )
        } else if (channel == "p2p") {
            // vc158：Wi-Fi Direct 凭据由系统随机生成，日志不打印手填热点字段。
            LiteLog.log(
                "ui",
                "发起连接（p2p/Wi-Fi Direct）：凭据随机生成 videoKey=$videoKey duck=${config.navDuckPercent}%",
            )
        } else {
            LiteLog.log(
                "ui",
                "发起连接（car）：ssid=${config.ssid} channel=${config.carIpv4Channel} security=${config.security} " +
                    "videoKey=$videoKey duck=${config.navDuckPercent}%",
            )
        }
        startService(
            Intent(this, LiteSessionService::class.java).putExtra(LiteSessionService.EXTRA_CHANNEL, channel),
        )
        toast("已启动会话服务")
    }

    /**
     * M6d：分辨率/帧率变更的二次确认。
     * vc147（T3 实测定案）：改为「下次连接生效」，不再停止会话/重建界面 ——
     * 原实现 stopService + finish(NEW_TASK|CLEAR_TASK) 重建后存在渲染端丢失竞态
     * （新会话解码器正常创建、rx=30fps 正常收流，但 shown=0 —— Surface 与解码器
     * 在 CLEAR_TASK 重建窗口内脱节，17:25 会话实测复现）。连接时构造
     * CarPlayController 本就按 config 现值取参，断开重连即按新参数运行，
     * 无需销毁 Activity；当前会话继续跑旧参数，无渲染异常。
     * appliedVideoKey 在确认时即落成新值：后续连接入口/自动连接不再二次弹确认
     * （1054/1068/1318 的 key 比较自然通过）。
     */
    private fun confirmVideoChange() {
        AppDialog.Builder(this)
            .setTitle("已修改分辨率/帧率")
            .setMessage(
                "视频参数将在下次连接时生效。\n\n" +
                    "当前连接继续使用旧参数，断开后重新连接即按新参数运行。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("知道了") { _, _ ->
                val config = LiteConfig.load(this)
                LiteConfig.saveAppliedVideoKey(this, LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps))
                LiteLog.log("ui", "视频参数已保存，下次连接生效")
            }
            .show()
    }

    private fun disconnect() {
        stopService(Intent(this, LiteSessionService::class.java))
        // vc247（V3）：入口一「断开连接」= 只停会话 + 抑制自动拉起；常驻广播/配对窗一律不动。
        autoConnectTried = true
        userDisconnected = true
        LiteLog.log("ui", "用户断开：仅停会话，常驻广播保持")
    }

    /**
     * vc247（V3）：入口二「停止外置蓝牙」——停会话 + 停常驻广播 + 关配对窗 + 抑制自动拉起。
     * 停止后不会自行恢复：重新打开配对页（ensureUsbBtStandby）或 App 重启才恢复。
     */
    internal fun stopExternalBt() {
        // vc248（VC248-03）：关闭档落盘，重启/回前台也不再自动拉起。
        LiteConfig.setUsbBtPowerMode(this, LiteConfig.USB_BT_MODE_OFF)
        stopService(Intent(this, LiteSessionService::class.java))
        // 用户主动 stop：静态配对窗字段必须一起清掉（否则下次启动仍会 scan=3 到窗口结束）。
        UsbBtStandby.exitPairingMode()
        UsbBtStandby.stop()
        autoConnectTried = true
        userDisconnected = true
        usbBtUserStopped = true
        LiteLog.log("ui", "用户停止外置蓝牙：会话已停，常驻广播已释放，配对窗已关闭")
    }

    // ---------- vc192：外置 USB 蓝牙常驻广播 ----------

    /**
     * vc192：选中「外置蓝牙」时把 dongle 拉成常驻广播态（幂等，回前台同步一次）。
     *
     * 对齐 legacy 0.3.1 实测流程（2026-10-27 日志）：App 一起来就
     * `USB Bluetooth enabled: advertising=…`，随后由 iPhone 主动回连；
     * 我们旧实现只在「点了连接」时才初始化 dongle，因此不点连接时 iPhone 蓝牙列表里
     * 看到的还是 dongle 出厂名（CM390），而不是设置页里配的广播名。
     */
    private fun syncUsbBtStandby(force: Boolean = false) {
        val config = LiteConfig.load(this)
        if (!config.usbBtDongle) {
            UsbBtStandby.stop()
            return
        }
        // vc248（VC248-03）：三态优先级「关闭 > 页面临时升档 > 用户档位」。
        // 关闭档即使 force（打开配对页）也不拉起，必须由用户在设置里显式改档。
        val powerMode = LiteConfig.usbBtPowerModeOf(this)
        if (powerMode == LiteConfig.USB_BT_MODE_OFF) {
            UsbBtStandby.stop()
            LiteLog.log("usbdongle", "外置蓝牙档位=关闭，跳过拉起")
            return
        }
        // vc247（V3/V4）：用户主动「停止外置蓝牙」后不再自动拉起（重新打开配对页才恢复）；
        // 判据固化为「开机自动初始化（老配置）|| 启动后自动连接」任一为真即初始化，
        // UI 已不再提供 autoInit 切换。打开配对窗/去配对走 force=true 按需启动。
        if (!force && usbBtUserStopped) {
            LiteLog.log("usbdongle", "用户已停止外置蓝牙，跳过自动拉起")
            return
        }
        // vc319（BT-318-01 方案B）：autoInit 语义降级为「是否常驻」。
        // 原四条件全假就 return ⇒ autoInit（缺省 false）+ autoConnect=false 时 standby 永不启动
        // ⇒ isUsable=false ⇒ 主页永久误报「未配对」（BT-318-01 症①）
        // ⇒ startDongleSelfCheck(expectStandby=true) 空等 15s 假报 HCI_NO_RESPONSE（症②）。
        // 现补一条放行：dongle 在场 且 磁盘有配对记忆 ⇒ 拉起一次（每 Activity 实例一次）。
        val autoBringUp = !force && !LiteConfig.usbBtAutoInitOf(this) && !config.autoConnect && !UsbBtStandby.isRunning
        if (autoBringUp) {
            if (usbBtAutoBroughtUp || !UsbDongleGuard.isPresent(this) || !UsbBtStandby.hasSavedPairing(this)) {
                return
            }
        }
        // vc248（VC248-03）：可被发现档 = inquiry + page scan；仅可连接档 = 只开 page scan。
        val discoverable = powerMode == LiteConfig.USB_BT_MODE_DISCOVERABLE
        UsbBtStandby.restoreDiscoverable(discoverable)
        UsbBtStandby.start(
            this,
            config.usbBtDongleName,
            onDiagnostic = { msg -> LiteLog.log("usbbt", msg) },
            onLinkReady = { onUsbBtLinkReady() },
            keepAlive = discoverable || autoBringUp,
        )
        if (autoBringUp) {
            usbBtAutoBroughtUp = true
            LiteLog.log("usbdongle", "自动拉起：dongle 在场且已有配对记忆（autoInit 未开，按 BT-318-01 方案B 拉起一次）")
        }
    }

    /** vc207：配对窗入口 —— 无论「开机自动初始化」开关如何，确保常驻广播已启动。 */
    internal fun ensureUsbBtStandby() {
        // vc248（VC248-03）：关闭档不因打开配对页被拉起（优先级「关闭 > 页面临时升档」）。
        if (LiteConfig.usbBtPowerModeOf(this) == LiteConfig.USB_BT_MODE_OFF) {
            LiteLog.log("ui", "外置蓝牙档位=关闭，配对页不自动拉起")
            return
        }
        // vc247（V3）：用户重新打开配对页 = 明确要恢复外置蓝牙，解除「已停止」抑制。
        usbBtUserStopped = false
        userDisconnected = false
        // vc246（USB-246-02）：收口为一个「等待上限 5s」的后台确认。
        // 未在跑 → 立即拉起；在跑但 host 仍在初始化（后台正在重开）→ 等最多 5s，
        // 超时则跳过本次（不叠加第二次 open），并上屏说明。
        if (!UsbBtStandby.isRunning) {
            syncUsbBtStandby(force = true)
            return
        }
        if (UsbBtStandby.isHostReady) return
        Thread {
            val deadline = System.currentTimeMillis() + STANDBY_ENSURE_WAIT_MILLIS
            while (System.currentTimeMillis() < deadline && UsbBtStandby.isRunning && !UsbBtStandby.isHostReady) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            if (!UsbBtStandby.isHostReady) {
                LiteLog.log("usbdongle", "配对入口：常驻广播 5s 内未就绪，跳过本次拉起")
                runOnUiThread {
                    Toast.makeText(this, "后台正在重开，已跳过本次", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * vc246（USB-246-04）：USB 链路的六字段判别日志。
     *
     * `layer=usb|bt / trigger / holder / gen / present / next`
     * - layer：usb=端口/句柄层，bt=蓝牙链路层；**bulk read -1 不再单列一层**，只计入计数。
     * - holder：当前持有句柄的一方（standby / controller / gate / none）。
     * - present：sysfs 旁证看到的 0a12 设备 vid:pid（只读旁证，不走 binder，不惧 D 态；无则 absent）。
     */
    private fun usbSixFields(layer: String, trigger: String, holder: String, next: String) {
        LiteLog.log(
            "usb",
            "layer=$layer trigger=$trigger holder=$holder gen=${UsbBtStandby.generationSnapshot}" +
                " present=${UsbDongleGuard.sysfsId() ?: "absent"} next=$next",
        )
    }

    /**
     * vc212：SSP 弹码确认（DisplayYesNo + MITM）。dongle 收到 UserConfirmationRequest 时
     * 由 UsbBtStandby.pairConfirmHandler 回调（host 线程），转主线程弹 6 位数字；
     * 用户点「确认配对」回确认，「取消」/弹窗销毁回拒绝（host 侧 30s 超时也兜底拒绝）。
     */
    private fun installUsbBtPairConfirmHook() {
        UsbBtStandby.pairConfirmHandler = { addr, digits, answer ->
            handler.post { showUsbBtPairConfirmDialog(addr, digits, answer) }
        }
    }

    private fun showUsbBtPairConfirmDialog(addr: String, digits: String, answer: (Boolean) -> Unit) {
        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) {
            answer(false)
            return
        }
        var answered = false
        fun answerOnce(ok: Boolean) {
            if (!answered) {
                answered = true
                answer(ok)
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("请在 iPhone 与车机屏幕上核对同一组数字：", 14, Ui.TEXT))
            addView(label(digits, 34, Ui.TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 48)
            })
            addView(label("对端设备：$addr\n仅当 iPhone 上显示的数字与上方完全一致时才点「确认配对」。", 12, Ui.SUB))
        }
        AppDialog.Builder(this)
            .setTitle("蓝牙配对确认")
            .setView(body)
            .setNegativeButton("取消") { _, _ -> answerOnce(false) }
            .setPositiveButton("确认配对") { _, _ -> answerOnce(true) }
            .setOnDismissListener { answerOnce(false) } // 销毁/超时关闭 = 取消（answerOnce 幂等）
            .show()
        LiteLog.log("ui", "外置蓝牙：弹码确认 digits=$digits addr=$addr")
    }

    /** vc207：配对窗改名落盘 + 重启常驻广播（广播名下一轮初始化生效）。 */
    internal fun applyUsbBtDongleName(name: String) {
        LiteConfig.setUsbBtDongleName(this, name)
        UsbBtStandby.stop()
        syncUsbBtStandby(force = true)
        LiteLog.log("ui", "外置蓝牙：广播名已改为「$name」，常驻广播重启生效")
    }

    /**
     * 常驻广播已拿到 iPhone 链路、但当前空闲 ⇒ 自动发起一次无线连接，
     * 免去「先点连接、再等手机回连」的对时序要求（legacy 也是回连即建链）。
     */
    private fun onUsbBtLinkReady() {
        // vc241：「优先用外置蓝牙广播」设置项已删除——固定为外置广播，iPhone 回连后一律自动发起连接。
        LiteLog.log("ui", "外置蓝牙：已固定为外置广播（iPhone 回连后自动发起连接）")
        handler.post {
            // vc247（V3）：用户主动断开后，iPhone 回连不再自动拉起连接。
            if (userDisconnected) {
                LiteLog.log("ui", "外置蓝牙：用户已断开，忽略 iPhone 回连自动连接")
                return@post
            }
            // vc231：FAILED 是上一轮 teardown 遗留状态（48s watchdog 超时等），
            // 这时 iPhone 重新连过来（standby publish）也应该触发自动连接。
            val p = LiteState.phase
            if (p == LiteState.Phase.CONNECTING || p == LiteState.Phase.ACTIVE) return@post
            if (LiteSessionService.current != null) return@post
            val config = LiteConfig.load(this)
            val channel = when (config.activeChannel) {
                "car", "relay", "p2p" -> config.activeChannel
                else -> "car"
            }
            // 热点凭据没配就只记日志，不弹 toast 骚扰（外测此时应先配好车机热点）。
            val ready = when (channel) {
                "car" -> config.ssid.isNotBlank()
                "relay" -> config.relaySsid.isNotBlank()
                else -> true
            }
            if (!ready) {
                LiteLog.log("ui", "外置蓝牙：iPhone 已回连，但通道 $channel 凭据未配置，先不自动连接")
                return@post
            }
            LiteLog.log("ui", "外置蓝牙：iPhone 已回连，自动发起无线连接（通道 $channel）")
            connect(channel, auto = true)
        }
    }

    // ---------- 自动连接 ----------

    /**
     * 勾了"启动后自动连接"时回到前台自动以默认通道发起。
     * car/relay/usb 均可自动连接；视频参数被改过则跳过（避免开机弹确认框）。
     */
    private fun maybeAutoConnect() {
        if (autoConnectTried) return
        // 1.0.16（方案四②）：自启等待期间不发起，等勾选项就绪且稳定后由 autostartPoll 触发。
        if (autostartWaitActive) return
        if (LiteState.phase != LiteState.Phase.IDLE) return
        if (LiteSessionService.current != null) return
        val config = LiteConfig.load(this)
        if (!config.autoConnect) return
        // vc311（UI-310-02）：与主按钮走同一个门槛出口，避免「按钮灰着但自连照发」。
        // 位置必须在 autoConnect 判定之后，否则没开自连的用户也会被打一条「跳过」。
        if (externalBtGateBlocked()) {
            LiteLog.log("ui", "自动连接跳过：外置蓝牙未就绪（${extBtState()}）")
            autoConnectTried = true
            return
        }
        val channel = config.activeChannel
        // vc158：新增 p2p（Wi-Fi Direct）通道。
        if (channel != "car" && channel != "relay" && channel != "usb" && channel != "p2p") return
        val ssidBlank = when (channel) {
            "car" -> config.ssid.isBlank()
            "relay" -> config.relaySsid.isBlank()
            else -> false // USB 有线 / Wi-Fi Direct 均不依赖手填热点凭据
        }
        if (ssidBlank) return
        autoConnectTried = true
        val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.videoFps)
        if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
            LiteLog.log("ui", "自动连接跳过：视频参数已改，请手动连接以应用")
            return
        }
        LiteLog.log("ui", "自动连接：按默认通道（$channel）自动发起")
        connect(channel, auto = true)
    }

    // ---------- 1.0.16（方案四②）开机自启等待弹窗 ----------

    /**
     * 上电拉起主页后显示「等待勾选的蓝牙或WIFI热点启动」弹窗：
     * - 弹窗带「断开」键，点击即结束自动流程（已开的蓝牙/热点保留不关）；
     * - 勾选项一直不开启则一直等待，直到 60s 超时显示失败提示（不再自动连接）；
     * - 勾选项全部就绪后需连续稳定 10s（滤波，防 ROM 状态抖动）才发起自动连接。
     * 仅当勾了蓝牙/热点自启且开了自动连接时才弹；否则走原自动连接（onResume 即发）。
     */
    private fun beginAutostartWait() {
        val config = LiteConfig.load(this)
        val hasWaitItems = config.bootAutoBluetooth || config.bootAutoHotspot
        if (!config.autoConnect || !hasWaitItems || autostartWaitActive) {
            LiteLog.log(
                "ui",
                "自启等待跳过：autoConnect=${config.autoConnect} bt=${config.bootAutoBluetooth} " +
                    "ap=${config.bootAutoHotspot}",
            )
            return
        }
        autostartWaitActive = true
        autostartStableSecs = 0
        autostartDeadline = System.currentTimeMillis() + AUTOSTART_WAIT_TIMEOUT_MILLIS
        autostartDialog = AppDialog.Builder(this)
            .setTitle("开机自启动")
            .setMessage("等待勾选的蓝牙或WIFI热点启动…")
            .setCancelable(false)
            .setNegativeButton("断开") { d, _ ->
                LiteLog.log("ui", "自启等待：用户点击断开，结束自动流程")
                autostartWaitActive = false
                handler.removeCallbacks(autostartPoll)
                d.dismiss()
            }
            .create()
        autostartDialog?.show()
        LiteLog.log(
            "ui",
            "自启等待：开始等待勾选项就绪（超时 ${AUTOSTART_WAIT_TIMEOUT_MILLIS / 1000}s，稳定 ${AUTOSTART_STABLE_SECS}s）",
        )
        handler.postDelayed(autostartPoll, 1_000)
    }

    private fun finishAutostartWait() {
        autostartWaitActive = false
        handler.removeCallbacks(autostartPoll)
        autostartDialog?.dismiss()
        autostartDialog = null
    }

    // ---------- 状态渲染 ----------

    private fun renderState() {
        val phase = LiteState.phase
        val running = LiteState.running
        val statusText = LiteState.statusText
        val statusCode = LiteState.statusCode
        val coarse = LiteState.coarse
        // vc308 INV-1（第二道保险）：C-3 出现后锁定，晚到的 C-2 不得把界面打回「连接中」。
        // 解锁集合按 15-编号表 §2.1 取「会话终态」七码：S-01/S-02/S-12/S-14/S-15/S-16/S-27。
        // 其中 S-16（连接已断开，尝试重连中）本身就在 C-2——若只认 S-01/S-14 解锁，
        // 断流后界面会一直停在「已激活」，INV-2 的「已等待 N 秒」也永远不出现。
        // 细状态迁移照旧记日志，本锁只管 UI 显示，不掩盖排障信息。
        when (statusCode) {
            LiteState.UiStateCode.S01,
            LiteState.UiStateCode.S02,
            LiteState.UiStateCode.S12,
            LiteState.UiStateCode.S14,
            LiteState.UiStateCode.S15,
            LiteState.UiStateCode.S16,
            LiteState.UiStateCode.S27,
            -> coarseLocked = false
            else -> Unit
        }
        if (coarse == LiteState.CoarseState.C3) coarseLocked = true
        val coarseSuppressed = coarseLocked && coarse == LiteState.CoarseState.C2
        // vc308 INV-2（方案 A）：C-2 持续 >15s 追加「· 已等待 N 秒」。
        // N 取 elapsedRealtime 差值（不用墙钟）；秒数必须在下方短路条件里参与比较，
        // 否则 statusText 不变会让界面不刷新、N 永远显示 0。
        val nowElapsed = SystemClock.elapsedRealtime()
        if (coarse == LiteState.CoarseState.C2) {
            if (c2SinceElapsed == 0L) c2SinceElapsed = nowElapsed
        } else {
            c2SinceElapsed = 0L
        }
        val waitedSeconds =
            if (c2SinceElapsed == 0L) 0L else (nowElapsed - c2SinceElapsed) / 1_000L
        val waitingSuffix = if (coarse == LiteState.CoarseState.C2 && waitedSeconds >= 15L) {
            " · 已等待 $waitedSeconds 秒"
        } else {
            ""
        }
        val displayText = statusText + waitingSuffix
        // vc145：状态未变即短路（哨兵 null 保证首 tick 必跑）；activeChannel/面板
        // 显隐有独立变化检测，保持每 tick 执行（设置页改动后回主页实时刷新依赖它）。
        if (phase != shownPhase || running != shownRunning || statusText != shownStatus ||
            displayText != shownDisplayText
        ) {
            shownPhase = phase
            shownRunning = running
            shownStatus = statusText
            shownDisplayText = displayText
            // 大状态卡状态点：总览实时状态。
            (statusDot.background as? GradientDrawable)?.setColor(
                when (phase) {
                    LiteState.Phase.ACTIVE -> Ui.GREEN
                    LiteState.Phase.CONNECTING -> DOT_CONNECTING
                    LiteState.Phase.FAILED -> DOT_FAILED
                    LiteState.Phase.IDLE -> Color.GRAY
                },
            )
            // vc308 INV-1：锁定期间收到 C-2，不改写状态行（保留已连接的 C-3 文字），日志照记。
            if (!coarseSuppressed) statusLine.text = displayText
            // M6k24：主按钮固定「立即连接」（连接中/激活置灰）；断开连接独立成键，仅连接中出现。
            primaryActionButton.isEnabled = !running
            primaryActionButton.alpha = if (running) 0.5f else 1f
            disconnectButton.visibility = if (running) View.VISIBLE else View.GONE
            // FAILED 态弹居中小卡片（标题+3行省略+重试）；其余状态收起。
            // vc300：等待热点态（CONNECTING + 「热点未开启」）同样弹卡，但按钮换成
            // 「去打开热点」——只跳系统热点页，不停会话，开热点后编排层自动续连。
            // vc308：判据从文案 contains 改为编号（S-26），改文案不再静默废掉这张卡（治 G-6）。
            val failed = phase == LiteState.Phase.FAILED
            val hotspotWaiting = phase == LiteState.Phase.CONNECTING &&
                statusCode == LiteState.UiStateCode.S26
            if (failed || hotspotWaiting) {
                errorTitleView.text = if (hotspotWaiting) "热点未开启" else "连接失败"
                errorTextView.text = statusText
                hotspotJumpButton.visibility = if (hotspotWaiting) View.VISIBLE else View.GONE
                if (hotspotWaiting) {
                    errorActionButton.visibility = View.GONE
                } else {
                    errorActionButton.visibility = View.VISIBLE
                    errorActionButton.text = when (LiteState.failureClass) {
                        LiteLog.FailureClass.CredTimeout -> "去打开热点"
                        LiteLog.FailureClass.BtNoResponse,
                        LiteLog.FailureClass.Iap2Timeout,
                        -> if (LiteConfig.usbBtDongleOf(this)) "去配对" else "重试"
                        else -> "重试"
                    }
                }
                errorCard.visibility = View.VISIBLE
            } else if (errorCard.visibility == View.VISIBLE) {
                errorCard.visibility = View.GONE
            }
            // 连接成功即自动收起面板 —— 此时用户要看的是 CarPlay 画面；断开后再自动展开。
            val active = phase == LiteState.Phase.ACTIVE
            if (active != lastActive) {
                lastActive = active
                setPanelVisible(!active)
            }
        }
        // 默认通道提示与设置页三选项同步（设置页改动后回到主页实时刷新）。
        // vc178：这里每 500ms 跑一次，改读单键（原 LiteConfig.load 全量读 30+ 键并构造
        // 30 字段 data class，只为取这一个字段）。
        val activeChannel = LiteConfig.activeChannelOf(this)
        if (activeChannel != shownActiveChannel) {
            shownActiveChannel = activeChannel
            hintLabel.text = defaultChannelHint(activeChannel)
        }
        // vc206：外置蓝牙门槛 —— 只保留 warnline 黄字（vc313 起不再灰化主按钮，点击已能说清原因）。
        // USB 直连不经蓝牙，不拦。
        // vc310：判据升级为三态（dongle 未插入 / 未就绪失败 / 未配对），三态只决定黄字文案。
        // 哨兵改用状态本身，避免「三态互切但文案相同」时不刷新。
        val extState = extBtState()
        if (extState != shownExtBtState) {
            shownExtBtState = extState
            pairGateLabel.text = when (extState) {
                ExtBtState.DONGLE_ABSENT -> "未检测到外置蓝牙"
                ExtBtState.NOT_READY -> "外置蓝牙连接失败"
                ExtBtState.UNPAIRED -> "未配对，请进入配对窗口进行配对"
                ExtBtState.USER_STOPPED -> "外置蓝牙已停止"
                else -> ""
            }
            // 可见性只看三态：都非 READY 就显示原因，即使正在连接（running）——
            // 否则连接失败后停在「未连接」反而看不到原因。
            pairGateLabel.visibility =
                if (extState == ExtBtState.DONGLE_ABSENT || extState == ExtBtState.NOT_READY ||
                    extState == ExtBtState.UNPAIRED || extState == ExtBtState.USER_STOPPED
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            btModeLabel.text = btModeText(LiteConfig.load(this))
        }
    }

    /** 当前会话通道（仅服务存活时有效）；errorCard 重试与每卡绿点用它定位通道。 */
    private fun currentSessionChannel(): String? =
        if (LiteState.running) {
            getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).getString(SESSION_CHANNEL_KEY, null)
        } else {
            null
        }

    internal fun readVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        // UI 层自有 prefs：只记"本轮会话通道"，不触碰 LiteConfig 逻辑字段。
        private const val SESSION_PREFS = "diplay_lite_ui"
        private const val SESSION_CHANNEL_KEY = "session_channel"
        // U4 USB 有线：VpnService 授权回调码 + Apple USB 厂商 ID（与 IphoneUsbMatcher 一致）。
        private const val REQUEST_VPN_PERMISSION = 7001
        // M6k25：CarIconPage 图库选择回调码（原 CarIconActivity.REQ_PICK_IMAGE=4301）。
        private const val REQUEST_GALLERY_PICK = 1001
        private const val APPLE_USB_VENDOR_ID = 0x05ac
        // vc154：有线切换拆栈完成回调的超时兜底（正常远小于此值，8s 覆盖慢速拆栈+竞态）。
        private const val WIRED_SWITCH_FALLBACK_MS = 8_000L
        private val DOT_CONNECTING = Color.rgb(240, 200, 60)
        private val DOT_FAILED = Color.rgb(240, 80, 80)

        // 1.0.16（方案四②）开机自启等待弹窗参数。
        /** BootReceiver 拉起主页时标记「开机自启等待」流程。 */
        const val EXTRA_AUTOSTART = "autostart_wait"
        /** 勾选项就绪后需连续保持的稳定时长（滤波）。 */
        const val AUTOSTART_STABLE_SECS = 10
        /** 等待勾选项就绪的总超时。 */
        const val AUTOSTART_WAIT_TIMEOUT_MILLIS = 60_000L

        // vc245：外置蓝牙掉线重连——退避 1s/2s/4s/8s（封顶 8s），连续 5 次失败即停并上屏。
        // vc246（USB-246-01）：数值统一由 UsbBtRetryPolicy 提供（与常驻广播同一条序列）。
        const val DONGLE_RETRY_MAX = UsbBtRetryPolicy.MAX_ATTEMPTS

        /** vc246（USB-246-02）：配对入口等待常驻广播就绪的上限。 */
        const val STANDBY_ENSURE_WAIT_MILLIS = 5_000L
    }
}
