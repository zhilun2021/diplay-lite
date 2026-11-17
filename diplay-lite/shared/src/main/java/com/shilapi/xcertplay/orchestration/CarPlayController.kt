package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.CarPlayClassifiedException
import com.shilapi.xcertplay.CarPlayFailureClass
import com.shilapi.xcertplay.diag.CarPlayStageLog
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AIRPLAY_HANDSHAKE_ZERO_BYTE_TIMEOUT_MILLIS
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.AirPlayTcpServer
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.HotspotInterfaceBssid
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayBonjourProtocol
import com.shilapi.xcertplay.network.WirelessMdnsAnnouncer
import com.shilapi.xcertplay.network.ConfirmedChannelMemory
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.NetworkFamilyClass
import com.shilapi.xcertplay.network.NetworkFamilyGuard
import com.shilapi.xcertplay.network.RelayClientManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WiredBonjourAdvertiser
import com.shilapi.xcertplay.network.WiredMdnsResponder
import com.shilapi.xcertplay.network.WiredNcmGuard
import com.shilapi.xcertplay.network.WiredVpnService
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.wifiInfoFrequencyMhz
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.LegacyUsbHostNative
import com.shilapi.xcertplay.transport.NcmClaimTrace
import com.shilapi.xcertplay.transport.RootNcmReclaimer
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlStage
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.KernelNcmException
import com.shilapi.xcertplay.transport.KernelNcmInterface
import com.shilapi.xcertplay.transport.KernelNcmProbe
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.carPlayFailureClass
import com.shilapi.xcertplay.usbbt.UsbBtBootstrapStream
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.vehicle.BluetoothAudioHandoff
import com.shilapi.xcertplay.vehicle.BondedDevicesResolver
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 无线 CarPlay 状态序列（Lite：仅保留无线路径）。
 *
 * 改编思路：原有线 USB 状态（DiscoveringIphone/WaitingForReenumeration/Pairing 等）
 * 与 P2P 相关状态全部裁剪，只留下「MFi → 热点 → 蓝牙 → AirPlay → Handoff」主线。
 */
sealed class CarPlayStatus {
    object DiscoveringMfi : CarPlayStatus() { override fun toString() = "DiscoveringMfi" }
    object MfiReady : CarPlayStatus() { override fun toString() = "MfiReady" }
    object StartingHotspot : CarPlayStatus() { override fun toString() = "StartingHotspot" }
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    object WaitingForPairedIphone : CarPlayStatus() { override fun toString() = "WaitingForPairedIphone" }
    object ConnectingBluetooth : CarPlayStatus() { override fun toString() = "ConnectingBluetooth" }
    object RunningWireless : CarPlayStatus() { override fun toString() = "RunningWireless" }
    object WirelessActive : CarPlayStatus() { override fun toString() = "WirelessActive" }
    object AttachingNetwork : CarPlayStatus() { override fun toString() = "AttachingNetwork" }
    object ControlEnded : CarPlayStatus() { override fun toString() = "ControlEnded" }
    // —— USB 有线状态序列（U3，语义对齐上游有线编排）——
    object DiscoveringIphone : CarPlayStatus() { override fun toString() = "DiscoveringIphone" }
    object WaitingForIphone : CarPlayStatus() { override fun toString() = "WaitingForIphone" }
    object RequestingIphonePermission : CarPlayStatus() { override fun toString() = "RequestingIphonePermission" }
    object WaitingForReenumeration : CarPlayStatus() { override fun toString() = "WaitingForReenumeration" }
    object SelectingConfiguration : CarPlayStatus() { override fun toString() = "SelectingConfiguration" }
    object OpeningDataPaths : CarPlayStatus() { override fun toString() = "OpeningDataPaths" }
    object Pairing : CarPlayStatus() { override fun toString() = "Pairing" }
    object ConnectingControl : CarPlayStatus() { override fun toString() = "ConnectingControl" }
    object RunningControl : CarPlayStatus() { override fun toString() = "RunningControl" }
    data class Failed(
        val message: String,
        val failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
    ) : CarPlayStatus() {
        // vc250：保持既有日志文案（文档按 "Failed(message=...)" 做锚点），不把新字段带进可读输出。
        override fun toString() = "Failed(message=$message)"
    }
    // M6k11：iPhone 僵尸会话提示——重试大概率无效，需要用户干预（关开 iPhone 蓝牙）。
    data class RecoveryHint(
        val message: String,
        // vc308：类型化原因（默认 Other，其它 11 处调用点无需改动）。
        // app 层取不到 companion 私有的 CAR_HOTSPOT_WAIT_HINT，G-6 必须靠本字段判 S-26。
        val reason: RecoveryReason = RecoveryReason.Other,
    ) : CarPlayStatus()

    /** vc308：`RecoveryHint` 的类型化原因（只按它分流，禁止按 message 文本分流）。 */
    enum class RecoveryReason { Other, HotspotOff }
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * vc299（方案 A）：车机热点未开启。
 *
 * 这是「环境未就绪」而不是链路失败——编排层据此转「等待热点 + 慢轮探测」，
 * 不得落 Failed 终态（热点后开时用户不需要再点一次连接）。
 */
private class CarHotspotOffException(message: String) : IOException(message)

/**
 * vc301（P0 零行为收口）：无线失败处理的唯一决策类型。
 *
 * 只做统一转发：调用时机、参数、执行顺序与既有实现完全一致；新增的观测日志仅在
 * [CarPlayController.decide] 内输出，不改变任何控制流。
 */
private enum class WirelessDecision {
    CLOSE_STACK,
    FAIL,
}

/**
 * 无线 CarPlay 编排器（Lite：车机自带热点 + 蓝牙引导 + Wi-Fi AirPlay）。
 *
 * 改编思路（相对 minapi21 分支）：
 * - 删除全部有线 USB/NCM/USBMUX/Lockdown 路径与 CH341/I2C/REMOTE MFi 后端，
 *   MFi 固定为 LOCAL 离线证书，热点固定为 MANUAL（车机自带热点）。
 * - AirPlay TCP 监听从 CarPlayVpnService（Android Service + Binder + VPN）
 *   替换为进程内 [AirPlayTcpServer]，去掉 Service 绑定与 VPN 权限流程，
 *   降低 4.1/4.4 老系统上的系统资源占用与兼容风险。
 * - 保留原分支的无线握手、type-130 隧道 handoff、看门狗与降级逻辑，行为不变。
 *
 * 阻塞工作运行在单线程 executor；状态回调投递到主线程。
 */
class CarPlayController(
    private val context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val locationProvider: Iap2LocationProvider? = null,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    // vc285：自动匹配选定设备后回写「上次成功 MAC」，供下一次多台歧义时兜底。
    private val rememberWirelessBluetoothAddress: (String) -> Unit = {},
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        BydNavigationOutputs.start(context.applicationContext)
        // vc100：root 抢回脚本模式需要 filesDir 落点（T3 SuperSU su -c 长命令静默降权缺陷）。
        RootNcmReclaimer.initialize(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext

    // API16 适配：BluetoothManager 类是 API 18+（getSystemService(String) 于 API 23 才支持
    // 该字符串键），4.x 上无法实例化。>=18 先尝试 Manager 路径，低版本直接回退
    // getDefaultAdapter()（API 1，虽已废弃但 4.1/4.4 上是唯一稳定入口）。
    private val bluetoothAdapter: BluetoothAdapter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } else {
            null
        } ?: BluetoothAdapter.getDefaultAdapter()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    /**
     * vc178：USB 主机（建链/重枚举/usbmux 打开）专用串行线程。
     * 原先这些任务跑在共享的 [executor] 上，而 `IphoneUsbHost` 内部最多会睡
     * 0.8s（配置落定）+0.3s（重试）+最长 5s（vc111 枚举收敛轮询）≈ 6s；同一队列上排着的
     * teardown / 拆栈任务只能干等，实测表现是「点了断开或拔线后界面卡住好几秒才响应」。
     * 建链是长阻塞任务，本就不该和拆栈抢同一条线程。close 时与 [executor] 一并 shutdownNow。
     */
    private val usbHostExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // M6k 触摸 latest-wins：C8815 上每个 ACTION_MOVE 都会走一次同步阻塞的 TCP 写
    // （AirPlaySession.sendCommand 内 write+flush），手指快速滑动时 MOVE 在单线程队列里
    // 排长队 → 触摸"不跟手"。改为：DOWN/UP（sticky）必发不合并；MOVE 只保留最新，
    // 中间态直接丢弃。发送泵（pump）在 touchExecutor 上串行排空 pendingTouch。
    private val touchLock = Any()
    private val pendingTouch = ArrayDeque<PendingTouch>()
    private var touchPumpRunning = false
    private var touchSentCount = 0
    private var touchCoalescedCount = 0
    private var touchDroppedCount = 0
    private var lastTouchStatsNs = System.nanoTime()
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    // M6k24（C 方案）：未建流 EOF 整代重启时暂存的热点附着（manager + 上轮信息），下轮复用。
    @Volatile private var retainedHotspot: Pair<WirelessHotspotManager, WirelessHotspotInfo>? = null
    /** vc310：热点接口存活采样的世代绑定（同代只起一次线程；换代自然失效）。 */
    @Volatile private var livenessSamplerGeneration = 0
    @Volatile private var lastHotspotInfo: WirelessHotspotInfo? = null
    @Volatile private var bonjour: CarPlayBonjour? = null

    /** vc272：热点模式 mDNS 单播通告器（软 AP 多播下行失效兜底），teardown 停止。 */
    @Volatile private var wirelessMdnsAnnouncer: WirelessMdnsAnnouncer? = null

    /**
     * vc272：已关联 STA IP 注入回调（logHotspotStaArp 观测到新 iPhone IP 时调）。
     * 由 announcer 启动时装载，teardown 置空。
     */
    @Volatile private var hotspotStaArpListener: ((InetAddress) -> Unit)? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BlockingDuplexByteStream? = null

    /**
     * vc246（两处误报修正）：蓝牙链路真正交出时的代次。First TCP 看门狗与
     * 「等常驻广播交出链路」阶段的提示都要先确认该值等于当前代次，
     * 避免「链路从未建立」被误报成「iPhone 没连 Wi-Fi」。
     */
    @Volatile private var bluetoothLinkReadyGeneration = 0
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var airPlayServer: AirPlayTcpServer? = null
    // vc260/vc261：诊断用连接探针（主监听之外的另一地址族 + 7000 端口），只记录 iPhone 的
    // TCP 目标，不参与会话。
    @Volatile private var probeSockets: List<ServerSocket> = emptyList()

    /** vc267：10 分钟内有 TCP → 视为 warm，跳过 0x4301 入网闸门。 */
    private fun recentlyConnectedOverAirPlay(): Boolean =
        lastAirPlayTcpAtElapsedMillisShared > 0L &&
            SystemClock.elapsedRealtime() - lastAirPlayTcpAtElapsedMillisShared < 10 * 60_000L
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)

    /**
     * vc201：媒体断供回收的「进行中」门禁。sink 层视频静默回调与 AIRPLAY_STALLED 路径
     * 可能同帧到达 notifyWirelessMediaStall——判活与拆栈之间无原子化时两线程都过检，
     * 导致双拆栈 + 双 scheduleWirelessRetry（wirelessRetryCount 双计、退避窗口错乱）。
     */
    private val mediaStallRecoveryInFlight = AtomicBoolean(false)

    /**
     * vc151：本代无线会话曾建立过（激活或降级态）。与 [wirelessActiveReported] 分开：
     * 后者在会话 ended 时复位，而 iPhone 偶发强断（无 goodbye TCP RST，K60 热点实测）
     * 后短时间内可能自行重投——此时 handoff watchdog（60s 定时）恰好到期，因 reported
     * 已复位而误判「handoff 超时」把还活着的蓝牙 iAP2 一并拆栈（K60 实测 20:35:53
     * 二次伤害），重投窗口被毁。本标志=true 表示 handoff 实际完成过，watchdog 放行，
     * 断流回收交给媒体断供看门狗与既有 auto-retry 链。每代启动时复位（见 737 行）。
     */
    private val wirelessEverActive = AtomicBoolean(false)

    /**
     * M6g：已判定为「降级态」——iPhone 已建立 Wi-Fi A/V，但不请求 type-130 iAP2 隧道，
     * 于是蓝牙 RFCOMM 继续承载 iAP2 控制面（见 armWirelessHandoffWatchdog）。
     *
     * 与 [wirelessActiveReported] 刻意分开：后者是「真交接完成（隧道就绪且已放开蓝牙）」，
     * 还被 CHANNEL_CLOSED / catch 分支用来决定「保活」还是「拆栈失败」；若在降级态复用它，
     * 蓝牙 RFCOMM 真断时就再也不会拆栈，会留下僵尸会话。
     */
    private val wirelessDegradedActive = AtomicBoolean(false)

    /**
     * vc295：AP 模式下「对端离开」巡检已覆盖的代次。同一代次只起一条巡检线程
     * （active→degraded 升级会二次调 [logWirelessSessionActive]）。
     */
    private val staWatchGeneration = AtomicInteger(-1)

    /** M6h：蓝牙 iAP2 掉链后的自动重连计数；只在「会话真的建立」或「用户手动重连」时清零。 */
    private val wirelessRetryCount = AtomicInteger(0)
    // vc286：额度用尽后的慢轮档位（0=未进入慢轮）。1→15s、2→30s、3→45s、4+→60s，
    // 会话成立即归零。旧行为是固定 60s：K60 12:16 实测 iPhone 回网后仍要等满一个慢轮
    // （掉线到出画 202s），前两档提前试探可把这段等待压到 15~30s。
    private val wirelessSlowRetryRound = AtomicInteger(0)
    // 1.0.10：wired peer 断链自动重试计数（FE-6 实测 iPhone 主动 connection closed 后 App 躺平）。
    private val wiredRetryCount = AtomicInteger(0)
    // 1.0.15：本代 0x5703 实际下发的信道；SESSION_ACTIVE 后写入成功配置记忆。
    @Volatile private var lastAdvertisedHotspotChannel = 0
    // vc274：本代信道是否为实测/配置值。默认值（assumed-2.4g / assumed-5g）不得写入
    // ConfirmedChannelMemory——否则「下发猜测值 → 下次读回当已知」形成自证循环。
    @Volatile private var lastAdvertisedChannelObserved = false
    private val wirelessGeneration = AtomicInteger(0)
    // vc250-06：控制面与媒体面拆成两条独立事实。活性判定（看门狗/降级/断流回收）
    // 只认无线媒体面；控制面（蓝牙 RFCOMM / type-130 隧道）死亡不再等同会话失败。
    private val wirelessControlProof = ControlProof<AirPlaySession>()
    private val wirelessMediaProof = MediaProof<AirPlaySession>()

    // vc152（微信语音挂断后会话不自愈）：无线控制环 client 引用——run 存活期间可被
    // 会话结束后自动重投逻辑调用 sendCarPlayInvitation()（外部线程直发 0x4301）。
    @Volatile private var wirelessControlClient: Iap2WirelessControlClient? = null
    private val peerEndInvitationArmed = AtomicBoolean(false)
    private val peerEndInvitationsSent = AtomicInteger(0)

    // M6k10 重连可观测：iAP2 隧道就绪即 authenticated；采样器每秒输出 tcpAccepted 等判据，
    // 定位「连上但 iPhone 不进来」卡在 Wi-Fi 段（tcpAccepted=0）还是 RTSP 段（tcpAccepted≥1）。
    private val wirelessAuthenticated = AtomicBoolean(false)
    // vc253：热点 STA 关联观测网段前缀（由热点自身 IPv4 前 3 段推导；只读诊断）。
    @Volatile private var hotspotStaSubnetPrefix: String = DEFAULT_HOTSPOT_STA_SUBNET_PREFIX
    @Volatile private var wirelessBringUpStartedAtMs = 0L
    // vc202：degraded 完成时刻锚点——隧道随后若补齐，SESSION_ACTIVE 附 tunnelReadyAfterActiveMs。
    @Volatile private var wirelessDegradedAtMs = 0L
    // vc202：handoff 请求时刻锚点——SESSION_ACTIVE 附 handoffMs（请求→完成的纯 handoff 段耗时）。
    @Volatile private var wirelessHandoffRequestedAtMs = 0L
    @Volatile private var wirelessRestartEpisodeStartedAtMs = 0L
    /**
     * vc250-07：本连接纪元内是否出过画（跨重试粘性）。与 06 的媒体面事实同源（由
     * onVideoFrameRendered 在 mediaProof 接受该帧时置位），决定快链耗尽后的慢轮节奏：
     * 出过画=LONG_OUTAGE 走 60s 快慢轮；从未出画=SLOW_FIRST_DISPLAY 保持 5min。
     */
    @Volatile private var wirelessRenderedThisEpisode = false
    /**
     * vc306：本代 `0x5703` 是否真的下发过（-1 = 尚未）。凭证已下发是「iPhone 在网且在等我们」
     * 的正面证据，供 first-TCP 看门狗决定是否延长宽限。按代次比对，换代自然失效，无需复位逻辑
     * （`start()` 里显式清 -1 见 C 项）。
     */
    @Volatile private var wirelessCredentialIssuedGeneration = -1
    // M6k11：RFCOMM 连接成功时刻锚点——CSM 打开后 <500ms 被 iPhone 关流（瞬时 EOF）
    // 即归为 IPHONE_ZOMBIE（TS7 实测 3 轮全是 +175ms 秒杀）。
    @Volatile private var wirelessLinkOpenedAtMs = 0L
    // M6k11：本轮重试是否为僵尸族——决定长退避、UI 提示与蓝牙适配器 reset。
    @Volatile private var wirelessZombieMode = false
    /**
     * 1.1.4（B 方案，TS7 mazda2 170921 实测）：First TCP 超时后的一轮「残留会话打断重启」。
     * 实测：断开后 19s 内重连，RFCOMM 活着 iPhone 就不清残留 CarPlay 会话——保持热点的
     * RESTART 两代双双 30s 不入网；用户手动断开（蓝牙+热点全拆）再连 17s 即成功。
     * 置位后 retry 强制 ≥8s 间隔且热点不保留，模拟「断开→重连」动作。
     */
    @Volatile private var wirelessStaleSessionBounce = false
    // M6k11 hotfix：分类结果缓存（teardown 前算好，scheduleWirelessRetry 消费后清空）。
    @Volatile private var lastWirelessFailureClass: String? = null
    // 1.1.3（8815 230420 定案）：连续 SDP 发现失败计数——iPhone iAP2 服务未广播=残留会话，
    // 重试治不了，连续达阈值即给用户「关开 iPhone 蓝牙」指引；RFCOMM 建链成功即清零。
    @Volatile private var sdpFailureConsecutiveCount = 0

    // 1.0.29（3e 222838 建立期楔死）：wired 0x4301 发出后 8s 内 iPhone 未发起任何流帧
    // → 关 CSM 触发控制环退出，走楔死类长退避全量重建（此前会话死在 pair-setup 阶段，
    // 流中看门狗因 armed 判据要 NCM 帧率而接不住）。
    @Volatile private var wiredEstablishWatchdogFired = false
    @Volatile private var wiredControlReturned = true
    // 1.0.29（EU5 C40DB 094001 修复）：看门狗解除信号——RTSP 会话激活或首帧渲染即视为
    // CarPlay 已真正开始，撤销建立期看门狗。此前唯一解除条件是控制环退出（wiredControlReturned
    // 只在 runWiredStack finally 置位），控制环 NO_TIMEOUT 常驻 → 健康会话也会在 0x4301+8s
    // 被看门狗误杀（EU5 实测每轮均 +8.0s 精确触发，RTSP/首帧已正常），拆完 iPhone 停留
    // config 6 不再重枚举 → 死循环。
    @Volatile private var wiredStreamSeen = false
    // FE-6 楔死自愈：本插线周期内 wired 是否成功跑过流。自愈重试开路时若同一设备实例
    // 曾跑过流，iPhone 必然仍在 CarPlay 配置——跳过 setConfiguration（见 IphoneUsbHost）。
    @Volatile private var wiredEverStreamed = false
    // vc93（TS7 083438 实锤）：楔死类重建强制全量复位。self-heal 短路（同设备实例曾跑流
    // → 跳过 setConfiguration/重枚举）在 RX 物理停摆后不成立——iPhone 会话状态未复位，
    // 对新 0x4301 视而不见（46s 死窗）。楔死/watchdog/usbmux-reset 重试前置位，
    // onIphonePermission 消费后清除；常规 peer-closed 重试不置位，保留快速重连。
    @Volatile private var wiredFullResetPending = false
    // vc153（K60 实锤 0x4301 后 iPhone 完全静默）：有线启动看门狗重试记账——判死自动重试
    // 额度 2 次封顶（耗尽落 ControlEnded，不再无限循环重建占住「有线链路建立中」）；代际
    // 令牌使 teardown/重 arm 后的待触发计时器失活。会话真建立即归零（对齐 wiredRetryCount 语义）。
    private val wiredStartWatchdogRetries = AtomicInteger(0)
    // vc178：代际令牌由 armWiredEstablishWatchdog()（主线程）与 closeWiredStack()（teardown
    // 线程）两处并发自增。原 `@Volatile var ++` 非原子，同刻调用会丢更新 → 旧计时器未被
    // 失活、或新计时器一出生就被判失效（表现为「判死后自动重试不触发」）。改 AtomicInteger。
    private val wiredStartWatchdogGeneration = AtomicInteger(0)
    // vc97③：wedge 判别器数据源——控制环最近收帧时刻（usbmux 控制口心跳在此浮现）。
    // Ipv6NcmBridge fatal 判定时探查：控制口活=端点级楔死快拆 / 控制口同停=iPhone 停发宽限。
    @Volatile private var wiredControlLastRxAtNanos = 0L
    @Volatile private var wiredOpenDeviceId = Int.MIN_VALUE
    @Volatile private var wiredStreamedDeviceId = Int.MIN_VALUE

    // M6k23：未建流阶段（无 SETUP/未激活）控制流 peer EOF 连续计数。8815 实测：
    // pair-verify 通过、加密开启后 iPhone 立即 EOF，每 ~5s 重连 TCP 循环、gen 恒定，
    // 重连状态机完全不触发。连续 ≥2 次 → 归入可重试族走整代重启（既有 RESTART 链）。
    // 会话已激活（换流 peer-teardown）或已渲染帧不计数，避免误杀正常流程。
    @Volatile private var preStreamEofCount = 0
    @Volatile private var wirelessSamplerThread: Thread? = null
    private var lastReportedStatus: CarPlayStatus? = null

    private val teardownComplete = CountDownLatch(1)

    // —— USB 有线（U3）：wired 资源独立字段，不与无线族共享；UsbManager 不可用时不致命，
    // 仅在启动有线模式时才报错（构造期崩溃会连带破坏无线路径）。
    private val usbManager: UsbManager? =
        runCatching { appContext.getSystemService(Context.USB_SERVICE) as? UsbManager }.getOrNull()
    private val iphoneHost: IphoneUsbHost? = usbManager?.let {
        IphoneUsbHost(appContext, it, IphoneUsbMatcher.appleVendor())
    }

    init {
        // 1.0.2：把 USB 主机侧关键节拍（setConfiguration/claim/重试）接进文件日志，
        // 避免只写 logcat 导致远端排障信息缺失。
        iphoneHost?.debugSink = { message -> debugLog(message) }
    }

    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    // 1.1.4（对齐上游 2.11(143) 有线首连策略）：本 controller 周期内是否做过 0x52 重枚举
    // + 发起重枚举时的源设备名。重枚举完成判定只认 deviceName 不同的**新实例**——
    // 0x52 后 iPhone 需 1~3s 才完成重枚举，旧实例仍留在 UsbManager.deviceList 里，
    // 按「有 Apple 设备」判定会对失效实例请求权限/claim，导致首连失败链。
    @Volatile private var reenumerationPerformed = false
    @Volatile private var reenumerationSourceDeviceName: String? = null
    // 1.1.4（TS7 mazda2 NTB16 绕过）：上次打开的 NCM control id + 上次失败要避开的组。
    // NCM 桥报错（含 NTB16 内核抢占污染）时把失败组记入 avoid，重建时轮换到下一组；
    // 流真正跑起来后清空。多 NCM 组的车机（iPhone 配置暴露两组 CDC-NCM）借此绕开
    // 内核 cdc_ncm 抢绑的那一组，无 root 解决双读污染。
    @Volatile private var ncmLastControlId: Int? = null
    @Volatile private var ncmAvoidControlId: Int? = null
    // 1.0.1：重枚举阶段看门狗 + 轮询兜底的车代（attach/权限路径会递增 availabilityPollGeneration），
    // 双定时器都以 phase==REENUMERATION && 代未变 && 未关闭 为存活条件。
    private val reenumerationPollGeneration = AtomicInteger(0)
    @Volatile private var permissionCloseable: Closeable? = null
    @Volatile private var attachCloseable: Closeable? = null
    @Volatile private var wiredMux: Iap2UsbMuxHost? = null
    // vc142：有线会话底层 USB session——close() 时立即关闭以打断阻塞中的 begin 并收回 fd。
    @Volatile private var wiredUsbSession: Iap2UsbSession? = null
    @Volatile private var wiredCsm: Iap2Session? = null
    @Volatile private var wiredBridge: Ipv6NcmBridge? = null
    // 2026-11-07：有线实验（内核 NCM）探测到的网卡快照；实验关闭时恒 null。
    @Volatile private var wiredKernelNcm: KernelNcmInterface? = null
    /** vc180：最近一次有线 attach 失败的可诊断原因（供 [wiredAttachFailureMessage] 生成用户文案）。 */
    @Volatile private var lastWiredAttachFailure: String? = null
    // vc68：有线 mDNS 应答器（iOS16.x 在 NCM 链路上 mDNS 发现 _airplay，无应答拒绝起会话）
    @Volatile private var wiredBonjour: WiredBonjourAdvertiser? = null
    // vc110（T3 KK 域）：JmDNS 查询接收失效后的自研替代，二者互斥（优先 responder）
    @Volatile private var wiredMdns: WiredMdnsResponder? = null
    @Volatile private var wiredNcmGuard: WiredNcmGuard? = null
    // 1.1.3（bt_audio 协同）：蓝牙音频模式下的 A2DP 监听（跟随上游 BluetoothAudioHandoff）。
    // 仅在 airPlayConfig.disableAudioOutput（av_path=bt_audio）时挂载；suppressed 恒 false，
    // 不做 A2DP 断开/重连，只监听连接与播放态（isA2dpPlaying 反射 + PLAYING 广播）供日志观测。
    @Volatile private var btAudioHandoff: BluetoothAudioHandoff? = null
    // vc96（FE-6 断链取证）：WaitingForIphone 黑等的快照计数/计时/提示状态。
    // 快照首拍+每 5 拍打一次 USB 总线拓扑；超 10s 提示用户拔插线；恢复发现后全复位。
    private var waitingSnapshotCount = 0
    private var waitingSinceNanos = 0L
    private var waitingPromptShown = false

    private fun ensureBtAudioHandoff(session: AirPlaySession) {
        if (closed || !airPlayConfig.disableAudioOutput) return
        val address = session.deviceBtMac
        if (address.isBlank()) return
        if (btAudioHandoff != null) return
        synchronized(this) {
            if (btAudioHandoff != null || closed) return
            val handoff = BluetoothAudioHandoff(
                appContext, address,
                report = { debugLog(it) },
                onPlaying = { playing -> debugLog("Bluetooth A2DP playing=$playing") },
            )
            btAudioHandoff = handoff
            debugLog("bt_audio handoff attached peer=$address")
            handoff.start()
        }
    }

    private fun closeBtAudioHandoff() {
        val handoff = btAudioHandoff ?: return
        btAudioHandoff = null
        runCatching { handoff.close() }
        debugLog("bt_audio handoff closed")
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (activeSession !== session) BydNavigationOutputs.start(appContext)
            activeSession = session
            ensureBtAudioHandoff(session)
            // 1.0.10：会话真建立 = wired 自动重试额度重置（对齐无线链「成功即归零」语义）。
            wiredRetryCount.set(0)
            // vc153：启动看门狗重试额度同步归零（会话已建立，静默判死族清账）。
            wiredStartWatchdogRetries.set(0)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                BydNavigationOutputs.endNow()
                closeBtAudioHandoff()
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            // 1.1.3（bt_audio 协同）：deviceBtMac 在此回调前才落位，onSessionActive 可能早于它。
            ensureBtAudioHandoff(session)
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                wirelessHandoffRequestedAtMs = SystemClock.elapsedRealtime()
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    /**
     * vc201：摘除 UI 回调。controller 由服务持有、生命周期长于 Activity——不摘除时
     * close() 之后 UI 侧 listener/闭包仍被引用（Activity 泄漏），且 teardown 后的
     * 状态上报仍会触达已销毁的 UI。调用点全部使用 `?.`，空安全。
     */
    fun detachUi() {
        uiListener = null
        uiStatusReporter = null
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = airPlayServer?.isAttached() == true

    /**
     * vc153：无线会话活跃查询（MainActivity 有线连接互斥判定用）——当前活跃（真交接
     * [wirelessActiveReported] / 降级态 [wirelessDegradedActive]）或本代曾建立过
     * [wirelessEverActive]（覆盖 iPhone 强断后无线栈仍在重试的窗口）任一即算活跃。
     */
    fun isWirelessSessionAlive(): Boolean =
        wirelessActiveReported.get() || wirelessDegradedActive.get() || wirelessEverActive.get()

    /** vc286：当前无线自动重试计数（0 = 正常态），供 UI 显示「自动重试 n/5」。 */
    fun wirelessAutoRetryAttempt(): Int = wirelessRetryCount.get()

    /**
     * vc250-07：出过画事实清零必须可观测。验收靠三条证据互证：
     * ①同代 `rendered=yes` + `slow-retry cadence kind=long-outage intervalMs=60000`；
     * ②跨代先见到本行 `reason=...`，随后才是 `kind=slow-first-display intervalMs=300000`；
     * ③不出现 `rendered=no` 却 `kind=long-outage` 的矛盾组合。
     * 只清零不打印时，跨代回 5 min 只能从慢轮行反推，故每次清零都留一行（带 was 值）。
     */
    private fun resetRenderedEpisode(reason: String) {
        val was = wirelessRenderedThisEpisode
        wirelessRenderedThisEpisode = false
        debugLog("wireless rendered flag reset reason=$reason was=$was")
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        wirelessRetryCount.set(0)
        // vc250-07：服务侧新起一轮 = 新连接纪元，出过画事实从零开始（打印原因，便于验收）。
        resetRenderedEpisode("service-recreate")
        // vc306：新连接纪元，凭证下发标记从零开始（按代次比对，此处只为日志可读）。
        wirelessCredentialIssuedGeneration = -1
        // 1.0.10：用户手动重连 = 重新给满 wired 自动重试额度（对齐无线链 M6h 语义）。
        wiredRetryCount.set(0)
        // vc153：启动看门狗重试额度同步给满。
        wiredStartWatchdogRetries.set(0)
        if (config.transport == CarPlayTransport.WIRED) {
            startWiredReceivers()
        }
        startMfi()
    }

    /** 重新加载本地离线 MFi 证书并重启流水线。 */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** 重跑 iPhone 流水线（无线：热点 -> 蓝牙 -> AirPlay；有线：发现 -> 配对 -> NCM）。保留原方法名便于上层复用。 */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        // M6h：用户手动重连 = 重新给满自动重试额度，否则上一轮用尽的计数会让这次秒失败。
        wirelessRetryCount.set(0)
        // vc250-07：手动重连视为新连接纪元（同 start()），清零留原因行。
        resetRenderedEpisode("user-reconnect")
        // 1.0.10：wired 额度同步重置。
        wiredRetryCount.set(0)
        // vc153：启动看门狗重试额度同步重置。
        wiredStartWatchdogRetries.set(0)
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRED) {
            startIphone()
        } else {
            restartWireless()
        }
    }

    /**
     * M6k：触摸入口。[sticky] 为 true 表示该事件代表"触点状态变化"（DOWN/UP/POINTER_UP 等），
     * 必须逐条送达；为 false 表示纯 MOVE，可被后续 MOVE 覆盖（latest-wins）。
     *
     * 与旧实现（每个事件都 execute 一个任务 → 无界排队）不同：这里先把事件并入 pendingTouch，
     * 再确保只有一个"发送泵"任务在跑，泵在线程上串行排空队列。这样快速滑动时中间 MOVE 会被
     * 合并掉，队列长度有界，触摸不再因排队而滞后。
     */
    fun sendTouch(contacts: List<AirPlayContact>, sticky: Boolean): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val shouldStart = synchronized(touchLock) {
            enqueueTouchLocked(contacts, sticky)
            if (touchPumpRunning) {
                false
            } else {
                touchPumpRunning = true
                true
            }
        }
        if (!shouldStart) return true
        return try {
            touchExecutor.execute { pumpTouch(session) }
            true
        } catch (_: Exception) {
            synchronized(touchLock) { touchPumpRunning = false }
            false
        }
    }

    // M6k22 方控：媒体 HID 按键 / Siri 请求入口（移植自上游 fork sendMediaButton）。
    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 入队：sticky 事件必留；MOVE 若队尾也是 MOVE 则就地覆盖（丢弃中间态）。 */
    private fun enqueueTouchLocked(contacts: List<AirPlayContact>, sticky: Boolean) {
        if (sticky) {
            if (pendingTouch.size >= MAX_PENDING_TOUCH) {
                // 极端滞后：丢最旧的 MOVE（队首通常是陈旧移动），但绝不丢 sticky 状态变化。
                val head = pendingTouch.peekFirst()
                if (head != null && !head.sticky) {
                    pendingTouch.removeFirst()
                    touchDroppedCount++
                }
            }
            pendingTouch.addLast(PendingTouch(contacts, true))
            return
        }
        val tail = pendingTouch.peekLast()
        if (tail != null && !tail.sticky) {
            pendingTouch.removeLast()
            pendingTouch.addLast(PendingTouch(contacts, false))
            touchCoalescedCount++
        } else {
            pendingTouch.addLast(PendingTouch(contacts, false))
        }
    }

    /** 发送泵：在 touchExecutor 单线程上串行排空队列；排空后释放"运行中"标志。 */
    private fun pumpTouch(session: AirPlaySession) {
        while (true) {
            val next = synchronized(touchLock) {
                if (closed || pendingTouch.isEmpty()) {
                    touchPumpRunning = false
                    null
                } else {
                    pendingTouch.removeFirst()
                }
            } ?: return
            try {
                session.sendTouch(next.contacts)
                synchronized(touchLock) { touchSentCount++ }
            } catch (error: Exception) {
                debugLog("touch send failed", error)
            }
            logTouchStatsIfDue()
        }
    }

    /** 每 [TOUCH_STATS_INTERVAL_NS] 输出一次发送/合并/丢弃计数，用于实机验证是否在合并。 */
    private fun logTouchStatsIfDue() {
        val now = System.nanoTime()
        val line = synchronized(touchLock) {
            if (now - lastTouchStatsNs < TOUCH_STATS_INTERVAL_NS) return
            if (touchSentCount == 0 && touchCoalescedCount == 0 && touchDroppedCount == 0) {
                lastTouchStatsNs = now
                return
            }
            val message = "touch stats sent=$touchSentCount coalesced=$touchCoalescedCount " +
                "dropped=$touchDroppedCount pending=${pendingTouch.size}"
            touchSentCount = 0
            touchCoalescedCount = 0
            touchDroppedCount = 0
            lastTouchStatsNs = now
            message
        }
        debugLog(line)
    }

    /** vc154：拆栈完成一次性回调（teardown 线程触发，调用方自行 post 主线程）。
     *  供 MainActivity 有线切换等待「无线栈拆干净」替代固定 2.5s 延迟；
     *  与 teardownComplete 闭锁同点触发，close() 重入被 closed 位防抖。 */
    @Volatile
    var onTeardownComplete: Runnable? = null

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        // vc201：先摘除 UI 回调——controller 由服务持有，不摘的话 Activity 闭包随它泄漏。
        detachUi()
        BydNavigationOutputs.endNow()
        closeBtAudioHandoff()
        closeWiredReceivers()
        // 1.0.1：整体关闭时停掉重枚举看门狗/轮询兜底，防止关闭后定时器仍触发。
        cancelReenumerationTimers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("wired stack") { closeWiredStack(reason = "controller-close") }
                        // vc142：立即关底层 USB session——打断阻塞在 Iap2UsbMuxHost.begin 的
                        // bring-up 协程（最长 60s），并把 fd 归权收回 controller，防止其迟到
                        // 的 finally 清理误伤下一次连接复用的 connection（openOnce 同 fd）。
                        val activeSession = wiredUsbSession
                        wiredUsbSession = null
                        closeBestEffort("wired usb session") { activeSession?.close() }
                    } else {
                        // M6k24：控制器整体关闭时，保留待复用的热点附着也要一并释放。
                        val retained = retainedHotspot
                        if (retained != null) {
                            closeBestEffort("RetainedHotspot") { retained.first.close() }
                            retainedHotspot = null
                        }
                        closeBestEffort("wireless stack") { decide(WirelessDecision.CLOSE_STACK, WirelessSource.Close) }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    // vc178：USB 主机线程与共享 executor 一起收（它上面的建链任务同样含阻塞 sleep）。
                    usbHostExecutor.shutdownNow()
                    executor.shutdownNow()
                    try {
                        executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        usbHostExecutor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    teardownComplete.countDown()
                    // vc154：闭锁放行后同点触发一次性回调（拆栈已彻底完成）。
                    onTeardownComplete?.let { callback ->
                        try {
                            callback.run()
                        } catch (_: Throwable) {
                        }
                    }
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 等待无线各层资源拆除完成；供非主线程的生命周期调用。 */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        // vc97③：记录控制面最近收帧时刻（心跳/应用帧均算）——Ipv6NcmBridge wedge
        // 判别器探针的数据源。无线路径同样更新，无副作用（探针仅有线桥使用）。
        wiredControlLastRxAtNanos = System.nanoTime()
        BydNavigationOutputs.onFrame(frame)
    }

    /** Lite：MFi 仅支持 LOCAL 离线证书，无 CH341/I2C/REMOTE 轮询。 */
    private fun startMfi() {
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        // API16 适配：noBackupFilesDir 是 API 21，改用 filesDir（API 1）。
        val offlineDirectory = java.io.File(appContext.filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        openLocalMfi(offlineDirectory)
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                if (config.transport == CarPlayTransport.WIRED) startIphone() else startWireless()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently skip authentication.
                decide(WirelessDecision.FAIL, WirelessSource.Mfi, error = error)
            }
        }
    }

    private fun startWireless() {
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessDegradedActive.set(false)
        wirelessDegradedAtMs = 0L // vc202：新代清 degraded/ handoff 锚点
        wirelessHandoffRequestedAtMs = 0L
        wirelessEverActive.set(false) // vc151：新代重置 watchdog 放行依据
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        // 1.1.4（B 方案）：打断轮的强制窗口已由 delay 保证，重启时清除标记回归正常节奏。
        wirelessStaleSessionBounce = false
        // M6k11 修复 gen 双自增：startWireless() 内部已 incrementAndGet，
        // 此处再 +1 导致实测 gen 1→3→5→7→…；陈旧重试失效判断不受影响。
        Thread(
            {
                // 1.1.5（提速）：重试保留热点附着（M6k24 C 方案路径扩展到所有重试）——
                // 10-12 实机日志：每轮 teardown 释放热点会把 iPhone 踢下网，下一轮
                // iPhone 重新入网 25s+；热点常开（旧版 0.2.6 模式）时 iPhone 不掉线、
                // 重连即秒连。僵尸第 3 次 reset 蓝牙适配器的路径不受影响（只动 BT 侧）。
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.Restart, keepHotspot = true)
                // M6k11：僵尸族第 3 次起，teardown 后顺带 reset 本机蓝牙适配器，
                // 清车机侧 RFCOMM 服务状态。失败只记日志不阻断重连。
                // vc244：外置 dongle 链路不 reset 机内适配器——本链路不走机内蓝牙，
                // disable()/enable() 只会把机内适配器拉起来，给 iPhone 多一个可配对目标。
                if (!config.usbBtDongle && !closed && wirelessZombieMode && wirelessRetryCount.get() >= 3) {
                    try {
                        val adapter = bluetoothAdapter
                        if (adapter?.isEnabled == true) {
                            debugLog("wireless local BT adapter reset begin (zombie recovery)")
                            adapter.disable()
                            SystemClock.sleep(1200)
                            adapter.enable()
                            // enable() 是异步的，等回 ON 再继续（最多 8s），防止 RFCOMM 白跑一轮
                            var waited = 0
                            while (!adapter.isEnabled && waited < 8_000) {
                                SystemClock.sleep(250)
                                waited += 250
                            }
                            debugLog("wireless local BT adapter reset done waitedMs=$waited")
                        }
                    } catch (error: Throwable) {
                        debugLog("wireless local BT adapter reset failed: ${error.message}")
                    }
                }
                if (!closed) startWireless()
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * vc250（VC248-09）：快链退避额度用尽后的低频续试入口。
     *
     * 只调整重试的频率与时机（5min 一轮），不改「连续多次失败即停快链」的既有策略：
     * 慢轮不复位计数，若仍失败会走到同一个 exhausted 分支，再排下一轮。
     */
    private fun scheduleSlowAutoRetry(
        tag: String,
        canRun: () -> Boolean = { true },
        // vc250-07：慢轮间隔可按链路事实分型（无线：出过画 60s / 从未出画 5min），
        // 默认仍为原 5min，wired 调用点不变。
        delayMs: Long = AUTO_RETRY_SLOW_INTERVAL_MILLIS,
        action: () -> Unit,
    ) {
        mainHandler.postDelayed({
            if (closed || !canRun()) return@postDelayed
            debugLog("$tag slow auto-retry tick after ${delayMs}ms")
            action()
        }, delayMs)
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            wirelessBringUpStartedAtMs = SystemClock.elapsedRealtime()
            decide(WirelessDecision.CLOSE_STACK, WirelessSource.BringupStart)
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                return
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            val hotspotInfo = startWirelessHotspot(generation)
            // 1.0.15：记住本代实际下发的信道，供 SESSION_ACTIVE 后写入成功配置记忆。
            // vc274：assumed-* 是默认值而非观测，不允许回灌记忆。
            lastAdvertisedHotspotChannel = hotspotInfo.channel
            lastAdvertisedChannelObserved = !hotspotInfo.channelSource.startsWith("assumed")
            if (isStaleWirelessRun(generation)) {
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaleHotspot)
                return
            }
            val startedHotspot = hotspot
            // vc250-06：热点确认仍要求「控制面鉴权 ∧ 媒体面渲染」，两处交叉判定。
            wirelessControlProof.begin(generation)
            wirelessMediaProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            // vc253：供 hotspot sta-arp 观测使用的网段前缀（只读诊断，不影响连接判断）。
            hotspotStaSubnetPrefix = hotspotStaSubnetPrefixOf(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            // vc256：配置值/观测值分开标注——channel=auto 表示未知（0x4301 会省略该字段），
            // frequency 的 source 说明它是从哪个接口读到的，避免再把猜测值当成实测值。
            val channelText = hotspotInfo.channel.takeIf { it > 0 }?.toString() ?: "auto"
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} " +
                    "channel=$channelText channelSource=${hotspotInfo.channelSource} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz " +
                    "frequencySource=${hotspotInfo.frequencySource}",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            // vc212：外置 dongle 路径不碰框架蓝牙（系统蓝牙可关）——跳过 adapter 检查/系统侧
            // 设备选择；配对在 dongle 侧完成（UsbBtStandby.isUsable），链路经
            // UsbBtBootstrapStream.open 取回。identification 的 hostBluetoothMac 语义=我方
            // 蓝牙地址，dongle 路径用 dongle 本机 BD_ADDR。
            val device = if (config.usbBtDongle) {
                null
            } else {
                val adapter = bluetoothAdapter
                    ?: throw IOException("Bluetooth adapter is unavailable")
                // vc93：双证据源判蓝牙开关（235233 实锤 ECARX 上用户已开蓝牙但
                // adapter.isEnabled 仍报 false——OEM 适配器代理状态滞后/失灵）。
                // adapter ∥ Settings.Secure bluetooth_on 任一为开即视为开。
                if (!isBluetoothEffectivelyEnabled(adapter)) {
                    // 自动补开（对齐中继模式既有约定）：双源都关时 enable + 轮询 ≤8s。
                    // vc94：ensureBluetoothEnabled 返回非空 Boolean，vc93 的 elvis-throw 不可达
                    //（编译器仅警告），补开失败会静默续连——改为显式判 false 才真正抛引导文案。
                    if (!ensureBluetoothEnabled(adapter)) {
                        throw IOException("Bluetooth is not enabled")
                    }
                }
                selectWirelessBluetoothDevice(adapter)
            }
            val hostBluetoothMac = if (config.usbBtDongle) {
                UsbBtBootstrapStream.lastBdAddr
                    ?: com.shilapi.xcertplay.usbbt.UsbBtHost.localAddressSnapshot
                    ?: bluetoothAdapter?.let { accessoryBluetoothMac(it) }
                    ?: "unknown"
            } else {
                bluetoothAdapter?.let { accessoryBluetoothMac(it) } ?: "unknown"
            }
            debugLog(
                "wireless selected Bluetooth target name=${device?.name ?: "usb-dongle"} " +
                    "address=${device?.address ?: "dongle"} localBt=$hostBluetoothMac",
            )
            // vc285：无论显式点选还是自动匹配，都记住这台机的 MAC（同值时不重复落盘）。
            device?.address?.let(rememberWirelessBluetoothAddress)
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )
            // vc67：attach 后可能因端口回退而与 wirelessAirPlayConfig 端口不同。
            var effectiveWirelessAirPlayConfig = wirelessAirPlayConfig

            // Lite：进程内 AirPlayTcpServer 取代 CarPlayVpnService 绑定。
            onStatus(CarPlayStatus.AttachingNetwork)
            startWirelessSampler(generation)
            val server = AirPlayTcpServer()
            when (
                val result = server.attach(
                    bindAddress = hostAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = wirelessSessionListener(generation),
                    media = media,
                    // vc250-08：零字节看门狗只给无线——有线握手正常 < 1 s，加了只增误伤风险。
                    handshakeIdleTimeoutMillis = AIRPLAY_HANDSHAKE_ZERO_BYTE_TIMEOUT_MILLIS,
                )
            ) {
                is AirPlayTcpServer.AttachResult.Started -> {
                    airPlayServer = server
                    // vc67：7100 被车机自带投屏服务占用（coocaa BEP10A 215128 实测）时端口
                    // 回退——实际端口同步进 config，Bonjour TXT 与 0x5703 airPlayPort 都按
                    // 真实端口下发（iPhone 不写死端口）。
                    effectiveWirelessAirPlayConfig = wirelessAirPlayConfig.copy(port = result.port)
                }
                AirPlayTcpServer.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is AirPlayTcpServer.AttachResult.Failed ->
                    throw IOException(result.message)
            }
            if (effectiveWirelessAirPlayConfig.port != airPlayConfig.port) {
                debugLog(
                    "wireless AirPlay port fallback: ${airPlayConfig.port} occupied, " +
                        "using ${effectiveWirelessAirPlayConfig.port} (injected into 0x5703 + Bonjour)",
                )
            }
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=${effectiveWirelessAirPlayConfig.port} (Bonjour advertisement uses the same port)",
            )
            // M6k20（移植自上游 FirstTcpWatchdog 语义，适配 Lite 架构）：
            // 热点+监听口就绪后，若 FIRST_TCP_TIMEOUT_MILLIS 内 iPhone 从未发起任何 TCP
            // 连接（陈旧 mDNS 缓存、Wi-Fi 未入网、AP 隔离），显性失败并走重连状态机，
            // 而不是挂到 45s handoff 超时（旧路径 bootstrap 超时不触发重连，等于死等）。
            // [RELAY-TEST] 实验模式下看门狗须让位：三阶段总时长 ~134s（60 重发+2+10+60 恢复），
            // 30s 默认值会在假凭证发出前把会话判死（00:28 日志实证 phase2 从未触达）。
            //
            // vc246（两处误报修正）：看门狗改到「蓝牙链路真正交出」之后再启动（见下方
            // bluetoothStream 赋值处）。此前在这里启动，dongle 路径还卡在「等常驻广播交出
            // iPhone 链路」时就被判 First TCP timeout 并弹 WLAN 提示——真实原因（链路未交出）
            // 反而被抢在它前面报出。未建立链路阶段不得启动、更不得判死。
            if (isStaleWirelessRun(generation)) {
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaleAttach)
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = effectiveWirelessAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                // vc269：广播地址诊断（A 记录/网卡地址）落文件日志。
                onDiagnostic = { line -> debugLog("wireless $line") },
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
            )
            bonjour = bonjourClient
            // vc66（提速）：start() 整体后台化。TS7 204308 实测：JmDNS create+registerService
            // 同步阻塞 7.5s（23.82 attach 完成 → 31.40 started），是关键路径上最大的单项
            // 开销。mDNS 广播不在连接关键链路上（iPhone 入网由 iAP2 0x5703 直发凭证驱动，
            // vc65 日志 tcpAccepted 与 Bonjour started 无先后依赖），且 start 失败本来就是
            // 非致命降级——后台化语义零变化。竞态护栏：代次过期则自行 close；与 teardown
            // 撞车时 CarPlayBonjour 内部 lifecycleLock/closed 保证行为正确（close 先到则
            // start 抛 check 走降级），bonjour 字段清理用引用比较防止误伤新一代实例。
            Thread(
                {
                    try {
                        bonjourClient.start()
                        if (isStaleWirelessRun(generation)) {
                            runCatching { bonjourClient.close() }
                            debugLog(
                                "wireless Bonjour async start done but generation stale; " +
                                    "closed gen=$generation",
                            )
                            return@Thread
                        }
                        // vc272（TS7 软 AP 多播下行失效兜底）：热点模式下 JmDNS 多播 announce
                        // 到不了已关联 iPhone（tcpdump 本地可见、iPhone 收不到、tcpAccepted=0），
                        // 改用单播 announce 直发 STA 的 5353（iPhone mDNSResponder 接受单播
                        // announce，收到即解析连 7100）。中继模式多播经上游路由器转发正常，不启用。
                        if (hotspotInfo != null) {
                            val announcer = WirelessMdnsAnnouncer(
                                onDiagnostic = { line -> debugLog("wireless $line") },
                            ).also { wirelessMdnsAnnouncer = it }
                            announcer.attach(
                                WirelessMdnsAnnouncer.Params(
                                    bindAddress = hostAddress,
                                    instanceName = effectiveWirelessAirPlayConfig.deviceName,
                                    port = effectiveWirelessAirPlayConfig.port,
                                    txtRecords = CarPlayBonjourProtocol.airPlayTxtRecords(
                                        effectiveWirelessAirPlayConfig,
                                        identity,
                                    ),
                                ),
                            )
                            hotspotStaArpListener = { address -> announcer.addTarget(address) }
                            announcer.start()
                            // vc272b：立刻读一次 ARP 播种目标——iPhone 通常在监听口就绪前
                            // 就已入网（vc272 首测 18:20:57 associated=true），等下一次
                            // sta-arp 观测会让前几轮通告空转（实测前 5 轮 sent=0）。
                            logHotspotStaArp("mdns-announcer-start")
                            debugLog(
                                "wireless mdns unicast announcer started gen=$generation " +
                                    "bind=$hostAddressText instance=${effectiveWirelessAirPlayConfig.deviceName}",
                            )
                        }
                        debugLog(
                            "wireless Bonjour services started mode=interface (async) " +
                                "iface=${hotspotInfo.interfaceName ?: "unknown"} gen=$generation",
                        )
                    } catch (error: Throwable) {
                        // 自研 mDNS 落地前的临时策略（10-02）：4.1 上 JmDNS joinGroup 报 ENODEV，
                        // 但 mDNS 广播与蓝牙 iAP2 握手相互独立。此处降级为非致命、放行蓝牙段，
                        // 以便先探测 Apple 端鉴权与 iPhone 入网行为；换自研 mDNS 后恢复为致命。
                        if (bonjour === bonjourClient) bonjour = null
                        runCatching { bonjourClient.close() }
                        debugLog(
                            "wireless Bonjour 广播失败（非致命，继续蓝牙段）: " +
                                "${error.message} gen=$generation",
                        )
                    }
                },
                "xcertplay-bonjour-start",
            ).apply {
                isDaemon = true
                start()
            }
            if (isStaleWirelessRun(generation)) {
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaleBonjour)
                return
            }

            onStatus(CarPlayStatus.ConnectingBluetooth)
            // vc212：dongle 路径无系统 RFCOMM socket——流由 UsbBtBootstrapStream.open 直接交出。
            val socket = if (config.usbBtDongle) {
                null
            } else {
                openWirelessRfcommSocket(device!!)
            }
            wirelessLinkOpenedAtMs = SystemClock.elapsedRealtime()
            // 1.1.3：建链成功= SDP 恢复可见，清掉连续失败计数。
            sdpFailureConsecutiveCount = 0
            debugLog("wireless RFCOMM connected address=${device?.address ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaleRfcomm)
                return
            }
            // vc167（外置 USB 蓝牙 dongle 第 2 步）：开关开启时改走 usbbt 探活流
            // （枚举→openDevice→HCI Reset→BD_ADDR，B 批；协议栈未就绪会抛明确异常）。
            // 未启用时必须走原分支，行为与此前完全一致——下方 btProbeMode 三轮握手一律不动。
            val stream = if (config.usbBtDongle) {
                // vc186：把 usbbt 全过程诊断接进 debugLog（→uiListener.onDebugLog→LiteLog 上云），
                // 否则 usbbt 只有 system logcat，远程拿不到 portReset/forceClaim/errno 证据。
                UsbBtBootstrapStream.open(context, config.usbBtDongleName) { msg -> debugLog(msg) }
            } else {
                BluetoothRfcommDuplexStream(socket!!)
            }.also {
                bluetoothStream = it
                // vc289：socket 所有权随流交接，控制器侧引用必须同时清空。留着它，并发 teardown
                // 里后到的一路会走「无流→直接关 socket」分支，对同一个 socket 二次 close
                // （C8815 13:26:14 tombstone：pool-2-thread-1 / free / deadbaad）。
                bluetoothSocket = null
            }
            // vc246（两处误报修正）：蓝牙链路已真正交出，此刻才启动 First TCP 看门狗。
            bluetoothLinkReadyGeneration = generation
            armFirstTcpWatchdog(
                generation,
                timeoutMillis = if (
                    Iap2WirelessControlClient.RELAY_TEST_ENABLED &&
                    config.wirelessHotspotMode == WirelessHotspotMode.RELAY
                ) {
                    RELAY_TEST_FIRST_TCP_TIMEOUT_MILLIS
                } else if (wirelessRenderedThisEpisode) {
                    // vc289：本次连接纪元已出过画（掉线重连），iPhone 不在网是确定事实，
                    // 不必按首连节奏盲等 95s；25s+10s 足够覆盖它在网但握手慢的情况。
                    FIRST_TCP_TIMEOUT_WARM_MILLIS
                } else {
                    FIRST_TCP_TIMEOUT_MILLIS
                },
                graceMillis = if (
                    !Iap2WirelessControlClient.RELAY_TEST_ENABLED &&
                    wirelessRenderedThisEpisode
                ) {
                    FIRST_TCP_GRACE_WARM_MILLIS
                } else {
                    FIRST_TCP_GRACE_MILLIS
                },
            )
            // [BT-PROBE] t3 蓝牙兼容实验：每代连接轮换一次握手策略，一轮重试拿满三种模式的现场。
            // mode 0 = 现行（发 Marker，等 iPhone 回 Marker 后协商）
            // mode 1 = 静默等待（不发 Marker，纯等 iPhone 先发 Marker）
            // mode 2 = 主动协商（发 Marker 后立即发 Sync，同 USB 有线时序）
            val btProbeMode = (generation - 1) % 3
            debugLog(
                "[BT-PROBE] mode=$btProbeMode " +
                    "(0=marker+wait 1=silent-wait 2=marker+initiate) gen=$generation",
            )
            val channel = Iap2Session.openWirelessProbe(
                stream,
                probeMode = btProbeMode,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
            )
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaleTunnel)
                return
            }
            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = effectiveWirelessAirPlayConfig.port,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                bssid = hotspotInfo.bssid,
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            // vc80 中继踢出（转正）：中继模式恒用假凭证踢出机制——iPhone 提前连到中继 WiFi 时
            // 0x5703 不触发 join（no-op），重发全废，看门狗 65s 判死 → 死循环。真凭证重发
            // 8 次（40s）仍 0 TCP 即踢出（假 0x5703 出 AP → 10s 后真凭证恢复 → 强制新鲜
            // join → ~62s 前完成），全程在 First TCP 看门狗 30s+35s 宽限窗口内。
            // 旧 [RELAY-TEST] 实验门控（RELAY_TEST_ENABLED）仅保留控制 150s 实验看门狗。
            val relayTestFakeCredential = if (
                config.wirelessHotspotMode == WirelessHotspotMode.RELAY
            ) {
                selectRelayTestFakeCredential(hotspotInfo.ssid)
            } else {
                null
            }

            // vc307：媒体面可能已用 airplay-frame 提前判据收口（WirelessActive 已上报），
            // 本段蓝牙控制面晚到时不得把 UI 打回「建立中」——K60 11-16 13:35 实测：
            // 媒体 3.7s 先活、RFCOMM 8.1s 才连上，晚到的 RunningWireless 让界面永久卡在建立中。
            if (wirelessActiveReported.get() || wirelessDegradedActive.get()) {
                debugLog("wireless Bluetooth iAP2 control starting (already active; keeping ACTIVE status)")
            } else {
                onStatus(CarPlayStatus.RunningWireless)
                debugLog("wireless Bluetooth iAP2 control starting")
            }
            // vc152：持有 client 引用——会话结束后自动重投逻辑经 sendCarPlayInvitation()
            // 外部线程直发 0x4301；控制环退出即清引用（RFCOMM 已断邀请通道无效）。
            val controlClient = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            )
            wirelessControlClient = controlClient
            // vc255：凭证锚点按尝试序号分档。wirelessRetryCount 是「1 起算的重试计数」：
            // 首次尝试为 0（scheduleWirelessRetry 里 incrementAndGet 后 1 才是第 1 次重试），
            // 故 ==0 即本纪元首轮 → 45s；其余（重试轮）→ 12s。成功建会话/用户重连会归零。
            // vc305：早期轮（首轮 + 首次重试）与首轮同等待遇——实测 iPhone 重新入网需 32s，
            // 12s 的换代窗口必杀。第 3 轮起回落到 12s，保证真下车时仍能快速转慢轮。
            val earlyRecovery = wirelessRetryCount.get() <= 1
            // vc306：车机热点（MANUAL/MANUAL_IPV4）下 45s 不够——K60 20261116 实测
            // 0x5703 → 首个 TCP 需 46.4s，45s 锚点会杀在最后 1.4 秒。放开到 70s。
            // RELAY 中继沿用 45s：那里 STA 常驻关联，长窗口会拖慢真掉线的转慢轮节奏。
            val credentialAnchorMillis = if (earlyRecovery) {
                if (config.wirelessHotspotMode.isManualHotspot) {
                    CREDENTIAL_JOIN_TIMEOUT_HOTSPOT_MILLIS
                } else {
                    CREDENTIAL_JOIN_TIMEOUT_FIRST_ATTEMPT_MILLIS
                }
            } else {
                CREDENTIAL_JOIN_TIMEOUT_MILLIS
            }
            // vc305：静默窗口同步分档。原恒定 20s 会让 T2 的 gen2 在 21.5s 处误判 credential-no-auth。
            val authSilenceMillis = if (earlyRecovery) {
                CREDENTIAL_AUTH_SILENCE_RECOVERY_MILLIS
            } else {
                Iap2WirelessControlClient.IAP2_AUTH_SILENCE_TIMEOUT_MILLIS
            }
            val result = controlClient.run(
                identification = identification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                onIncoming = ::onRouteFrame,
                onProgress = ::debugLog,
                // M6k24（A 方案）：iPhone 一旦发起过 AirPlay TCP 连接就停止凭证重发。
                phoneOnAirPlay = { (airPlayServer?.acceptedConnectionCount() ?: 0) > 0 },
                // vc252 凭证锚点：0x5703 下发后窗口内无任何 TCP → 控制环返回
                // CREDENTIAL_NO_JOIN，下面按「可重试失败」走既有整代重建。
                // vc253：8s→12s。vc255：首轮 45s / 重试轮 12s（见 credentialAnchorMillis）。
                // vc305：早期轮（retryCount<=1）同为 45s，第 3 轮起回落 12s（iPhone 入网实测 32s）。
                credentialJoinTimeoutMillis = credentialAnchorMillis,
                // vc253 静默轮判据：控制面建立后 20s 内无 0x5702 / 隧道鉴权 / 首 TCP →
                // CREDENTIAL_NO_AUTH；不必空等 First TCP 看门狗 60s + 35s grace。
                authSilenceTimeoutMillis = authSilenceMillis,
                phoneAuthenticated = { wirelessAuthenticated.get() },
                // vc250-05：真凭证 0x5703 下发成功 → summary credential 三态（full/cached）。
                onCredentialSent = { beforeAirPlayTcp ->
                    CarPlayStageLog.credentialIssued(beforeAirPlayTcp)
                    // vc253：凭据下发瞬间的热点 STA 关联快照（只读诊断）。
                    logHotspotStaArp("credential-issued")
                    // vc306：本代凭证已真实下发 → 允许 first-TCP 看门狗进入长宽限。
                    wirelessCredentialIssuedGeneration = generation
                },
                relayTestFakeCredential = relayTestFakeCredential,
                // vc83：中继模式 2 次重发（~10s）仍 0 TCP 即进入假凭证踢出，
                // 踢出后 1.5s 恢复真凭证并持续重发（详见 Iap2WirelessControlClient 注释）。
                relayKickAfterResends = if (relayTestFakeCredential != null) 2 else -1,
                // vc264：热点明确报告 0 客户端时，0x4301 前等 iPhone 入网；
                // 已关联或未知一律放行（warm 路径零延迟）。
                // vc284：改三态。null=拿不到权威关联态，控制层只等 2s 而不是盲等满 8s；
                // API16~20 没有 SoftApCallback，ARP 读不到仍按 false（历史行为不变）。
                staAssociated = { hotspotStaAssociationState() },
                // vc287：中继模式下「未关联」的等待预算收到 3s——该模式下 iPhone 入网由
                // 我方 0x5703 凭证驱动，等关联是反因果（C8815 实测白等 8s 后 0.9s 就入网）。
                staAbsentWaitNanos = if (config.wirelessHotspotMode == WirelessHotspotMode.RELAY) {
                    RELAY_STA_ABSENT_WAIT_NANOS
                } else {
                    START_SESSION_STA_ABSENT_WAIT_NANOS
                },
            )
            // vc152：控制环退出（RFCOMM 断/terminal 返回）后邀请通道失效，清引用。
            wirelessControlClient = null
            if (isStaleWirelessRun(generation)) {
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaleControl)
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        // vc250-06：媒体面已成立（确有渲染帧）时，控制面（蓝牙 RFCOMM）断开
                        // 只降级——不拆栈、不消耗重试、不 Failed；Wi-Fi A/V 继续跑。
                        if (wirelessMediaProof.hasRenderedFrame(generation)) {
                            debugLog(
                                "wireless control plane closed but media is live; " +
                                    "degrading instead of tearing down " +
                                    "(handoffRequested=${wirelessHandoffRequested.get()} " +
                                    "tunnelReady=${wirelessTunnelReady.get()})",
                            )
                            completeWirelessHandoffDegraded(
                                generation,
                                handoffReason = "control-closed-media-alive",
                            )
                            return
                        }
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw CarPlayClassifiedException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                                CarPlayFailureClass.TunnelTimeout,
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    // M6g：降级态下 iAP2 挂在蓝牙上、音视频照常，「控制窗口结束」不等于会话结束，
                    // 不要报 ControlEnded 误导（T43 实测过这个误报）。真交接态由
                    // wirelessActiveReported 抑制；两者都没发生（iAP2 建链就没起来）才如实上报。
                    if (!wirelessActiveReported.get() && !wirelessDegradedActive.get()) {
                        onStatus(CarPlayStatus.ControlEnded)
                    }
                // 1.0.29（AC8227 倒车闪屏，移植上游 #229 FirstTcpWatchdog 精神）：
                // 会话激活后 AirPlay TCP 断流超时（iPhone 端会话死、RFCOMM 还活着）——
                // 无线模式下音视频就是 AirPlay，断流即会话实质死亡，按链路掉线整代重启。
                Iap2WirelessControlTerminal.AIRPLAY_STALLED ->
                    if (wirelessActiveReported.get()) {
                        throw IOException("wireless AirPlay stalled: no TCP after active session")
                    }
                // vc252：凭证锚点超时——0x5703 已下发但窗口内 iPhone 始终没发起 AirPlay TCP。
                // 实测继续每 5s 重发凭证 4 次全无效，而手动重建一整轮 5.4s 就入网。
                // 因此这里不重发，直接抛可重试异常 → 走既有 closeWirelessStack +
                // scheduleWirelessRetry（同一份额度、同一退避），不新增状态机。
                Iap2WirelessControlTerminal.CREDENTIAL_NO_JOIN -> {
                    logHotspotStaArp("credential-no-join")
                    throw IOException(
                        "wireless credential anchor: iap2 first tcp timeout " +
                            "${credentialAnchorMillis}ms after 0x5703 " +
                            "(wifiConfigs=${result.wifiConfigurationsSent}, " +
                            "carPlayStarts=${result.carPlayStartSessionsSent}); " +
                            "restarting generation reason=credential-no-join",
                    )
                }
                // vc253：静默轮判据命中——控制面建立后 20s 内没有 0x5702、没有隧道鉴权、
                // 也没有首个 TCP。沿用 vc252 的整代重建路径，仅失败分型改为 credential-no-auth。
                // 文案保留 `iap2` + `first tcp timeout` 关键字，既有重试分类不变。
                Iap2WirelessControlTerminal.CREDENTIAL_NO_AUTH -> {
                    logHotspotStaArp("credential-no-auth")
                    CarPlayStageLog.credentialNoAuth()
                    throw IOException(
                        "wireless credential anchor: iap2 first tcp timeout " +
                            "(no 0x5702, no iAP2 tunnel auth, no first TCP) " +
                            "${authSilenceMillis}ms " +
                            "after control-plane ready " +
                            "(wifiConfigs=${result.wifiConfigurationsSent}, " +
                            "carPlayStarts=${result.carPlayStartSessionsSent}); " +
                            "restarting generation reason=credential-no-auth",
                    )
                }
            }
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            // vc250-06：媒体面成立时控制面异常（RFCOMM 断/控制环异常）只降级——
            // 不拆栈、不消耗重试、不 Failed；音视频照常，后续断流由媒体看门狗接管。
            if (error !is Error && wirelessMediaProof.hasRenderedFrame(generation)) {
                debugLog(
                    "wireless control plane failed while media is live: " +
                        "${error.message}; degrading instead of tearing down",
                )
                if (!wirelessActiveReported.get()) {
                    // vc307：completeWirelessHandoffDegraded 的 compareAndSet 一次性守卫会吞掉
                    // 重复调用（守卫本意只是防 SESSION_ACTIVE 日志双发），若此前已降级过一次，
                    // UI 可能仍停在晚到控制面打回的 RunningWireless——这里无条件补发一次 active。
                    val alreadyDegraded = wirelessDegradedActive.get()
                    completeWirelessHandoffDegraded(
                        generation,
                        handoffReason = "control-failed-media-alive",
                    )
                    if (alreadyDegraded) onStatus(CarPlayStatus.WirelessActive)
                }
            } else if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                // M6k11 hotfix：必须在 closeWirelessStack() 之前完成分类——teardown 会清零
                // wirelessLinkOpenedAtMs，先 teardown 后分类会丢失僵尸时间窗证据，
                // 导致 hoo 实测（23:11 日志 +172ms 秒杀）全部误落普通族 IAP2_LINK_ENDED。
                // vc299：热点未开单独归类 HOTSPOT_OFF（环境未就绪，非链路失败）。
                // vc300：EADDRNOTAVAIL（热点已播报开启、但目标地址还没挂到网卡）同属环境未就绪。
                val hotspotAddressPending = isHotspotAddressNotReady(error)
                lastWirelessFailureClass = when {
                    error is CarHotspotOffException -> "HOTSPOT_OFF"
                    hotspotAddressPending -> "HOTSPOT_ADDR_NOT_READY"
                    else -> wirelessFailureClass(error)
                }
                // 1.1.6(vc76)：可重试族失败不拆热点（M6k24 C 方案全路径对齐）——8815
                // 010442 实测 CONNECTION_ABORT 路径 teardown step5/5 释放热点句柄，违反
                // 「失败重试禁止释放热点」硬约束（iPhone 被踢下网 → 重入网恶性循环）。
                // 热点保留 + iPhone 关联不掉，重试秒连；僵尸族例外（需 ≥8s 蓝牙/热点
                // 全断窗口清 iPhone 残留会话态）；终态失败照旧释放热点。
                val retryable = isRetryableWirelessFailure(error)
                val keepHotspot = (retryable || hotspotAddressPending) &&
                    lastWirelessFailureClass != "IPHONE_ZOMBIE" &&
                    !wirelessStaleSessionBounce
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.RunCatch, keepHotspot = keepHotspot)
                if (error is Error) throw error
                // M6h：链路掉线（abort/reset/RFCOMM 断）不再直接停在 Failed 要手点，
                // 自动重试若干次；热点没开、配置不合法这类确定性失败仍如实报错。
                if (error is CarHotspotOffException || hotspotAddressPending) {
                    // vc299（方案 A 冷启动自愈）：热点未开不算终态——慢轮探测 AP 状态，
                    // 用户一开热点即自动重连，不消耗 auto-retry 额度。
                    // vc300：EADDRNOTAVAIL 并入同一等待——区别只是就绪判据多一条
                    // 「目标地址已挂到网卡」，避免 5s 一轮空转 bind。
                    scheduleCarHotspotWait(generation)
                } else if (retryable) {
                    scheduleWirelessRetry(generation, error)
                } else {
                    decide(WirelessDecision.FAIL, WirelessSource.RunCatch, error = error)
                }
            }
        }
    }

    /**
     * M6h：判断是否值得自动重连。只认"链路掉了"这一族瞬时故障
     * （T36 实测：微信电话期间 `IAP2 RX FAILED: Software caused connection abort`；
     * RFCOMM 层也常见 `Connection refused` / `Service discovery failed`，T14 曾手点 7 次才连上）。
     */
    private fun isRetryableWirelessFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return RETRYABLE_WIRELESS_FAILURES.any { message.contains(it) }
    }

    /**
     * M6k9：把错误归到一族标签，纯为日志可读（判据仍是 [RETRYABLE_WIRELESS_FAILURES] 子串表，
     * 不另建一套分类逻辑）。日志里见到 class=PEER_REQUEST/LOCAL_STOP 即「不该重连」，
     * class=OTHER 即「不在可重连族内」。
     */
    /**
     * M6k11：瞬时 EOF 单独归类 IPHONE_ZOMBIE——判据是时间窗：CSM 打开后 <500ms
     * 被关即视为 iPhone 侧僵尸会话（TS7 实测 3 轮全是 +175ms rx EOF；重试治不了，
     * 需长退避 + 用户关开 iPhone 蓝牙）。正常断链（如 Wi-Fi 切断后几十秒的 EOF）不受影响。
     */
    private fun wirelessFailureClass(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        // 中继确定性失败（AP 隔离/连错网/Wi-Fi 关闭/密码错）单独成族——不重试，直接 Failed。
        if (message.contains("relay_unreachable") || message.contains("relay network") ||
            message.contains("relay client") || message.contains("wi-fi is off")
        ) {
            return "RELAY_CONFIG"
        }
        if (isRetryableWirelessFailure(error) &&
            (message.contains("iap2") || message.contains("eof") || message.contains("tunnel")) &&
            wirelessLinkOpenedAtMs != 0L
        ) {
            val sinceOpen = SystemClock.elapsedRealtime() - wirelessLinkOpenedAtMs
            if (sinceOpen < ZOMBIE_WINDOW_MILLIS) {
                return "IPHONE_ZOMBIE"
            }
        }
        val message2 = message
        return when {
            !isRetryableWirelessFailure(error) -> "OTHER"
            message2.contains("handoff") -> "HANDOFF_TIMEOUT"
            message2.contains("abort") -> "CONNECTION_ABORT"
            message2.contains("service discovery") || message2.contains("refused") -> "SERVICE_DISCOVERY_FAILED"
            message2.contains("iap2") || message2.contains("tunnel") -> "IAP2_LINK_ENDED"
            message2.contains("eof") -> "EOF"
            else -> "SOCKET_EXCEPTION"
        }
    }

    /**
     * vc299（方案 A）：车机热点未开启的等待入口。
     *
     * 冷启动时车机热点常晚于 App 就绪（或用户还没开）：旧实现直接落 Failed 终态，
     * 之后用户开热点也不会自动连。改为「每 [CAR_HOTSPOT_WAIT_POLL_MILLIS] 读一次
     * AP 状态」，热点就绪即整代重建；不消耗 auto-retry 额度，也不改既有重试节奏。
     */
    private fun scheduleCarHotspotWait(generation: Int, round: Int = 1) {
        // 只在首轮报状态：轮询期间 UI 保持这条提示，不随轮次刷屏。
        if (round == 1) {
            onStatus(
                CarPlayStatus.RecoveryHint(CAR_HOTSPOT_WAIT_HINT, CarPlayStatus.RecoveryReason.HotspotOff),
            )
        }
        // vc300：轮询日志节流——前 3 轮逐条，之后每 12 轮一条（5s/轮 ⇒ 约 1 分钟一条）。
        if (round <= 3 || round % 12 == 0) {
            debugLog(
                "car hotspot wait: round=$round not ready; " +
                    "poll in ${CAR_HOTSPOT_WAIT_POLL_MILLIS}ms (kind=hotspot-wait)",
            )
        }
        mainHandler.postDelayed({
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                return@postDelayed
            }
            // 判据与 startWirelessHotspot 同源：状态为关时再给 AP 专用接口兜底一次。
            val apReady = CarHotspotStatus.isEnabled(appContext) != false ||
                CarHotspotStatus.hasDedicatedApInterface()
            if (!apReady) {
                scheduleCarHotspotWait(generation, round + 1)
                return@postDelayed
            }
            // vc300：AP 状态先就绪、地址后下发（EADDRNOTAVAIL 根因）——目标地址没上接口时
            // 继续等，避免 bind 必败后空转一整代建链。
            if (hotspotHostAddressReady() == false) {
                if (round <= 3 || round % 12 == 0) {
                    debugLog(
                        "car hotspot wait: round=$round AP ready but host address not on " +
                            "interface yet; keep waiting",
                    )
                }
                scheduleCarHotspotWait(generation, round + 1)
                return@postDelayed
            }
            val rawApState = CarHotspotStatus.rawState(appContext)
            debugLog(
                "car hotspot wait: ready after round=$round " +
                    "state=${rawApState ?: "unknown"}(${CarHotspotStatus.rawStateLabel(rawApState)}); " +
                    "resuming wireless bring-up",
            )
            restartWireless()
        }, CAR_HOTSPOT_WAIT_POLL_MILLIS)
    }

    /**
     * vc300：EADDRNOTAVAIL 并入热点等待的判据。
     *
     * 证据（C8815 20261115 19:03）：`HotspotReady` 后 1s，AirPlay bind 报
     * `Cannot assign requested address`（当时网卡上还是旧地址 192.168.250.254），
     * 旧路径按普通链路失败当场落 Failed。判据只认 manual hotspot + 该错误码：
     * relay 模式目标地址来自上游路由，不走「地址未下发」这一解释。
     */
    private fun isHotspotAddressNotReady(error: Throwable): Boolean {
        if (!config.wirelessHotspotMode.isManualHotspot) return false
        val message = error.message.orEmpty().lowercase()
        return message.contains("eaddrnotavail") ||
            message.contains("cannot assign requested address")
    }

    /**
     * vc300：热点等待的就绪判据之一——目标监听地址是否已真正挂到网卡上。
     * 返回 null = 无法判定（非 manual hotspot / 无已知地址 / 读取失败），交回 AP 状态判据。
     */
    private fun hotspotHostAddressReady(): Boolean? {
        if (!config.wirelessHotspotMode.isManualHotspot) return null
        val expected = lastHotspotInfo?.hostAddress?.hostAddress?.substringBefore('%')
            ?: return null
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var found = false
            while (interfaces.hasMoreElements() && !found) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val candidate = addresses.nextElement().hostAddress?.substringBefore('%')
                    if (candidate != null && candidate == expected) {
                        found = true
                        break
                    }
                }
            }
            found
        } catch (_: Throwable) {
            null
        }
    }

    /** M6h：延时后重跑无线流水线；额度用尽才把失败交给 UI。 */
    private fun scheduleWirelessRetry(generation: Int, error: Throwable) {
        val attempt = wirelessRetryCount.incrementAndGet()
        if (wirelessRestartEpisodeStartedAtMs == 0L) {
            // 纪元起点 = 本轮故障第一次触发重连的时刻；SESSION_ACTIVE 的 total 由此起算。
            wirelessRestartEpisodeStartedAtMs = SystemClock.elapsedRealtime()
        }
        if (attempt > MAX_WIRELESS_RETRIES) {
            debugLog("wireless auto-retry exhausted after $MAX_WIRELESS_RETRIES attempts")
            decide(WirelessDecision.FAIL, WirelessSource.RetryExhausted, error = error)
            // vc250（VC248-09）：快链额度用尽不彻底放弃——低频续试。慢轮不重置计数，
            // 失败后自然再排下一轮，既覆盖 iPhone 中途上车，也不会把 vc246 压掉的自激环放回来。
            // vc250-07：按「本纪元是否出过画」分型——LONG_OUTAGE（用户正在用、曾上屏）
            // 走 4×15s=60s 快慢轮；SLOW_FIRST_DISPLAY（从未上屏）保持 5min，避免空转。
            val renderedThisEpisode = wirelessRenderedThisEpisode
            // vc286：慢轮档位递进（15s→30s→45s→60s 封顶），不再固定 60s。
            // 未出过画（SLOW_FIRST_DISPLAY）保持 5min，避免空转。
            val slowRound = if (renderedThisEpisode) wirelessSlowRetryRound.incrementAndGet() else 0
            val slowDelayMs = if (renderedThisEpisode) {
                WIRELESS_RETRY_DELAY_MAX_MILLIS * minOf(slowRound, 4)
            } else {
                AUTO_RETRY_SLOW_INTERVAL_MILLIS
            }
            debugLog(
                "wireless slow-retry cadence: " +
                    "kind=${if (renderedThisEpisode) "long-outage" else "slow-first-display"} " +
                    "round=$slowRound intervalMs=$slowDelayMs",
            )
            scheduleSlowAutoRetry(
                "wireless",
                { phase == Phase.WIRELESS && generation == wirelessGeneration.get() },
                slowDelayMs,
            ) { restartWireless() }
            return
        }
        val failureClass = lastWirelessFailureClass ?: wirelessFailureClass(error)
        lastWirelessFailureClass = null
        // 1.1.3（8815 230420 定案）**已于 vc168 证伪**，此处仅保留计数与提示骨架：
        // 旧定案认为本族 = "iPhone iAP2 服务未在广播（残留会话）"，但 20261022 的 C8815
        // 日志显示失败时探测 **services=12 且含 iAP2 UUID、iap2Visible=true** —— 服务在广播，
        // 失败点在框架 `BluetoothSocket$SdpHelper.doSdp`。因此：
        // ①文案不再声称"iPhone 服务不可见"（会误导用户与后续排查）；
        // ②保留"重开 iPhone 蓝牙"作为兜底动作（它确实能重置 iPhone 侧 SDP，偶发有效），
        //   但表述为"蓝牙连接不稳定"而非"服务不可见"。
        // 真正的判据交给 openWirelessRfcommSocket() 里的 sdpProbe(pre/round/final)。
        if (failureClass == "SERVICE_DISCOVERY_FAILED") {
            sdpFailureConsecutiveCount++
            if (sdpFailureConsecutiveCount == SDP_FAILURE_HINT_AFTER) {
                onStatus(
                    CarPlayStatus.RecoveryHint(
                        "蓝牙连接不稳定（车机侧服务发现失败，iPhone 侧服务仍在广播）；" +
                            "请稍候重试，或关闭再打开 iPhone 蓝牙",
                    ),
                )
            }
        }
        // M6k11：僵尸族分流——快试 2 次后切长退避；第 3 次起提示用户干预并标记僵尸模式
        //（restartWireless 会据此做本机蓝牙适配器 reset）。非僵尸族走原 4s×attempt 封顶 15s。
        if (failureClass == "IPHONE_ZOMBIE") {
            wirelessZombieMode = true
        }
        val delayMs = if (wirelessStaleSessionBounce) {
            // 1.1.4（B 方案）：残留会话打断轮——强制 ≥8s 蓝牙/热点全断窗口，让 iOS 清态。
            maxOf(STALE_SESSION_BOUNCE_DELAY_MILLIS, minOf(WIRELESS_RETRY_DELAY_BASE_MILLIS * attempt, WIRELESS_RETRY_DELAY_MAX_MILLIS))
        } else if (wirelessZombieMode) {
            when (attempt) {
                1, 2 -> ZOMBIE_FAST_RETRY_MILLIS
                else -> {
                    if (attempt == 3) {
                        onStatus(
                            CarPlayStatus.RecoveryHint(
                                "连接被 iPhone 立即断开，正在长间隔重试；若持续失败，请关开 iPhone 蓝牙后重试",
                            ),
                        )
                    }
                    if (attempt == 3) ZOMBIE_LONG_RETRY_1_MILLIS else ZOMBIE_LONG_RETRY_2_MILLIS
                }
            }
        } else {
            minOf(
                WIRELESS_RETRY_DELAY_BASE_MILLIS * attempt,
                WIRELESS_RETRY_DELAY_MAX_MILLIS,
            )
        }
        debugLog(
            "wireless link dropped (${error.message ?: error.javaClass.simpleName}) " +
                "class=$failureClass; " +
                "auto-retry $attempt/$MAX_WIRELESS_RETRIES " +
                "RESTART gen=$generation backoff=${delayMs}ms" +
                if (wirelessStaleSessionBounce) " stale-session-bounce" else "",
        )
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@postDelayed
                }
                restartWireless()
            },
            delayMs,
        )
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed) return false
        val generation = wirelessGeneration.get()
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        // vc201 代次护栏：openTunnel 期间若发生整代重启/关闭，本流已属上一代——
        // 此时装进 wirelessTunnelChannel 会落在 teardown 快照（先快照再清字段）之后，
        // 逃逸本轮关闭，残留 channel 与新代互抢。
        if (closed || generation != wirelessGeneration.get()) {
            closeBestEffort("stale tunneled iAP2 link") { channel.close() }
            return false
        }
        wirelessTunnelChannel = channel
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            // vc250-06：媒体面成立时隧道（控制面）死亡只降级，
                            // 不拆栈、不消耗重试、不 Failed。
                            if (wirelessMediaProof.hasRenderedFrame(generation)) {
                                debugLog(
                                    "wireless iAP2 tunnel closed while media is live; " +
                                        "degrading instead of tearing down",
                                )
                                if (!wirelessActiveReported.get()) {
                                    completeWirelessHandoffDegraded(
                                        generation,
                                        handoffReason = "tunnel-closed-media-alive",
                                    )
                                }
                            } else {
                                onStatus(
                                    CarPlayStatus.Failed(
                                        "Wireless iAP2 tunnel closed",
                                        CarPlayFailureClass.TunnelTimeout,
                                    ),
                                )
                            }
                        // 1.0.29：tunnel 路径未传 phoneOnAirPlay，看门狗不会触发；穷尽分支兜底。
                        Iap2WirelessControlTerminal.AIRPLAY_STALLED ->
                            onStatus(
                                CarPlayStatus.Failed(
                                    "Wireless iAP2 tunnel stalled",
                                    CarPlayFailureClass.TunnelTimeout,
                                ),
                            )
                        // vc252：tunnel 路径未开凭证锚点（credentialJoinTimeoutMillis=0），
                        // 该终态不会出现；穷尽分支保留，避免枚举新增后编译漏判。
                        // vc253：静默判据同样只在蓝牙承载路径开启（authSilenceTimeoutMillis=0）。
                        Iap2WirelessControlTerminal.CREDENTIAL_NO_JOIN,
                        Iap2WirelessControlTerminal.CREDENTIAL_NO_AUTH ->
                            onStatus(
                                CarPlayStatus.Failed(
                                    "Wireless iAP2 tunnel credential anchor timed out",
                                    CarPlayFailureClass.TunnelTimeout,
                                ),
                            )
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        // vc250-06：媒体面成立时隧道控制面异常只降级，不 Failed。
                        if (error !is Error && wirelessMediaProof.hasRenderedFrame(generation)) {
                            debugLog(
                                "tunneled iAP2 control failed while media is live: " +
                                    "${error.message}; degrading instead of tearing down",
                            )
                            if (!wirelessActiveReported.get()) {
                                completeWirelessHandoffDegraded(
                                    generation,
                                    handoffReason = "tunnel-failed-media-alive",
                                )
                            }
                        } else {
                            debugLog("tunneled iAP2 control failed", error)
                            onStatus(
                                CarPlayStatus.Failed(
                                    error.message ?: error.javaClass.simpleName,
                                    error.carPlayFailureClass(CarPlayFailureClass.TunnelTimeout),
                                ),
                            )
                        }
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            /**
             * vc250-08：首个 AirPlay TCP accept 即武装 handoff 锚点看门狗。
             * 旧路径只在 disableBluetooth 时武装，iPhone 只建 TCP 不发该命令时
             * 永远落不到 SESSION_ACTIVE（僵尸），此处补独立锚点。
             */
            override fun onControlConnectionAccepted(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                // vc250-05：TCP 到达事实（含缓存凭证入网），供 summary credential 三态。
                CarPlayStageLog.airPlayTcpAccepted()
                // vc267：记录 warm 判据时刻（进程级，下一次连接 10 分钟内跳过入网闸门）。
                lastAirPlayTcpAtElapsedMillisShared = SystemClock.elapsedRealtime()
                debugLog(
                    "wireless AirPlay TCP accepted; handoff anchor armed " +
                        "timeout=${WIRELESS_TCP_ANCHOR_TIMEOUT_MILLIS}ms gen=$generation",
                )
                armWirelessTcpAnchorWatchdog(generation)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                // vc152：重投成功（自动邀请或 iPhone 自发 0x4300）即撤销邀请定时器。
                peerEndInvitationArmed.set(false)
                wirelessControlProof.activate(generation, session)
                wirelessMediaProof.activate(generation, session)
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessControlProof.end(generation, session)
                wirelessMediaProof.end(generation, session)
                handlePreStreamEof(generation, session)
                // vc152：本代会话曾激活 + 对端正常结束（微信语音挂断等）→ 自动邀请重投。
                // 用户断开（disconnect）走整代拆栈 → generation 失配/isStale 已拦。
                if (wirelessActiveReported.get() || wirelessDegradedActive.get()) {
                    armPeerEndInvitation(generation)
                }
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessMediaProof.rendered(generation, session)
                // vc250-07：出过画事实（同源媒体面），供慢轮节奏分型。
                wirelessRenderedThisEpisode = true
                // vc250-06：媒体面成立；若控制面已鉴权，则补上热点确认（旧 confirmed 语义）。
                if (wirelessControlProof.hasAuthenticatedControl(generation)) {
                    wirelessMediaProof.confirm(generation)
                }
                // vc202（竞品 legacy handoff 对照）：已渲染帧 = 媒体面成立（vc250-06 拆分后
                // 这不等同控制面鉴权；控制面是否鉴权另由 wirelessControlProof 记录）。
                // handoff 已请求但隧道未就绪时立即按 degraded 完成，不再死等 45s 看门狗；
                // 隧道继续在后台建立（onWirelessTunnelReady → maybeCompleteWirelessHandoff
                // 走升级分支），不丢隧道。开关关闭则回退 vc201 行为。
                if (
                    config.wirelessEarlyHandoffOnFrame &&
                    wirelessHandoffRequested.get() &&
                    !wirelessTunnelReady.get() &&
                    !wirelessActiveReported.get() &&
                    !wirelessDegradedActive.get()
                ) {
                    Thread(
                        { completeWirelessHandoffDegraded(generation, handoffReason = "airplay-frame") },
                        "xcertplay-wireless-early-handoff",
                    ).apply { isDaemon = true }.start()
                }
            }
        }

    /**
     * vc152（K60 21:03:49 实锤）：无线会话被 iPhone 主动优雅结束后自动邀请重投。
     * 背景：微信语音挂断后 iPhone 走有秩序 teardown 结束整会话，但不发 0x4300，而车机
     * 的 0x4301 是应答式（CARPLAY_AVAILABILITY 分支）→ 干等永不重投。此处经存活的蓝牙
     * RFCOMM 控制环直发 0x4301 邀请。
     *
     * vc304（等待链收敛）：节奏改为 **1s 首拍 → 1.5s 次拍 → 4s 整代重建**（原为 3s/30s
     * 两拍后转 3×10s 探测，共空转 93s）。实测依据：换代 bringUp 1.6s、拆栈到首个 TCP
     * 0.52s；而手机重新入网后旧控制链路上的邀请 3 拍全部无应答 ⇒ 邀请只保留「对端还在、
     * 一次就回」的无损快路径，答不上来立即换代（新代自然会重发 0x5703 凭证，比空等更能
     * 催促 iPhone 入网）。重投成功（onSessionActive 复位 armed）、代更替（generation 判定）
     * 或 RFCOMM 断即终止。用户断开路径不受影响。
     */
    private fun armPeerEndInvitation(generation: Int) {
        if (!peerEndInvitationArmed.compareAndSet(false, true)) return
        peerEndInvitationsSent.set(0)
        Thread(
            {
                val client = wirelessControlClient ?: run {
                    peerEndInvitationArmed.set(false)
                    return@Thread
                }
                var delayMillis = PEER_END_INVITATION_FIRST_DELAY_MILLIS
                while (true) {
                    try {
                        Thread.sleep(delayMillis)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                    if (closed || phase != Phase.WIRELESS) return@Thread
                    if (generation != wirelessGeneration.get()) return@Thread
                    if (activeSession != null) return@Thread // 已重投成功
                    if (peerEndInvitationsSent.get() >= PEER_END_INVITATION_MAX_SENDS) {
                        debugLog(
                            "wireless peer-end recovery: $PEER_END_INVITATION_MAX_SENDS invitations unheeded " +
                                "→ restarting generation (vc304)",
                        )
                        restartWireless()
                        return@Thread
                    }
                    if (!client.sendCarPlayInvitation()) {
                        debugLog("wireless peer-end invitation failed (RFCOMM gone) → restarting generation (vc304)")
                        restartWireless()
                        return@Thread
                    }
                    val sent = peerEndInvitationsSent.incrementAndGet()
                    debugLog(
                        "wireless peer-end invitation #$sent sent (0x4301 after session ended)",
                    )
                    delayMillis = PEER_END_INVITATION_RETRY_DELAY_MILLIS
                }
            },
            "xcertplay-peer-end-invite",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 1.0.29（EU5 修复）：wired 建立期看门狗的解除信号——RTSP 激活或首帧渲染即撤销看门狗。 */
    private fun wiredSessionListener(): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                wiredStreamSeen = true
                wiredEverStreamed = true
                wiredStreamedDeviceId = wiredOpenDeviceId
                // 1.1.4：当前 NCM 组跑通了流——清掉轮换避让，保持优选。
                ncmAvoidControlId = null
                sessionListener.onSessionActive(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                wiredStreamSeen = true
                wiredEverStreamed = true
                wiredStreamedDeviceId = wiredOpenDeviceId
                ncmAvoidControlId = null
                sessionListener.onVideoFrameRendered(session)
            }

            /**
             * 1.1.5/fe6t3(vc84)：Siri 后无声恢复（FE-6 fe6t2 日志实证）。iPhone 进 Siri 拆
             * 媒体音频流、Siri 结束后不再重发音频 SETUP，音乐静音到用户手动断开。会话级
             * close() 会先给 iPhone 补发 best-effort TEARDOWN（iOS 会当真结束会话）→ 控制环
             * 退出 → 既有 wired 自动重试链（4s×attempt 封顶 15s、额度 5 次）全量重建，
             * 音频 SETUP 随之恢复。仅挂 wired：无线链路无此复现，且无线收会话语义不同。
             */
            override fun onSiriAudioStall(session: AirPlaySession) {
                if (closed || activeSession !== session) return
                debugLog("siri audio stall recovery: closing session to re-establish media stream")
                onStatus(
                    CarPlayStatus.RecoveryHint("Siri 后音频未恢复，正在自动重建会话（约 5 秒）"),
                )
                session.close()
            }
        }

    /**
     * M6k23：未建流阶段控制流异常断开计数 → 连续 ≥2 次触发整代重启。
     * 判定条件（全部满足才计数）：本代未激活、未降级、无渲染帧；结束原因为 peer EOF
     * 或解密失败（8815 实测 pair-verify 循环的特征）。重启走既有 RESTART 链
     * （closeWirelessStack + scheduleWirelessRetry），gen 单调递增/退避语义不变。
     */
    private fun handlePreStreamEof(generation: Int, session: AirPlaySession) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) return
        if (wirelessActiveReported.get() || wirelessDegradedActive.get()) return
        if (wirelessMediaProof.hasRenderedFrame(generation)) return
        val reason = session.lastCloseReason.lowercase()
        if (!reason.contains("eof") && !reason.contains("decrypt failed")) return
        preStreamEofCount++
        debugLog(
            "wireless pre-stream control EOF #$preStreamEofCount " +
                "(reason=${session.lastCloseReason} activeStreams=${session.activeStreams.size})",
        )
        if (preStreamEofCount < PRE_STREAM_EOF_RESTART_THRESHOLD) return
        preStreamEofCount = 0
        debugLog(
            "wireless pre-stream EOF x$PRE_STREAM_EOF_RESTART_THRESHOLD; " +
                "failing generation=$generation for full restart",
        )
        Thread(
            {
                if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) return@Thread
                // M6k24（C 方案）：重启保留热点附着，iPhone 不掉网，TCP 重试即刻可达。
                decide(WirelessDecision.CLOSE_STACK, WirelessSource.PreStreamEof, keepHotspot = true)
                scheduleWirelessRetry(generation, IOException("pre-stream control peer EOF (pair-verify loop)"))
            },
            "xcertplay-prestream-eof",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        wirelessAuthenticated.set(true)
        wirelessControlProof.authenticated(generation)
        // vc250-06：控制面刚鉴权；若媒体面已渲染，补上热点确认（旧 confirmed 语义）。
        if (wirelessMediaProof.hasRenderedFrame(generation)) {
            wirelessMediaProof.confirm(generation)
        }
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        wirelessEverActive.set(true) // vc151：watchdog 放行依据（本代 handoff 实际完成过）
        val generation = wirelessGeneration.get()
        val attempt = wirelessRetryCount.getAndSet(0)
        wirelessSlowRetryRound.set(0) // vc286：会话成立，慢轮档位回到最短档
        // M6k11：会话真建立即退出僵尸模式（后续断链重新按普通族分类）。
        wirelessZombieMode = false
        // M6k23：会话建立即清未建流 EOF 计数。
        preStreamEofCount = 0
        // 1.0.15：会话真建立 → 本代下发的信道记为「验证过能连上」，下次读不到信道时复用。
        // vc274：仅写入实测/配置信道；默认值不算验证（否则默认值会被反复自我确认）。
        if (lastAdvertisedChannelObserved) {
            ConfirmedChannelMemory.remember(
                context,
                if (config.wirelessHotspotMode == WirelessHotspotMode.RELAY) {
                    ConfirmedChannelMemory.ROLE_RELAY
                } else {
                    ConfirmedChannelMemory.ROLE_CAR
                },
                lastAdvertisedHotspotChannel,
            )
        } else {
            debugLog(
                "wireless channel memory skipped: advertised=${lastAdvertisedHotspotChannel} " +
                    "is a default (assumed), not an observed value",
            )
        }
        logWirelessSessionActive(generation, attempt)
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 1.1.6(vc76)：媒体断供看门狗回调（形态 A 僵尸会话对策）。8815 整夜实测：iPhone
     * 野蛮退出 CarPlay 不发 teardown——媒体 UDP 静默死、BT iAP2 心跳仍活、RTSP 会话挂
     * ended=false 傻等 4 分钟+（若 iPhone 走了文明的 teardown 路径，AIRPLAY_STALLED
     * 看门狗已覆盖，无需本机制）。判死依据在媒质层（sink 视频静默 60s，音频不参与——
     * vc290：用户可手动把音频切走），回收动作
     * 在编排层：媒体面成立（有渲染帧或已上报 active/degraded）时按链路掉线整代重启，热点保留（M6k24）。
     * 代次护栏防与控制环并发调度竞态；有线会话（WIRELESS 判定天然为假）不受影响。
     */
    fun notifyWirelessMediaStall() {
        val generation = wirelessGeneration.get()
        Thread(
            {
                if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                    return@Thread
                }
                // vc250-06：活性只看媒体面。旧门槛要求「正式/降级 SESSION_ACTIVE 已上报」，
                // 而 handoff 未完成时会话既非 active 也非 degraded → 断流后无人回收（僵尸）。
                if (
                    !wirelessActiveReported.get() &&
                    !wirelessDegradedActive.get() &&
                    !wirelessMediaProof.hasRenderedFrame(generation)
                ) {
                    return@Thread
                }
                // vc201：判活到拆栈必须原子化——视频静默回调与 AIRPLAY_STALLED 同帧到达时，
                // 无 CAS 会双拆栈、双 retry（见 mediaStallRecoveryInFlight 声明处注释）。
                if (!mediaStallRecoveryInFlight.compareAndSet(false, true)) return@Thread
                try {
                    debugLog("wireless media stall watchdog: video silent with session active; recovering")
                    val error = IOException("wireless media stall: no video packets while session active")
                    lastWirelessFailureClass = wirelessFailureClass(error)
                    decide(WirelessDecision.CLOSE_STACK, WirelessSource.MediaStall, keepHotspot = true)
                    if (isRetryableWirelessFailure(error)) {
                        scheduleWirelessRetry(generation, error)
                    } else {
                        decide(WirelessDecision.FAIL, WirelessSource.MediaStall, error = error)
                    }
                } finally {
                    mediaStallRecoveryInFlight.set(false)
                }
            },
            "xcertplay-media-stall",
        ).apply { isDaemon = true; start() }
    }

    /**
     * vc295：AP 模式下「iPhone 已离开热点」的快速检测。
     *
     * 起因（C8815 15:40 实测）：iPhone 关掉 Wi-Fi 后不发 FIN、蓝牙 iAP2 还活着，App 唯一的
     * 感知通道是 [AndroidMediaSink] 的 300s 视频断供看门狗 —— 于是界面挂着「无线 CarPlay
     * 已激活」整整 5 分 45 秒（15:39:56 激活 → 15:45:41 才拆栈），用户以为状态卡死。
     *
     * 判据只读 /proc/net/arp（vc294 起只认 ATF_COM 有效项）：本机是 AP 时，iPhone 在网的
     * 唯一证据就是它的 ARP 表项有效；连续 [STA_ABSENT_CONFIRM_MILLIS] 看不到任何有效对端
     * 即判定对端已离开，走与断供看门狗**同一条**恢复路径（保热点 + 代次护栏 + 自动重试），
     * 把「5 分钟」压到约 20 秒。不碰音频判据，也不参与「用户手动切音频」那种合法状态。
     *
     * 保守边界：ARP 表不可读（K60 安卓14 的 FileNotFoundException）或读数为 null 时
     * **直接退出巡检**，把判死权交回 300s 看门狗，绝不因为「读不到」而拆栈。
     */
    private fun startWirelessStaWatch(generation: Int) {
        if (staWatchGeneration.get() == generation) return
        staWatchGeneration.set(generation)
        Thread(
            {
                var absentSinceMs = 0L
                while (true) {
                    try {
                        Thread.sleep(STA_WATCH_INTERVAL_MILLIS)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                    if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                        return@Thread
                    }
                    val associated = hotspotStaArpState() ?: return@Thread
                    if (associated) {
                        absentSinceMs = 0L
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (absentSinceMs == 0L) {
                        absentSinceMs = now
                        continue
                    }
                    if (now - absentSinceMs < STA_ABSENT_CONFIRM_MILLIS) continue
                    if (
                        !wirelessActiveReported.get() &&
                        !wirelessDegradedActive.get() &&
                        !wirelessMediaProof.hasRenderedFrame(generation)
                    ) {
                        return@Thread
                    }
                    // 与断供看门狗共用同一条拆栈闸门：两者同刻触发时只允许一次拆栈+一次重试。
                    if (!mediaStallRecoveryInFlight.compareAndSet(false, true)) return@Thread
                    try {
                        debugLog(
                            "wireless sta watch: iPhone gone from hotspot subnet for " +
                                "${now - absentSinceMs}ms; recovering",
                        )
                        val error = IOException("wireless sta gone: iPhone left the car network")
                        lastWirelessFailureClass = wirelessFailureClass(error)
                        decide(WirelessDecision.CLOSE_STACK, WirelessSource.StaWatch, keepHotspot = true)
                        if (isRetryableWirelessFailure(error)) {
                            scheduleWirelessRetry(generation, error)
                        } else {
                            decide(WirelessDecision.FAIL, WirelessSource.StaWatch, error = error)
                        }
                    } finally {
                        mediaStallRecoveryInFlight.set(false)
                    }
                    return@Thread
                }
            },
            "xcertplay-sta-watch",
        ).apply { isDaemon = true; start() }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get() ||
                    // vc151（K60 热点实测）：本代 handoff 实际完成过（会话曾激活/降级态）时，
                    // 会话 ended（iPhone 偶发无 goodbye 强断）会把 reported/degraded 复位，
                    // watchdog 到期会误判「handoff 超时」把活着的蓝牙 iAP2 一并拆栈，
                    // 毁掉 iPhone 短窗口内的自动重投机会。曾激活即放行，断流回收交给
                    // 媒体断供看门狗（notifyWirelessMediaStall）与既有 auto-retry 链。
                    wirelessEverActive.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get() ||
                            wirelessEverActive.get() // vc151：与 postDelayed 判定同款放行
                        ) {
                            return@Thread
                        }
                        // 合并方案A与作者 hasRenderedFrame 判定：以是否确已渲染视频帧作为分类依据。
                        // - 已渲染帧：iPhone 已建立 Wi-Fi A/V 但始终未请求 type-130 iAP2 隧道，属合法降级场景，
                        //   不再拆栈/失败，保持「蓝牙 iAP2 控制 + Wi-Fi A/V」继续运行，避免可见重连循环。
                        // - 未渲染帧：会话并未真正建立，走作者逻辑拆栈并 fail。
                        if (wirelessMediaProof.hasRenderedFrame(generation)) {
                            completeWirelessHandoffDegraded(
                                generation,
                                handoffReason = "watchdog-timeout",
                            )
                            return@Thread
                        }
                        // 1.0.13：handoff 超时不再终态失败（MX6DQ 实测弹「画面握手未完成」后
                        // 直接躺平，而 First TCP 超时靠重连自愈）——与控制环 catch 同款分类：
                        // 先分类留证，再拆栈，可重试族走 RESTART 链；postDelayed 里的
                        // generation 护栏保证控制环并发调度时后到者作废，不会双开新一代。
                        val error = CarPlayClassifiedException(
                            "Wireless CarPlay handoff timed out",
                            CarPlayFailureClass.TunnelTimeout,
                        )
                        lastWirelessFailureClass = wirelessFailureClass(error)
                        decide(WirelessDecision.CLOSE_STACK, WirelessSource.HandoffWatchdog)
                        if (isRetryableWirelessFailure(error)) {
                            scheduleWirelessRetry(generation, error)
                        } else {
                            decide(WirelessDecision.FAIL, WirelessSource.HandoffWatchdog, error = error)
                        }
                    },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    /**
     * vc250-08：交棒看门狗的第二个锚点——首个 AirPlay TCP accept（不再只依赖
     * disableBluetooth）。iPhone 只建 TCP 不发该命令时，旧路径的 45s handoff 看门狗
     * 从未装弹，maybeCompleteWirelessHandoff 也无从触发，会话永远落不到
     * SESSION_ACTIVE；而媒体断供看门狗要求 active/degraded 才回收，断流后无人拆栈
     * （僵尸）。本看门狗只补「成功收口」：到期已有渲染帧 → 按 degraded 完成（复用
     * [completeWirelessHandoffDegraded]，不拆栈、不消耗重试，隧道随后补齐仍可升级）。
     * 未渲染帧只在日志留证，失败收口继续交给既有链（handoff/first-TCP/pre-stream EOF），
     * 不改既有失败语义。
     */
    private fun armWirelessTcpAnchorWatchdog(generation: Int) {
        // disableBluetooth 已到 → 既有 45s handoff 看门狗接管，避免双收口。
        if (wirelessHandoffRequested.get()) return
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get() ||
                    wirelessDegradedActive.get() ||
                    wirelessEverActive.get()
                ) {
                    return@postDelayed
                }
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessHandoffRequested.get() ||
                            wirelessActiveReported.get() ||
                            wirelessDegradedActive.get() ||
                            wirelessEverActive.get()
                        ) {
                            return@Thread
                        }
                        if (!wirelessMediaProof.hasRenderedFrame(generation)) {
                            debugLog(
                                "wireless handoff anchor: TCP up ${WIRELESS_TCP_ANCHOR_TIMEOUT_MILLIS}ms " +
                                    "without disableBluetooth or rendered frame; " +
                                    "leaving recovery to existing chains gen=$generation",
                            )
                            return@Thread
                        }
                        debugLog(
                            "wireless handoff anchor: frames rendered but no disableBluetooth " +
                                "within ${WIRELESS_TCP_ANCHOR_TIMEOUT_MILLIS}ms; " +
                                "completing degraded reason=tcp-anchor gen=$generation",
                        )
                        completeWirelessHandoffDegraded(generation, handoffReason = "tcp-anchor")
                    },
                    "xcertplay-wireless-tcp-anchor",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_TCP_ANCHOR_TIMEOUT_MILLIS,
        )
    }

    /**
     * degraded 完成 handoff 的共用收口（原 watchdog 内联块抽出，vc202）。
     * 语义不变：保留蓝牙 iAP2 + Wi-Fi A/V，不补 type-130 隧道；先置位再上报防竞态。
     * [handoffReason] 落 SESSION_ACTIVE 日志（airplay-frame=已渲染帧提前判据 /
     * watchdog-timeout=45s 看门狗兜底 / tcp-anchor=首个 TCP accept 锚点兜底 /
     * control-closed-media-alive、control-failed-media-alive、tunnel-closed-media-alive、
     * tunnel-failed-media-alive=vc250-06 控制面死亡但媒体面存活），供外测区分完成路径与耗时。
     */
    private fun completeWirelessHandoffDegraded(generation: Int, handoffReason: String) {
        // M6g：先置位再上报，避免与控制环超时分支/提前判据并发双发。
        if (!wirelessDegradedActive.compareAndSet(false, true)) return
        wirelessEverActive.set(true) // vc151：降级态同样证明 handoff 完成过
        // M6h：会话已经可用（A/V 在跑），自动重试额度重新给满。
        val attempt = wirelessRetryCount.getAndSet(0)
        wirelessSlowRetryRound.set(0) // vc286：降级态收口同样重置慢轮档位
        wirelessZombieMode = false // M6k11：degraded 收口同样退出僵尸模式
        preStreamEofCount = 0 // M6k23：degraded 收口同样清未建流 EOF 计数
        wirelessDegradedAtMs = SystemClock.elapsedRealtime()
        logWirelessSessionActive(generation, attempt, degraded = true, handoffReason = handoffReason)
        debugLog(
            "handoff degraded: keeping Bluetooth iAP2 + Wi-Fi A/V " +
                "(hasRenderedFrame, no type-130 iAP2 tunnel, reason=$handoffReason) " +
                // vc275（误导项标注）：这是一条正常路径，不是故障——当前实验档使用 MFi 身份，
                // 不建 type-130 隧道；A/V 与画面上屏不受影响，仅控制面留在蓝牙。
                "note=expected(experimental-MFi-identity; control-on-BT, media-on-WiFi)",
        )
        onStatus(CarPlayStatus.WirelessActive)
    }

    /** M6k20：first-TCP 看门狗。触发条件与 handoff 看门狗同代次护栏；TCP 已有连接或已激活则静默过期。 */
    private fun armFirstTcpWatchdog(
        generation: Int,
        timeoutMillis: Long = FIRST_TCP_TIMEOUT_MILLIS,
        // vc289：出过画的链路（重连场景）用短宽限——中继模式 STA 常驻关联，
        // credential-no-join 判据不触发，旧值 60s+35s 会让每次重试盲等 95s。
        graceMillis: Long = FIRST_TCP_GRACE_MILLIS,
    ) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    // vc246（两处误报修正）：链路从未交出的阶段不启动、不判死、不提示 WLAN。
                    bluetoothLinkReadyGeneration != generation ||
                    wirelessActiveReported.get() ||
                    wirelessDegradedActive.get() ||
                    // handoff 流程有自己的 45s 看门狗与降级判定，互不干预。
                    wirelessHandoffRequested.get()
                ) {
                    return@postDelayed
                }
                val accepted = airPlayServer?.acceptedConnectionCount() ?: 0
                if (accepted > 0) return@postDelayed
                debugLog(
                    "first TCP watchdog: no iPhone connection within " +
                        "${timeoutMillis}ms; failing generation=$generation",
                )
                // 1.0.0-demo：中继模式（形态B）单独标记——iPhone 收到凭证却零 TCP。
                val relaySuspect =
                    config.transport == CarPlayTransport.WIRELESS &&
                        config.wirelessHotspotMode == WirelessHotspotMode.RELAY
                // 1.0.29（011741 三样本结论）：0x5703 已发 + 0 TCP 的首要嫌疑 = iPhone WLAN
                // 开关关闭（iOS 保护性放弃后表现为 Wi-Fi 图标变白）或 join 未发生，AP 隔离降为
                // 次要嫌疑——主动给用户可操作的一步提示，替代盲等。
                // 1.1.29（CatPlay 0.68.4 K60 实测迁移，20261022）：iPhone 入网耗时 1.3~40s
                // 波动的两大实证因素——①iOS 熄屏时暂停 Wi-Fi 扫描/热点自动加入（亮屏后秒连，
                // 竞品两轮实测 44s vs 8.6s）；②「私有无线局域网地址」开启时 iOS 对热点做
                // 完整信任评估（用户实测体感相关，与竞品静态分析互证）。写进提示文案。
                // vc174（C8815 中继/车机热点实测纠正）：上述两条经复测**不成立**——
                // 亮屏=单样本推断，私有地址两轮复测与入网耗时无强关联（关 37s / 开 1.3s），
                // 且两条都无法在 iPhone 上换来一步动作。入网慢的真因定案是
                // **热点无互联网出口 → iOS 需用户确认**（提示「无互联网连接」，须点
                // 「仍要加入」才继续；C8815 首次入网 76.5s 几乎全耗在这里）。
                // 提示改为可直接照做的一步：点进热点 → 选「仍要加入」。
                onStatus(
                    CarPlayStatus.RecoveryHint(
                        if (relaySuspect) {
                            "连接停滞：请打开 iPhone 的 WLAN 开关（若已开请关闭再打开）；中继热点无互联网，iPhone 入网会等确认——请在「无线局域网」页点进该热点并选「仍要加入」，正在自动重试"
                        } else {
                            "连接停滞：车机热点无互联网，iPhone 入网较慢——请在 iPhone「无线局域网」页点进车机热点，看到「无互联网连接」时选「仍要加入」，正在自动重试"
                        },
                    ),
                )
                if (relaySuspect) {
                    debugLog(
                        "backend=relay join-stagnation: credential sent but 0 TCP within window " +
                            "(top suspect: iPhone WLAN off / join not happened)",
                    )
                }
                // 1.1.5（提速）：宽限复查。10-12 实机三样本（Manual 与 Relay 各一次）：
                // iPhone 实际入网 +25~35s，0x5703 重发预算 12×5s=60s 仍在推进，
                // 30s 一刀切 teardown 恰好杀在 iPhone 入网/握手途中（差 2~5s 就能成），
                // 且 teardown 拆热点会踢掉 iPhone 关联，越重试越慢。
                // 改为：fire 时只提示，再给 FIRST_TCP_GRACE_MILLIS 窗口；
                // 窗口内任一 TCP 进入即静默放行，仍为 0 才走 teardown+fail。
                // vc306：凭证已下发 + 车机热点 ⇒ 放宽到 45s（合计 25s+45s=70s）。
                // 判据三条同时成立才放宽：非中继、本代凭据确实发过、MANUAL 模式。
                // RELAY 下 STA 常驻关联，凭证已发+零 TCP 仍是「iPhone 不在」的强信号，
                // 保持 vc289 的 10s（原 graceMillis）不变。
                val effectiveGraceMillis = if (
                    !relaySuspect &&
                    wirelessCredentialIssuedGeneration == generation &&
                    config.wirelessHotspotMode.isManualHotspot
                ) {
                    FIRST_TCP_GRACE_CREDENTIAL_MILLIS
                } else {
                    graceMillis
                }
                mainHandler.postDelayed(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get() ||
                            wirelessDegradedActive.get() ||
                            wirelessHandoffRequested.get()
                        ) {
                            return@postDelayed
                        }
                        val graceAccepted = airPlayServer?.acceptedConnectionCount() ?: 0
                        if (graceAccepted > 0) {
                            debugLog(
                                "first TCP watchdog: connection arrived during grace window; " +
                                    "continuing generation=$generation",
                            )
                            return@postDelayed
                        }
                        debugLog(
                            "first TCP watchdog: still 0 TCP after ${timeoutMillis}ms+" +
                                "${effectiveGraceMillis}ms grace; failing generation=$generation " +
                                "credentialIssued=${wirelessCredentialIssuedGeneration == generation} " +
                                "manualHotspot=${config.wirelessHotspotMode.isManualHotspot}",
                        )
                        Thread(
                            {
                                if (closed || generation != wirelessGeneration.get()) return@Thread
                                if (!relaySuspect) {
                                    // 1.1.4（B 方案）：先拆蓝牙引导链——RFCOMM 活着 iPhone 就维持
                                    // 残留会话态；配合 retry 的 ≥8s 间隔模拟「断开→重连」。
                                    debugLog(
                                        "first TCP timeout: bouncing Bluetooth bootstrap to clear " +
                                            "stale iPhone CarPlay session",
                                    )
                                    wirelessStaleSessionBounce = true
                                    closeBluetoothBootstrapTransport()
                                }
                                // 1.1.5（提速）：失败重试不拆热点（M6k24 C 方案路径）——
                                // 车机热点保持 + iPhone 关联不掉，下一轮 0x5703 即刻秒连；
                                // 旧版 0.2.6 即为热点常开、iPhone 挂网不掉线的模式。
                                decide(WirelessDecision.CLOSE_STACK, WirelessSource.FirstTcp, keepHotspot = true)
                                // vc253：判死瞬间的热点 STA 关联快照（与 credential-issued 前后对照）。
                                logHotspotStaArp("first-tcp-timeout")
                                decide(WirelessDecision.FAIL, WirelessSource.FirstTcp, error =
                                    IOException(
                                        (if (relaySuspect) {
                                            "First TCP timeout (relay): iPhone never connected over the relay Wi-Fi " +
                                                "(iPhone WLAN off / join not happened; AP isolation secondary)"
                                        } else {
                                            "First TCP timeout: iPhone never connected to the car network " +
                                                "(stale mDNS cache / Wi-Fi not joined / AP isolation)"
                                        }),
                                    ),
                                    failureClass = CarPlayFailureClass.CredTimeout,
                                )
                            },
                            "xcertplay-first-tcp-timeout",
                        ).apply {
                            isDaemon = true
                            start()
                        }
                    },
                    effectiveGraceMillis,
                )
            },
            timeoutMillis,
        )
    }

    /**
     * vc289（C8815 13:26:14 定案）：本函数会被两条线程同时进入——First TCP 看门狗判死与
     * bring-up 失败各拆一次栈，两次 teardown 的 step=4 时间戳交叠（.330 与 .337）。
     * vc288 只保证「同一次 teardown 内不重复关」，挡不住并发：先到的一路把 bluetoothStream
     * 置空并关掉流，后到的一路看到 stream==null，却仍握着 [bluetoothSocket] 引用，
     * 于是对同一 socket 再 close 一次 → 4.1 的 JNI close 二次释放 native 堆 →
     * SIGSEGV(fault addr deadbaad)/dlfree，崩溃线程 pool-2-thread-1。故此处串行化，
     * 让「取引用 + 置空 + 关闭」成为一个原子段落。
     */
    @Synchronized
    private fun closeBluetoothBootstrapTransport() {
        val activeStream = bluetoothStream
        bluetoothStream = null
        // vc288（C8815 13:01:55 定案）：RFCOMM 流内部持有并关闭同一个 BluetoothSocket，
        // 旧实现先关流、再对同一 socket 补一次 close()，Android 4.1 的 JNI close 会二次
        // 释放 native 内存（Fatal signal 11 / INVALID HEAP ADDRESS，栈顶 dlfree→free，
        // 线程 xcertplay-first，紧跟本函数 10ms）。流存在时 socket 所有权归流，控制器不再插手；
        // 仅在无流（例如引导期只建了 socket）时才由控制器直接关。
        if (activeStream != null) {
            closeBestEffort("wireless RFCOMM stream") { activeStream.close() }
            bluetoothSocket = null
            return
        }
        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    /**
     * Lite：MANUAL / MANUAL_IPV4（车机自带热点）或 RELAY（双 client 接入随身WiFi）。
     * RELAY 复用 manualHotspotSsid/Passphrase 字段作外部网络凭据；下游 0x5703/监听/Bonjour
     * 全部吃返回的 WirelessHotspotInfo，无需感知网络是建出来的还是加入的。
     */
    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        // M6k24（C 方案）：上轮未建流 EOF 重启保留的热点附着仍健康 → 直接复用，
        // 跳过重新附着（最慢一步），iPhone 也不掉网、TCP 重试即刻可达。
        retainedHotspot?.let { (manager, info) ->
            retainedHotspot = null
            hotspot = manager
            lastHotspotInfo = info
            debugLog(
                "wireless hotspot reused from previous generation ssid=${info.ssid} " +
                    "host=${info.hostAddress?.let(::hostAddressText) ?: "unknown"}",
            )
            observeHotspotIdentity("reuse", generation, info)
            return info
        }
        val hotspotMode = config.wirelessHotspotMode
        // vc157：Wi-Fi Direct 升为一级模式——选此模式直接建 P2P GO，绕过 SoftAP 判死逻辑
        //（不再依赖「热点恰好没开」触发兜底的副作用）。retained 复用已在上方统一处理
        //（P2P 组支持 keepHotspotForReuse，跨代保留同样走复用快路径）。
        if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            return startP2pHotspot(generation, "mode selected")
        }
        if (
            hotspotMode.isManualHotspot &&
            CarHotspotStatus.isEnabled(appContext) == false
        ) {
            // OEM 可能魔改 AP 状态值；把原始值 + 可读名打进日志便于诊断 4.1 定制 ROM
            //（vc299：`state=11(disabled)`，避免 `state=11 (AOSP 13=enabled)` 被读成 11 即 enabled）。
            val rawApState = CarHotspotStatus.rawState(appContext)
            debugLog("car hotspot AP state=${rawApState ?: "unknown"}(${CarHotspotStatus.rawStateLabel(rawApState)})")
            // vc93：OEM 状态语义失灵兜底（235233 实锤 ECARX state=11 但 ap0 存活带地址）——
            // 状态判死前查 AP 专用接口，接口活着就不阻塞连接。
            if (!CarHotspotStatus.hasDedicatedApInterface()) {
                // vc157：SoftAP 判死时不再自动切 P2P GO——Wi-Fi Direct 已是一级模式，
                // 需要即用设置页显式选择（保留原始错误，避免自动切换无线电角色扰动主路径）。
                // vc299：改用专用异常，编排层转「等待热点就绪」慢轮重试，不再落 Failed 终态。
                throw CarHotspotOffException(
                    "The car hotspot is off. Turn it on in the car settings and connect again.",
                )
            }
            debugLog("car hotspot state check overridden by live AP interface (OEM state semantics)")
        }
        val manager: WirelessHotspotManager = if (hotspotMode == WirelessHotspotMode.RELAY) {
            RelayClientManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Relay SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                onDiagnostic = ::debugLog,
            )
        } else {
            // vc202（竞品 disconnectTwoPointFourStation 对照）：本机 STA 关联在 2.4G 时，
            // STA+AP 信道并发约束会把车机热点锁在 2.4G 拥挤信道（慢入网族主因之一）。
            // 车机热点路径（MANUAL/MANUAL_IPV4）启动前主动断开 STA，让热点自由落 5G。
            // RELAY（本机就是 STA，断开=自断中继）与 WIFI_P2P 不走这里；失败只记日志不阻断。
            maybeKickTwoPointFourStation()
            // vc262：地址族守卫——启动自检判 2 类（仅 IPv4）时，强制 IPv4 选址，
            // 不再走 IPv6 广播/监听分支（IPv6 栈不可用的 ROM 上那套必然失败）。
            val familyVerdict = NetworkFamilyGuard.effectiveVerdict()
            val ipv6Forbidden = familyVerdict.familyClass == NetworkFamilyClass.IPV4_ONLY
            debugLog("wireless family guard ${familyVerdict.describe()}")
            ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                preferIpv4 = ipv6Forbidden || hotspotMode == WirelessHotspotMode.MANUAL_IPV4,
                onDiagnostic = ::debugLog,
            )
        }
        hotspot = manager
        return try {
            val info = manager.start(HOTSPOT_START_TIMEOUT_MILLIS)
            // vc262：热点接口已就绪，按实际接口复核地址族（启动自检只反映栈能力）。
            debugLog(
                "wireless family guard iface " +
                    NetworkFamilyGuard.classifyInterface(info.interfaceName).describe(),
            )
            // vc281：撤回 vc280「中继恒发 channel=0」——现场反证（C8815 11-15 00:39）：
            // channel=0 下连发 12 轮 0x5703/0x4301（observed=6），iPhone 一次 TCP 都没来，
            // 93s 判 First TCP timeout；同一份日志里车机热点链路（channel=6）三次都在 +4s
            // 收到 TCP。与 vc274 的 TS7 实测一致：0x4301 没有可用信道时 iOS 不做定向扫描。
            // 故中继也发入网后实测信道；读不到（0）时自然退化为全频段扫描。
            val rememberedChannel = ConfirmedChannelMemory.recall(
                appContext,
                if (hotspotMode == WirelessHotspotMode.RELAY) {
                    ConfirmedChannelMemory.ROLE_RELAY
                } else {
                    ConfirmedChannelMemory.ROLE_CAR
                },
            )
            debugLog(
                "credential channel=${info.channel} mem=$rememberedChannel " +
                    "backend=${info.backend}",
            )
            lastHotspotInfo = info
            observeHotspotIdentity("fresh", generation, info)
            info
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    /**
     * vc157：P2P GO 建组（一级模式，移植自上游 0.2.21 WifiP2pGroupManager）。
     * 触发路径：仅 wirelessHotspotMode=WIFI_P2P（"mode selected"）。
     * MANUAL 热点失败不再自动兜底——需要 Wi-Fi Direct 请显式选该模式。
     * 成功后返回的 WirelessHotspotInfo（P2P 接口 IPv4/组网信息）走既有凭证下发链路
     * （0x5703 / AirPlay 监听 / Bonjour），下游对 backend 无感知；失败一律包装为 IOException。
     * close/保留复用语义与 ManualHotspotManager 相同
     * （closeWirelessStack(keepHotspotForReuse=true) 时 P2P 组同样跨代保留不掉网）。
     */
    private fun startP2pHotspot(generation: Int, cause: String): WirelessHotspotInfo {
        val manager = try {
            WifiP2pGroupManager(appContext, ::debugLog)
        } catch (unavailable: Exception) {
            // P2P 不可用（服务缺失/ROM 裁剪）：包装后由调用方静默降级回主路径错误。
            throw IOException("Wi-Fi P2P is unavailable on this ROM: " +
                (unavailable.message ?: unavailable.javaClass.simpleName), unavailable)
        }
        hotspot = manager
        return try {
            val info = manager.start(P2P_FALLBACK_START_TIMEOUT_MILLIS)
            debugLog(
                "wifi-p2p fallback ready ($cause) ssid=${info.ssid} channel=${info.channel} " +
                    "host=${info.hostAddress?.let(::hostAddressText) ?: "unknown"} " +
                    "iface=${info.interfaceName ?: "unknown"}",
            )
            lastHotspotInfo = info
            info
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort("wifi-p2p-fallback") { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Wi-Fi P2P fallback failed: ${failure.message ?: failure.javaClass.simpleName}",
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    /** [RELAY-TEST] 从扫描缓存选一个非当前中继 SSID 的假凭证目标（读缓存不阻塞建链）。 */
    private fun selectRelayTestFakeCredential(currentSsid: String): Pair<String, String> {
        val fakePassphrase = randomTestPassphrase()
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val candidates = wifiManager?.scanResults
            .orEmpty()
            .mapNotNull { it.SSID?.takeIf(String::isNotBlank) }
            .filter { it != currentSsid }
            .distinct()
        return if (candidates.isNotEmpty()) {
            debugLog(
                "[RELAY-TEST] fake ssid from scan cache: $candidates -> ${candidates.first()}",
            )
            candidates.first() to fakePassphrase
        } else {
            // 附近无其他 SSID：用不存在的 SSID，验证「扫不到目标时 iPhone 是否仍切出当前 AP」
            val fallback = "DiPlayTest-" + (1000 + java.util.Random().nextInt(9000))
            debugLog(
                "[RELAY-TEST] scan cache empty -> nonexistent fake ssid=$fallback " +
                    "(branch: scan-miss)",
            )
            fallback to fakePassphrase
        }
    }

    private fun randomTestPassphrase(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = java.util.Random()
        val builder = StringBuilder(10)
        repeat(10) { builder.append(chars[random.nextInt(chars.length)]) }
        return builder.toString()
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        // vc97：ECARX 平台 getBondedDevices() 失灵（FE-6 实测已配对却返回空）→
        // root bt_config.conf 兜底枚举，还原 BluetoothDevice 后走同一匹配逻辑。
        var bonded: Collection<BluetoothDevice> = adapter.bondedDevices.orEmpty()
        if (bonded.isEmpty()) {
            val fallback = BondedDevicesResolver.resolveDevices(adapter) { debugLog(it) }
            debugLog("wireless Bluetooth adapter bonded empty; root-config fallback=${fallback.size} devices")
            bonded = fallback
        }
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
        }
        val nameMatchedIPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        // vc284：名字之外再认「缓存里含 iAP2 服务 UUID」。用户把 iPhone 蓝牙名改成不含
        // "iPhone" 的自定义名时，旧过滤完全选不到这台机（只剩 "No unambiguous bonded
        // iPhone"），而 SDP 缓存里 iAP2 UUID 一直在。只读缓存（getUuids），不发 SDP 查询、
        // 不增加时延；名字过滤的结果始终是该集合的子集，故只会补进设备，不会把已选中的排除。
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true ||
                hasCachedIap2Uuid(device)
        }
        if (iPhones.size != nameMatchedIPhones.size) {
            debugLog(
                "wireless Bluetooth iPhone filter widened by iap2-uuid: " +
                    "byName=${nameMatchedIPhones.size} withUuid=${iPhones.size}",
            )
        }
        // 1.1.5（提速）：仅一台已配对 iPhone 时直接选定——RFCOMM 建链本身就是可达性验证，
        // 跳过 HFP/A2DP profile proxy 查询（每 profile 最长 3s，10-12 实机日志：累计 ~8s）。
        // 旧版 0.2.6 同场景 0.1~0.9s 即发起 RFCOMM。单台时与原逻辑结果等价（原逻辑最终
        // 也落到 iPhones.single()），多台时仍走连接态判定消歧。
        if (iPhones.size == 1) return iPhones.single()
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        // vc285：多台已配对 iPhone 但一台都没回连（典型：车机重启时 iPhone 还没连上蓝牙）。
        // 不再直接抛歧义——用「上次成功连接过的 MAC」兜底；名字会因同名设备/改名失效，
        // MAC 才是稳定标识。没有记忆、或记忆已不在配对列表时才走原有报错文案。
        if (connectedIPhones.isEmpty()) {
            val remembered = config.wirelessBluetoothLastGoodAddress?.let { address ->
                iPhones.firstOrNull { it.address.equals(address, ignoreCase = true) }
            }
            if (remembered != null) {
                debugLog(
                    "wireless Bluetooth ambiguous (none connected); falling back to " +
                        "last-good address=${remembered.address} name=${remembered.name ?: "unknown"}",
                )
                return remembered
            }
        }
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    /**
     * M6k6：RFCOMM 建链加固。C8815 实测（20:31 日志，导出档 -203326；注意与车机 B 的
     * 20:33 日志区分——后者是 `iAP2 link ended`，与 `Service discovery failed` 不同病）`Service discovery failed` 可连续
     * 十余次失败（T14 手点 7 次才连上的同族问题），此前每轮重试都用同一种套接字：
     * ①建链前取消适配器在跑的发现——发现扫描与 SDP 建链冲突是老栈的经典坑；
     * ②安全口失败后回落不安全口（API10+；MFi 鉴权在 iAP2 层完成，不依赖 RFCOMM 加密）。
     *
     * ## vc168：**原定案已被日志证伪，见下方 sdpProbe() 注释。** 失败不是"iPhone 没广播
     * iAP2"，而是**框架 `BluetoothSocket$SdpHelper.doSdp` 自己的 SDP 查询失败**。
     * 本轮据此做三件便宜的事，全部靠日志判据决定下一步：
     * ①**建链前**先 SDP（原仅失败路径才探）——部分栈的 helper 会读缓存；
     * ②`cancelDiscovery()` 是异步的，补一个收敛等待窗口；
     * ③两种口各试一次就放弃太急，改为**多轮退避重试**。
     */
    private fun openWirelessRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        // vc186⓪：**建链前适配器体检**（照搬 vc180 有线 TUN 的 probeTunEnvironment 思路）。
        // 判据来自 LiteSessionService.kt 既有先验「getAddress() 非全零才可信」：
        // 「开关 ON 但硬件未初始化」的半残栈（FE6 20261025 实证：native 栈 /dev/stpbt 缺失致
        // address=00:00:00:00:00:00）会让后续 RFCOMM 白跑 3 轮×2 模式（15s 超时或毫秒立败），
        // 用户只见"无线链路建立中"干等。体检不过即收手并给可行动文案，不再浪费时间。
        // 注意：API31+ 读 getAddress() 需 BLUETOOTH_CONNECT，无权限会抛 SecurityException
        // （既有教训：05-果实-实测过程-02 的 USB 闪退）——读不到只跳过体检，不得误判为半残。
        val adapter = bluetoothAdapter
        val stateResult = runCatching { adapter?.state }
        val addressResult = runCatching { adapter?.address }
        val state = stateResult.getOrNull()
        val localAddress = addressResult.getOrNull()
        debugLog(
            "wireless RFCOMM precheck adapterState=$state" +
                " localAddress=${if (addressResult.isSuccess) localAddress else "<unreadable>"}",
        )
        if (adapter != null && state != null && state != BluetoothAdapter.STATE_ON) {
            throw IOException(
                "bluetooth adapter not on: state=$state; " +
                    "本机蓝牙未开启，请打开车机蓝牙后重试",
            )
        }
        if (adapter != null && addressResult.isSuccess &&
            (localAddress.isNullOrBlank() || localAddress == "00:00:00:00:00:00")
        ) {
            throw IOException(
                "bluetooth adapter half-dead: getAddress()=$localAddress while state=$state; " +
                    "本机蓝牙适配器未真正初始化（MAC 为空/全零）——系统蓝牙栈异常，" +
                    "请检查系统蓝牙或重启车机后重试",
            )
        }
        // ①建链前发一次 SDP 请求（vc168 引入，vc172 改为**发射后不管**）。
        // 原为阻塞探测（fetch + 固定 sleep 1.5s ≈ 2.4s），每次连接都付——含一帆风顺时。
        // 诊断目的已达成（服务始终在广播，失败在框架 SdpHelper.doSdp），
        // 而 probe 结果只有失败路径才消费 ⇒ 成功路径不该阻塞。
        // 诊断能力不丢：失败路径的 sdpProbe(round/final) 完整保留。
        // vc283：默认不发（见 RFCOMM_PROBE_BEFORE_CONNECT 的原因说明）。
        if (RFCOMM_PROBE_BEFORE_CONNECT) sdpProbeAsync(device)

        // ②取消发现并给栈收敛时间（原实现 cancelDiscovery 后立刻建链，而它是异步的）
        if (bluetoothAdapter?.isDiscovering == true) {
            debugLog("wireless RFCOMM canceling discovery before connect")
            runCatching { bluetoothAdapter?.cancelDiscovery() }
            runCatching { Thread.sleep(DISCOVERY_CANCEL_SETTLE_MILLIS) }
            debugLog(
                "wireless RFCOMM discovery settle ${DISCOVERY_CANCEL_SETTLE_MILLIS}ms, " +
                    "discovering=${bluetoothAdapter?.isDiscovering}",
            )
        }

        var lastFailure: Throwable? = null
        // vc186：累计每次尝试的异常类型/耗时——此前失败原因被吞（只剩一句无信息的
        // "RFCOMM connect failed"），无法区分「本地栈毫秒立败」与「iPhone 不应答(15s 超时)」，
        // 而这两者用户处置完全不同。
        val attempts = ArrayList<String>()
        var fastFailures = 0
        var timeouts = 0
        // vc282：最近一次 SDP 探测的判据（服务是否仍在广播）。只有「可见」才值得走下面的
        // 直连通道兜底；不可见说明 iPhone 侧真的不在，按原有路径失败收手。
        var sdpVisible = false
        // ③多轮退避重试（原为 secure+insecure 各一次即放弃）
        for (round in 0 until RFCOMM_ROUNDS) {
            for (mode in listOf("secure", "insecure")) {
                val startedAt = System.currentTimeMillis()
                try {
                    val uuid = UUID.fromString(IAP2_IPHONE_UUID)
                    val socket = if (mode == "secure") {
                        device.createRfcommSocketToServiceRecord(uuid)
                    } else {
                        device.createInsecureRfcommSocketToServiceRecord(uuid)
                    }.also { bluetoothSocket = it }
                    debugLog(
                        "wireless RFCOMM connecting address=${device.address} " +
                            "mode=$mode round=$round/${RFCOMM_ROUNDS} uuid=$IAP2_IPHONE_UUID",
                    )
                    connectBluetoothSocket(socket, device.address)
                    debugLog(
                        "wireless RFCOMM connected address=${device.address} " +
                            "mode=$mode round=$round elapsedMs=${System.currentTimeMillis() - startedAt}",
                    )
                    return socket
                } catch (failure: Throwable) {
                    lastFailure = failure
                    val elapsed = System.currentTimeMillis() - startedAt
                    // vc186：立败(<1s，本地栈直接拒绝) 与 超时(≈15s，iPhone 不应答) 分开计数。
                    if (elapsed < 1000) fastFailures++ else timeouts++
                    attempts += "$mode/r$round ${failure.javaClass.simpleName}:" +
                        "${failure.message ?: "-"} ${elapsed}ms"
                    debugLog(
                        "wireless RFCOMM connect ($mode) failed round=$round " +
                            "elapsedMs=$elapsed: " +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
                    runCatching { bluetoothSocket?.close() }
                    bluetoothSocket = null
                }
            }
            if (round < RFCOMM_ROUNDS - 1) {
                // 每轮之间再探一次 SDP：观察"服务是否仍在广播"随时间的变化
                sdpVisible = sdpProbe(device, "round$round")
                if (bluetoothAdapter?.isDiscovering == true) {
                    debugLog("wireless RFCOMM canceling discovery before next round")
                    runCatching { bluetoothAdapter?.cancelDiscovery() }
                }
                val backoff = RFCOMM_ROUND_BACKOFF_MILLIS[round]
                runCatching { Thread.sleep(backoff) }
            }
        }
        // vc282（vc169 反射方案落地）：UUID 路径全灭、但 SDP 探测显示 iAP2 仍在广播 ——
        // 正是 vc168 定下的第三格判据「框架 `SdpHelper.doSdp` 在本 ROM 不可用」。
        // 证据（C8815 11-15 vc281）：6 次 connect 全部 `Service discovery failed`
        // （secure 6027/3875/2976ms，insecure 22/41/25ms），而紧邻的三次 fetchUuidsWithSdp
        // 都拿到 services=12 iap2Visible=true。老 BlueZ 栈里 SDP 通道发现失败不影响
        // 直连通道，故用隐藏 API `createRfcommSocket(channel)` 绕过 SDP 直接建链。
        // 只在「可见」时尝试：服务不可见说明 iPhone 真的不在，多试只是拖慢失败。
        if (sdpVisible) {
            for (channel in RFCOMM_DIRECT_CHANNELS) {
                val startedAt = System.currentTimeMillis()
                val socket = createDirectRfcommSocket(device, channel) ?: continue
                bluetoothSocket = socket
                debugLog(
                    "wireless RFCOMM direct channel=$channel connecting " +
                        "address=${device.address}",
                )
                try {
                    connectBluetoothSocket(socket, device.address, RFCOMM_DIRECT_TIMEOUT_MILLIS)
                    debugLog(
                        "wireless RFCOMM direct channel=$channel connected " +
                            "elapsedMs=${System.currentTimeMillis() - startedAt}",
                    )
                    return socket
                } catch (failure: Throwable) {
                    lastFailure = failure
                    attempts += "direct/ch$channel ${failure.javaClass.simpleName}:" +
                        "${failure.message ?: "-"} ${System.currentTimeMillis() - startedAt}ms"
                    debugLog(
                        "wireless RFCOMM direct channel=$channel failed " +
                            "elapsedMs=${System.currentTimeMillis() - startedAt}: " +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
                    runCatching { socket.close() }
                    bluetoothSocket = null
                }
            }
        }
        // vc186：失败原因不再吞——保留 "RFCOMM connect failed" 前缀以兼容既有失败分类，
        // 附带各轮摘要与立败/超时计数（App 层据此分级文案），cause 保留原异常。
        val failure = CarPlayClassifiedException(
            "RFCOMM connect failed after $RFCOMM_ROUNDS rounds" +
                " (fastFail=$fastFailures, timeout=$timeouts)" +
                (if (attempts.isNotEmpty()) ": " + attempts.joinToString(" | ") else ""),
            CarPlayFailureClass.BtNoResponse,
            lastFailure,
        )
        // 全轮失败：最终再探一次，坐实"服务仍在广播但框架 SDP 建不了链"
        sdpProbe(device, "final")
        throw failure
    }

    /**
     * vc282：用隐藏 API 直接按通道号建 RFCOMM 套接字，跳过 UUID→SDP 通道发现。
     *
     * `BluetoothDevice.createRfcommSocket(int)` 自 API 1 就存在（广被 SPP 应用用来绕开
     * 老栈的 SDP 失败），反射取不到时返回 null —— 上层按「兜底不可用」继续原失败路径，
     * 不影响任何既有行为。
     */
    private fun createDirectRfcommSocket(device: BluetoothDevice, channel: Int): BluetoothSocket? =
        runCatching {
            val method = device.javaClass.getMethod("createRfcommSocket", Integer.TYPE)
            method.invoke(device, channel) as? BluetoothSocket
        }.onFailure {
            debugLog("wireless RFCOMM direct reflection unavailable: ${it.message}")
        }.getOrNull()

    /**
     * vc168：主动拉一次 iPhone 的 SDP 服务列表并落日志。
     *
     * **这条探针推翻了 1.1.3（8815 230420）的旧定案。** 旧定案认为"两种口都失败 =
     * iPhone SDP 上已无 iAP2 服务（残留会话）"，而 20261022 的 C8815 日志显示：
     * 失败后探测 **services=12，列表中明确含 `00000000-deca-fade-deca-deafdecacafe`，
     * iap2Visible=true** —— 服务**在**广播。真正的失败点在框架
     * `BluetoothSocket$SdpHelper.doSdp`（建 socket 时重新做一次 SDP 的那一步）。
     *
     * 因此 vc168 把它前移到建链前，并在每轮之间、最终失败时各探一次，
     * 用"服务可见性 ✕ 建链结果"的交叉判据区分：
     * - 可见 + 建链成功 ⇒ 时序/缓存问题（本轮修复有效）
     * - 可见 + 全轮失败 ⇒ 框架 SDP 在此 ROM 不可用（需 vc169 反射建 channel socket）
     * - 不可见 ⇒ 才是旧定案说的"iPhone 未广播"，按残留会话处理
     */
    /**
     * **发射后不管**：只把 SDP 请求发出去，不等待、不读结果。
     *
     * ## vc172：为什么改成这样
     *
     * vc168 为诊断（区分"服务不在广播" vs "框架 SDP 失败"）把 [sdpProbe] 前移到建链前，
     * 而它内部有 `Thread.sleep(SDP_QUERY_SETTLE_MILLIS)` = **1.5s 固定等待** ——
     * 于是**每次连接都要付约 2.4s**（1.5s sleep + ~0.9s fetch），连一帆风顺时也付。
     *
     * 诊断目的早已达成（定案：服务始终在广播，失败在框架 `SdpHelper.doSdp` 间歇性），
     * 而这 2.4s 是 K60 对照实测里我方与竞品差距的**绝大部分**
     * （见 `09-逆向-车机与取证/竞品分析-CatPlay-0.68.4-K60实测与补丁.md` §6.2）。
     *
     * 关键点：**probe 的产出只有失败路径才用得上**（靠它判断服务在不在）。
     * 成功路径上它只打一行日志，没人消费 ⇒ 成功路径不该为它阻塞。
     * 失败路径的 [sdpProbe]（round/final）完整保留，**诊断能力一点不丢**。
     *
     * 若某些栈确实靠这次 SDP 预热缓存，收益仍在（请求照样发出去了）——
     * 只是我们不等它。这是严格优于"阻塞探测"和"并行但仍等结果"的做法。
     */
    private fun sdpProbeAsync(device: BluetoothDevice) {
        runCatching {
            device.fetchUuidsWithSdp()
            debugLog("wireless RFCOMM SDP probe[async] issued address=${device.address}")
        }.onFailure {
            debugLog("wireless RFCOMM SDP probe[async] failed: ${it.message}")
        }
    }

    private fun sdpProbe(device: BluetoothDevice, tag: String): Boolean {
        return runCatching {
            device.fetchUuidsWithSdp()
            Thread.sleep(SDP_QUERY_SETTLE_MILLIS)
            val advertised = device.uuids?.map { u -> u.uuid.toString() }.orEmpty()
            val visible = advertised.any { u -> u.equals(IAP2_IPHONE_UUID, ignoreCase = true) }
            debugLog(
                "wireless RFCOMM SDP probe[$tag] address=${device.address} " +
                    "services=${advertised.size} iap2Visible=$visible list=$advertised",
            )
            visible
        }.onFailure {
            debugLog("wireless RFCOMM SDP probe[$tag] failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun connectBluetoothSocket(
        socket: BluetoothSocket,
        address: String,
        timeoutMillis: Long = RFCOMM_CONNECT_TIMEOUT_MILLIS,
    ) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after ${timeoutMillis}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${timeoutMillis}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /**
     * M6k10：bring-up 期间每秒采样关键判据（作者 0.2.11 诊断包结构）。
     * tcpAccepted=0 → iPhone 还没加入热点 Wi-Fi（Wi-Fi 段等待）；≥1 → 已进 RTSP 阶段。
     * active=true 或代次失效时自动停止；teardown 也会强制停止。
     */
    private fun startWirelessSampler(generation: Int) {
        stopWirelessSampler()
        val sampler = Thread(
            {
                var elapsedSeconds = 0L
                // vc300：configuredNetworks 每次都要跨 Binder 拉全量配置（ANR trace 里
                // system_server 多个 Binder 线程卡在 syncGetConfiguredNetworks），
                // 1s/次太重——降为 5s 刷新一次、其余轮复用缓存，日志字段名不变。
                var wifiConfigs = -1
                while (
                    !closed &&
                    phase == Phase.WIRELESS &&
                    generation == wirelessGeneration.get() &&
                    !wirelessActiveReported.get() &&
                    !wirelessDegradedActive.get()
                ) {
                    if (elapsedSeconds % 5L == 0L) wifiConfigs = wifiConfigCount()
                    debugLog(
                        "wireless sample gen=$generation t=+${elapsedSeconds}s " +
                            "tcpAccepted=${airPlayServer?.acceptedConnectionCount() ?: 0} " +
                            "authenticated=${wirelessAuthenticated.get()} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wifiConfigs=$wifiConfigs",
                    )
                    try {
                        Thread.sleep(1_000L)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                    elapsedSeconds += 1
                }
            },
            "xcertplay-wireless-sampler",
        )
        sampler.isDaemon = true
        wirelessSamplerThread = sampler
        sampler.start()
    }

    private fun stopWirelessSampler() {
        val sampler = wirelessSamplerThread ?: return
        wirelessSamplerThread = null
        sampler.interrupt()
    }

    /** 会话建立收口：SESSION_ACTIVE 一行讲清耗时（重连总耗时从故障纪元起点起算）。 */
    private fun logWirelessSessionActive(
        generation: Int,
        attempt: Int,
        degraded: Boolean = false,
        handoffReason: String = if (degraded) "unknown" else "tunnel-ready",
    ) {
        val now = SystemClock.elapsedRealtime()
        // vc295：会话可用的同时开「对端是否还在热点上」巡检（AP 模式；ARP 不可读时自退）。
        startWirelessStaWatch(generation)
        val bringUpMs = wirelessBringUpStartedAtMs.takeIf { it > 0 }?.let { now - it } ?: -1L
        // vc202：纯 handoff 段耗时（请求→完成），区分慢在 bring-up 还是 handoff 判据。
        val handoffMs = wirelessHandoffRequestedAtMs.takeIf { it > 0 }?.let { now - it } ?: -1L
        // vc239（LOG-238-03）：首轮也要可读——没有「重连纪元」时用本代 bringUpMs 顶上，
        // 并标注 first-run，避免 4/4 全是 n/a(first-run) 无法判读。不改任何计时逻辑。
        val reconnectEpisodeMs =
            wirelessRestartEpisodeStartedAtMs.takeIf { it > 0 }?.let { now - it }
        val reconnectTotalMs = reconnectEpisodeMs ?: bringUpMs.takeIf { it > 0 } ?: 0L
        wirelessRestartEpisodeStartedAtMs = 0L
        // vc202：degraded 先行完成后隧道才就绪时，补记隧道落后已激活的毫秒数（未走 degraded 则省略）。
        val tunnelReadyAfterActiveMs = if (!degraded && wirelessDegradedAtMs > 0L) {
            now - wirelessDegradedAtMs
        } else {
            null
        }
        // vc202：热点频段归一化（H1 验证「踢 2.4G 后是否真走上 5G」直接读这个字段）。
        val wifiBand = when (lastHotspotInfo?.bandLabel) {
            "5 GHz" -> "5g"
            "2.4 GHz" -> "2.4g"
            else -> "unknown"
        }
        debugLog(
            // vc239（LOG-238-03）：attempt 内部是 0 基重试计数，展示改 1 基（首轮 attempt=1），
            // gen/attempt 两值首轮即可读；不改计数与重试逻辑。
            "wireless SESSION_ACTIVE gen=$generation attempt=${attempt + 1}" +
                "${if (degraded) " (degraded)" else ""} " +
                "bringUpMs=$bringUpMs handoffMs=$handoffMs handoffReason=$handoffReason " +
                "wifiBand=$wifiBand " +
                (tunnelReadyAfterActiveMs?.let { "tunnelReadyAfterActiveMs=$it " } ?: "") +
                "reconnectTotalMs=$reconnectTotalMs" +
                (if (reconnectEpisodeMs == null) "(first-run,bringUpMs)" else ""),
        )
    }

    /**
     * vc202（竞品 disconnectTwoPointFourStation 对照移植）：车机热点启动前踢 2.4G STA。
     * 判据与护栏：
     * - 仅由 MANUAL/MANUAL_IPV4 路径调用（调用点在 startWirelessHotspot）；RELAY 场景
     *   本机就是 STA，断开等于自断中继，绝不能走到这里；
     * - 开关 config.wirelessStaKickFor5g=false 时完全不动 STA（回退 vc201 行为）；
     * - 未关联 / 已在 5G / 频段读不出 → staKickSkipped=<reason>，原流程继续；
     * - 2.4G 关联 → wifi.disconnect()（deprecated 但 API1 起存在，minSdk16 安全）；
     *   返回值与异常都只落日志，不阻断建链（踢不掉就按 2.4G 原样走）。
     */
    @Suppress("DEPRECATION")
    private fun maybeKickTwoPointFourStation() {
        if (!config.wirelessStaKickFor5g) {
            debugLog("staKickSkipped=disabled")
            return
        }
        try {
            val wifiManager =
                appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            if (info == null || info.networkId == -1) {
                debugLog("staKickSkipped=not-associated")
                return
            }
            val freqMHz = wifiInfoFrequencyMhz(info)
            when {
                freqMHz == null -> debugLog("staKickSkipped=freq-unknown ssid=${info.ssid}")
                freqMHz in 5000..6000 ->
                    debugLog("staKickSkipped=already-5g freq=${freqMHz}MHz ssid=${info.ssid}")
                freqMHz in 2400..2500 -> {
                    debugLog(
                        "staKick: STA associated on 2.4G (freq=${freqMHz}MHz ssid=${info.ssid}); " +
                            "disconnecting so the car hotspot can use 5G",
                    )
                    val ok = wifiManager.disconnect()
                    debugLog("staKick disconnect()=$ok")
                }
                else -> debugLog("staKickSkipped=band-other freq=${freqMHz}MHz")
            }
        } catch (error: Exception) {
            // 任何 ROM 差异/权限拒绝都不许影响建链。
            debugLog("staKick failed (non-fatal): ${error.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiConfigCount(): Int = try {
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.configuredNetworks?.size ?: -1
    } catch (error: Exception) {
        // API16 适配：configuredNetworks 部分厂商 ROM 抛 SecurityException/RuntimeException。
        -1
    }

    private fun probeListen(generation: Int, address: InetAddress, port: Int): ServerSocket? {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, port), 4)
            }
        } catch (error: Throwable) {
            debugLog("wireless probe: bind failed ${address.hostAddress}:$port ${error.message}")
            return null
        }
        debugLog(
            "wireless probe: listening ${address.hostAddress}:$port " +
                "family=${if (address is Inet6Address) "IPv6" else "IPv4"} gen=$generation (diagnostic)",
        )
        Thread(
            {
                while (!socket.isClosed) {
                    try {
                        val connection = socket.accept()
                        debugLog(
                            "wireless probe: TCP accepted from ${connection.inetAddress.hostAddress} " +
                                "to ${address.hostAddress}:$port gen=$generation",
                        )
                        try {
                            connection.close()
                        } catch (_: Throwable) {
                        }
                    } catch (error: Throwable) {
                        if (!socket.isClosed) {
                            debugLog("wireless probe: accept ended ${address.hostAddress}:$port ${error.message}")
                        }
                        break
                    }
                }
            },
            "xcertplay-connect-probe-$port",
        ).apply {
            isDaemon = true
            start()
        }
        return socket
    }

    private fun alternateFamilyAddress(primary: InetAddress): InetAddress? = try {
        if (primary is Inet6Address) {
            NetworkInterface.getByInetAddress(primary)?.inetAddresses?.let { addresses ->
                java.util.Collections.list(addresses).filterIsInstance<Inet4Address>().firstOrNull()
            }
        } else {
            NetworkInterface.getNetworkInterfaces()?.let { interfaces ->
                java.util.Collections.list(interfaces)
                    .firstOrNull { nif ->
                        java.util.Collections.list(nif.inetAddresses).any { it == primary }
                    }
                    ?.inetAddresses
                    ?.let { addresses ->
                        java.util.Collections.list(addresses)
                            .filterIsInstance<Inet6Address>()
                            .firstOrNull { it.isLinkLocalAddress && it.scopeId != 0 }
                    }
            }
        }
    } catch (error: Throwable) {
        null
    }

    @Synchronized
    private fun stopDualStackProbe() {
        stopDualStackProbeLocked()
    }

    private fun stopDualStackProbeLocked() {
        val sockets = probeSockets
        probeSockets = emptyList()
        for (socket in sockets) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * vc308：`(source, action)` → `EventCode`（`15-编号表` §3.3 / §3.4 / §3.6）。
     *
     * 31 个真实组合 + `W-299` 兜底。未知组合返回 `W-299` 而不是抛异常——编号只用于日志，
     * 任何情况下都不允许影响控制流。`W-207`（`retry-exhausted` 的 `CLOSE_STACK`）是死编号，
     * 源码无该用法，故意不写分支。
     */
    private fun codeFor(source: WirelessSource, decision: WirelessDecision): EventCode =
        when (decision) {
            WirelessDecision.CLOSE_STACK -> when (source) {
                WirelessSource.FirstTcp -> EventCode.W201
                WirelessSource.MediaStall -> EventCode.W202
                WirelessSource.StaWatch -> EventCode.W203
                WirelessSource.HandoffWatchdog -> EventCode.W204
                WirelessSource.PreStreamEof -> EventCode.W205
                WirelessSource.RunCatch -> EventCode.W206
                WirelessSource.Restart -> EventCode.W208
                WirelessSource.BringupStart -> EventCode.W209
                WirelessSource.StaleHotspot -> EventCode.W210
                WirelessSource.StaleAttach -> EventCode.W211
                WirelessSource.StaleBonjour -> EventCode.W212
                WirelessSource.StaleRfcomm -> EventCode.W213
                WirelessSource.StaleTunnel -> EventCode.W214
                WirelessSource.StaleControl -> EventCode.W215
                WirelessSource.Close -> EventCode.W216
                WirelessSource.WiredBootstrap -> EventCode.U201
                else -> EventCode.W299
            }
            WirelessDecision.FAIL -> when (source) {
                WirelessSource.FirstTcp -> EventCode.W301
                WirelessSource.MediaStall -> EventCode.W302
                WirelessSource.StaWatch -> EventCode.W303
                WirelessSource.HandoffWatchdog -> EventCode.W304
                WirelessSource.Mfi -> EventCode.W305
                WirelessSource.RunCatch -> EventCode.W306
                WirelessSource.RetryExhausted -> EventCode.W307
                WirelessSource.WiredHost -> EventCode.U301
                WirelessSource.WiredConfig -> EventCode.U302
                WirelessSource.WiredTransition -> EventCode.U303
                WirelessSource.Reenumeration -> EventCode.U304
                WirelessSource.WiredControl -> EventCode.U305
                WirelessSource.WiredIap2 -> EventCode.U306
                WirelessSource.WiredKernelNcm -> EventCode.U307
                WirelessSource.WiredRun -> EventCode.U308
                else -> EventCode.W299
            }
        }

    /**
     * vc301（P0 零行为收口）：无线失败处理的唯一出口。
     *
     * 纯转发——不新增条件、不调整顺序、不改变参数；只在执行前输出一条 [decide] 观测行，
     * 供 vc300 基线与 vc301 的逐序列比对。
     * vc308：source 由字符串收紧为 [WirelessSource]，并在 [decide] 之后追加一行 verdict（编号 + 人话）。
     */
    private fun decide(
        decision: WirelessDecision,
        source: WirelessSource,
        error: Throwable? = null,
        failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
        keepHotspot: Boolean = false,
    ) {
        debugLog(
            "[decide] source=${source.wire} action=$decision stage=$phase gen=${wirelessGeneration.get()} " +
                "class=${failureClass.name} keepHotspot=$keepHotspot " +
                "error=${error?.javaClass?.simpleName ?: "-"}:${error?.message ?: "-"}",
        )
        // vc308：每代唯一结论（`15-编号表` §4）。`|` 右侧必须是 human，不能写 source.wire。
        val ev = codeFor(source, decision)
        debugLog(
            "[verdict] ${ev.id} g=${wirelessGeneration.get()} " +
                "action=${if (decision == WirelessDecision.FAIL) "fail" else "retry"} | ${ev.human}",
        )
        when (decision) {
            WirelessDecision.CLOSE_STACK -> closeWirelessStack(keepHotspot)
            WirelessDecision.FAIL -> {
                val failure = error ?: return
                fail(failure, failureClass)
            }
        }
    }

    private fun closeWirelessStack(keepHotspotForReuse: Boolean = false) {
        stopDualStackProbe()
        wirelessControlProof.clear()
        wirelessMediaProof.clear()
        wirelessLinkOpenedAtMs = 0L

        // 断链顺序（M6k8 调整）：**先下线 Bonjour 广报**（NsdManager unregisterService /
        // JmDNS close 发 goodbye），iPhone 立即感知服务消失并清 mDNS 缓存；随后才关 AirPlay
        // 监听与会话。若反序：监听已死而广报记录仍在 TTL 内，iPhone 重连会先撞死端口、
        // 等缓存过期（实测 ~40s）。10-02 八轮实测的约束保留：AirPlay 会话的 TCP FIN 必须先于
        // iAP2 隧道/蓝牙断开，否则 iOS 把蓝牙掉线当瞬时抖动而保持 CarPlay（用户实测：
        // 必须手动关车机热点/蓝牙才退）。每步补文件日志，供下一轮无 adb 的实机日志定位。
        debugLog("wireless teardown step=1/5 unregistering Bonjour advertisement")
        // vc272：单播通告器与 STA 注入回调随代次结束停止（先停通告再关监听，语义同上）。
        hotspotStaArpListener = null
        runCatching { wirelessMdnsAnnouncer?.stop() }
        wirelessMdnsAnnouncer = null
        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) {
            // 1.1.5（提速）：JmDNS close（goodbye 重传等待 + worker join）实测可阻塞 7~8s
            //（10-12 实机：elapsedMs=7021/8019 两样本），直接拖慢整条 teardown 与下一次
            // 尝试启动。goodbye 报文在 close() 首毫秒即发出，改为后台线程异步收尾，主
            // teardown 只等 300ms 保证 goodbye 先于 AirPlay 下线（保住 M6k8 顺序约束）；
            // 多播套接字 SO_REUSEADDR 允许与下一代注册短暂并存。
            val bonjourGoodbye = CountDownLatch(1)
            Thread(
                {
                    try {
                        activeBonjour.close()
                        debugLog("wireless teardown resource=Bonjour completed (async)")
                    } catch (error: Throwable) {
                        debugLog("wireless Bonjour async close failed: ${error.message}")
                    } finally {
                        bonjourGoodbye.countDown()
                    }
                },
                "xcertplay-bonjour-close",
            ).apply {
                isDaemon = true
                start()
            }
            // vc178：teardown 线程若在关闭期间被中断，await 抛 InterruptedException 会连带
            // 掀掉整条 teardown（step=2/5 起的 AirPlay 下线、iAP2 隧道回收全部不执行 →
            // 资源泄漏 + iOS 侧认为会话还在）。吞掉并按约定恢复中断标志，让后续步骤照常走完。
            try {
                bonjourGoodbye.await(300, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                debugLog("wireless teardown resource=Bonjour goodbye wait interrupted")
            }
            debugLog(
                "wireless teardown resource=Bonjour goodbyeFlushed=" +
                    (bonjourGoodbye.count == 0L) + " (async close continues in background)",
            )
        } else {
            debugLog("wireless teardown resource=Bonjour completed=true elapsedMs=0")
        }

        val activeServer = airPlayServer
        airPlayServer = null
        debugLog("wireless teardown step=2/5 closing AirPlay server and sessions")
        timedStep("AirPlayServer") { activeServer?.detach() }

        debugLog("wireless teardown step=3/5 releasing iAP2 tunnel handler")
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        timedStep("iAP2Tunnel") { activeTunnel?.close() }

        debugLog("wireless teardown step=4/5 closing Bluetooth bootstrap transport")
        timedStep("BtBootstrap") { closeBluetoothBootstrapTransport() }

        debugLog("wireless teardown step=5/5 releasing hotspot handle")
        val activeHotspot = hotspot
        hotspot = null
        if (keepHotspotForReuse && activeHotspot != null && retainedHotspot == null) {
            // M6k24（C 方案）：未建流 EOF 的整代重启保留热点附着——车机热点是既有 AP，
            // 重建附着最慢且会打断 iPhone 的 Wi-Fi 连接（它每 5s 重试 TCP 的前提就是还在网上）。
            // 把 (manager, info) 移出栈外暂存，下一轮 startWirelessHotspot 直接复用。
            val info = lastHotspotInfo
            if (info != null) {
                retainedHotspot = activeHotspot to info
                debugLog("wireless teardown hotspot retained for reuse")
            } else {
                timedStep("HotspotHandle") { activeHotspot.close() }
            }
        } else {
            timedStep("HotspotHandle") { activeHotspot?.close() }
        }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessDegradedActive.set(false)
        wirelessAuthenticated.set(false)
        stopWirelessSampler()
        debugLog("wireless teardown complete")
    }

    // ==================================================================
    // USB 有线（U3）：iPhone 经 USB 直连，USBMUX/lockdown 控制面 + NCM 数据面。
    // 流程对齐上游有线编排：发现 -> USB 权限 -> (0x52 重枚举) -> USBMUX 会话 ->
    // Lockdown 配对 -> carkit TLS 流 -> iAP2 CSM -> NCM/TUN/AirPlay attach ->
    // 0x4300/0x4301 控制环。USB 拔出 = 本地用户断开语义：控制环 EOF 只报
    // ControlEnded 停住，不进无线自动重连族。
    // ==================================================================

    private fun startWiredReceivers() {
        val host = iphoneHost
        if (host == null) {
            debugLog("wired USB host service unavailable on this device")
            return
        }
        permissionCloseable = host.registerPermissionReceiver(::onIphonePermission)
        attachCloseable = host.registerAttachReceiver(::onIphoneAttached)
    }

    private fun closeWiredReceivers() {
        val permission = permissionCloseable
        permissionCloseable = null
        runCatching { permission?.close() }
        val attach = attachCloseable
        attachCloseable = null
        runCatching { attach?.close() }
    }

    private fun startIphone() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val host = iphoneHost
        if (host == null) {
            decide(WirelessDecision.FAIL, WirelessSource.WiredHost, error = IphoneUsbException.DeviceUnavailable("USB host is unavailable on this device"))
            return
        }
        val device = host.discover().firstOrNull()
        if (device == null) {
            // vc96：WaitingForIphone 仅首次进入时上报——轮询期每 2s 重发会把超时提示文案
            // 冲掉，且持续重发无信息增量；恢复发现后复位，下一轮断链重新计。
            if (waitingSinceNanos == 0L) {
                waitingSinceNanos = System.nanoTime()
                onStatus(CarPlayStatus.WaitingForIphone)
            }
            // vc96（FE-6 实测 94s 黑等取证）：发现不到 iPhone 时快照 USB 总线全拓扑——
            // 「总线空」= 设备掉总线（iPhone 侧复位退出 CarPlay 配置/host 端口楔死）；
            // 「有 Apple 设备但 discover 认不出」= pid 变了（退出 CarPlay 模式回普通充电模式）。
            // 一行日志区分两者，远端排障不再盲猜。UsbManager.deviceList 无需任何权限。
            waitingSnapshotCount += 1
            if (waitingSnapshotCount == 1 || waitingSnapshotCount % 5 == 0) {
                val topology = usbManager?.deviceList?.values
                    ?.joinToString(separator = ", ", prefix = "[", postfix = "]") {
                        "vid=0x%04x pid=0x%04x name=%s".format(
                            it.vendorId, it.productId, it.deviceName.substringAfterLast('/'),
                        )
                    } ?: "usbManager unavailable"
                debugLog("iphone wait snapshot #$waitingSnapshotCount: usbDevices=$topology")
            }
            // vc96：黑等超 10s 提示用户拔插线——FE-6 实测 94s 无任何 UI 反馈，车主以为 App 死了。
            // iPhone 侧终止会话后重枚举可能长时间不完成，拔插线是最快恢复路径。
            val waitedMs = (System.nanoTime() - waitingSinceNanos) / 1_000_000L
            if (waitedMs >= IPHONE_WAIT_PROMPT_MILLIS && !waitingPromptShown) {
                waitingPromptShown = true
                debugLog("iphone wait ${waitedMs}ms: no Apple device on bus; ask user to replug the USB cable")
                onStatus(CarPlayStatus.RecoveryHint("iPhone 未响应：请拔插 USB 线重试"))
            }
            scheduleAvailabilityPoll(Phase.IPHONE) { checkIphoneAvailability() }
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            // vc250：USB 枚举侧直接打点（只带 VID/PID，不带序列号/MAC）。
            CarPlayStageLog.stage(
                "usb-enum",
                "iphone discovered vid=0x%04x pid=0x%04x role=iphone".format(
                    device.vendorId, device.productId,
                ),
            )
            availabilityPollGeneration.incrementAndGet()
            // vc96：恢复发现后复位黑等取证状态（计数/计时/提示），下一轮断链重新计。
            waitingSnapshotCount = 0
            waitingSinceNanos = 0L
            waitingPromptShown = false
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        val host = iphoneHost
        if (host == null) {
            decide(WirelessDecision.FAIL, WirelessSource.WiredHost, error = IphoneUsbException.DeviceUnavailable("USB host is unavailable on this device"))
            return
        }
        try {
            when (val request = host.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    // vc250：权限侧直接打点。
                    CarPlayStageLog.stage(
                        "permission",
                        "already-granted vid=0x%04x pid=0x%04x".format(
                            request.device.vendorId, request.device.productId,
                        ),
                    )
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    CarPlayStageLog.stage(
                        "permission",
                        "requested vid=0x%04x pid=0x%04x".format(
                            request.device.vendorId, request.device.productId,
                        ),
                    )
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            decide(WirelessDecision.FAIL, WirelessSource.WiredConfig, error = error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                CarPlayStageLog.stage(
                    "permission",
                    "granted vid=0x%04x pid=0x%04x".format(
                        result.device.vendorId, result.device.productId,
                    ),
                )
                permissionPollGeneration += 1
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        // <21 上 find 返回 null（UsbConfiguration 是 API 21+），由重枚举 +
                        // NcmFunctionDiscovery.findLegacy 用默认配置兜底。
                        // 1.0.4：API<21 的重枚举判定改走 legacy 探测——此前恒判"无 CarPlay
                        // 配置"导致重发 0x52（iPhone 已切模式会拒绝 0x6）并重试耗尽误报。
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        val legacyCarPlayReady = configuration != null ||
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP &&
                                IphoneCarPlayConfiguration.usbMuxInterfaceLegacy(result.device) != null &&
                                NcmFunctionDiscovery.findLegacy(result.device) != null)
                        val canReenumerate = reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS
                        // 干净重置仅对 API≥21 生效：上游 2.11(143) minSdk 24，一次成功率路径
                        // 只在 Android 7+ 实证过；API<21 老车机保持「ready 即开路」原行为，
                        // 避免 0x52+二次权限在低版本上引入新回归。
                        val wantCleanReset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                        // vc93：楔死类重建强制全量复位——消费 wiredFullResetPending（一次性）。
                        // 置位时无视 carPlayReady 缓存与 self-heal 短路，走 0x52 重枚举 +
                        // setConfiguration 全量路径（iPhone 需要显式会话复位才理会新 0x4301）。
                        val fullReset = wiredFullResetPending
                        if (fullReset) {
                            wiredFullResetPending = false
                            // vc94：fullReset 会话重置重枚举预算——wedge 反复触发多轮重建后
                            // attempts 可能已耗尽，不重置的话 when 链会落入 open-data-paths
                            // self-heal 短路（TS7 实锤 iPhone 无视 0x4301 的 46s 死窗复发）。
                            reenumerationAttempts = 0
                        }
                        // 1.1.4（对齐上游 2.11(143) 外测一次成功率路径）：即使 iPhone 已带
                        // CarPlay 配置（上次会话残留的 0x52 形态），只要本周期还没做过 0x52
                        // 重枚举，就先重枚举拿干净状态再开路——残留栈直接 usbmux/Lockdown
                        // 握手易超时（外测「首连常失败、重连才成」实锤根因）。做过则直接开路。
                        val action = when {
                            fullReset && canReenumerate && legacyCarPlayReady -> "clean-reenumerate"
                            fullReset && canReenumerate -> "force-reenumerate"
                            legacyCarPlayReady && wantCleanReset && !reenumerationPerformed && canReenumerate -> "clean-reenumerate"
                            legacyCarPlayReady -> "open-data-paths"
                            canReenumerate -> "force-reenumerate"
                            else -> "reject-missing-configuration"
                        }
                        debugLog(
                            "wired permission granted: carPlayReady=$legacyCarPlayReady " +
                                "cleanReset=$wantCleanReset attempts=$reenumerationAttempts " +
                                "performed=$reenumerationPerformed fullReset=$fullReset action=$action",
                        )
                        when (action) {
                            "clean-reenumerate" -> beginReenumeration(result.device)
                            "open-data-paths" -> openDataPaths(result.device)
                            "force-reenumerate" -> {
                                // M6k24d 诊断：iPhone 未带 CarPlay 配置时，dump 完整
                                // 描述符（配置/接口/类三元组），确认它到底以什么形态回来。
                                debugLog(
                                    "wired re-attached iPhone pid=0x" + result.device.productId.toString(16) +
                                        " topology=" + IphoneCarPlayConfiguration.describeDevice(result.device),
                                )
                                beginReenumeration(result.device)
                            }
                            else -> decide(WirelessDecision.FAIL, WirelessSource.WiredConfig, error =
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                CarPlayStageLog.stage(
                    "permission",
                    "denied vid=0x%04x pid=0x%04x".format(
                        result.device.vendorId, result.device.productId,
                    ),
                )
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        permissionPollGeneration += 1
        val generation = permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager?.hasPermission(device) == true) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        CarPlayStageLog.stage(
                            "permission",
                            "timeout vid=0x%04x pid=0x%04x".format(
                                device.vendorId, device.productId,
                            ),
                        )
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        // 真重枚举（0x52 模式切换）后必须重新走 setConfiguration——自愈豁免仅限同插线周期。
        wiredEverStreamed = false
        wiredStreamedDeviceId = Int.MIN_VALUE
        // 1.1.4：记录「做过重枚举」与源设备名——权限回来时据此决定干净重置/直接开路，
        // 轮询据此只认 deviceName 不同的新实例（上游同款双标志）。
        reenumerationPerformed = true
        reenumerationSourceDeviceName = device.deviceName
        onStatus(CarPlayStatus.SelectingConfiguration)
        // vc178：改走 usbHostExecutor（建链含最长 ~6s 阻塞 sleep，不再占用共享 executor）。
        iphoneHost?.requestCarPlayReenumerationAsync(device, usbHostExecutor) { transition ->
            when (transition) {
                is IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    // M6k24d 诊断：0x52 的响应字节决定 iPhone 是否接受 CarPlay 模式切换。
                    debugLog("wired CarPlay vendor request 0x52 response=0x${transition.vendorResponseByte.toString(16)}")
                    // 1.1.4（TS7 mazda2 实测）：response=0x6 = iPhone 已处于 CarPlay 配置、
                    // 拒绝重枚举——新实例永远不会出现，旧实例也未失效。取消等待，直接对
                    // 当前设备开数据面（重枚举定时器在 phase 离开 REENUMERATION 后自然失活）。
                    if (transition.vendorResponseByte == 0x06) {
                        debugLog("wired 0x52 rejected (already configured); opening data paths directly")
                        openDataPaths(device)
                    } else {
                        onStatus(CarPlayStatus.WaitingForReenumeration)
                        startReenumerationWatchdog()
                    }
                }
                is IphoneUsbHost.TransitionResult.Failed -> decide(WirelessDecision.FAIL, WirelessSource.WiredTransition, error = transition.error)
            }
        }
    }

    /**
     * 1.0.1：重枚举阶段的三重保障（车机实测卡 WaitingForReenumeration 永久无事件）。
     * 1) 15s 看门狗：超时显性失败给可行动提示（原实现会永久卡死无提示无重试）；
     * 2) 2s 轮询兜底：扫系统 USB 设备列表发现 Apple 设备即按重枚举完成处理，
     *    防 ROM 的 ACTION_USB_DEVICE_ATTACHED 广播丢失；
     * 3) 5s 样本日志：记录当前 Apple 设备 vid/pid，下次日志可直接判定 iPhone 是否物理重枚举。
     */
    private fun startReenumerationWatchdog() {
        val generation = reenumerationPollGeneration.incrementAndGet()
        // 轮询兜底 + 样本日志（自续约）
        var polls = 0
        fun pollOnce() {
            if (closed || phase != Phase.REENUMERATION || generation != reenumerationPollGeneration.get()) return
            polls += 1
            val appleDevices = usbManager?.deviceList?.values?.filter {
                IphoneUsbMatcher.appleVendor().matches(it.vendorId, it.productId)
            }.orEmpty()
            // 1.1.4（对齐上游 deviceName 比对）：0x52 后旧实例仍留在设备列表 1~3s，
            // 完成判定只认 deviceName 与源设备不同的**新实例**，防止对失效实例请求权限。
            val sourceName = reenumerationSourceDeviceName
            val freshDevices = if (sourceName == null) appleDevices else {
                appleDevices.filter { it.deviceName != sourceName }
            }
            if (polls % 8 == 1) {
                debugLog(
                    "reenumerate waiting: appleDevices=" +
                        appleDevices.joinToString(separator = ", ", prefix = "[", postfix = "]") { "vid=0x${it.vendorId.toString(16)} pid=0x${it.productId.toString(16)}" } +
                        " fresh=${freshDevices.size}",
                )
            }
            if (freshDevices.isNotEmpty()) {
                val reattached = freshDevices.first()
                debugLog(
                    "reenumerate poll: detected fresh Apple device (name=${reattached.deviceName}); treating as re-attached",
                )
                // 1.0.19（AC822X API17 实测）：补打重枚举后拓扑——此前只有首 attach 的
                // legacy dump，renum 后进没进 CarPlay 配置、iface2/3 是不是真 NCM 全靠猜。
                debugLog(
                    "reenumerate poll: re-attached topology " +
                        IphoneCarPlayConfiguration.describeDevice(reattached),
                )
                onIphoneAttached(reattached)
                return
            }
            mainHandler.postDelayed(::pollOnce, REENUMERATION_POLL_INTERVAL_MILLIS)
        }
        mainHandler.postDelayed(::pollOnce, REENUMERATION_POLL_INTERVAL_MILLIS)
        // 看门狗
        mainHandler.postDelayed(
            {
                if (closed || phase != Phase.REENUMERATION || generation != reenumerationPollGeneration.get()) {
                    return@postDelayed
                }
                debugLog("reenumerate watchdog: no re-enumeration within ${REENUMERATION_WATCHDOG_MILLIS}ms; failing")
                reenumerationPollGeneration.incrementAndGet()
                decide(WirelessDecision.FAIL, WirelessSource.Reenumeration, error =
                    IOException(
                        "iPhone did not re-enumerate within ${REENUMERATION_WATCHDOG_MILLIS / 1000}s " +
                            "(cable/port/trust prompt); ask the user to replug",
                    ),
                )
            },
            REENUMERATION_WATCHDOG_MILLIS,
        )
    }

    private fun cancelReenumerationTimers() {
        reenumerationPollGeneration.incrementAndGet()
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.IPHONE, Phase.REENUMERATION -> {
                // 1.0.1：离开重枚举阶段（广播或轮询任一路径），停掉看门狗与轮询兜底。
                cancelReenumerationTimers()
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(expected: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    phase == expected &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        // 1.0.20（3e EMUI 图库抢 USB 实测）：授权与开路之间设备可能被图库/USB 模式切换
        // 触发重枚举，授权对象是旧实例 → hasPermission=false / openDevice=null。
        // 开路前重新枚举取当前 Apple 实例；当前实例无权限则补走一次授权流程
        //（回到 IPHONE 相位复用既有 granted→openDataPaths 链，比原地等待更稳）。
        val current = currentAppleDevice()
        val target = current ?: device
        if (current != null && current.deviceId != device.deviceId) {
            debugLog(
                "wired device instance changed before open: old id=" + device.deviceId +
                    " new id=" + current.deviceId,
            )
        }
        if (usbManager?.hasPermission(target) == false) {
            debugLog("wired current instance lacks permission; re-requesting once")
            phase = Phase.IPHONE
            permissionGrant.set(false)
            requestIphonePermission(target)
            return
        }
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        wiredOpenDeviceId = target.deviceId
        // FE-6 楔死自愈：同一设备实例曾跑过流 → iPhone 仍在 CarPlay 配置，首跳跳过
        // setConfiguration，避免多余的 re-enumerate 作废刚拿到的 USB 权限。
        val assumeConfigured = wiredEverStreamed && target.deviceId == wiredStreamedDeviceId
        if (assumeConfigured) {
            debugLog("wired self-heal retry: same device instance streamed before; assuming CarPlay configuration")
        }
        onStatus(CarPlayStatus.SelectingConfiguration)
        // vc143：重试轮次可见。version-timeout 复位重试时，界面此前每轮被
        // 「正在打开 USB 数据通道…」覆盖后进入长静默（观感=卡死，复位提示一闪
        // 而过）。轮次>0 时把轮次带进文案，静默等待期始终可见。
        val retryAttempt = wiredRetryCount.get()
        if (retryAttempt > 0) {
            onStatus(
                CarPlayStatus.RecoveryHint(
                    "正在打开 USB 数据通道…（自动重试第 $retryAttempt/$MAX_WIRED_RETRIES 轮）",
                ),
            )
        } else {
            onStatus(CarPlayStatus.OpeningDataPaths)
        }
        iphoneHost?.openIap2UsbSessionAsync(
            target,
            usbHostExecutor, // vc178：同上，建链不再占用共享 executor
            callback = { result ->
                when (result) {
                    is IphoneUsbHost.Iap2SessionResult.Connected -> {
                        try {
                            // 2026-11-07：内核 NCM 实验不打开用户态 NcmUsbBridge（它会
                            // forceClaim control/data，把内核 cdc_ncm 网卡踢掉），只保留
                            // usbmux 控制面；NCM 由内核驱动独占。
                            val ncm = if (config.wiredKernelNcmExperiment) null else openNcm(target, result.session)
                            runWiredStack(result.session, ncm)
                        } catch (error: Throwable) {
                            result.session.close()
                            decide(WirelessDecision.FAIL, WirelessSource.WiredControl, error = error)
                        }
                    }
                    is IphoneUsbHost.Iap2SessionResult.Failed -> decide(WirelessDecision.FAIL, WirelessSource.WiredIap2, error = result.error)
                }
            },
            assumeAlreadyConfigured = assumeConfigured,
        )
    }

    /** 1.0.20：重新枚举当前在位的 Apple 设备实例（图库/USB 模式切换会造成实例更替）。 */
    private fun currentAppleDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        return try {
            manager.deviceList.values.firstOrNull { it.vendorId == 0x05ac }
        } catch (error: Throwable) {
            null
        }
    }

    private fun openNcm(device: UsbDevice, usbSession: Iap2UsbSession? = null): NcmUsbBridge {
        // 1.0.19（AC822X API17 实测）：NCM 数据接口 claim 失败原为一次即终态；现比照
        // 1.0.2 usbmux 段做整连接重试（关连接→重开→重发现→再 claim），耗尽才失败。
        var target = device
        var lastError: IphoneUsbException? = null
        repeat(2) { attempt ->
            if (attempt > 0) {
                // 1.0.20：重试前重新枚举——图库/USB 模式切换重枚举后旧实例 openDevice 恒 null。
                val current = currentAppleDevice()
                if (current != null && current.deviceId != target.deviceId) {
                    debugLog("ncm retry switching to current device instance id=" + current.deviceId)
                    target = current
                }
                debugLog("ncm open retry #$attempt after claim failure")
                // vc278（有线提速）：500 → 200。上一轮已关连接重开，claim 失败是端点表问题
                // 而非「没落定」，不必多等；下一跳仍会走完整路径兜底。
                Thread.sleep(200L)
            }
            try {
                return openNcmOnce(target, attempt, usbSession)
            } catch (error: IphoneUsbException) {
                lastError = error
                // vc103g（T3 终审）：NCM 层裸 URB 探测实锤内核端点表残缺后已发 USBDEVFS_RESET
                // 强制重枚举（KERNEL_RESET_DONE 标记）。RESET 后设备断开再重枚举（数秒），
                // 等设备重新出现在 deviceList（拿到即切——RESET 后新实例/新内核表），再进
                // 下一跳重开：重开后的内核接口表/端点表是全量重解析结果，常规路径即可用。
                if (error.message?.contains("KERNEL_RESET_DONE") == true) {
                    debugLog("ncm: kernel reset done; waiting for re-enumeration before retry")
                    val deadline = System.currentTimeMillis() + 10_000L
                    while (System.currentTimeMillis() < deadline) {
                        Thread.sleep(500L)
                        val current = currentAppleDevice()
                        if (current != null) {
                            target = current
                            debugLog(
                                "ncm: device back after reset id=" + current.deviceId +
                                    " (was " + device.deviceId + ")",
                            )
                            break
                        }
                    }
                }
            }
        }
        throw lastError ?: IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
    }

    private fun openNcmOnce(device: UsbDevice, attempt: Int, usbSession: Iap2UsbSession? = null): NcmUsbBridge {
        // 调度器：>=21 且拿到多配置时走 find(configuration)，否则 findLegacy 扫默认配置。
        // 1.1.4：传入 avoid 组——NTB16 类失败重建时轮换到下一 NCM 组（优选内核未绑定组）。
        val configuration = IphoneCarPlayConfiguration.find(device)
        // vc103r（Legacy 对齐）：NCM 复用 usbmux 会话的同一 fd——内核 claim 位图按 fd
        // 记账，双 fd 状态割裂（KK 第二 fd claim 恒 ENOENT，T3 实锤）；Legacy 全程单 fd。
        // 无会话（纯 NCM 重建路径）保持原独立 openDevice。
        val connection = usbSession?.sharedConnection()
            ?: (usbManager?.openDevice(device)
                ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection"))
        // vc103p（T3 终审）：KK 上 framework 接口缓存可与内核活动配置错位（实测配置 5 vs 6），
        // 先按 sysfs bConfigurationValue + rawDescriptors 拓扑重新发现 NCM 对，失败走 legacy。
        val function = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            NcmFunctionDiscovery.findKernelAligned(device, connection.rawDescriptors)
                ?: run {
                    // vc103q（T3 用户拍板）：内核活动配置下无 NCM（0x52 重枚举后
                    // choose_configuration 停在 PTP/usbmux 初始形态，选几全看内核运气）——
                    // 不猜内核了，直接 SETCONFIGURATION(6) 强切 CarPlay 配置（T3 dmesg
                    // 实锤 legacy 用 6）。EBUSY(16)=usbmux 会话 fd 的 claim 挡路（usbfs
                    // 全设备级），挂起 usbmux claim 后重试，切完恢复 claim 再重新发现。
                    forceCarplayConfiguration(connection, usbSession)
                    NcmFunctionDiscovery.findKernelAligned(device, connection.rawDescriptors)
                        ?: NcmFunctionDiscovery.find(device, null, ncmAvoidControlId)
                }
        } else {
            NcmFunctionDiscovery.find(device, configuration, ncmAvoidControlId)
        } ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        ncmLastControlId = function.control.id
        debugLog(
            // getAlternateSetting 是 API 24+（at8317 实测 API17 崩），诊断行低版本打 0。
            // 1.0.19：补 control/data 接口类——真 NCM 应为 control=2(CDC) data=10(CDC-Data)，
            // 用于识别 ROM 拓扑误配。1.1.4：补 avoid 组与候选选择线索。
            // API16：`configuration` 来自 IphoneCarPlayConfiguration.find()，该函数在 SDK<21
            // 已提前返回 null，`?.id` 本身不会执行；这里把版本条件显式写出来，既让 lint 可证，
            // 也避免将来 configuration 从别处来非 null 值时在旧机崩。
            "ncm attempt#$attempt config=${
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) configuration?.id else null
            } kernelAligned=${function.kernelAligned} " +
                "control=${function.control.id}/${if (Build.VERSION.SDK_INT >= 24) function.control.alternateSetting else 0}" +
                " class=${function.control.interfaceClass}" +
                " data=${function.data.id}/${if (Build.VERSION.SDK_INT >= 24) function.data.alternateSetting else 0}" +
                " class=${function.data.interfaceClass}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}" +
                " avoid=${ncmAvoidControlId ?: "-"}",
        )
        // vc94：root 抢回成功后原 connection 已被 openOnce 失败路径 close，工厂重新 openDevice。
        // vc103r：共享 usbmux fd 时 ownsConnection=false（bridge 不 close 连接）且不给
        // reopen 工厂——重开新 fd 会破坏单 fd 共享，重试沿用原连接。
        return NcmUsbBridge.open(
            connection,
            function,
            reopen = if (usbSession == null) {
                { usbManager?.openDevice(device) }
            } else {
                null
            },
            ownsConnection = usbSession == null,
        )
    }

    /**
     * vc103q（T3 终审）：直接强切 CarPlay 配置——不猜内核 choose_configuration 选几。
     * 目标 6 为 T3 dmesg 实锤（legacy 成功配方 SET config #6，usbmux+NCM+DQA 形态，
     * NCM control=3/data=4）。native setConfiguration 内部自带 GETDRIVER+DISCONNECT
     * 踢内核驱动兜底；EBUSY(16) 只可能来自 usbmux 会话 fd 的 usbfs claim（usbfs 全设备
     * 级互斥），挂起→重试→恢复。切失败不硬失败（返回后由调用方重新发现，发现不到仍走
     * 原报错路径），全程写独立 trace 便于判读。
     */
    private fun forceCarplayConfiguration(connection: UsbDeviceConnection, usbSession: Iap2UsbSession?) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT || !LegacyUsbHostNative.available) return
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) return
        var errno = runCatching { LegacyUsbHostNative.setConfiguration(fd, 6) }.getOrDefault(-1)
        NcmClaimTrace.trace("vc103q direct setConfiguration(6) fd=$fd errno=$errno")
        debugLog("vc103q direct setConfiguration(6) errno=$errno")
        if (errno == 16 && usbSession != null) {
            val suspended = usbSession.suspendUsbmuxClaim()
            NcmClaimTrace.trace("vc103q EBUSY -> suspend usbmux claim ok=$suspended")
            debugLog("vc103q EBUSY -> suspend usbmux claim ok=$suspended")
            if (suspended) {
                // vc178：挂起后必须恢复——原先 resume 是直线代码，中间只要出任何 Throwable
                // （Error、NPE 等）就永久停在"已 release 接口"状态，后续 usbmux 全部失败，
                // 表象是有线连接彻底坏掉且无报错。改为 finally 保证恢复。
                try {
                    errno = runCatching { LegacyUsbHostNative.setConfiguration(fd, 6) }.getOrDefault(-1)
                    NcmClaimTrace.trace("vc103q retry setConfiguration(6) errno=$errno")
                    debugLog("vc103q retry setConfiguration(6) errno=$errno")
                } finally {
                    val resumed = usbSession.resumeUsbmuxClaim()
                    NcmClaimTrace.trace("vc103q resume usbmux claim ok=$resumed")
                    debugLog("vc103q resume usbmux claim ok=$resumed")
                }
            }
        }
    }

    /**
     * 1.0.29：wired 建立期看门狗——0x4301 后 [WIRED_ESTABLISH_WATCHDOG_MILLIS] 无流帧即关桥重建。
     * vc153（K60 实锤 0x4301 后 iPhone 完全静默、界面恒「有线链路建立中」）：判死后结束本次
     * 有线尝试并自动重试，额度 [WIRED_START_WATCHDOG_MAX_RETRIES] 次封顶；第 2 轮重试前置
     * [wiredFullResetPending] 升级为「0x52 CarPlay mode request + 重新枚举」恢复路径（对齐
     * 上游 WiredConfigurationAction.REQUEST_TRANSITION，经既有 onIphonePermission 消费）。
     * 计时器以代际令牌失活：会话激活（wiredStreamSeen 短路）与 teardown（closeWiredStack
     * 令牌递增）均使待触发计时器取消，重 arm 亦使旧计时器作废。
     */
    private fun armWiredEstablishWatchdog() {
        wiredControlReturned = false
        wiredEstablishWatchdogFired = false
        wiredStreamSeen = false
        val generation = wiredStartWatchdogGeneration.incrementAndGet()
        mainHandler.postDelayed({
            if (generation != wiredStartWatchdogGeneration.get()) return@postDelayed
            if (closed || wiredControlReturned || wiredStreamSeen) return@postDelayed
            wiredEstablishWatchdogFired = true
            val retries = wiredStartWatchdogRetries.incrementAndGet()
            debugLog(
                "wired start watchdog: no session within " +
                    "${WIRED_ESTABLISH_WATCHDOG_MILLIS}ms after 0x4301; " +
                    "ending attempt (watchdog retry #$retries/$WIRED_START_WATCHDOG_MAX_RETRIES)",
            )
            // vc153：第 2 轮重试前升级恢复路径——重试代强制 0x52 + setConfiguration 全量
            // 复位（streamFrames>0 时 wedgeSuspect 不置位，此处兜底补上 mode request）。
            if (retries >= 2) wiredFullResetPending = true
            runCatching { wiredCsm?.close() }
        }, WIRED_ESTABLISH_WATCHDOG_MILLIS)
    }

    private fun runWiredStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge?) {
        phase = Phase.CONTROL
        // vc142（TS7 实测 14:14-14:19）：USBMUX version 卡死时 begin 阻塞可达 60s，用户断开
        // 后旧 bring-up 协程仍存活，超时退出后 finally 的 ncm.close() 会关掉已被下一次连接
        // 复用的共享 fd（openOnce 同 device 复用 connection）→ 新会话全线 EBADF 崩溃。
        // 修复①：session 挂到实例字段，close() 时立即关闭 → begin 内 bulk 读秒败退出，
        // fd 归属始终清晰（close 时归 controller、运行期归会话），僵尸协程不再存活 60s。
        wiredUsbSession = usbSession
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession)
            wiredMux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            val csm = Iap2Session.open(
                carkit,
                traceContext = "wired",
                onTrace = ::debugLog,
            )
            wiredCsm = csm
            debugLog("wired iAP2 CSM channel opened")

            // vc102 USB bootstrap 无线升级分叉：CSM 就绪即改走无线引导——不开 NCM 数据面，
            // 音视频全程走 WiFi AirPlay（Apple 官方「插 USB 弹提醒可无线」流程）。
            if (config.usbWirelessUpgrade) {
                if (runWiredUsbBootstrapUpgrade(csm)) {
                    // 拔线后会话保持：AirPlay 流在 WiFi 上继续跑，CSM/USB 资源随断线回收；
                    // NCM 桥由 finally 按原语义关闭（本路径从未使用）。
                    return
                }
                throw IOException("USB bootstrap control ended before iPhone joined WiFi")
            }

            val ncmHostMac: ByteArray
            val bridge: Ipv6NcmBridge?
            if (config.wiredKernelNcmExperiment) {
                // 2026-11-07：内核 NCM 路径——不建 TUN/VPN、不建 Ipv6NcmBridge、不建 NCM 守护。
                // 失败直接抛出，由下面的 catch 结束本次会话（不回退 TUN、不自动重试）。
                val kernelNcm = attachKernelNcmNetwork(usbSession)
                    ?: throw IphoneUsbException.DeviceUnavailable(wiredAttachFailureMessage())
                wiredKernelNcm = kernelNcm
                ncmHostMac = kernelNcm.macBytes
                bridge = null
            } else {
                val activeNcm = ncm
                    ?: throw IphoneUsbException.DeviceUnavailable("NCM bridge is required outside the kernel-NCM experiment")
                ncmHostMac = activeNcm.hostMac ?: config.hostMac
                bridge = attachWiredNetwork(activeNcm, ncmHostMac)
                    ?: throw IphoneUsbException.DeviceUnavailable(wiredAttachFailureMessage())
            }
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            wiredBridge = bridge
            ncmOwnedLocally = false
            debugLog("wired NCM/AirPlay transport attached")
            if (closed) return

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            // 2026-11-07：内核模式用内核网卡地址下发 0x4301；去掉 %scope（iPhone 侧按
            // 自己链路解析）。TUN/用户态 NCM 路径仍用 fd00::2。
            val endpointAddresses = wiredKernelNcm
                ?.let { listOf(it.addressWithoutScope) }
                ?: listOf(config.linkLocal)
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = endpointAddresses,
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
                locationProvider = locationProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message -> debugLog("wired $message") },
                onStage = { stage ->
                    debugLog("wired stage=$stage")
                    // 1.0.29：0x4301 发出即 armed 建立期看门狗（健康路径 160ms 内 iPhone 就来 RTSP）。
                    if (stage == Iap2WiredControlStage.CARPLAY_START_SENT) {
                        armWiredEstablishWatchdog()
                    }
                },
                // vc187：把「AirPlay 会话是否已激活」交给 0x4301 重发判据。wiredStreamSeen
                // 由 wiredSessionListener 的 onSessionActive / onVideoFrameRendered 置位，
                // 且每次 armWiredEstablishWatchdog 会重置为 false——正是同一条真值口径。
                airPlayActive = { wiredStreamSeen },
            )
            debugLog("wired control ended terminal=${result.terminal} stage=${result.stage}")
            // 1.0.16（方案四①）：只有终态（用户已断开/controller 已关）才做网络恢复；自动重试路径不动 Wi-Fi。
            closeWiredStack(restoreNetwork = closed, reason = "control-ended terminal=${result.terminal} closed=$closed")
            // 2026-11-07：内核 NCM 实验不做自动重试（重试会掩盖「实验到底行不行」）。
            // 控制环提前结束 = iAP2 没起来，当场报明确原因并结束本次有线会话。
            if (config.wiredKernelNcmExperiment) {
                debugLog("wired kernel-ncm failed reason=control-ended terminal=${result.terminal} stage=${result.stage}")
                decide(WirelessDecision.FAIL, WirelessSource.WiredKernelNcm, error =
                    IphoneUsbException.DeviceUnavailable(
                        kernelNcmFailureMessage(
                            "iAP2 控制环在 stage=${result.stage} 提前结束（terminal=${result.terminal}）",
                        ),
                    ),
                )
                return
            }
            // 1.0.10：控制环结束 ≠ 一定是用户断开。controller 未关闭（用户没点断开、服务没停）
            // 即 iPhone 侧主动断链（FE-6 实测：iPhone 停发视频后 connection closed，App 躺平 3 分半
            // 等手动）。此时走自动重试：4s×attempt 封顶 15s，额度 5 次；第 3 次起提示用户干预。
            // 全部 API16 安全原语（Handler.postDelayed + AtomicInteger），与无线重连链同款节奏。
            if (closed) {
                onStatus(CarPlayStatus.ControlEnded)
                return
            }
            // vc153：启动看门狗重试额度耗尽——不再自动重试，落终态避免「建立中」假死循环。
            if (wiredStartWatchdogRetries.get() > WIRED_START_WATCHDOG_MAX_RETRIES) {
                debugLog("wired start watchdog retries exhausted; giving up")
                onStatus(CarPlayStatus.ControlEnded)
                return
            }
            val attempt = wiredRetryCount.incrementAndGet()
            if (attempt > MAX_WIRED_RETRIES) {
                debugLog("wired auto-retry exhausted after $MAX_WIRED_RETRIES attempts")
                onStatus(CarPlayStatus.ControlEnded)
                // vc250（VC248-09）：额度用尽后转 5min 低频续试；用户已转无线时不抢占。
                scheduleSlowAutoRetry("wired", { phase != Phase.WIRELESS }) { startIphone() }
                return
            }
            // 1.0.29：楔死类分流——0x4301 已发但 iPhone 零流帧（watchdog 关桥或自然断链均落到此形态）
            // 用长退避 ≥30s：3e 实测快速连续重试会在死 USB 栈上加压加深楔死，静置才给 usbfs 自愈窗口。
            // vc97②：判据换 streamFrames——旧判据吃 forwardedFrames 心跳污染恒 false，
            // wedge 重建置位从未生效（TS7 46s 死窗实锤 open-data-paths 短路复发）。
            val wedgeSuspect = result.stage == Iap2WiredControlStage.CARPLAY_START_SENT &&
                result.streamFrames == 0
            val delayMs = if (wedgeSuspect) {
                maxOf(WIRED_RETRY_DELAY_MAX_MILLIS, WIRED_WEDGE_RETRY_DELAY_MILLIS)
            } else {
                minOf(WIRED_RETRY_DELAY_BASE_MILLIS * attempt, WIRED_RETRY_DELAY_MAX_MILLIS)
            }
            if (attempt >= 3) {
                onStatus(
                    CarPlayStatus.RecoveryHint(
                        "iPhone 已断开连接，正在自动重连；若持续失败，请关开 iPhone 蓝牙或重新插拔数据线",
                    ),
                )
            }
            debugLog("wired peer closed; auto-retry $attempt/$MAX_WIRED_RETRIES backoff=${delayMs}ms")
            // vc93：楔死嫌疑（0x4301 已发但零流帧）→ 重建强制全量复位（self-heal 短路无效实锤）。
            if (wedgeSuspect) wiredFullResetPending = true
            mainHandler.postDelayed({
                if (closed) return@postDelayed
                startIphone()
            }, delayMs)
        } catch (error: Throwable) {
            // vc142：controller 已关闭后的到达异常（含 close() 关 session 引发的 begin 秒败）
            // 属于僵尸协程的迟到错误——直接丢弃，禁止再触碰 fail/重试/清栈（它们此时要么
            // 无害要么误伤下一次连接的资源）。
            if (closed) {
                debugLog("wired bring-up error after close; stale error discarded", error)
                return
            }
            debugLog("wired bring-up failed", error)
            // 2026-11-07：内核 NCM 实验——失败即终态，不回落 TUN、不自动重试掩盖。
            // probe / 等待超时 / bind / mDNS / iAP2 任一失败都在这里当场结束本次有线会话。
            if (config.wiredKernelNcmExperiment) {
                val detail = error.message ?: error.javaClass.simpleName
                debugLog("wired kernel-ncm failed reason=$detail")
                closeWiredStack(reason = "kernel-ncm failed: $detail")
                decide(WirelessDecision.FAIL, WirelessSource.WiredKernelNcm, error =
                    if (detail.contains("有线实验（内核 NCM）")) {
                        error
                    } else {
                        IphoneUsbException.DeviceUnavailable(kernelNcmFailureMessage(detail))
                    },
                )
                return
            }
            // vc142：USBMUX version reply 超时 = iPhone 侧 CarPlay 会话僵死（13:39/14:14 两次
            // 实证，拔插线才恢复）。归入自动重试并置全量复位——重试时 0x52+setConfiguration
            // 强制 iPhone 重新枚举，等价于用户拔插线，不再傻等 60s 后终态失败。
            if (error is IphoneUsbException.TimedOut &&
                error.message.orEmpty().contains("USBMUX version reply", ignoreCase = true)
            ) {
                closeWiredStack(restoreNetwork = false, reason = "usbmux-version-timeout")
                wiredFullResetPending = true
                val attempt = wiredRetryCount.incrementAndGet()
                if (attempt > MAX_WIRED_RETRIES) {
                    debugLog("wired usbmux-version-timeout auto-retry exhausted")
                    onStatus(CarPlayStatus.ControlEnded)
                    scheduleSlowAutoRetry("wired", { phase != Phase.WIRELESS }) { startIphone() }
                    return
                }
                onStatus(
                    CarPlayStatus.RecoveryHint("iPhone 无响应，正在复位 USB 重连（第 $attempt 次）"),
                )
                debugLog("wired usbmux version timeout; full-reset auto-retry $attempt/$MAX_WIRED_RETRIES")
                mainHandler.postDelayed({
                    if (closed) return@postDelayed
                    startIphone()
                }, WIRED_RETRY_DELAY_BASE_MILLIS * attempt)
                return
            }
            // 1.0.29：看门狗关桥引发的底层异常（close 竞态）不做终态 fail，与 wedgeSuspect
            // 正常路径同节奏：清栈 + 30s 长退避后 startIphone 重建。
            if (wiredEstablishWatchdogFired && !closed) {
                wiredEstablishWatchdogFired = false
                // vc153：启动看门狗重试额度耗尽 → 终态，不再循环重建。
                if (wiredStartWatchdogRetries.get() > WIRED_START_WATCHDOG_MAX_RETRIES) {
                    closeWiredStack(reason = "establish-watchdog-retries-exhausted")
                    onStatus(CarPlayStatus.ControlEnded)
                    return
                }
                closeWiredStack(reason = "establish-watchdog-wedge")
                onStatus(
                    CarPlayStatus.RecoveryHint("USB 连接停滞，正在重建（约 30 秒后自动重试）"),
                )
                debugLog("wired wedge fallback; rebuild in ${WIRED_WEDGE_RETRY_DELAY_MILLIS}ms")
                // vc93：watchdog 楔死重建 → 强制全量复位（0x52 + setConfiguration）。
                wiredFullResetPending = true
                mainHandler.postDelayed({
                    if (closed) return@postDelayed
                    startIphone()
                }, WIRED_WEDGE_RETRY_DELAY_MILLIS)
                return
            }
            // 1.1.2（EU5 实车「USBMUX TCP connection was reset by the peer」）：iPhone 端残留
            // 会话/USB 栈抖动把刚建的 USBMUX 控制连接 reset——多为暂时态，拔插重试即可过。
            // 归入自动重试（清栈 + 常规退避），不再直接打终态 Failed 让用户手点。
            val message = error.message.orEmpty()
            if (!closed && message.contains("reset by the peer", ignoreCase = true)) {
                closeWiredStack(reason = "usbmux-reset")
                val attempt = wiredRetryCount.incrementAndGet()
                if (attempt > MAX_WIRED_RETRIES) {
                    debugLog("wired usbmux-reset auto-retry exhausted")
                    onStatus(CarPlayStatus.ControlEnded)
                    scheduleSlowAutoRetry("wired", { phase != Phase.WIRELESS }) { startIphone() }
                    return
                }
                val delayMs = minOf(WIRED_RETRY_DELAY_BASE_MILLIS * attempt, WIRED_RETRY_DELAY_MAX_MILLIS)
                onStatus(
                    CarPlayStatus.RecoveryHint("USB 连接被 iPhone 重置，正在自动重连（第 $attempt 次）"),
                )
                debugLog("wired usbmux reset; auto-retry $attempt/$MAX_WIRED_RETRIES backoff=${delayMs}ms")
                // vc93：残留会话 reset 形态 → 重建走全量复位，清 iPhone 侧会话状态。
                wiredFullResetPending = true
                mainHandler.postDelayed({
                    if (closed) return@postDelayed
                    startIphone()
                }, delayMs)
                return
            }
            closeWiredStack(reason = "bring-up-failed ${error.javaClass.simpleName}: ${error.message ?: "-"}")
            if (error is Error) throw error
            decide(WirelessDecision.FAIL, WirelessSource.WiredRun, error = error)
        } finally {
            // 1.0.29：无论正常返回还是异常退出，控制权都已交还，取消未触发的看门狗。
            wiredControlReturned = true
            if (ncmOwnedLocally) {
                closeBestEffort("wired NCM") { ncm?.close() }
            }
        }
    }

    /**
     * vc102 USB bootstrap 无线升级（实验）：有线 CSM 就绪后改走无线引导。流程对照
     * runWireless 精简——开热点（复用 wirelessHotspotMode 组）→ AirPlay 监听 → mDNS →
     * 识别帧 group16(USB)+group24(SSID) 双宣告 → Iap2WirelessControlClient 经 USB CSM
     * 跑无线控制环（0x5703 凭据/0x4301 邀请经 USB 隧道发）。NCM 数据面整体不参与。
     *
     * 返回 true = iPhone 已从 WiFi 入网（AirPlay 有 TCP 连接），CSM 死只是拔线，会话保持；
     * 返回 false = 引导失败（iPhone 未入网控制环即退），调用方拆栈走失败处理。
     */
    private fun runWiredUsbBootstrapUpgrade(csm: Iap2Session): Boolean {
        decide(WirelessDecision.CLOSE_STACK, WirelessSource.WiredBootstrap)
        if (closed) return false
        val mfi = mfiSession?.client
            ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
        // 热点参数复用无线模式配置（RELAY/MANUAL 组）；startWirelessHotspot 内部的
        // isStaleWirelessRun 只影响 catch 分支的异常包装文案，成功路径不受 phase 影响。
        val hotspotInfo = startWirelessHotspot(wirelessGeneration.get())
        val hostAddress = hotspotInfo.hostAddress
            ?: throw IOException("USB bootstrap hotspot did not provide a usable host address")
        val hostAddressText = hostAddressText(hostAddress)
        // vc253：供 hotspot sta-arp 观测使用的网段前缀（只读诊断，不影响连接判断）。
        hotspotStaSubnetPrefix = hotspotStaSubnetPrefixOf(hostAddress)
        val deviceIdentifier = hotspotInfo.bssid
            ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
            ?: airPlayConfig.deviceId
        debugLog(
            "usb-bootstrap hotspot ssid=${hotspotInfo.ssid} channel=${hotspotInfo.channel} " +
                "backend=${hotspotInfo.backend.label} host=$hostAddressText " +
                "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"}",
        )
        onStatus(
            CarPlayStatus.HotspotReady(
                ssid = hotspotInfo.ssid,
                band = hotspotInfo.bandLabel,
                channel = hotspotInfo.channel,
                bssid = deviceIdentifier,
                address = hostAddressText,
                backend = hotspotInfo.backend.label,
            ),
        )
        onStatus(CarPlayStatus.WaitingForPairedIphone)

        val wirelessAirPlayConfig = airPlayConfig.copy(deviceId = deviceIdentifier)
        var effectiveConfig = wirelessAirPlayConfig
        onStatus(CarPlayStatus.AttachingNetwork)
        val server = AirPlayTcpServer()
        when (
            val result = server.attach(
                bindAddress = hostAddress,
                config = wirelessAirPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfi,
                listener = sessionListener,
                media = media,
                // vc250-08：USB bootstrap 走无线引导（Iap2WirelessControlClient），同属无线链路。
                handshakeIdleTimeoutMillis = AIRPLAY_HANDSHAKE_ZERO_BYTE_TIMEOUT_MILLIS,
            )
        ) {
            is AirPlayTcpServer.AttachResult.Started -> {
                airPlayServer = server
                effectiveConfig = wirelessAirPlayConfig.copy(port = result.port)
            }
            AirPlayTcpServer.AttachResult.AlreadyStarted ->
                throw IOException("USB bootstrap AirPlay transport is already attached")
            is AirPlayTcpServer.AttachResult.Failed -> throw IOException(result.message)
        }
        if (effectiveConfig.port != airPlayConfig.port) {
            debugLog(
                "usb-bootstrap AirPlay port fallback: ${airPlayConfig.port} occupied, " +
                    "using ${effectiveConfig.port} (injected into 0x5703 + Bonjour)",
            )
        }
        val bonjourClient = CarPlayBonjour(
            context = appContext,
            config = effectiveConfig,
            identity = identity,
            advertisedHost = hostAddress.hostAddress,
            useInterfaceMdns = true,
            onEvent = { event -> debugLog("usb-bootstrap bonjour: ${event.diagnosticSummary()}") },
        )
        bonjour = bonjourClient
        // mDNS 后台化（对齐无线模式 vc66 语义）：广播不在引导关键链路上，失败非致命。
        Thread(
            {
                try {
                    bonjourClient.start()
                    debugLog("usb-bootstrap Bonjour started (async)")
                } catch (error: Throwable) {
                    if (bonjour === bonjourClient) bonjour = null
                    runCatching { bonjourClient.close() }
                    debugLog(
                        "usb-bootstrap Bonjour 广播失败（非致命，继续 USB 引导段）: ${error.message}",
                    )
                }
            },
            "xcertplay-usb-bootstrap-bonjour",
        ).apply {
            isDaemon = true
            start()
        }

        // 识别帧第三形态：group16(USBHostTransport)+group24(SSID)，iAP2 承载在 USB 但宣告无线能力。
        val identification = config.identification.copy(
            usbWirelessUpgradeSsid = hotspotInfo.ssid,
        )
        val endpoint = Iap2WirelessCarPlayEndpoint(
            ssid = hotspotInfo.ssid,
            passphrase = hotspotInfo.passphrase,
            channel = hotspotInfo.channel,
            security = hotspotInfo.security,
            ipAddresses = listOf(hostAddressText),
            airPlayPort = effectiveConfig.port,
            deviceIdentifier = deviceIdentifier,
            publicKey = identity.publicKeyHex,
            sourceVersion = airPlayConfig.sourceVersion,
            bssid = hotspotInfo.bssid,
        )
        wirelessIdentification = identification
        wirelessAirPlayEndpoint = endpoint
        media.setIapTunnelHandler(::startWirelessTunnelControl)

        onStatus(CarPlayStatus.RunningControl)
        debugLog("usb-bootstrap iAP2 control starting over USB CSM")
        val controlResult = Iap2WirelessControlClient(
            session = csm,
            mfi = Iap2MfiAuthenticationClient(mfi),
        ).run(
            identification = identification,
            endpoint = endpoint,
            timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
            locationProvider = locationProvider,
            onIncoming = ::onRouteFrame,
            onProgress = { message -> debugLog("usb-bootstrap $message") },
            phoneOnAirPlay = { (airPlayServer?.acceptedConnectionCount() ?: 0) > 0 },
        )
        val acceptedTcp = airPlayServer?.acceptedConnectionCount() ?: 0
        debugLog(
            "usb-bootstrap control ended terminal=${controlResult.terminal} stage=${controlResult.stage} " +
                "wirelessCarPlayAvailable=${controlResult.wirelessCarPlayAvailableSeen} " +
                "carPlayStartSessions=${controlResult.carPlayStartSessionsSent} " +
                "postTransportConfigs=${controlResult.postTransportWiFiConfigurationsSent} " +
                "acceptedTcp=$acceptedTcp",
        )
        if (acceptedTcp > 0) {
            // 拔线保持：iPhone 已在 WiFi 会话上，USB 控制面退出不影响音视频。
            debugLog("usb-bootstrap line removed after WiFi session established; keeping session")
            return true
        }
        decide(WirelessDecision.CLOSE_STACK, WirelessSource.WiredBootstrap)
        return false
    }

    /**
     * vc180：有线 attach 失败的用户可见文案分级。
     *
     * 旧文案恒为「USB 网络通道建立失败，请拔插数据线重试」——对内核无 TUN 的车机
     * （AC822X/API17 实测：5 次全在同一处 7~19ms 内失败）是纯误导：拔插多少次都不可能
     * 成功。按原因分两类：
     * ① 结构性不可治（内核无 TUN 节点 / VpnJni 建不出接口）→ 直说不支持，引导改无线；
     * ② 其余（VPN 未授权、IPv6 ioctl 失败、等待超时）→ 保留原提示并括注技术细节，
     *    便于远程定位（细节来自 [WiredVpnService.lastFailure]）。
     */
    private fun wiredAttachFailureMessage(): String {
        val detail = lastWiredAttachFailure
        if (detail != null && detail.startsWith("kernel-ncm: ")) {
            return kernelNcmFailureMessage(detail.removePrefix("kernel-ncm: "))
        }
        return when {
            detail == null -> "Could not attach the NCM/AirPlay transport"
            detail.contains("no TUN device node") ||
                detail.contains("Cannot create interface") ||
                detail.contains("CONFIG_TUN missing") ->
                "USB 有线需要车机内核支持 VPN 隧道（TUN），本车机缺少 /dev/tun，" +
                    "有线模式不可用；请改用无线连接（车机热点/中继）"
            else -> "Could not attach the NCM/AirPlay transport ($detail)"
        }
    }

    /**
     * 2026-11-07：内核 NCM 实验失败的统一界面文案。
     * 明确说明「不是普通连不上，而是内核 NCM 不可用」，并说明退出路径（取消勾选后重连），
     * 强调本次会话已结束且**没有回退 TUN、没有自动重试**。
     */
    private fun kernelNcmFailureMessage(detail: String): String {
        // detail 形如 `class=build <技术原因>`；两类 DRIVER 失败要给出不同的人话提示：
        // build = 包里的 native 库有问题（换正确 APK 可救）；machine = 本车内核没这能力（换包无解）。
        val marker = "class="
        val failureClass: String
        val reason: String
        if (detail.startsWith(marker)) {
            val space = detail.indexOf(' ')
            failureClass = if (space > 0) detail.substring(marker.length, space) else detail.removePrefix(marker)
            reason = if (space > 0) detail.substring(space + 1) else "-"
        } else {
            failureClass = "runtime"
            reason = detail
        }
        val head = when (failureClass) {
            "build" -> "构建问题（native 库缺失或符号不匹配，需确认装的是含新库的 APK）"
            "machine" -> "机器限制（本车内核没有可用的 cdc_ncm，换版本也无法启用）"
            else -> "运行期问题"
        }
        return "有线实验（内核 NCM）不可用：$head｜$reason；" +
            "已结束本次有线会话（不回退 TUN、不自动重试）。" +
            "取消勾选「有线实验：内核 NCM」后重连可回到原路径"
    }

    private fun failKernelNcmAttach(detail: String, failureClass: String = "runtime") {
        lastWiredAttachFailure = "kernel-ncm: class=$failureClass $detail"
        // class 供日志分诊：build=构建问题（换包可救）/ machine=机器限制（内核能力不足）/
        // runtime=其它运行期原因。两类 DRIVER 失败因此不再混同一个锚点。
        debugLog("wired kernel-ncm failed class=$failureClass reason=$detail")
    }

    /**
     * vc200：拉起 WiredVpnService 并等 fd —— `request()` → `startService` → `awaitTun` 三步合一。
     * 抽成函数是为了让「失败后整轮重开一次」复用同一段时序（vc200 新增重开路径），
     * 不复制粘贴两份。
     */
    private fun requestWiredTun(timeoutMillis: Long): ParcelFileDescriptor? {
        WiredVpnService.request(config.linkLocal)
        try {
            appContext.startService(
                Intent(appContext, WiredVpnService::class.java)
                    .putExtra(WiredVpnService.EXTRA_LINK_LOCAL, config.linkLocal),
            )
        } catch (error: Throwable) {
            // 4.x 上 startService 一般可用；失败则 awaitTun 超时返回 null 走失败路径。
            debugLog("wired VPN service start failed: ${error.message}")
        }
        return WiredVpnService.awaitTun(timeoutMillis)
    }

    /**
     * 有线数据面 attach（对应上游 attachVpn，Lite 裁剪为三步）：
     * 1) WiredVpnService 仅承担 establish()（需要系统 VPN 授权），fd 交付给桥；
     * 2) AirPlayTcpServer 绑 NCM 链路本地地址（与无线共用进程内 server，互斥使用）；
     * 3) Ipv6NcmBridge 双向泵（桥 owns ncm+tun，close 时级联释放）。
     */
    private fun attachWiredNetwork(ncm: NcmUsbBridge, hostMac: ByteArray): Ipv6NcmBridge? {
        onStatus(CarPlayStatus.AttachingNetwork)
        // vc180（AC822X/API17 定案）：TUN 设备节点缺失 = 结构性不可治，立刻收手。
        // 旧行为：照走完整 USB 建链（枚举 + 选配置 + claim NCM + Lockdown 配对），
        // 实测白跑 11s 后在同一点报「wired VPN TUN unavailable」，用户只看到
        // 「请拔插数据线重试」——拔插一万次也不可能成功（内核根本没 TUN）。
        // 只判「缺失」这一个方向：节点存在但 establish 仍失败时不在此列，继续走原流程，
        // 原因由 WiredVpnService.lastFailure() 带出（vc180 新增）。
        if (!WiredVpnService.tunDevicePresent()) {
            val reason = "no TUN device node (no /dev/tun, no /dev/net/tun); the kernel has no TUN support"
            lastWiredAttachFailure = reason
            debugLog("wired TUN device node missing: $reason")
            ncm.close()
            return null
        }
        // vc74：内置 cdc_ncm 解绑守护（TS7 内核反复绑定 6.3 与 App 抢接口，外置循环实测
        // 有效后内置；无 root 静默降级）。claim(force=true) 只在 claim 时解绑一次，此后
        // 内核的周期性 rebind 由本守护处理。
        // 1.1.6(vc76)：守护开关（BuildConfig.WIRED_NCM_GUARD_ENABLED 注入）——TS7 无 root
        // 裁决实验：实测内核绑定 6.3 的 2-4s 窗口内会话无受损、NTB16 重同步可吸收错位。
        // vc77 定案：guard-off 裁决通过（无守护 80+ 分钟健康），BuildConfig 恒 false，
        // 无 root 正式支持；开关保留供未来 root 平台启用。
        if (config.wiredNcmGuardEnabled) {
            val guard = WiredNcmGuard(onDiagnostic = ::debugLog)
            guard.start()
            wiredNcmGuard = guard
        } else {
            debugLog("wired NCM guard disabled (vc76 no-root A/B build)")
        }
        var tun = requestWiredTun(WIRED_VPN_TUN_TIMEOUT_MILLIS)
        if (tun == null) {
            // vc200「残疾 VPN 重开」：establish 返回 null 多见于 KK 上 mPackage 残留 /
            // 上一次连接的 stub 未清。Service 侧已自带「prepare 复核 + 同参重建一次」，
            // 仍拿不到 fd 时在这里**整轮重开一次**：重新 request + startService，让
            // onStartCommand 用全新的 Builder 重建（等价用户「拔线再点一次」的效果）。
            // 只在失败路径触发，健康连接零成本；重开用较短超时，失败场景最多 10s+4s。
            val firstReason = WiredVpnService.lastFailure()
            debugLog("wired VPN TUN unavailable${firstReason?.let { ": $it" } ?: ""}; reopening once")
            tun = requestWiredTun(WIRED_VPN_TUN_REOPEN_TIMEOUT_MILLIS)
            if (tun == null) {
                // vc180：把 establish 的真实失败原因带出来（远程日志此前只有这一句无因的
                // 「unavailable」，看不到是未授权、内核无 TUN 还是 IPv6 ioctl 失败）。
                val reason = WiredVpnService.lastFailure()
                lastWiredAttachFailure = reason
                debugLog("wired VPN TUN unavailable after reopen${reason?.let { ": $it" } ?: ""}")
                ncm.close()
                return null
            }
            debugLog("wired VPN TUN reopened on retry")
        }
        val server = AirPlayTcpServer()
        when (val result = server.attach(
            bindAddress = InetAddress.getByName(config.linkLocal),
            config = airPlayConfig,
            identity = identity,
            pairings = pairings,
            mfi = mfiSession?.client,
            listener = wiredSessionListener(),
            media = media,
        )) {
            is AirPlayTcpServer.AttachResult.Started -> {
                airPlayServer = server
                debugLog("wired AirPlay listener attached port=${result.port}")
                // vc68：tun 地址上注册 airplay/raop mDNS 应答（iOS16.x NCM 链路发现必需）
                // vc110（T3 KK 域）：JmDNS 在老内核 tun0 上查询接收失效（pcap 实锤
                // iPhone 反复查询零应答），改用自研应答器；启动失败回退 JmDNS 通告。
                val linkLocalAddr = InetAddress.getByName(config.linkLocal)
                val responder = WiredMdnsResponder(
                    bindAddress = linkLocalAddr,
                    airPlayConfig = airPlayConfig,
                    identity = identity,
                    onDiagnostic = ::debugLog,
                )
                if (responder.start()) {
                    wiredMdns = responder
                } else {
                    val advertiser = WiredBonjourAdvertiser(
                        bindAddress = linkLocalAddr,
                        config = airPlayConfig,
                        identity = identity,
                        onDiagnostic = ::debugLog,
                    )
                    advertiser.start()
                    wiredBonjour = advertiser
                }
            }
            AirPlayTcpServer.AttachResult.AlreadyStarted -> {
                debugLog("wired AirPlay transport is already attached")
                runCatching { tun.close() }
                ncm.close()
                return null
            }
            is AirPlayTcpServer.AttachResult.Failed -> {
                debugLog("wired AirPlay attach failed: ${result.message}")
                runCatching { tun.close() }
                ncm.close()
                return null
            }
        }
        val bridge = Ipv6NcmBridge(
            ncm = ncm,
            tun = tun,
            hostMac = hostMac,
            // vc96（FE-6 断链分析）：no_audio 会话（av_path=bt_audio）没有音频 50pkt/s 恒流
            // 垫底，画面静止时视频+RTSP 本来就可能断流数秒——wedge fatal 阈值放宽到 15s
            // 防擦边误杀；有音频会话维持 6s（音频恒流在，断流即真楔死）。
            stallFatalMillis = if (airPlayConfig.disableAudioOutput) 15_000L else 6_000L,
            // vc97③：wedge 判别器探针——控制面 8s 内收过帧（iAP2 心跳间隔通常 ≤5s）即认为
            // usbmux 活；NCM 断流而控制口活=端点级真楔死（快拆），控制口同停=iPhone 停发
            // 型（FE-6 自愈形态，宽限 20s 防误拆好桥）。
            usbmuxAlive = {
                wiredControlLastRxAtNanos != 0L &&
                    System.nanoTime() - wiredControlLastRxAtNanos < USBMUX_ALIVE_WINDOW_MILLIS * 1_000_000L
            },
            onDiagnostic = ::debugLog,
            onError = { error ->
                debugLog("wired NCM bridge error: ${error.message ?: error.javaClass.simpleName}")
                // 1.1.4：NCM 桥报错（含 NTB16 内核抢占污染）→ 记住失败组，重建时轮换。
                ncmAvoidControlId = ncmLastControlId ?: ncmAvoidControlId
                // 1.1.2（EU5 104759 实测）：桥在流中楔死后自杀关桥，但 USBMUX 控制面是独立
                // 连接不受影响 → 控制环不结束 → 僵尸会话（音频 rx=0 直至拔线）。这里主动关
                // wiredCsm 让控制环正常退出，走既有 peer 自动重试链全量重建。
                runCatching { wiredCsm?.close() }
            },
        )
        // vc125：KK 内核对 tun 组播不投递应用 socket——进程内旁路，入向帧直喂应答器
        // vc127：应答出向同样旁路（KK sendto link-local 抛异常）——手工组帧直发 USB
        (wiredMdns as? WiredMdnsResponder)?.let { responder ->
            responder.setHostMac(hostMac)
            responder.inlineFrameSender = { frame -> ncm.send(frame, 2000) }
            bridge.inboundFrameTap = responder::acceptFrame
        }
        bridge.start()
        return bridge
    }

    /**
     * 2026-11-07：有线实验「内核 NCM」数据面。**无兜底**：
     * - 不检查 `tunDevicePresent()`、不 requestWiredTun、不建 Ipv6NcmBridge、不启动 WiredNcmGuard；
     * - 不打开 NcmUsbBridge（避免 forceClaim 把内核 cdc_ncm 网卡踢掉）；
     * - restore 内核驱动 → 等网卡 up → 读带 scope 的链路本地 IPv6/MAC → AirPlay 直接 bind 内核地址；
     * - 任一步失败返回 null 并留下 `kernel-ncm: <reason>`，调用方立刻结束本次会话（不回落 TUN）。
     */
    private fun attachKernelNcmNetwork(usbSession: Iap2UsbSession): KernelNcmInterface? {
        onStatus(CarPlayStatus.AttachingNetwork)
        debugLog("wired kernel-ncm: no tun/vpn, no NCM claim, no guard; kernel owns the data interface")
        val device = currentAppleDevice()
        if (device == null) {
            failKernelNcmAttach("iPhone disappeared before the kernel NCM probe")
            return null
        }
        val connection = usbSession.sharedConnection()
        val configuration = IphoneCarPlayConfiguration.find(device)
        // 内核实验**绝不**调用 forceCarplayConfiguration：SETCONFIGURATION 会解绑内核驱动。
        val function = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            NcmFunctionDiscovery.findKernelAligned(device, runCatching { connection.rawDescriptors }.getOrNull())
                ?: NcmFunctionDiscovery.find(device, null, ncmAvoidControlId)
        } else {
            NcmFunctionDiscovery.find(device, configuration, ncmAvoidControlId)
        }
        if (function == null) {
            failKernelNcmAttach("iPhone configuration does not expose an NCM function")
            return null
        }
        debugLog("wired kernel-ncm probe control-iface=${function.control.id} data-iface=${function.data.id}")
        val kernel = try {
            KernelNcmProbe.restoreAndAwait(
                connection = connection,
                controlInterfaceId = function.control.id,
                onDiagnostic = ::debugLog,
                isCancelled = { closed },
            )
        } catch (error: KernelNcmException) {
            // 日志锚点带 class=：build（符号/库不匹配）与 machine（内核无 cdc_ncm）一眼可分。
            failKernelNcmAttach(error.reasonText, error.failure.failureClass)
            return null
        } catch (error: Throwable) {
            failKernelNcmAttach("${error.javaClass.simpleName}: ${error.message ?: "-"}")
            return null
        }
        debugLog("wired kernel-ncm probe iface=${kernel.name}")
        debugLog("wired kernel-ncm addr=${kernel.addressWithScope}")

        val server = AirPlayTcpServer()
        var boundPort = 0
        when (val result = server.attach(
            bindAddress = kernel.address,
            config = airPlayConfig,
            identity = identity,
            pairings = pairings,
            mfi = mfiSession?.client,
            listener = wiredSessionListener(),
            media = media,
        )) {
            is AirPlayTcpServer.AttachResult.Started -> {
                airPlayServer = server
                boundPort = result.port
            }
            AirPlayTcpServer.AttachResult.AlreadyStarted -> {
                failKernelNcmAttach("wired AirPlay transport is already attached")
                return null
            }
            is AirPlayTcpServer.AttachResult.Failed -> {
                failKernelNcmAttach("AirPlay bind on ${kernel.name} failed: ${result.message}")
                return null
            }
        }

        // 内核模式下 mDNS 必须真实绑到内核网卡：做不到就明确失败，不静默关掉。
        val responder = WiredMdnsResponder(
            bindAddress = kernel.address,
            airPlayConfig = airPlayConfig,
            identity = identity,
            onDiagnostic = ::debugLog,
            strictBind = true,
            kernelInterfaceName = kernel.name,
        )
        if (!responder.start()) {
            val reason = responder.lastFailure ?: "wired mDNS responder failed"
            server.detach()
            airPlayServer = null
            failKernelNcmAttach("mDNS on ${kernel.name} failed: $reason")
            return null
        }
        wiredMdns = responder
        debugLog(
            "wired kernel-ncm ready (no tun/vpn) iface=${kernel.name} " +
                "addr=${kernel.addressWithScope} port=$boundPort mac=${kernel.mac}",
        )
        return kernel
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    /** 有线栈 teardown：先断 AirPlay（发 FIN，让 iPhone 先撤会话），再关控制面与数据面。
     * restoreNetwork=false 用于自动重连路径（FE-6 实测 iPhone 侧断链会周期性重建有线栈），
     * 此时不动车机 Wi-Fi，避免重连间隙反复断网。
     * vc87（fe6t5）掉线取证：fe6t3 日志两次「掉线不自动重连」无法定位 close 发起者
     * （实测一次为用户断开按钮、一次根因=对端关 event 连接），reason 全量落盘。 */
    private fun closeWiredStack(restoreNetwork: Boolean = true, reason: String = "unspecified") {
        // vc153：teardown 取消待触发的启动看门狗计时器（代际令牌失活）。
        wiredStartWatchdogGeneration.incrementAndGet()
        debugLog("wired teardown starting reason=$reason")
        val activeServer = airPlayServer
        airPlayServer = null
        timedStep("WiredAirPlayServer") { activeServer?.detach() }
        // vc68：mDNS 应答器在 AirPlay 服务器之后收（goodbye 随 close 异步发出）
        val activeMdns = wiredMdns
        wiredMdns = null
        timedStep("WiredMdns") { activeMdns?.stop() }
        val activeBonjour = wiredBonjour
        wiredBonjour = null
        timedStep("WiredBonjour") { activeBonjour?.close() }
        // vc74：解绑守护最先停（避免 teardown 期间 su 轮询干扰收尾）
        val activeGuard = wiredNcmGuard
        wiredNcmGuard = null
        timedStep("WiredNcmGuard") { activeGuard?.close() }

        val activeCsm = wiredCsm
        wiredCsm = null
        timedStep("WiredCsm") { activeCsm?.close() }

        val activeMux = wiredMux
        wiredMux = null
        timedStep("WiredUsbMux") { activeMux?.close() }

        val activeBridge = wiredBridge
        wiredBridge = null
        timedStep("WiredNcmBridge") { activeBridge?.close() }
        // 2026-11-07：内核 NCM 实验没有 bridge，只有一份探测快照；teardown 时清掉。
        wiredKernelNcm = null

        // bridge 未建立而中途失败的残留兜底（幂等）。
        timedStep("WiredVpnTun") { WiredVpnService.shutdownTun() }
        // M6k24m（1.0.0-demo）：USB 断开必须显式停掉 VPN 服务——EMUI 上仅关 fd 后
        // 系统 VPN 状态可能残留，用户侧表现为断开后流量仍走死代理，Gitee 上传失败。
        try {
            appContext.stopService(Intent(appContext, WiredVpnService::class.java))
        } catch (error: Throwable) {
            debugLog("wired VPN service stop failed: ${error.message}")
        }
        // 1.0.16（方案四①）：USB 断开后「车机网络未恢复」取证 + 自动恢复——终态断开
        // （用户断开/失败/关闭）时探测 DNS，失败则把 Wi-Fi 关→1.2s→开 强制 ConnectivityService
        // 重评网络（fd 已关但系统未切回的 VPN 残留兜底）。自动重连路径（restoreNetwork=false）
        // 完全跳过探测与恢复，不拖慢重连节奏。
        if (restoreNetwork) {
            val dnsReachable = try {
                java.net.InetAddress.getByName("gitee.com")
                debugLog("wired post-teardown network: dns=gitee.com OK")
                true
            } catch (dnsError: Throwable) {
                debugLog("wired post-teardown network: dns=gitee.com FAIL ${dnsError.javaClass.simpleName}")
                false
            }
            try {
                val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                val active = cm?.activeNetworkInfo
                debugLog(
                    "wired post-teardown network: active=" +
                        (active?.let { "${it.typeName}/${it.detailedState}" } ?: "none"),
                )
            } catch (error: Throwable) {
                debugLog("wired post-teardown network probe failed: ${error.message}")
            }
            if (!dnsReachable) {
                val wifi = appContext.getSystemService(android.content.Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager
                if (wifi != null) {
                    // 1.0.17（C40DB 实测）：部分车机 ROM 的 isWifiEnabled 会误报 false
                    // （active=WIFI/CONNECTED 但 WifiManager 报关），原来这种情况兜底被跳过。
                    // 现在统一处理：开着 → 关→1.2s→开 重评；报关 → 直接 enable 尝试拉起。
                    val wasEnabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
                    if (wasEnabled) {
                        debugLog("wired post-teardown network: dns FAIL → Wi-Fi 重开强制重评")
                        runCatching { wifi.isWifiEnabled = false }
                            .onFailure { debugLog("wired network restore: Wi-Fi off failed ${it.message}") }
                        try {
                            Thread.sleep(1_200)
                        } catch (_: InterruptedException) {
                        }
                    } else {
                        debugLog("wired post-teardown network: dns FAIL + isWifiEnabled=false（疑似 ROM 误报）→ 直接 enable")
                    }
                    runCatching { wifi.isWifiEnabled = true }
                        .onFailure { debugLog("wired network restore: Wi-Fi on failed ${it.message}") }
                    try {
                        java.net.InetAddress.getByName("gitee.com")
                        debugLog("wired post-teardown network: dns after Wi-Fi re-toggle OK")
                    } catch (retryError: Throwable) {
                        debugLog(
                            "wired post-teardown network: dns after Wi-Fi re-toggle FAIL " +
                                retryError.javaClass.simpleName,
                        )
                    }
                }
            }
        }
        debugLog("wired teardown complete")
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    /**
     * vc284：设备缓存里是否含 CarPlay iAP2 服务 UUID（`getUuids` 只读本地缓存，API15+）。
     * 不发 SDP；API31+ 无 BLUETOOTH_CONNECT 时按「不含」处理，不影响名字过滤路径。
     */
    private fun hasCachedIap2Uuid(device: BluetoothDevice): Boolean = try {
        device.uuids?.any { it.uuid.toString().equals(IAP2_IPHONE_UUID, ignoreCase = true) } == true
    } catch (_: Exception) {
        false
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    } catch (error: Exception) {
        // API16 适配：ReflectiveOperationException 是 API 19+，统一按 Exception 捕获。
        false
    }

    /**
     * vc284：HFP/A2DP 连接态改为**并发、共用 3s 总预算**（原为串行各 3s，最坏 6s）。
     * 语义不变——调用方只消费「已连接设备集合」。超时只影响消歧速度，不影响选择结果：
     * 拿到的那一路照样可用，超时的那一路在日志里点名。
     */
    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> {
        val profiles = listOf(
            "HEADSET" to (BluetoothProfile.HEADSET to BluetoothHeadset::class.java),
            "A2DP" to (BluetoothProfile.A2DP to BluetoothA2dp::class.java),
        )
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val completed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val latch = CountDownLatch(profiles.size)
        val startedAt = SystemClock.elapsedRealtime()
        for ((name, profileSpec) in profiles) {
            collectConnectedDevices(
                adapter = adapter,
                profile = profileSpec.first,
                profileClass = profileSpec.second,
                devices = devices,
                profileName = name,
                completed = completed,
                latch = latch,
            )
        }
        if (!latch.await(CONNECTED_PROFILE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            val missing = profiles.map { it.first }.filter { it !in completed }
            Log.w(
                IphoneCarPlayConfiguration.TAG,
                "Timed out reading Bluetooth profiles=${missing.joinToString(",")} " +
                    "after ${SystemClock.elapsedRealtime() - startedAt}ms",
            )
        }
        return synchronized(devices) { devices.toSet() }
    }

    private fun <T : BluetoothProfile> collectConnectedDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
        devices: MutableSet<BluetoothDevice>,
        profileName: String,
        completed: MutableSet<String>,
        latch: CountDownLatch,
    ) {
        // 同一 profile 可能先后回调 connected/disconnected：只允许完成一次计数，
        // 否则 latch 提前归零会把另一路的预算截断。
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) {
                completed.add(profileName)
                latch.countDown()
            }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    finish()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                finish()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) {
            // 注册失败也要立即收尾，否则这一路白白吃掉 3s 预算。
            finish()
        }
    }

    /** vc93：蓝牙开关双证据源。adapter.isEnabled 可能被 OEM 适配器代理滞后/失灵
     *  （235233 ECARX 实锤：用户已进系统页开启仍报 false），Settings.Secure
     *  bluetooth_on 作第二证据，任一为开即视为开。 */
    private fun isBluetoothEffectivelyEnabled(adapter: BluetoothAdapter): Boolean {
        val adapterOn = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (adapterOn) return true
        val settingsOn = runCatching {
            Settings.Secure.getInt(appContext.contentResolver, "bluetooth_on", 0) == 1
        }.getOrDefault(false)
        if (settingsOn) {
            debugLog("bluetooth adapter reports off but Settings.Secure bluetooth_on=1 (OEM state lag) — treating as on")
        }
        return settingsOn
    }

    /** vc93：双源都关时自动补开蓝牙（对齐中继模式「发起前自动检测并开启」约定），
     *  500ms 轮询 ≤8s；false=补开失败（由调用方抛错引导用户）。 */
    @Suppress("DEPRECATION")
    private fun ensureBluetoothEnabled(adapter: BluetoothAdapter): Boolean {
        val requested = runCatching { adapter.enable() }.getOrDefault(false)
        debugLog("bluetooth both sources off — auto enable requested=$requested")
        repeat(16) {
            Thread.sleep(500)
            if (isBluetoothEffectivelyEnabled(adapter)) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        val live = listOfNotNull(address, settingsAddress).firstOrNull {
            BLUETOOTH_ADDRESS.matches(it) &&
                !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
        }
        // M6k24（D' 方案）配件身份稳定：本机蓝牙地址一旦取到就进程级缓存。
        // 8815 实测（11:52:44）：僵尸恢复 disable/enable 适配器窗口内 address 取不到，
        // 回落成 deviceId（96:…:89）——iPhone 看到同一热点下配件蓝牙身份漂移，pair-verify 后弃连。
        // 修复：取不到时沿用上一次成功值，绝不在代际间漂移。
        if (live != null) {
            cachedLocalBtMac = live
            return live
        }
        return cachedLocalBtMac ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Wireless hotspot host address is unavailable")
        }
        return text
    }

    /**
     * vc310（HOT-309-01 阶段1，只观测）：对比「本轮将下发的 BSSID」与「接口当前真实身份」。
     * 只读网络接口，不发包、不改任何状态；取不到时记 observed=unknown 且 changed=false。
     * 已知盲区：只走 EUI-64 反解（hardwareAddress 被 ROM 屏蔽时唯一可用路径），
     * 若某机型 hardwareAddress 可读而 IPv6 非 EUI-64，会出现 observed=unknown —— 属预期，不要改。
     */
    private fun observeHotspotIdentity(source: String, generation: Int, info: WirelessHotspotInfo) {
        val observed = HotspotInterfaceBssid.read(info.interfaceName)
        val issued = info.bssid
        val changed = observed != null && issued != null && !observed.equals(issued, ignoreCase = true)
        debugLog(
            "hotspot identity source=$source gen=$generation " +
                "iface=${info.interfaceName ?: "unknown"} " +
                "issued=${issued ?: "unknown"} observed=${observed ?: "unknown"} changed=$changed",
        )
        startHotspotLivenessSampler(generation, info.interfaceName)
    }

    /**
     * vc310（P0-302-02 P0，只观测）：每 [HOTSPOT_LIVENESS_SAMPLE_MILLIS] 读一次热点接口，
     * **只在快照变化时**打一行。世代绑定：closed / 离开 WIRELESS / generation 变更即下一拍退出。
     * 纯读，不参与任何判断；不绑端口、不发包、不碰 manager。
     */
    private fun startHotspotLivenessSampler(generation: Int, interfaceName: String?) {
        val name = interfaceName ?: return
        if (livenessSamplerGeneration == generation) return // 同代只起一次
        livenessSamplerGeneration = generation
        Thread(
            {
                var last = ""
                while (true) {
                    if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                        return@Thread
                    }
                    val now = snapshotHotspotInterface(name)
                    if (now != last) {
                        debugLog("hotspot liveness gen=$generation $now")
                        last = now
                    }
                    try {
                        Thread.sleep(HOTSPOT_LIVENESS_SAMPLE_MILLIS)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "xcertplay-hotspot-liveness",
        ).apply { isDaemon = true }.start()
    }

    /**
     * vc310：热点接口的一次只读快照（present / up / ipv4 / mac）。
     * 平台 API 异常必须记类名；**禁止** runCatching{}.getOrDefault(false)——
     * NoSuchMethodError 是 Error，吞掉会把「读不到」误判成「接口不在」。
     */
    private fun snapshotHotspotInterface(interfaceName: String): String = try {
        val nif = NetworkInterface.getByName(interfaceName)
        if (nif == null) {
            "iface=$interfaceName present=false"
        } else {
            val ipv4 = nif.inetAddresses.toList()
                .firstOrNull { it is Inet4Address }?.hostAddress ?: "none"
            val mac = HotspotInterfaceBssid.read(interfaceName) ?: "unknown"
            "iface=$interfaceName present=true up=${nif.isUp} ipv4=$ipv4 mac=$mac"
        }
    } catch (failure: Throwable) {
        "iface=$interfaceName read-failed=${failure.javaClass.simpleName}"
    }

    /**
     * vc253：热点侧 STA 关联观测（只读诊断，不参与任何连接判断）。
     *
     * 车机是热点（AP），iPhone 关联后会出现在热点网段的 ARP 表里。把它落进日志后，
     * `credential-no-join` / `credential-no-auth` 才能再分成 (associated) 与
     * (not-associated)——「入网慢」和「压根没来」不再同形。只读 /proc/net/arp：
     * 无 root 可读，不触发任何网络动作，也不改变连接判断。
     */
    private fun logHotspotStaArp(reason: String) {
        val prefix = hotspotStaSubnetPrefix
        val (entries, excludedCountValue) = try {
            val file = File("/proc/net/arp")
            if (!file.exists()) {
                debugLog("hotspot sta-arp at=$reason subnet=$prefix unreadable=no-file")
                return
            }
            val excluded = localStaExclusionAddresses()
            var excludedCount = 0
            val peers = file.useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    val ip = columns.firstOrNull()?.takeIf { it.startsWith(prefix) }
                        ?: return@mapNotNull null
                    // vc287：与仲裁判定同口径——本机地址与 DHCP 网关不计入 STA。
                    // 旧实现在中继模式下把网关当成「已有 STA」，日志与闸门结论互相矛盾
                    //（C8815 12:47:27 实测：日志 count=1 .218，闸门却判 associated=false）。
                    if (ip in excluded) {
                        excludedCount++
                        return@mapNotNull null
                    }
                    // vc278：补 flags 与脱敏 MAC。只打 IP 时分不清「iPhone 已入网」和
                    // 「上一会话残留的其他设备」——现场实测 0x5703 发出后 3ms 就有 .182，
                    // 只能是残留表项。flags=0x2 才是完整表项，0x0/0x? 表示对端未应答。
                    StaArpPeer(
                        ip = ip,
                        flags = columns.getOrNull(2) ?: "?",
                        mac = maskStaMac(columns.getOrNull(3)),
                    )
                }.toList()
            }
            peers to excludedCount
        } catch (error: Throwable) {
            debugLog(
                "hotspot sta-arp at=$reason subnet=$prefix " +
                    "unreadable=${error.javaClass.simpleName}",
            )
            return
        }
        val ipText = if (entries.isEmpty()) "none" else entries.joinToString(",") { it.ip }
        val macText = if (entries.isEmpty()) {
            "none"
        } else {
            entries.joinToString(",") { "${it.mac}(${it.flags})" }
        }
        // vc294：`associated=` 改与闸门同口径（只认 ATF_COM 表项）——否则 iPhone 断开后
        // 脏行(0x0)会让日志说 associated=true 而闸门判 false，重现 vc287 那种自相矛盾。
        debugLog(
            "hotspot sta-arp at=$reason subnet=$prefix " +
                "associated=${entries.any { arpFlagsComplete(it.flags) }} " +
                "count=${entries.size} ips=$ipText macs=$macText excluded=$excludedCountValue",
        )
        // vc272：观测到新 STA IP 即喂给单播通告器（幂等去重，announcer 未启动时为 no-op）。
        for (entry in entries) {
            runCatching { hotspotStaArpListener?.invoke(InetAddress.getByName(entry.ip)) }
        }
    }

    /**
     * vc278：/proc/net/arp 的对端 MAC 只保留 OUI + 稳定短哈希（`3c:22:fb:**:**:**#a1b2`），
     * 口径与上传件的通用 MAC 打码一致：现场既能在同一热点下区分不同 iPhone（OUI 判厂商、
     * 哈希判同一台），又不把完整硬件地址写进日志/上传件。
     */
    private fun maskStaMac(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.US) ?: return "unknown"
        val parts = value.split(":")
        if (parts.size != 6 || parts.any { it.length != 2 }) return "unknown"
        var hash = 2166136261u.toInt()
        for (ch in value) {
            hash = hash xor ch.code
            hash *= 16777619
        }
        val tag = String.format(Locale.US, "%04x", hash and 0xFFFF)
        return parts.take(3).joinToString(":") + ":**:**:**#" + tag
    }

    private data class StaArpPeer(val ip: String, val flags: String, val mac: String)

    /** vc294：ARP flags 文本（如 `0x2`）是否含 ATF_COM——闸门与诊断日志共用同一判据。 */
    private fun arpFlagsComplete(flags: String?): Boolean {
        val parsed = flags?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16)
            ?: return false
        return parsed and ARP_FLAG_COMPLETE != 0
    }

    /**
     * vc284：/proc/net/arp 的 STA 关联读数。**null = 表不可读**（区别于「可读但为空」）——
     * 旧实现把不可读直接折叠成 false，正是 0x4301 前盲等 8.1s 的根源。
     *
     * vc285：排除「本机地址」与「DHCP 网关」。中继模式下本机只是外部热点的一个 STA，
     * ARP 表里必然常驻网关条目（C8815 实测 192.168.250.218），旧逻辑把网关当成
     * 「已有 STA 关联」→ 闸门首检 true → 0x4301 早于 iPhone 入网 24.7s 发出
     * （逼近 iOS 30s 超时）。排除后只认第三方对端。
     *
     * vc294：只认 flags 含 ATF_COM(0x2) 的行。旧逻辑只看「行存在」，iPhone 断开后
     * 内核把那行改成 flags=0x0（ATF_INCOMPLETE，解析失败）却永不回收——该表只有 2 条，
     * 远低于 gc_thresh1(128)，周期清理不会动它。于是闸门恒判「已关联」直接短路：
     * 既让 vc266 的 10 分钟 warm 额度永不参与决策（C8815 实测 2m25s 内 5 代重连
     * 全走 true 分支、`start-session gate` 零次），又把 0x4301 提前到 iPhone 入网前
     * 发出（iOS 侧多花约 35s，正是闸门本身要避免的）。0x0 表示对端不在，不能当作已关联。
     */
    private fun hotspotStaArpState(): Boolean? {
        val prefix = hotspotStaSubnetPrefix
        return try {
            val file = File("/proc/net/arp")
            if (!file.exists()) {
                null
            } else {
                val excluded = localStaExclusionAddresses()
                file.useLines { lines ->
                    lines.drop(1).any { line ->
                        val columns = line.trim().split(Regex("\\s+"))
                        val address = columns.firstOrNull()
                        // vc294：ATF_COM(0x2)=已完成解析。flags=0x0 是解析失败的残行。
                        arpFlagsComplete(columns.getOrNull(2)) &&
                            address != null && address.startsWith(prefix) && address !in excluded
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** vc285：ARP 关联判定的排除集——本机全部 IPv4 地址 + 当前 DHCP 网关。 */
    private fun localStaExclusionAddresses(): Set<String> {
        val excluded = HashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return excluded
            while (interfaces.hasMoreElements()) {
                val inetAddresses = interfaces.nextElement().inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val address = inetAddresses.nextElement()
                    if (address is Inet4Address) {
                        address.hostAddress?.substringBefore('%')?.let { excluded.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
            // 读不到本机地址时保持空集：宁可回到旧行为，也不要误判为「未关联」。
        }
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val gateway = wifi?.dhcpInfo?.gateway ?: 0
            if (gateway != 0) {
                // Android 的 DhcpInfo.gateway 是小端 int：低字节是第一个八位。
                excluded.add(
                    String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        gateway and 0xff,
                        (gateway shr 8) and 0xff,
                        (gateway shr 16) and 0xff,
                        (gateway shr 24) and 0xff,
                    ),
                )
            }
        } catch (_: Throwable) {
            // 网关读不到时只排除本机地址。
        }
        return excluded
    }

    /**
     * vc284：0x4301 前的 STA 关联态三态判定。
     *
     * - true：已关联——SoftApCallback 客户端数 > 0，或 ARP 有本网段对端；
     * - false：有否定证据——SoftAp 明确报 0 且 ARP 可读为空；或 ARP 可读为空且历史 warm
     *   启发式不成立（vc266/vc276 行为保留）；
     * - null：**未知**——ARP 读不到，且拿不到 SoftApCallback 客户端数。上层据此把等待
     *   封顶 2s（原为固定 8.1s）。
     *
     * API16~20 没有 SoftApCallback（API30+），ARP 读不到继续按 false 处理，路径一行不改。
     * SoftApCallback 由 ManualHotspotManager 注册，`onConnectedClientsChanged` 事件更新
     * `staClientCount()`（volatile 读），所以这里拿到的是事件驱动的实时值而非轮询猜测。
     */
    private fun hotspotStaAssociationState(): Boolean? {
        val clients = hotspot?.staClientCount() ?: -1
        val arp = hotspotStaArpState()
        if (clients > 0 || arp == true) return true
        if (arp == false) {
            // vc276：部分 ROM 的 SoftAp 客户端数恒报 0，只有 ARP 可读为空时才信任它。
            return if (clients == 0) false else recentlyConnectedOverAirPlay()
        }
        // ARP 不可读：warm 会话照旧直接放行（vc266 启发式）。
        if (recentlyConnectedOverAirPlay()) return true
        // API16~20 没有 SoftApCallback，保持原语义「未关联」；21+ 记为未知。
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT_WATCH) false else null
    }

    /** vc253：由热点自身 IPv4 推导 STA 观测网段前缀；IPv6/异常时回落约定网段。 */
    private fun hotspotStaSubnetPrefixOf(address: InetAddress): String {
        if (address !is Inet4Address) return DEFAULT_HOTSPOT_STA_SUBNET_PREFIX
        val text = address.hostAddress?.substringBefore('%') ?: return DEFAULT_HOTSPOT_STA_SUBNET_PREFIX
        val lastDot = text.lastIndexOf('.')
        if (lastDot <= 0) return DEFAULT_HOTSPOT_STA_SUBNET_PREFIX
        return text.substring(0, lastDot + 1)
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        }
    }

    /**
     * M6k9：teardown 逐资源打点。每条独立 try，失败只记 completed=false、不中断其余步骤
     * （下代 bring-up 自会重建）；耗时用于判断是哪一步拖慢重连。资源为 null 时按 completed=true
     * 记一条（表示「本代未持有，无需拆」），保证 5 步每代都有完整痕迹。
     */
    private inline fun timedStep(name: String, close: () -> Unit) {
        val startedNs = System.nanoTime()
        var ok = true
        try {
            close()
        } catch (error: Throwable) {
            ok = false
            debugLog("$name teardown failed", error)
        }
        debugLog(
            "teardown resource=$name completed=$ok " +
                "elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000}",
        )
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun fail(error: Throwable, failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown) {
        if (closed) return
        // M6k11：重连链终结后清掉僵尸模式，防止污染用户下一次手动重连的退避策略。
        wirelessZombieMode = false
        lastWirelessFailureClass = null
        val resolved = if (failureClass != CarPlayFailureClass.Unknown) {
            failureClass
        } else {
            error.carPlayFailureClass()
        }
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName, resolved))
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            // 文件日志只收单行 UI 回调：把栈顶几帧拼进来，远程排障不再盲猜 NPE 位置。
            val topFrames = error.stackTrace
                .take(4)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            uiListener?.onDebugLog(
                "$message: ${error.javaClass.simpleName}: ${error.message ?: "-"} [$topFrames]",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: loading the local offline MFi identity"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: attaching to the car's own hotspot"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            // 该状态同时被“真交接完成”和“handoff degraded（无 type-130，蓝牙保持）”两条路径使用。
            // 原文案宣称 tunnel ready / 蓝牙已释放，在 degraded 路径上与事实不符（T25 实测：
            // 之后仍有 credential resend 走蓝牙），会误导排查，故改为中性描述。
            "STEP wireless/active: wireless CarPlay session active"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: starting the AirPlay TCP listener"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: discovering a connected iPhone"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: waiting for an iPhone to be plugged in"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB permission for the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenumerate: waiting for the iPhone to re-enumerate"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the CarPlay USB configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/open: opening the USBMUX and NCM data paths"
        CarPlayStatus.Pairing ->
            "STEP usb/pair: performing Lockdown pairing with the iPhone"
        CarPlayStatus.ConnectingControl ->
            "STEP usb/carkit: opening com.apple.carkit.service over lockdown"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
        is CarPlayStatus.RecoveryHint ->
            "HINT $message"
    }

    /** M6k：排队中的一条触摸事件。[sticky] 标记它是否代表触点状态变化（不可被 MOVE 覆盖）。 */
    private class PendingTouch(val contacts: List<AirPlayContact>, val sticky: Boolean)

    companion object {
        /** vc267：进程级 warm 时刻（每次连接会新建 controller，实例字段跨会话会丢）。 */
        @Volatile
        private var lastAirPlayTcpAtElapsedMillisShared: Long = 0L

        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        // M6k24（D' 方案）：本机蓝牙地址进程级缓存（accessoryBluetoothMac 写入/消费）。
        @Volatile private var cachedLocalBtMac: String? = null
        // —— USB 有线（U3）——
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        // vc278（有线提速）：500 → 120。权限轮询是纯本地状态查询，授权后应立刻推进建链。
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 120L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        // vc278（有线提速）：2s → 500ms。插线后 iPhone 由图库模式切到 CarPlay 组态固定
        // 1~3s，轮询越密越早动起来；纯 listDevices 遍历，成本可忽略。
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 500L
        // vc96（FE-6 实测 94s 黑等）：WaitingForIphone 超此时长提示用户拔插 USB 线。
        private const val IPHONE_WAIT_PROMPT_MILLIS = 10_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val WIRED_VPN_TUN_TIMEOUT_MILLIS = 10_000L
        // vc200：establish 失败后整轮重开的等待上限。只在失败路径用，取短值是为了让
        // 「确定失败」的车机（内核无 TUN 等）不至于把失败场景拖到 10s+10s。
        private const val WIRED_VPN_TUN_REOPEN_TIMEOUT_MILLIS = 4_000L
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        /** vc310：热点接口存活采样周期（只观测；5s 一拍，只在快照翻转时打一行）。 */
        private const val HOTSPOT_LIVENESS_SAMPLE_MILLIS = 5_000L
        // vc156（B 级补充路径）：P2P fallback 建组超时（对齐上游 0.2.21 WIFI_P2P_START_TIMEOUT_MILLIS；
        // 信道计划逐项尝试 + 组建 + 接口地址等待，20s 为上游实测值）。
        private const val P2P_FALLBACK_START_TIMEOUT_MILLIS = 20_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        /**
         * vc250-08：TCP 锚点看门狗窗口。与 handoff 看门狗同量级——从首个 AirPlay
         * TCP accept 起算，超时仍无 disableBluetooth 且已有渲染帧即按 degraded 完成。
         * 取 45s 不抢在正常 handoff 路径（同样 45s、但以 disableBluetooth 为起点）之前。
         */
        private const val WIRELESS_TCP_ANCHOR_TIMEOUT_MILLIS = 45_000L
        // M6k20：first-TCP 看门狗超时。vc83 30s→60s：082530 日志实证热点直连模式 iPhone
        // 实际入网 39s（0x5703 首发→pair-setup），30s 判死差 9s；60s 覆盖慢入网，
        // fire 后仍有 35s 宽限复查兜底（总上限 60+35=95s）。
        private const val FIRST_TCP_TIMEOUT_MILLIS = 60_000L

        /**
         * 1.1.5（提速）：first-TCP 看门狗宽限复查窗口。10-12 实机三样本：iPhone 实际入网
         * 距 0x5703 首发需 25~35s（第 3 轮才成），而 0x5703 重发预算为 12×5s=60s；
         * 30s 一刀切 teardown 会杀在 iPhone 入网途中。fire 后先给宽限，窗口内 TCP 进入
         * 即放行，仍为 0 才 fail（总上限 60+35=95s，仍短于 handoff 45s+重试循环的总代价）。
         */
        private const val FIRST_TCP_GRACE_MILLIS = 35_000L
        /**
         * vc289（C8815 中继实测 13:22~13:29）：本纪元已出过画后的掉线重连，iPhone 不在网
         * 是确定事实，却仍按首连节奏盲等 60s+35s——5 次重试要耗 9 分钟才进慢轮。
         * 重连代次改用 25s+10s：覆盖「在网但握手慢」，同时把单轮从 95s 压到 35s。
         */
        private const val FIRST_TCP_TIMEOUT_WARM_MILLIS = 25_000L
        private const val FIRST_TCP_GRACE_WARM_MILLIS = 10_000L
        /**
         * vc306：车机热点（MANUAL/MANUAL_IPV4）且本代凭证已下发时的 first-TCP 宽限。
         * K60 20261116 10:22 实测（T2 补测 gen6）：10:22:49.255 收到 0x5702、10:22:49.256 下发
         * 0x5703（SSID/密码/wirelessIP 全对，每 5s 重发至 #6），iPhone 直到 10:23:35.688 才发起
         * 首个 TCP —— **46.4s**，而 warm 档 25s+10s 在 10:23:13.335 就判死了整代（砍早 21s）。
         * 换代后 gen7 仅 1.3s 即连上，证明不是「iPhone 没连」，是「我们砍太早」。
         * 凭证已下发 = iPhone 明确要求入网，与「iPhone 不在网」互斥，只能放宽不能收紧。
         * 25s + 45s = 70s，对 46.4s 留 23.6s 余量。
         * **仅 MANUAL/MANUAL_IPV4 生效**：RELAY 下 STA 常驻关联，凭证已发+零 TCP 仍指向
         * 「iPhone 不在」，vc289 的 25s+10s 原样保留。
         */
        private const val FIRST_TCP_GRACE_CREDENTIAL_MILLIS = 45_000L
        /** [RELAY-TEST] 实验模式下 first-TCP 看门狗超时：须覆盖三阶段总时长（~134s）。 */
        private const val RELAY_TEST_FIRST_TCP_TIMEOUT_MILLIS = 150_000L

        // 1.0.1：重枚举阶段看门狗（15s 显性失败）与轮询兜底（1.1.4 起 500ms）
        // /样本日志节拍（polls%8，约 4s 一打）。
        private const val REENUMERATION_WATCHDOG_MILLIS = 15_000L
        // 1.1.4：2s→500ms（对齐上游 400ms）——重枚举通常 1~3s，2s 轮询白耗时间且
        // 与看门狗窗口不匹配；500ms 让重枚举完成后尽快进入权限请求。
        // vc278（有线提速）：500ms → 120ms。重枚举通常 1~3s 完成，加密轮询让完成后立刻
        // 进入权限请求；仍是纯 listDevices 遍历。
        private const val REENUMERATION_POLL_INTERVAL_MILLIS = 120L
        // M6k23：未建流阶段控制流 peer EOF 触发整代重启的阈值（连续次数）。
        private const val PRE_STREAM_EOF_RESTART_THRESHOLD = 2
        // vc304：会话结束后「快邀请 + 快换代」。首拍 1s、次拍 1.5s 后，两拍无应答即换代。
        // 依据 K60 2026-11-16 09:04 实测定案：旧节奏（3s/30s + 3×10s probe）93s 全空转，
        // 而换代 bringUpMs=1624、拆栈到首个 TCP 0.52s；且手机重新入网后旧控制链路上的
        // 0x4301 邀请 3 拍全部无应答 ⇒ 等待本身无价值，不应优化而应删除。
        private const val PEER_END_INVITATION_MAX_SENDS = 2
        private const val PEER_END_INVITATION_FIRST_DELAY_MILLIS = 1_000L
        private const val PEER_END_INVITATION_RETRY_DELAY_MILLIS = 1_500L
        // M6h：链路掉线后的自动重连（次数 + 间隔）。
        // M6k9：额度 3 → 5，间隔由固定 5s 改为指数退避（4s×attempt，封顶 15s）；
        // 重建会话成功（WirelessActive / degraded）即 attempt 归零，避免陈旧计数放大退避。
        private const val MAX_WIRELESS_RETRIES = 5
        private const val WIRELESS_RETRY_DELAY_BASE_MILLIS = 4_000L
        private const val WIRELESS_RETRY_DELAY_MAX_MILLIS = 15_000L
        /**
         * vc299（方案 A）：车机热点未开启时的等待轮询间隔。热点一开最多等这么久就自动重连；
         * 读 AP 状态是纯本地探测，不建链、不占 auto-retry 额度。
         */
        private const val CAR_HOTSPOT_WAIT_POLL_MILLIS = 5_000L
        private const val CAR_HOTSPOT_WAIT_HINT =
            "车机热点未开启：请在车机设置中打开热点，开启后会自动连接"
        // vc252：凭证锚点窗口——0x5703 下发后该时长内无任何 AirPlay TCP accept 即整代重建。
        // 现场可改这一个数：设小更激进（更快重建，误伤慢入网 iPhone 的概率更高），设大更保守。
        // vc253：8s→12s——冷路径实测首轮 0x5703 后 8139ms 才有 TCP，仅差 139ms 被判死。
        // vc255：拆成「首轮 / 重试轮」两档——12s 仍用于重试轮；首轮放宽到 45s。
        //   依据：同一台 K60 冷态从未低于 21.7s，成功样本集中在 21.7~38.3s，12s 锚点是在
        //   成功途中打断（vc253 冷态首轮 12192ms 被判死，又夹一轮 20s 静默，总 58.88s，
        //   比早期 38~43s 更差）。45s 覆盖观测上界并留 7s 余量，仍有界，不会落到
        //   First TCP 看门狗 60s + 35s grace 的 95s 最坏情况。
        private const val CREDENTIAL_JOIN_TIMEOUT_MILLIS = 12_000L
        /** vc255：首轮（本纪元第 1 次尝试）凭证锚点——冷态成功上界 38.3s + 余量。 */
        private const val CREDENTIAL_JOIN_TIMEOUT_FIRST_ATTEMPT_MILLIS = 45_000L
        /**
         * vc306：车机热点 + 早期轮（retryCount<=1）的凭证锚点窗口。
         * vc305 定的 45s 差 1.4 秒就能成立——K60 实测 0x5703 → 首个 TCP = 46.4s，
         * 45s 锚点会在 10:23:34.256 判死，而 iPhone 在 10:23:35.688 才连上。
         * 抬到 70s，留 23.6s 余量；与 FIRST_TCP_GRACE_CREDENTIAL_MILLIS 同源同值语义。
         * 第 3 轮起仍回落 CREDENTIAL_JOIN_TIMEOUT_MILLIS(12s)，保证真下车能快速转慢轮。
         */
        private const val CREDENTIAL_JOIN_TIMEOUT_HOTSPOT_MILLIS = 70_000L
        // vc305：会话结束后的换代轮必须覆盖 iPhone 重新入网。
        // K60 实测（20261116 长断流 44.2s）：0x5703 下发 → iPhone 首个 AirPlay TCP = 32.0s，
        // 而换代轮 credentialJoin 只有 12s、authSilence 恒 20s ⇒ 必然提前放弃并白烧整代。
        // 只放宽早期轮（wirelessRetryCount<=1）；用户真下车时后端仍是 20s / 12s。
        private const val CREDENTIAL_AUTH_SILENCE_RECOVERY_MILLIS = 45_000L
        /** vc253：热点 STA 观测的约定网段（热点自身 192.168.250.218 所在 /24）。 */
        private const val DEFAULT_HOTSPOT_STA_SUBNET_PREFIX = "192.168.250."
        /** vc287：车机自建热点（AP）下「确认未关联」的 0x4301 前等待预算（沿用历史值）。 */
        private const val START_SESSION_STA_ABSENT_WAIT_NANOS = 8_000_000_000L
        /** vc287：中继模式下同一预算收到 3s——入网由我方凭证驱动，等关联无收益。 */
        private const val RELAY_STA_ABSENT_WAIT_NANOS = 3_000_000_000L
        /** vc294：/proc/net/arp 第三列的 ATF_COM 位——只有该位置位的行才是可用的对端。 */
        private const val ARP_FLAG_COMPLETE = 0x2
        /** vc295：对端巡检周期。5s 足够快，又不会让老车机频繁读 /proc。 */
        private const val STA_WATCH_INTERVAL_MILLIS = 5_000L
        /** vc295：连续看不到有效对端的确认窗口。覆盖 ARP 表项刷新（probe 在途）的抖动。 */
        private const val STA_ABSENT_CONFIRM_MILLIS = 15_000L
        // vc250（VC248-09）：快链退避额度用尽后的低频续试间隔（车机常电 / iPhone 中途上车）。
        private const val AUTO_RETRY_SLOW_INTERVAL_MILLIS = 5 * 60 * 1000L
        // 1.1.4（B 方案）：残留会话打断轮的最小全断窗口（蓝牙+热点），实测 17s 内可重连成功。
        private const val STALE_SESSION_BOUNCE_DELAY_MILLIS = 8_000L
        // 1.0.10：wired peer 断链自动重试（与无线链同款退避节奏，API16 安全原语）。
        private const val MAX_WIRED_RETRIES = 5
        private const val WIRED_RETRY_DELAY_BASE_MILLIS = 4_000L
        private const val WIRED_RETRY_DELAY_MAX_MILLIS = 15_000L
        // 1.0.29：wired 建立期看门狗——0x4301 后仍零流帧即关桥重建；楔死类退避 ≥30s。
        // vc62（EU5 123838 实测）：iPhone 起流可慢至 8~16s（两轮被 8s 看门狗杀掉，第 3 轮
        // 才成，插线到画面 ~45s），8s→15s 一轮等到，省两轮重建。
        // vc68：15s→30s——WiredBonjourAdvertiser 的 JmDNS 注册实测耗时 ~10s（tun 接口上
        // bind+announce 慢），15s 窗口只留 5s 应答期，iPhone 的 _airplay 查询全落在盲区被拆桥。
        // 30s 给足「注册 10s + 应答 20s」，健康路径（0.9s 直连）不受影响（看门狗只是上限）。
        // vc70/71（TS7 实测）：选 3/4 组后 iPhone 首次真实连入（tcp->7100 + 1500B 视频帧），
        // 但连接发生在 0x4301+30s 整——iPhone 在此平台的 mDNS 问答循环要 ~30s 才收敛，
        // 30s 看门狗在数据到达前 57ms 拆桥误杀。30s→60s：查询收敛 ~30s + 流建立 20s 富余；
        // wiredStreamSeen 置位后看门狗即失效，健康路径不变。
        // vc153（K60 定案）：60s→15s——K60 实锤 0x4301 后 iPhone 完全静默（无会话无流帧），
        // 60s 判死 × 多轮重试 = 数分钟「有线链路建立中」假死。15s 判死 + 重试 2 次封顶 +
        // 第 2 轮前 0x52 重枚举升级；健康平台会话 2~13s 内激活即令计时器失活（对齐上游
        // WiredStartWatchdog：工作车机 0x4301 后 2~13s 会话即激活）。
        // **vc187（t3 20261025-215214 实测）**：6 次成功轮的 0x4301→视频 SETUP 耗时
        // 6.6~11.0s（全部 <15s），说明 **15s 并没有误杀慢连接**；7 次超时里 6 次是 iPhone
        // 收到 0x4301 后根本没发起 RTSP。放宽到 25s 只为兜住唯一一次卡在 14.4s 的握手，
        // 真正的解是 Iap2WiredControlClient 侧按 airPlayActive 重发 0x4301（同版修复）。
        private const val WIRED_ESTABLISH_WATCHDOG_MILLIS = 25_000L
        // vc153：有线启动看门狗判死后的自动重试轮数封顶（超出落 ControlEnded 终态）。
        private const val WIRED_START_WATCHDOG_MAX_RETRIES = 2
        private const val WIRED_WEDGE_RETRY_DELAY_MILLIS = 30_000L
        // vc97③：usbmux 控制面活性窗口——iAP2 心跳间隔通常 ≤5s，8s 收不到帧判控制面停。
        private const val USBMUX_ALIVE_WINDOW_MILLIS = 8_000L
        // M6k11：CSM 打开后 <500ms 被 iPhone 关流 → IPHONE_ZOMBIE（重试治不了）。
        // 僵尸策略：快试 2 次（4s）→ 第 3 次起长退避 30s/60s 并提示用户关开 iPhone 蓝牙；
        // 第 3 次重试前顺带 reset 本机蓝牙适配器（清车机侧 RFCOMM 服务状态）。
        private const val ZOMBIE_WINDOW_MILLIS = 500L
        private const val ZOMBIE_FAST_RETRY_MILLIS = 4_000L
        private const val ZOMBIE_LONG_RETRY_1_MILLIS = 30_000L
        private const val ZOMBIE_LONG_RETRY_2_MILLIS = 60_000L
        // M6k：触摸队列上限（超过则丢最旧 MOVE）与计数日志间隔。
        private const val MAX_PENDING_TOUCH = 8
        private const val TOUCH_STATS_INTERVAL_NS = 5_000_000_000L
        private val RETRYABLE_WIRELESS_FAILURES = listOf(
            "connection abort",
            "iap2 link ended",
            "connection reset",
            "broken pipe",
            "connection refused",
            "service discovery failed",
            "eof",
            "closed before tunnel iap2 ready",
            // M6k20：first-TCP 超时归入可重试族（陈旧 mDNS 缓存场景靠重连自愈）。
            "first tcp timeout",
            // 1.0.13：handoff（画面握手）超时同归可重试族——MX6DQ 实测 TCP 已通、A/V 未出帧，
            // 弹「画面握手未完成」终态躺平；换新一代重连实测可自愈（重试 8 次后 gen=3 成功）。
            "handoff timed out",
            // 1.0.29：AirPlay 断流看门狗（倒车/离网后会话死亡）——整代重启可自愈。
            "airplay stalled",
            // 1.1.6(vc76)：媒体断供看门狗（形态 A 僵尸会话）——iPhone 不发 teardown，
            // 视频静默 60s 由 sink 判死（vc290 起只看视频），整代重启自愈。
            "media stall",
            // vc296：对端巡检判 iPhone 已离开热点（vc295）——与断供看门狗同性质，
            // 归可重试族，否则直接 Failed、iPhone 回网后不会自动恢复。
            "sta gone",
        )
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val RFCOMM_FALLBACK_DELAY_MILLIS = 150L
        // vc168（RFCOMM 建链实验轮）：
        // - cancelDiscovery() 是异步的，原实现取消后立刻建链；给它一个收敛窗口。
        // - 多轮退避重试：原实现 secure+insecure 各一次即放弃，而日志显示偶发能连上
        //   （car 13 次成功 2、relay 3 次成功 1），加大重试机会。
        // 三者都是"便宜假设"，靠 sdpProbe 的交叉判据验证，无效则上 vc169 反射方案。
        private const val DISCOVERY_CANCEL_SETTLE_MILLIS = 400L
        private const val RFCOMM_ROUNDS = 3
        private val RFCOMM_ROUND_BACKOFF_MILLIS = longArrayOf(600L, 1_500L)
        // vc284：HFP+A2DP 连接态查询的**共用**总预算。两路并发注册，只等这一个窗口
        // （原为串行各 3s，多台 iPhone 消歧时最坏白等 6s）。
        private const val CONNECTED_PROFILE_WAIT_MILLIS = 3_000L
        // vc283（A/B 实验）：建链前是否发 sdpProbeAsync。
        // K60 冷启动实测：探测请求会抢占 SDP 通道——日志里第一条应答永远是探测的 149 条
        // （r2 热 +0.90s / r3 冷 +4.69s），建链自己的 141 条永远排第二，端口打开被拖到
        // 2.21s / 5.86s。竞品 CatPlay 1.0.2 的 dex 里只有一次 createRfcommSocketToServiceRecord，
        // 无 fetchUuidsWithSdp，冷启动建链 1.60s。故关掉此探测做对照实验；
        // 失败路径的 sdpProbe(round/final) 不受影响，诊断能力不丢。
        private const val RFCOMM_PROBE_BEFORE_CONNECT = false
        // vc282：UUID 路径全灭且 iAP2 仍在广播时，用隐藏 API 直连通道号（绕过 SDP 通道发现）。
        // iPhone 的 iAP2 RFCOMM 服务落在低通道号，1~3 覆盖实测范围；每个通道单独限时，
        // 避免错误通道把失败路径拖长（最坏 +9s，且只在「服务可见却建不了链」时发生）。
        private val RFCOMM_DIRECT_CHANNELS = intArrayOf(1, 2, 3)
        private const val RFCOMM_DIRECT_TIMEOUT_MILLIS = 3_000L
        // 1.1.3：SDP 探测等待（fetchUuidsWithSdp 异步回调落缓存）与连续失败提示阈值。
        private const val SDP_QUERY_SETTLE_MILLIS = 1_500L
        private const val SDP_FAILURE_HINT_AFTER = 2
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
