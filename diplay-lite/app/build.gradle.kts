import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// vc202+1（08-路线图 §7.6 条目）：release 签名「静默回落」改「大声失败/醒目告警」。
// 提到文件作用域，供 android{} 内 signingConfigs 与 ciRelease 任务共用同一份判据，防漂移。
// 判据语义不变：环境变量 ANDROID_KEYSTORE_PATH 优先；缺省回落 SDK 标准 debug keystore。
val releaseKeystoreEnvSet = !System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    ?: "${System.getProperty("user.home")}${File.separator}.android${File.separator}debug.keystore"
val releaseKeystoreUsable = releaseKeystorePath.isNotBlank() && File(releaseKeystorePath).exists()

android {
    namespace = "com.shilapi.diplay.lite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shilapi.diplay.lite"
        minSdk = 16
        // vc151（K60 安卓14 上向兼容）：19→34。协议/栈零改动，仅壳层适配：
        // 明文 HTTP 放行、前台服务类型+通知渠道、动态 receiver EXPORTED 旗标、
        // 运行时权限（RECORD_AUDIO/定位/POST_NOTIFICATIONS）。老车机（KK API19）零回归。
        targetSdk = 34
        // versionCode 唯一生效处在下方 vc 迭代注释区（随版本递增，出包时改那里）。
        // 2026-10-21 整顿：此处原有 `versionCode = 56` 为失效的重复赋值，被后者静默
        // 覆盖；出包时改错一处即打错版本，已移除。
        // 构建标识：T15 曾因 versionName 恒为 0.1-lite 而误跑旧包、结论作废。

        // 版本迭代规则：1.0.x 系列止于 1.0.29，自 1.1.x 重新起排（每次出包 patch +1）。
        // 1.1.2：7100 端口 SO_REUSEADDR/显式关闭 + USBMUX 重置纳入重试 + NCM 桥死亡联动关
        // wiredCsm + 「Siri/语音提示走媒体音量」开关。
        // 1.1.2-test：bt_audio 排查探针包（/info 声明探针进 LiteLog + A2DP/HFP 状态广播 +
        // RTSP rx 入口打点），专供「carplay+蓝牙」外测取证。
        // 1.1.2-btaudio-test：bt_audio 试验包——移植上游 BluetoothAudioHandoff（A2DP 监听，
        // 仅 bt_audio 模式挂载）+ /info 声明变体（长按「蓝牙音频+CarPlay」切换 no_audio/
        // keep_formats），攻 iOS 16+ 零音频声明不建屏幕流。
        // 1.1.2-root-test：root 抢回试验包——claim NCM 失败且 su 可用时，sysfs unbind 内核
        // cdc_ncm + rmmod 后自动重试 claim 一次，攻「内核 CDC-NCM 抢占 NCM 数据接口」。
        // 1.1.3：蓝底图标 + 设置 UI 字号/小标题/弹窗确认重构 + 诊断弹窗与 USB 取证段
        //（usb-uevent/usb-devices/net-dev/ps-all/usb-holder）+ 8815 SDP 连续失败用户指引与
        // SDP 服务列表取证 + StandardCharsets→Charset.forName（API19 前车机 Lockdown 不再挂）
        // + 硬解渲染只渲最新帧/renderHold 恢复抑制（绿线+burst 节流）+ 开源声明弹窗 + 日志 token 轮换。
        // 1.1.3(vc54)：修复 drainOutput 忙死循环（TRY_AGAIN 未无条件退出，解码线程卡死致全黑屏，
        // 8815 010252 定案）+ 设置页复选框字号统一 13f（实机反馈）。
        // 1.1.3(vc56)：运动噪块修复——MAX_FRAME_AGE 350ms→1100ms 消 900ms radio gap 引发的
        // flush churn + API<21 fps 协商压 30（Adreno 203 档峰值负载静默纠错噪块）。
        // 1.1.3(vc57)：系统诊断重做（中文检测报告）——首启快检（新装自动触发可跳过）+ 设置页
        // 复测逐项检测（ROOT/ADB/蓝牙 A2DP sink 探测/Wi-Fi 热点实测含恢复原状/USB 静态+动态
        // 人工步骤）+ 连接建议矩阵 + 「蓝牙音频+CarPlay」改操作指引弹窗（底层恒走全量 CarPlay）
        // + 主页面 hero 文案删除与右列对齐；取证数据只写日志不上屏。
        // 1.1.3(vc58)：诊断过程弹窗按钮被挤出可视区修复——ScrollView 加 weight=1，
        // 「下一步/跳过」恒定钉在弹窗底部（8815 横屏 540px 实测）。
        // 1.1.3(vc59)：分辨率设定落盘丢键修复——LiteConfig.save 漏写 video_resolution_mode/
        // manual_resolution_w/h，「手动设定分辨率」确认重启后单选弹回、输入行不显示。
        // 1.1.3(vc60)：分辨率交互改版（点选→右方应用键→重启生效，手动输入行恒显示）
        // vc61：FE-6 楔死自愈跳过重复选配（消除权限竞态误报）+ 诊断取证加 dmesg 采集
        // 1.1.4(vc62)：EU5 电话无收音修复（telephony 源序 6→1→7 避免源 7 毒化 HAL +
        // 上行启动失败延时重试 800ms/2s + 全败矩阵明细/音频系统快照落盘）+
        // wired 建立期看门狗 8s→15s（EU5 iPhone 起流可慢至 8~16s，8s 杀了两轮）。
        // 1.1.4(vc63)：TS7 mazda2 修复——①0x52 response=0x6（已配置拒绝重枚举）不再死等，
        // 直接开数据面；②多 NCM 组优选内核未绑定组 + 失败轮换（绕开 cdc_ncm 抢占致
        // NTB16 打碎，无 root 方案）。
        // 1.1.4(vc64)：无线 First TCP 超时自动打断残留会话（B 方案，TS7 170921 实测）——
        // 超时时拆蓝牙引导链并强制 ≥8s 全断窗口重启（热点不保留），模拟「断开→重连」
        // 强制 iOS 清残留 CarPlay 会话（此前保持热点的 RESTART 两代 30s 双超时）。
        // 1.1.4(vc65)：无线连接提速（10-12 TS7 Manual/Relay 双样本日志对比 0.2.6 结论）——
        // ①BT 单台直选：唯一 bonded iPhone 跳过 HFP/A2DP profile proxy 查询（省 ~8s）；
        // ②First TCP 看门狗 30s 后加 35s 宽限复查，窗口内 TCP 进入即放行（iPhone 实测
        // 入网 25~35s，旧值恰好杀在入网途中）；③重试/看门狗失败路径保留热点附着
        // （keepHotspotForReuse，iPhone 不掉网、下一轮秒连，对齐 0.2.6 热点常开模式）；
        // ④Bonjour 注销异步化（goodbye 限时 300ms，解除 JmDNS close 7~8s 阻塞）。
        // 1.1.4(vc66)：Bonjour start() 整体后台化——TS7 204308 实测 JmDNS
        // create+registerService 同步阻塞 7.5s/次（真凶是它而非 BT 探针，BT 快路径
        // vc65 已生效）。mDNS 广播非连接关键链路（0x5703 直驱），失败本就非致命降级；
        // 后台完成后代次过期自查 close，bonjour 字段引用比较防误伤新一代实例。
        // 实测 vc65 已达 Manual 15.4s / Relay 17.3s（vc64 为 104~107s），vc66 预期 ~8s。
        // 1.1.4(vc67)：AirPlay 监听端口回退——coocaa BEP10A（Android14）215128 实测车机自带
        // 投屏服务常驻监听 7100（EADDRINUSE，inode 随服务重启变化，无 root 不可清）。三层
        // 策略：①首选端口重试（自占用竞态自愈）②扫 /proc/net/tcp(6) LISTEN 黑名单后从
        // 7101..7110 直选空闲端口③bind(port=0) 内核随机端口兜底；实际端口注入 0x5703
        // airPlayPort + Bonjour TXT（iPhone 侧不写死端口），失败诊断升级为全 LISTEN 表。
        // 1.1.5/vc70：TS7 端点差分——NCM 选组反转为优先 3/4（OUT=0x06，带 interrupt EP），
        // A/B 测 MUSB EP 0x07 TX 静默丢弃嫌疑。vc70 实锤：iPhone 首次真实连入。
        // 1.1.5/vc71：建立看门狗 30s→60s（iPhone mDNS 收敛 ~30s 压线误杀）。vc71 实测画面
        // 已出但 MUSB 满流丢包致 NTB16 错位、一次校验失败即拆桥。
        // 1.1.5/vc72：NTB16 失步重同步（丢错位前缀对齐下一签名，风暴上限 64）。
        // 1.1.5/vc73：风暴计数窗口化（30s 无错位重置，窗口 256）——静态画面期错位
        // ~1-2 次/秒，vc72 累计 64 上限 2.5 分钟自拆（20:26:18 实测）。
        // 1.1.5/vc74：内置 cdc_ncm 解绑守护（WiredNcmGuard，2s 扫配置 6 接口，su 写
        // unbind，无 root 降级）——替代电脑侧外置循环，脱离电脑可独立工作。
        // 1.1.5/vc75：守护收窄为只解绑 6.3——解绑已 claim 的 6.4 会触发 alt 复位打死
        // bulk-out（20:56 实测两窗口全超时），外置守护成功行为就是只动 6.3。
        // 1.1.5/vc76：①WiredNcmGuard 守护关闭裁决版（WIRED_NCM_GUARD_ENABLED=false，
        // TS7 无 root A/B 实验：仅 NTB16 重同步能否稳住会话，vc77 按裁决回 true/false）；
        // ②无线媒体断供看门狗（音 102/视频 110 双静默 20s → 拆栈重试，攻 iPhone 野蛮
        // 退出无 teardown 的僵尸会话）；③失败重试路径保留热点附着（形态 B：BT 中止
        // teardown 后 5/5 SDP 全灭的根因是热点被释放，iPhone 掉网重入网恶性循环）；
        // ④BYD-HUD bindService 指数退避（8815 每 250ms 风暴实测）。
        // 1.1.5/vc77：①WIRED_NCM_GUARD_ENABLED=false 定案——vc76 guard-off 裁决通过
        // （TS7 无守护健康 80+ 分钟零异常，内核绑定 6.3 全程未解绑），正式支持无 root
        // 运行，守护代码保留作未来 root 平台的开关项；②触摸/控制通道 TCP_NODELAY
        // （eventSocket accept 侧补齐，对齐上游 0.2.13 触摸修复，降低 touch2frame 延迟）。
        // 1.1.5/fe6t1(vc78)：FE-6 外测专用试验包——①超宽屏声明覆盖：1920x620（96:31，
        // MFi 官方列表外）导致 iOS 首连接受（图标拉伸）重连回退 16:9（黑边 409px/侧），
        // 改声明官方超宽 8:3（高 snap 宽*3/8），keep-aspect 后黑边收窄 ~133px/侧且
        // 重连分辨率预期稳定（仅纵横比宽于 8:3 的屏受影响）；②应用层 pcap 抓包（无 root）：
        // NcmUsbBridge 入/出站以太网帧经 PcapTap 环形落盘（3 文件×32MB），设置页开关，
        // 文件在 Android/data/<pkg>/files/pcap/，攻 wedge/黑边类数据面取证。
        // 1.1.5/fe6t2(vc79)：方控监听——FE-6「方控上/下一首无效」，MediaSession/logcat/广播
        // 三条通路零触发记录，dispatchKeyEvent 全量 KeyEvent 落日志（只观察不改分发），
        // 确认车机按键是否以窗口 KeyEvent 形态进 App 及键码/来源，再定映射修复。
        // 1.1.5/vc80(relayfix)：中继踢出转正——攻「iPhone 提前连到中继 WiFi 后 CarPlay 永远
        // 连不上」。根因：iPhone 已在目标网络时 0x5703 不触发 join 事件（no-op），12 次重发
        // 全废，First TCP 看门狗 30s+35s 宽限判死 → 重试死循环。修复：中继模式恒启用假凭证
        // 踢出（原 [RELAY-TEST] 实验分支，曾因看门狗 65s < 三阶段 134s 而废弃，00:28 日志
        // phase2 从未触达），真凭证重发 8 次（40s）仍 0 TCP → 假 0x5703 踢出当前 AP → 10s
        // 后真凭证恢复 → 强制新鲜 join → ~62s 前完成，全程在 65s 看门狗窗口内。
        // 1.1.5/vc81(wifilock)：中继链路抗抖动——C8815 实测无线会话视频 rx=6.8fps、音频输入
        // maxGap 389~934ms、queuedMs 180~1427ms 震荡（听感一卡一卡），为 WiFi 省电轮询突发
        // 断供特征，App 此前全程未持 WifiLock。中继入网成功即持 WIFI_MODE_FULL_HIGH_PERF
        // 锁（RelayClientManager 幂等获取，close 释放），失败重试 keepForReuse 下重新持锁。
        // 1.1.5/vc83(relaykick2)：攻「中继热点要点两次连接才能配上」（082530 日志 8 次点击
        // 定案）。三根因三修：①踢出后 10s 凭证真空期 iPhone 无凭证可回、用户在恢复前放弃
        // →恢复延迟 10s→1.5s，且恢复后重发预算恢复满额 12×5s；②踢出时机 8 次重发(40s)太晚
        // →2 次重发(~10s)即踢，踢出→回连全程压缩 35s；③车机 WiFi 关闭态中继发起秒失败
        // →RelayClientManager 自动 setWifiEnabled(true)（等 8s）。④First TCP 看门狗 30s→60s
        // （082530 tap2 实证热点直连慢入网 39s，30s 判死差 9s；总上限 60+35=95s）。
        // 1.1.5/fe6t3(vc84)：Siri 后无声恢复——FE-6 USB 有线会话（fe6t2/vc79 日志实证）iPhone
        // 进 Siri 拆媒体音频流（TEARDOWN type=100 ended=true）→ SETUP speechrecognition 上行
        // → Siri 结束后第二个 TEARDOWN，此后 iPhone 不再重发媒体音频 SETUP，音乐静音到用户
        // 手动断开（会话其余全健康，enhancedSiri 会话特有，TS7/8815 无复现）。AirPlaySession
        // 层检测「speechrecognition 音频流已现 + 音频流被 TEARDOWN 且会话内无音频流」→ 10s
        // 看门狗（期间任一音频 SETUP 即撤销）→ onSiriAudioStall 回调；wired 编排层 close 会话
        // （先补发 best-effort TEARDOWN，iPhone 当真结束）→ 既有 wired 自动重试链全量重建。
        // 1.1.5/e5t1(vc85)：EU5 通话收音修复/取证——三招打「上行无声」：①数字静默自愈
        // （核心）：采集 peak==0（真全零，DSP 静音路径特征；<300 近静音是环境噪音绝不触发）
        // 连续 5s → 采集线程内热换下一个未试音频源（沿 6→1→7 逐个排除，RTP 不断流），
        // 全源轮完仍全零 → 停轮换并落 QG DSP 嫌疑取证；②麦克风静音位自动恢复：上行启动
        // 前检测 AudioManager mute 位，为 true 自动解除并复检落证（App 层能修的静音路径）；
        // ③QG DSP 存在性探测（pm list packages qinggan，无 root）附在取证里。
        // 1.1.5/fe6t4(vc86 sirirestore2)：Siri 看门狗装弹判据修复——fe6t3 实测
        // （diplay-20261015-185536）看门狗零触发、测试者仍手动重连两次（Siri 后静音 117s）。
        // 根因：Siri speech TEARDOWN 时 101/default 控制音频流残存（本就反复 flap），
        // 「会话内无任何音频流」判据永不满足。改用 type→audioType 映射（activeAudioKinds）
        // 跟踪活跃音频流，判据改为「无 audioType=media 音频流」；到期收会话走 wired
        // 重试链全量重建，预期 Siri 后 ~15s 音乐自动恢复。
        // 1.1.5/fe6t4(vc86)：Siri 恢复看门狗判据修正——fe6t3(185536 日志) 实测看门狗零触发
        // （Siri speech TEARDOWN 时 101/default 控制流残存，其本就反复 flap，「无任何音频流」
        // 判据永不满足，用户仍手动两次点击），判据改为「无 audioType=media 音频流」。
        // 1.1.5/fe6t5(vc87)：FE-6 三问题修复/取证——①方控派发接通：fe6t3 实锤按键以窗口
        // KeyEvent 形态进 App（code=87/88 dev=-1 src=0，MediaSession/logcat/广播三通路零触发），
        // 学习绑定建得起但永不派发；dispatchKeyEvent 观察侧升级观察+派发（DOWN 命中学习绑定、
        // UP 命中标准媒体键，不改 super 分发）。②掉线取证：fe6t3 实锤一次掉线=用户点断开、
        // 一次根因=iPhone 关 event 连接（Siri 结束后整 60s）→ wedge 看门狗连锁误判拆桥 →
        // auto-retry 其实跑通全量重建并重发 0x4301，但 iPhone 无响应且用户 29s 手动打断；
        // closeWiredStack 全调用点 reason 落盘 + event 连接关闭区分对端 EOF/空闲时长。
        // ③收音无：fe6t3 日志 uplink started 后无任何 level window/tx 心跳=AudioRecord.read
        // 恒返 0 的 HAL 出数失败形态（凑不满整帧连 e5t1 静默自愈都不触发），补零读换源自愈。
        // 1.1.5/fe6t5(vc87)：FE-6 三项修复（fe6t3 视频+日志定案）。①方控无响应：按键以窗口
        // KeyEvent 形态进 App（code=87/88 dev=-1 src=0），MediaSession/logcat/广播三通路零触发；
        // 学习绑定经 onKeyDown 能建立但标准派发只有 MediaSession+logcat/广播两条路，窗口 KeyEvent
        // 永不派发 → dispatchKeyEvent 加观察+派发（DOWN 命中学习绑定 event=ACTION_DOWN、
        // UP 命中标准媒体键）。②掉线取证：closeWiredStack 全调用点 reason 落盘 + event 连接
        // 关闭区分对端 EOF/空闲时长。③收音无：fe6t3 日志 uplink started 后无任何 level window/tx
        // 心跳=AudioRecord.read 恒返 0 的 HAL 出数失败形态（凑不满整帧连 e5t1 静默自愈都不触发），
        // 补零读换源自愈。
        // 1.1.5/direct1-3(vc88-90)：客户端直连实验（已终止并回退）——车机 STA 加入 iPhone 个人
        // 热点拓扑三轮实测（不带组/带组/全套 0x5703+0x4301=中继同款）均被 iPhone 完全忽略
        // （tcpAccepted=0，60s 看门狗判死）。终案：iOS 硬限制——拒绝对自身个人热点的端点声明，
        // 不会在自己热点接口上发起 CarPlay 会话，App 层无解。实验资产归档：apks/DiPlay-1.1.5-direct*。
        // 1.1.6(vc91)：①车标页全品牌选择器（assets/car-logos 141 品牌：搜索过滤/中文名/列数
        // 自适应/点选即补方 256 存 CarIconStore+名称替换，素材源为公开车标素材整理 + 自绘补绘）；
        // ②WiredNcmGuard 解绑严谨化（借鉴 legacy-ref）：解绑前读 driver/uevent 驱动名，仅放行
        // cdc_ncm/cdc_ether，usbfs/未知驱动拒绝并落盘取证——不再无差别解绑。
        // 1.1.6(vc92)：未配 SSID 报错文案明确化（fe6 102301 日志：服务自启走默认通道秒失败弹窗
        // 误导测试者）——「当前是车机热点模式，未配置SSID，无法连接」/中继同款；手动点击路径原有
        // 引导 toast 不变；0x4301 重建后忽略问题（fe6 185536 日志二次实锤，57s 死窗主因）另案处理。
        // 1.1.6-fe6t5(vc92)：enhancedSiri 根治实验（用户拍板无开关直接关）：/info extendedFeatures
        // 移除 "enhancedRequestCarUI"（增强式 Siri 请求车机 UI 的唯一声明位）。SETUP 请求里
        // iPhone 确实带 enhancedSiri，但 SETUP 响应各平台本就一致不回（TS7/8815 同），关闭点
        // 收敛到 /info extendedFeatures。判据：Siri 后媒体流是否自动重发 SETUP（不再触发
        // onSiriAudioStall/60s 重建）；风险：若会话被拒则恢复双元素声明。
        // 1.1.7(vc93)：①VPN 授权三段式（ECARX 235521 实锤 ROM 裁剪 vpndialogs 致 USB 点击
        // 闪退）：startActivityForResult catch + root appops ACTIVATE_VPN 免对话框 + 明确报错；
        // ②车机热点状态三证据源（235233 实锤 ECARX state=11 但 ap0 存活）：state/isWifiApEnabled/
        // AP 专用接口交叉验证，不设机型判断；③蓝牙开关双证据源+自动补开（同车实锤 adapter
        // 失灵）：adapter ∥ Settings.Secure bluetooth_on，双关时 enable+轮询 8s；
        // ④楔死重建全量复位兜底（TS7 083438 实锤 self-heal 短路后 iPhone 无视 0x4301）：
        // wedge/watchdog/usbmux-reset 重试置 wiredFullResetPending 走 0x52+setConfiguration
        // 全量复位路径 + 0x4301 建立期重发预算（2s×6）。B 组安全项（ADB 开关/PIN 门禁）缓行。
        // 1.1.8(vc94)：全库逻辑审查 12 项修复（A 组 9 机械 + B 组 3 行为）——
        // ①A1 0x4301 重发检查移出 recv-null 分支（心跳常驻形态下 vc93 预算逻辑零触发）；
        // ②A2 fullReset 消费时重置重枚举预算（wedge 多轮重建后 attempts 耗尽落 self-heal 短路）；
        // ③A3 蓝牙补开 elvis-throw 死代码修复（ensureBluetoothEnabled 非空 Boolean，throw 不可达）；
        // ④A4 AirPlayTcpServer 回退绑定成功后 active 复位（bindOnce 失败释放 active 置 false，
        //   回退路径 acceptLoop 首判即退，iPhone 连入无会话假活）；
        // ⑤A5 音频 rx 循环兜底 catch（下游 RuntimeException 线程静默死亡，对齐 ScreenStream）；
        // ⑥A6 NcmUsbBridge root 抢回重试前经工厂重新 openDevice（openOnce 失败已 close connection，
        //   复用必败，1.1.3 起 root 抢回整体失效）；
        // ⑦A7 中继热点 PSK 一致性校验（改密后 configuredNetworks 命中同名即复用，join 恒超时）；
        // ⑧A8 三处 su 执行加超时（MainActivity/WiredNcmGuard/Diagnostics，exitValue 轮询，
        //   API16 兼容不用 waitFor(timeout)，超时 destroy 按失败处理）；
        // ⑨A9 WiredVpnService 快速重连旧 TUN fd 覆盖泄漏（establish 前关存量 fd）；
        // ⑩B10 NTP offset 应用门控加 60s 老化（极小 RTT 霸占延迟窗口致 offset 冻结、晶振漂移无界累积）；
        // ⑪B11 Ipv6NcmBridge close 写 ff02::1 哑包唤醒阻塞 TUN read 的出站线程（僵尸线程随重连累积）；
        // ⑫B12 音频重排 distance==0 交付前清 held 同序号残帧（PT=86 注入与原包竞速滞留，
        //   firstKey 回拨基准致整段重复交付）。
        // 1.1.9(vc95)：①中继网关探测放宽 2s×2→3s×4/间隔1s（SM-T210 实锤手机热点数据面启动
        //   窗口期 isReachable 假阴性致 RELAY_UNREACHABLE，用户需手动重试；全程上限 ~15s 消除
        //   误报）；②日志抬头新增 ROM 版本行（Build.DISPLAY→Build.ID→Build.TIME 三层兜底，
        //   ROM 版本+车机型号辨识测试机防日志混淆）；③车标选择改 5×2 横向滑动栏内联直选
        //   （厂家预置行+「全部品牌」跳页合并，部分车机网格页无法预览清单用户裁定；BEI JING/
        //   Stellantis raw 独有置顶+assets 141 品牌，独立网格页 BrandAdapter 机制移除）。
        // 1.1.9(vc96)（FE-6 断链分析定案，全部 app 层）：①WaitingForIphone 打 USB 总线拓扑
        //   快照（首拍+每5拍，区分设备掉总线 vs pid 变化退出 CarPlay 模式）；②黑等超 10s
        //   UI 提示「请拔插 USB 线」（实测 94s 无反馈车主以为 App 死）；③no_audio 会话
        //   wedge fatal 阈值 6s→15s（无音频恒流垫底，画面静止断流属正常防误杀）；④root
        //   USB 取证探针 WiredUsbForensics（会话期 dmesg USB 线增量进 LiteLog + wedge 时
        //   sysfs 设备树快照，内核级回答「谁先动手」；无 root 静默关闭）。
        // vc97：①A1 重发门控换「无流帧」判据（streamFrames 独立计数，0x5001/0x5201
        //   心跳污染 forwardedFrames 致重发饿死+wedge 误判——TS7 46s 死窗根因链闭合）；
        //   ②wedgeSuspect 接通后 wedge 重建置位真正生效；③wedge 判别器（usbmux 控制面
        //   活=端点真楔死按变体阈值快拆 / 同停=iPhone 停发型宽限 20s）；⑤usbfx 内核
        //   时间戳水位去重（T3 全量重放治）+snapshot 去 tail 依赖（toybox 无管道）；
        //   ④蓝牙多源枚举 BondedDevicesResolver（FE-6 getBondedDevices 失灵族）随包。
        //   vc98（T3 当晚续）：KK(API<21) 无 setInterface——旧 runCatching 兜底在 T3 上
        //   必然 NoSuchMethodError→false，设备侧 NCM alt 恒 0，iPhone 数据面零包、60s
        //   看门狗必触发（协议栈 claim/MFi/0x4301 全通只差 iPhone 第一个包）。改发
        //   SET_INTERFACE 类请求（claim 后可发，无需 root），App 自闭环不依赖外部时序。
        //   vc99（T3 当晚续2）：claim 失败 root 抢回根治——unbind 后内核 67ms re-probe 绑回
        //   （dmesg 实锤），unbind 必败竞速。改先扫 sysfs 取 iPhone PID → cdc_ncm remove_id
        //   （本开机周期内核失明，重启自动复原）→ 再 unbind 踢绑定 → 重试 claim 必成。
        //   vc100（T3 定案）：SuperSU 2.82 su -c 长命令静默降权缺陷（含重定向/分号整体
        //   降为 shell，uid 短命令 10 连测全过 vs 同串 id=2000 实锤）——reclaim 改脚本
        //   文件模式：写 filesDir/ncm-reclaim.sh → su -c "sh <path>" 单短命令 → stdout 判定。
        //   vc101（T3 定案2，借鉴 legacy-ref usb_host_compat）：root reclaim 竞速根治——
        //   remove_id+unbind 后内核 67ms re-probe 绑回（22:09 会话 dmesg 实锤两轮 unregister
        //   →register），sysfs 路线在 T3 静态 ID 表下必败。改 usbfs 原子路径（免 root）：
        //   ①NCM claim 失败先走 USBDEVFS_DISCONNECT_CLAIM ioctl（内核锁下一步「断驱动+claim」，
        //   消除竞速窗口，legacy-ref forceClaim 同款）；②vc98 SET_INTERFACE 升级为
        //   USBDEVFS_SETINTERFACE ioctl 优先（内核 usb_set_interface 同步 ep 启用表，
        //   controlTransfer 只切设备侧不更新内核，bulk URB 可能提交失败），controlTransfer
        //   兜底。NDK libusb_host_compat.so（v7a/v8a，API21 编译 API19 可运行）随包，
        //   加载失败自动降级原路径。已知遗留：KK 重插线内核选错配置（无 setConfiguration
        //   API+快照陈旧）留 vc102（KitKatUsbDescriptors+native setConfiguration）。
        // vc103r（Legacy 对齐三改动，目标 T3/AC822X「入向零帧」）：
        //   ①SET_INTERFACE 改描述符驱动 alt + 5次×100ms 重试（cdc_ncm claim/SET 间抢绑竞速）
        //   ②NCM 复用 usbmux 会话同一 fd（内核 claim 位图按 fd 记账，双 fd 割裂=KK ENOENT 根因）
        //   ③SETCONFIGURATION(6) 后 GET_CONFIGURATION 回读验证（errno=0≠内核真切过去）。
        // vc164：版本号开始迭代——versionName 第三位随每次出包 +1（1.2.0 基线 = vc160）。
        // 每次出包只改 versionPatch 一个数字（+1）：vc164 → 1.2.1，vc165 → 1.2.2，依此 +1。
        // vc166（外置 USB 蓝牙 dongle 第一步）：版本标记 + 实验开关 + USB 设备探测显示
        //（UsbDongleProbe.kt 只枚举不打开；前提验证：T3 插 dongle 能否被系统枚举）。
        // vc167（第二步）：落 A+B 批——usbbt 开关接入 CarPlayController 建链缝 +
        // USB 传输层探活（枚举→openDevice→HCI Reset→读 BD_ADDR）；UsbDongleProbe
        // 枚举能力收进 shared/.../usbbt/UsbBtSession 并删除。
        // versionPatch/versionName 迁至 versionCode 附近统一维护（vc171 起，见下方 vc171 注释块）。
        // vc143：USBMUX 握手超时 60s→20s（快速进入 full-reset 重试）+ 重试轮次显示
        // （复位提示此前 8s 被覆盖、静默期观感=卡死）。17:40 会话实证 vc142 自愈链路正常。
        // vc144：NCM 同步读成功后 settle 1ms 再 arm 下一次传输——vc143-snap-2008 定案
        // MUSB 在短包结束→重新 arm 交界处吃掉下一 NTB 第一个 512B 包（remnant 1060 实锤）。
        // vc145：renderState 状态未变即短路（C8815 实测 dalvik 上每 500ms tick 主线程
        // ~11ms+持续分配，是未连接期按钮延迟的 app 侧放大器；首启快检对话框首次
        // inflate 触发 GC 风暴 12 连发/Skipped 57 帧为一次性事件，不在本修内）。
        // vc146（T3 实测）：悬浮球初始位按 displayMetrics.heightPixels(600) 计算，
        // wm overscan 车机可视仅 500 → 球落到 y≈526 可视区外被黑条吞掉；布局完成后
        // 按 root 实际高度 post 校正一次。
        // vc149（USB 拔插彻底清理）：注册 USB_DEVICE_DETACHED 监听，拔线即按「用户断开」
        // 等价清理（LiteSessionService.onDestroy 全拆 controller 栈/mediaSink/VPN）；
        // 重插 ATTACHED 经 onNewIntent 触发 maybeAutoConnect 全新建链，根治热重连脏会话
        // （shown=0/触摸延迟 190ms）。此前拔线 app 零感知，旧地基叠会话。
        // vc150（T3 来电假死）：静默渲染看门狗——rx 满帧但 shown=0 持续 3s（来电/通话/Siri
        // 流重建只发 P 帧不补参数集），自动 flush + 请求关键帧自愈，10s 限频。
        // vc151（K60 安卓14 上向兼容）：targetSdk 19→34 壳层适配（见上方 targetSdk 注释）。
        // vc152（K60 微信语音挂断后会话不自愈）：对端优雅结束会话后自动 0x4301 邀请重投。
        // vc153（K60 有线连接 0x4301 后 iPhone 静默假死）：有线启动看门狗 60s→15s 判死 +
        // 自动重试 2 次封顶 + 第 2 轮前 0x52 mode request/重枚举升级；MainActivity 有线
        // 连接前无线互斥（拆无线栈延迟 2.5s 再走有线）。
        // vc154（待办收口三件套）：④有线切换改拆栈完成回调驱动（替代固定 2.5s，8s 兜底）；
        // ⑤VPN 授权失败引导页（原两处失败终点只弹 toast，升级为重试授权/改无线引导弹窗）；
        // ⑦CI 化构建 task（签名固化缺省 debug keystore + ciRelease 一键构建归档）。
        // vc155（K60 实测 UI 修复）：主面板横屏右列 MATCH_PARENT→WRAP_CONTENT——断开连接
        // 按钮 GONE 左卡变矮后右列被压至同高，设置键底部溢出圆角被裁；改右列反撑行高根治。
        // vc156（上游 0.2.21 待移植双项）：①音频抗抖动=Opus 编码选择层（native→平台回退→
        // 失败容错）+ MediaPacketLagGuard 积压丢帧 + AudioTrack 预缓冲水位（audioPrebuffer 开关）；
        // ②Wi-Fi Direct fallback=热点建链失败时 P2P GO 兜底（wifiP2pFallback 开关，默认关）。
        // vc157（Wi-Fi Direct 升格为一级模式）：承载方式改为「车机热点 / Wi-Fi Direct」二选一，
        // 选 P2P 时直接建 GO，不再依赖车机热点开关状态（绕过 SoftAP 判死逻辑）；
        // 同时帧率由 limit30Fps 布尔升级为四档（25/30/40/60，默认 60），
        // 修复页面重建后指示灯变灰/断开按钮消失（render 末尾清空渲染哨兵）。
        // vc158（UI 调整）：①竖屏弹窗宽度收敛（AlertDialog 按物理横屏宽布局会被裁，
        // 22 处构建点统一走 showFitted()）；②Wi-Fi Direct 由「体验优化里的承载方式开关」
        // 改为「自动连接方式」第四项（车机热点/中继热点/Wi-Fi Direct/USB连接），
        // 承载方式单选删除，旧 wifi_p2p_mode=true 迁移为 activeChannel=p2p；
        // ③帧率四档从体验优化组移入「画面设置」，小节标题「画面帧率」。
        // vc159（设置页闪退修复）：vc158 帧率组迁移残留——rgFps 在体验优化组旧位置
        // 忘删（第二次 addView 同一实例）→ 点设置必崩 IllegalStateException；
        // logcat -b crash 实锤，删除旧挂载行（SettingsPage buildContent 单条 column，横竖屏同崩）。
        // vc160（弹窗错位修复）：vc158 的 fitWidthToScreen 用 displayMetrics 短边做锚点，
        // 冷启动横→竖旋转后 dm 陈旧 → 弹窗仍右溢（K60 实测）；改 decorView.width 优先
        // + setGravity(CENTER)。横竖屏弹窗行为改真实冷重启（启动级属性「下次连接生效」
        // 不成立），音视频/分辨率/超采样文案对齐「下次连接生效」。
        // vc161（弹窗错位根治）：放弃 AlertDialog 独立 Window——Manifest 是
        // screenOrientation=landscape + configChanges 自处理，运行时强制竖屏，
        // Dialog 由 WMS 按 display 坐标系居中于横屏 frame → 竖屏下必右溢，
        // 改宽度/锚点都无效（vc158/vc160 两次实测均未解决）。
        // 新增 AppDialog.kt：**应用内覆盖层**（同 errorCardView 做法），22 处调用点
        // 由 AlertDialog.Builder 机械替换；fitWidthToScreen/showFitted 随之删除。
        // 追加两条竖屏修复：①弹窗内容区改「包内容、限高 70%」（短内容不再留白）
        // ②自动连接方式/画面帧率两组单选改 WrapRadioGroup 可折行容器
        //   （竖屏四项一行放不下；首版折行挂在 onSizeChanged 导致整组空白，改 onMeasure）。
        // vc162：横竖屏确认后的重启方式由「Launcher intent + killProcess」改为
        // activity.recreate()——避免 startActivity 未送达就被杀（点了没起来），
        // 且走完整生命周期让 onDestroy 优雅拆栈（原实现不执行 onDestroy）。
        // vc163：**悬浮球全局化**——原先是两套球（home 可拖拽 46dp + 子页各自
        // attachPageBall 建的 34dp 不可拖），视觉/交互/位置都不统一且切页闪烁。
        // 改为 MainActivity.render() 里挂一颗全局球，所有页面共用、可拖拽、
        // 位置跨页面保留；点击语义集中一处（home=收起/唤出面板，子页=逐级返回）；
        // 删除 UiKit.attachPageBall 与三个子页的调用点。
        // vc171（K60 实测迁移）：first TCP watchdog RecoveryHint 文案
        // 补充两大实证因素——iPhone 保持亮屏解锁（iOS 熄屏暂停热点自动加入，实测 44s vs
        // 8.6s）+ 关闭该网络「私有无线局域网地址」（用户实测体感相关）；行为层不动：
        // 既有 60s 窗口+宽限复查已覆盖 40s 慢入网上限（实测数据见内部工程文档）。
        // vc174（C8815 中继 00:26~00:28 三轮日志取证，含两个误判修复 + 一条文案）：
        // ①网关自检误杀——Windows 承载网络（192.168.137.x）/多数手机热点默认不回 ICMP，
        //   车机已拿到 192.168.137.35 却被判 RELAY_UNREACHABLE 弹「AP隔离」。改为 ICMP 全灭后
        //   补一次 TCP(53/80/443) 探测：有应答即判「只是禁 ping」记告警继续，真隔离才报错。
        // ②PSK 比对失效——Android 4.x 的 getConfiguredNetworks() 把 PSK 掩码成 "*"（日志
        //   `PSK mismatch (stored=1 chars)`），导致每次点连接都 update+disconnect+reconnect，
        //   DHCP 每次重来 ~20s。改为掩码视为不可比对；已在该网且拿到 IP 时一概不动配置。
        // ③attachNetwork 显式写 allowedKeyManagement=WPA_PSK——只给 preSharedKey 不给 key_mgmt
        //   时老 supplicant 落到开放网络语义，去连 WPA2 热点必然握手失败，表象正是
        //   「密码明明对却报密码错误」。
        // ④first TCP watchdog 两条无效提示（亮屏解锁/私有地址，复测不成立）改为
        //   「无互联网连接 → 仍要加入」（C8815 入网 76.5s 的真因）。
        // vc175（K60 有线拔线实测，20261023）：LiteSessionService.onSessionEnded 区分
        // 主动断开/中断——拔线瞬间不再弹误导性的「会话已结束」（用户多点一次按钮的根因），
        // 改可行动文案；重插→attach→授权→重枚举本就全自动，无需其他行为改动。
        // vc176（K60 复测实锤，20261023）：vc175 只改了文案没治本——首次授权后 0x52=0x0
        // 重枚举的旧实例 DETACHED 被 MainActivity vc149 拔线监听误判为用户拔线 →
        // stopService 杀掉重枚举兜底 → 弹「控制通道已结束」。改为重枚举等待期
        // （WaitingForReenumeration）忽略 DETACHED：授权①→重枚举→授权②→出画面全自动，
        // 不再弹结束提示、无需二次点按钮。改动：LiteState.reenumerating 标志 +
        // Service 状态机维护 + MainActivity detach receiver 忽略判断。
        // vc177（20261023）：修 `autostartPoll` 判据写反（`!isFinishing` ⇒ 轮询体从不执行，
        // 开机自启等待 60s 后必然走超时分支，「上车即连」实际从未触发）。仅去掉一个 `!`。
        // vc178（20261023）：源码盘点批次 1~7 收敛（P1/P2 健壮性，无功能变更）：
        // ①BplistCodec/Iap2WireCodec 对端字节边界校验（防 NegativeArraySize/OOM 类崩溃）；
        // ②MicrophoneUplink.rotateSilentRecorder 先起新后停旧 + 失败回滚（修「换了音源后
        // 上行无声」）与每次轮换泄漏的 AEC/NS；③Iap2LinkChannel.close join 前 notifyAll +
        // 外层超时 1s→3s（内层脚本可跑 16s，原设定必然假超时）；④CarPlayController 建链
        // （含最长 ~6s 的收敛 sleep）移出单线程池、forceCarplayConfiguration 的 resume 进
        // finally（任何 Throwable 都不再永久占用 USB 接口）；⑤AudioRenderer.close 先 stop
        // 再 interrupt（WRITE_BLOCKING 不响应中断）+ AAC 零拷贝喂 codec（省一次全帧复制）；
        // ⑥wiredStartWatchdogGeneration 改 AtomicInteger、bonjourGoodbye.await 补中断保护；
        // ⑦UI：Typeface 静态缓存（整页 40~100 次 create → 复用）、renderState 单键读
        // activeChannel（原每 500ms 全量 load 30+ 键）。
        // vc179（K60 API34 实测，20261023）：中继通道在 Android 11+ 上**根本没被支持过**——
        // RelayClientManager 全链路用的是 legacy WifiManager 配置 API（configuredNetworks /
        // addNetwork / updateNetwork），自 API 30 起对第三方应用一律失效（addNetwork 恒返回 -1、
        // 读不到任何已保存网络）。K60 实测：系统已连着 7205 且握手 COMPLETED、IP 已拿到，
        // App 仍报「Adding relay network '7205' failed (code=-1); check the password」——
        // 纯属误导，与密码无关。改动：①attachNetwork 开头短路「已连目标网且有 IP 就复用，
        // 不碰配置 API」（高版本唯一可用路径，对 API16 同样正确）；②addNetwork 失败文案按
        // 版本区分，API30+ 明确提示「不是密码问题，去系统 Wi-Fi 设置先连上」。
        // ③（C8815 07:50 实测）网关可达性判据换成**二层 ARP**：Windows 承载网络 192.168.137.1
        // 是纯 NAT 网关——防火墙禁 ping 且自身不监听 53/80/443，于是 vc174 的「ICMP 不通补
        // TCP 探测」兜底同样失效，车机明明已拿到 192.168.137.35 却被判成「AP 隔离」硬失败
        // （同网段 K60 adb 通信正常，链路是活的）。改为查 /proc/net/arp 能解析到网关真实
        // MAC 即判链路活；解析不到仍回落 ICMP+TCP 原失败路径，保护不丢。
        // vc180：有线 TUN 失败可观测化 + 失败文案可行动化（AC822X/API17 远程排查定案）。
        // ① 该车机 5 次有线全在 network/attach 后 7~19ms 报「wired VPN TUN unavailable」：
        //    AOSP 4.2 的 VpnJni create_interface() 直接 open("/dev/tun") 且不检查返回值，
        //    内核缺 CONFIG_TUN 时 ioctl 作用在 fd=-1 上 → IllegalStateException("Cannot
        //    create interface")；原因此前只 Log.w 到 system logcat，而日志上云只带 LiteLog
        //    → 远程完全盲区。现把异常类型/消息 + /dev/tun 与 AF_INET6 体检记进 LiteLog，
        //    并区分 establish「抛异常」与「返回 null（未 prepared/UID 不匹配）」两种失败。
        // ② /dev/tun 缺失时建链前即收手（不再白跑 11s 的 USB 枚举 + Lockdown 配对），
        //    失败文案改「内核不支持 TUN，请改用无线」，替代误导性的「请拔插数据线重试」。
        // vc181：外置 USB 蓝牙 C+D 批落地（shared/.../usbbt/ 新增 18 个文件）。自驱 dongle
        // 直驱 usbfs（HCI→L2CAP→SDP→RFCOMM→iAP2），无线建链不再依赖系统蓝牙栈；接缝仍是
        // CarPlayController 一处，开关 usbBtDongle=false 时行为与 vc180 完全一致。
        // 注明两处自我限制：link key 仅内存（断电需重配对）、v1 无主动扫描（等 iPhone 回连）。
        // vc182：中继热点在 Android 11+ 报错文案误导修复（K60 API34 实证）。addNetwork() 在
        // API30+ 对第三方恒返回 -1，与密码无关；LiteSessionService.localizeFailure 原把
        // "adding relay network" 一律翻成「请核对随身WiFi密码」，改为按 SDK≥R 分流提示
        // 「请先系统 Wi-Fi 设置连热点再回 App」，API≤29 保留密码提示。行为不变（仍走
        // isJoinedWithIp 复用短路），仅纠错提示文字。
        // vc183：外置 USB 蓝牙 dongle 在 t3（全志 sunxi + Android4.4.2/KK）初始化超时修复。
        // t3 日志 diplay-20261023-225656 实证：USB 事件端点 bulkTransfer 持续 n<0（≥20 次）
        // → "dongle 初始化超时"。根因是 sunxi KK 内核「描述符半解析」使 HCI 事件端点表残缺。
        // UsbBtDriver.open 照搬有线路径 NcmUsbBridge 的 USBDEVFS_RESET（portReset 强制重枚举，
        // RESET 后重开连接再 claim），并在 KK 上补 forceClaim 踢内核 btusb 驱动。仅 KK 且
        // native 可用时生效，其他平台内核不受影响。这是 dongle 首次真机（t3）实测暴露的平台缺口。
        // vc186：蓝牙名称行几何完全对齐 buildValueRow（应用键去 leftMargin=8、标签去 rightMargin=8、
        // 行外层 block(top=4,bottom=4)）——修复 K60 实测输入框与「分辨率自定义」偏差 8dp。
        // vc187：t3（KK/全志）有线 NCM 三修，全部来自 1.2.20 两份日志（215214/212928）：
        // ① 0x4301 重发判据换掉 streamFrames——iPhone 的 0xae01/0x4158/0x4155/0x1dxx/0xaaxx
        //   均不属 LINK_CONTROL 域 0x5000..0x5FFF，恒使 streamFrames>0，把重发饿死
        //   （实证 14 次首发、resend 0 次）。改用 controller 注入的 airPlayActive()。
        // ② Siri 音频恢复看门狗：10s→20s，且视频流仍在时禁止 close 整条会话——实证它
        //   把「音频恢复慢」升级成 NCM 楔死→wired teardown（单份日志 48 次）。
        // ③ mDNS 未命中查询把 qname 打进日志（69 查询/0 应答长期无据可查）。
        // 附带：IPv6 不再指名绑 `::`，改用通配地址（双栈行为不变，IPv4 仅作异常回退）、建立期看门狗 15s→25s、mDNS 端口日志纠错。
        // vc188：给 T3 外测的【探测版】——目标是回答「是否因低 iOS 版本差异导致」，
        // 所以本版以**可观测性**为主，不改变建链主流程的成功/失败语义。
        // ① 【构建守卫·补漏洞】injectRuntimeIdentity 完整性检查加「非空」判据。
        //    vc187 只判 isFile（存在），而 File.isFile 对 0 字节文件同样返回 true
        //    ⇒ 空文件过检被拷进 assets ⇒ 运行时必抛 "Local identity file is empty"。
        //    这正是 220020 的失效形态，原守卫恰好漏掉它要防的那一种。
        // ② 【链路健壮性】0x52（CarPlay 模式切换）厂商请求在本次 attempt 内做
        //    最多 3 次尝试、每次间隔 400ms settle，覆盖 KK 上首次 open / 重枚举竞态。
        //    ⚠️ 防御性增强非确诊修复：222631 六轮全 -1 提示多为陈旧实例或 iPhone USB 态卡死。
        // ③ 【错误分级】日志区分 0x52 的 0x6（已配置拒绝）/ 0x0（接受切换）/ -1（传输未完成）。
        //    附：framework 的 controlTransfer 不返回 errno，细化需 native usbfs（见 PROBE_TODO）。
        // ④ 【核心探测】在建链早期 dump 完整 USB 设备描述符要素（含 bcdDevice）。
        //    价值：222631 死在 usb/config（早于 iAP2 机型交换）⇒ model=/ios= 全缺失，
        //    无法验证「是否 iOS15 / SE2020 特有」。bcdDevice 通常编码 iOS 版本
        //    （高字节≈主版本，0x0F=15）⇒ 不建链也能反推 iOS 版本。
        // ❌ 不做：档 C（全志系主动封顶 CarPlay 分辨率）——待外测数据再定。
        // ─────────────────────────────────────────────────────────────
        // vc189（组合发布，含两独立改动）：
        // ① 【native usbfs errno 细化（诊断）】0x52 控制传输失败时改走 native
        //    controlTransferErrno（usb_host_compat.c，USBDEVFS_CONTROL，失败返回
        //    -errno），Kotlin 层记录 errno 数值 + 名称（ENODEV/EPIPE/ETIMEDOUT/EBUSY…），
        //    把「-1 传输未完成」细化为可定位的 USB 层原因。仅 0x52 失败路径触发，成功路径零变化。
        // ② 【CM390（Realtek RTL8761BUV 0bda:8771）支持 · 方案 A 全量移植】fork 时剥离的
        //    Realtek 固件下载子系统完整回植：RealtekFirmware.kt（芯片家族识别 + patch 解析）
        //    + UsbBtFirmwareLoader.kt（HCI 下载流程，桥接 UsbBtDriver.hciCommand）。
        //    固件二进制（linux-firmware 再分发许可）走私有仓 /auth/usbbt_fw 注入
        //    app/src/main/assets/usbbt_fw（已被 .gitignore 排除，绝不进公开仓）。
        //    UsbBtDriver.open() 之后、UsbBtHost.start() 之前按 vendorId/productId 触发
        //    detectChipFamily → 仅 Realtek 走下载，CSR8510（现用 dongle）判 CSR_NO_FW 空过，
        //    老设备零回归风险。T3 实测 CM390 验证目标。
        // ─────────────────────────────────────────────────────────────
        // vc191（T3-C + 绿联 CM390 bulk 阻断修复，两独立改动）：
        // ① 【核心】KK usbfs 端点激活改用 SET_INTERFACE 标准请求（B 兜底优先）。
        //    vc190 实测（日志 20261027-195041）：portReset/重开/claim/拓扑端点表全部正常、
        //    固件 Skipped 零回归，但 `setInterface` 行 native USBDEVFS_SETINTERFACE
        //    在该 KK 上 unavailable=-1，退化 framework 又未真正同步内核 ep 表 ⇒
        //    HCI 接口 bulk/interrupt 端点仍 disable ⇒ bulk read 持续 n=-1（failures→20）
        //    ⇒ 初始化超时 Failed、BD_ADDR 未读。
        //    修法：UsbBtDriver.applySetInterface() 在 claim 后先发标准设备请求
        //    controlTransfer(0x01, 0x0B, 0, iface.id, null, 0, 5000)
        //    （bmRequestType=0x01 / bRequest=0x0B / wValue=alt0 / wIndex=接口号），
        //    纯 framework 不依赖 native ioctl；ret>=0 即视为成功，失败才退化
        //    原「L+ framework → native」路径（零回归）。对齐 legacy v0.2.98 在 KK 可连的做法。
        //    验收：日志出现 `std-request ret=0` + bulk 不再 n=-1 + MAC 非全零。
        // ② 【可观测性】设置页新增「外置 USB 蓝牙状态」实时块：已识别 VID/PID、本地 MAC、
        //    广播名、状态（2s 轮询，退页停表，仅选「外置蓝牙」时显示）。
        //    动因：外测反馈「外置蓝牙不显示」只能盲测（vc190 日志零 dongle 痕迹）；
        //    配套 UsbBtBootstrapStream.dongleVidPid() 暴露 VID/PID 查询。
        // ③ 【命名定调】不做 MAC 派生广播名：自定义优先(usbBtDongleName)，
        //    未改即用默认 "DiPlay CarPlay"（视频里 DiPlay-1A21 仅为对方形态，不影响配对）。
        // ─────────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────
        // vc192（外置蓝牙「常驻广播」，对齐 legacy 0.3.1 实测流程）：
        // 外测反馈（T3-C + 绿联 CM390，vc191 设置页）：`本地 MAC：-`、`状态：待连接时探活`，
        // 且「iPhone 蓝牙里看到的还是 CM390 模块」。截图即证明本会话从未 open() dongle
        // ⇒ 名字从未写入 ⇒ iPhone 看到的是 dongle「已 patch 未改名」的出厂态。
        // legacy 0.3.1 实测（同车同 dongle，日志 20261027-201057）：App 一起来就
        // `USB Bluetooth enabled … advertising=DiPlay-1A21`，挂 4 分钟等 iPhone 主动回连，
        // 交接后以 `handoff shutdown stage=… HCI reset` 收尾。差异三条：
        //  ① 我们「点连接才 open」→ 平时 dongle 不可发现/名字未写；
        //  ② Write Local Name 仅在 open() 内，失败即永不写；
        //  ③ stop() 无 HCI Reset → 失败退出后 dongle 残留出厂名继续广播。
        // 本版改动：
        //  ① UsbBtHost.stop() 收尾补 best-effort HCI Reset（对齐 legacy stage=HCI reset）。
        //  ② 新增 usbbt/UsbBtStandby.kt：常驻广播保活线程（初始化→写名→ScanEnable→
        //     长窗口 10min 等回连；拿到 iAP2 RFCOMM DLC 放交接槽；无人取用 60s 后重新广播）。
        //  ③ UsbBtBootstrapStream.open()：常驻广播在跑即取它交出的流（避免二次 claim），
        //     否则走原逻辑（零回归）；拆出 openDirect(linkTimeoutMillis) 供保活复用。
        //  ④ MainActivity：onResume 选中「外置蓝牙」即 start standby（幂等），
        //     onDestroy stop；iPhone 回连且当前 IDLE ⇒ 自动发起无线连接。
        //  ⑤ 设置页状态块：常驻广播在跑时显示真实建链状态（`广播中「…」，等待 iPhone 配对…`），
        //     不再显示「待连接时探活」。
        // ─────────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────
        // vc193（FE6 root 语音流取证 + 多档回落测试，包代号 1.2.27-fe6root）：
        // 背景（日志 20261028-174703 + vc192 分析）：FE6 有线 Siri 的麦克风上行存在
        // **两种互斥的失效形态**——α「读到全零」（v1.1.x 四次成功出包但 peak=0）
        // 与 β「read 阻塞」（v1.2.x 连 read 都不返回）。现有代码只有打开时参数矩阵
        // 与 zero-read/静音换源，**探测器对 β 完全无感**（既非 error 亦非 0）。
        // 本版三层（仅诊断，单开关 FE6_ROOT_PROBE 隔离，不动 dongle 包行为）：
        //  ① 层 A 轻量观测（MicrophoneUplink，通用观测、无 root 也跑）：
        //     首包看门狗 3s（对齐 iPhone 3.31s 拆流）+ 阻塞读探针 >2s + 采集态快照
        //     （threadAlive/recorderState/recordingState）。把 β 从"静默"变铁证。
        //  ② 层 B root 语音流路径取证（Fe6VoiceProbe，只读）：su 自报 uid 防 SuperSU
        //     静默降权（T3 vc100 定案）→ B1 节点 / B2 占用（/proc/asound/pcm*c/sub0/
        //     status 的 owner_pid）/ B3 AudioFlinger 输入线程 / B4 QG 策略与属性 /
        //     B5 mixer，全量拉回宿主侧过滤（老 ROM su 管道不可用）。
        //  ③ 层 C 11 档回落矩阵：L0-L6 AudioRecord（源×率×声道，独立线程 + 超时
        //     stop() 唤醒阻塞读，判 OK/SILENT/BLOCKED/OPEN_FAIL）+ R1 root 直采
        //     （自带 diplay-pcmcap，auth/tinycap 构建期注入 assets）+ R2 停占用者 +
        //     R3 解 mic-mute（R2/R3 为写操作，需用户在设置页显式勾选授权）。
        // 验收：日志出现 micprobe root=uid=0 / [B1]-[B5] / matrix L0-L10 verdict= /
        //       Microphone firstPacketWatchdog 或 read blocked。方案见
        //       工程文档/方案-20261028-FE6root语音流回落测试.md。
        // ─────────────────────────────────────────────────────────────
        // vc194（FE6 root 诊断包二版）：修 vc193 实测暴露的六项缺陷。
        //   实测依据 工程文档/日志分析-20261028-FE6-vc193-体检卡死与mixer快照.md：
        //   层 C 矩阵在第一档永久卡死（B5 之后 685s 零日志、MATRIX 0 条），根因是
        //   AudioRecord 构造与 stop() 都在超时保护之外；层 B 则拿到了决定性 mixer 快照
        //   （MIC Input Volume L/R=0、Audio_ADC_1~4_Switch=Off，数字侧全通）。
        //   F1 构造/start/read 全纳入超时 + stop 走守护线程限时
        //   F2 B2 改 glob 遍历真实存在的 substream 目录
        //   F3 新增采集期 mixer 快照 B5T/B2T 与空闲态 diff（判定 HAL 有没有配 ADC）
        //   F4 R3 覆盖 MIC Input Volume=0（写中值）与 Audio_ADC_x_Switch=Off（置 On）
        //   F5 体检总看门狗 180s 强制复位，防僵尸 AudioRecord 占住正式 Siri 的麦克风
        //   F6 progress 每 5s 落 HEARTBEAT，卡死可定位阶段
        // 验收：MATRIX_BEGIN + MATRIX L0-L6 逐档 verdict= + B5-DIFF 一行 + CONCLUSION。
        // ─────────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────
        // vc195（FE6 root 诊断包三版 / 1.2.27.fe6root3）：以「一次性判定走哪条路」为目标。
        //   实测依据 工程文档/日志分析-20261029-FE6-vc194-体检完成与DSP定案.md：
        //   体检已跑通（95s / END / 666 行），但三个结论强度不足 —— 矩阵 READ_FAIL 是误判、
        //   R1 输出被 stdio 缓冲吞掉、采集期快照落在 blocked 档。故本版全是「让证据成立」的修：
        //   G1 verdict 判据顺序改为「先阻塞后错误码」，-3 记为 closeArtifact 不计 READ_FAIL
        //   G2 R2/R3 复测档从恒 L0 改为 BEST_TIER（矩阵里最能读的那一档）
        //   G3 采集期 mixer 快照挪到 BEST_TIER 采集期间抓（两轮都在 3s 窗内）
        //   G4 R3 写完必须回滚（vc194 把车机 MIC Input Volume 永久改成 10 了）
        //   G5 diplay-pcmcap v2：stdout 无缓冲 + 每阶段 alarm 硬超时 + open 用 O_NONBLOCK
        //   G6 R1 改三步走：R1a 三段设备扫描(open+hw_params) → R1b 按 aif3/aif4 优先真采
        //      → R1c 系统 /system/bin/tinycap 交叉对照（换实现再问一遍）
        //   G7 结论按「已证实 / 待证实 / 未定型」分级，不再把无流期间的 mixer 不变写成定案
        //   另：体检总看门狗 180s → 600s（新流程正常路径 180~240s，180s 会半路掐断）
        // 验收：R1A_SUMMARY hw_ok=N + R1B/R1c 的 peak/frames + BEST_TIER 行 + CONCLUSION 首词。
        // ─────────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────
        // vc196（1.2.28.fe6mic）：**首个不依赖 root 的实修版**。
        //
        // 转折点：vc195 之后用户实测「车机自带语音助手正常收音」（附录像）。这一条把结论
        // 彻底改写——硬件、AK7738 DSP、MicBias 偏置、系统麦克风总开关全都没坏，坏的只是
        // 「DiPlay 这条 AudioRecord 通路拿到的样本恒为 0」。于是 vc195 的 MicBias 假设作废，
        // root 也从「修复手段」退化为纯诊断工具。
        //
        // 根因（两处都在 App 侧，不需要任何权限）：
        //   R1 `tryOpen` 以「能否 STATE_INITIALIZED」为判据。FE6 上 mono 恒能初始化，但
        //      AudioFlinger 交付的是静音填充 ⇒ 首个初始化成功即返回，永远停在 mono 第一档。
        //   R2「静默自愈」只轮换**音频源**、从不轮换**声道**。而 vc195 拓扑实证：车机自己的
        //      录音进程（pid 3191）以 16000/2ch 独占 pcm1c(aif3, MultiMedia1_Capture
        //      ak7738-aif3-1) 且 state=RUNNING、hw_ptr 稳定递增 —— aif3 就是麦克风主通路，
        //      它按 2 声道工作。⇒ 二十多个版本从未试过 stereo。
        //
        // 本版改动（MicrophoneUplink，全机型共享，正常机型零行为变化）：
        //   M1 新增有声性探测 probeAudio()：开流读 250ms 看真实样本峰值，成功时**不 stop**
        //      并把已读音频交回，探测不吞语音。
        //   M2 新增声道升级 promoteChannels()：连续 1s **精确全零**（不是 <300 近静音）时，
        //      同源依次试「同率换声道 → 16000 → 48000」，命中即热切换。判据用「精确全零」
        //      而不是定时探测，正是为了**在没有任何音频可丢的时刻**才动手；正常机型永远
        //      走不到这条分支。
        //   M3 升级后的通路**不挂 AEC/NS** —— 未设 MODE_IN_COMMUNICATION 时部分 HAL 的 AEC
        //      会把输入打成静音，顺带把它变成一个可观测变量。
        //   M4 tryOpen 保持「首个可初始化即胜出」不变（开机探测会吞掉用户开口的那几百毫秒）。
        //   体检侧：矩阵补 L7–L12（24000Hz 与 stereo 档）——vc195 的矩阵只有 48k/16k/44.1k
        //   且全 mono，既把真实业务档「源6/24000」整片误判成 BLOCKED，也从未试到 stereo。
        //
        // 验收：真实 Siri 会话里出现 `Microphone channel promotion: 24000Hz/1ch -> .../2ch`
        //   且随后 level window 的 peak > 0（不再恒 0）。
        //
        // vc197 / 1.2.29.fe6mic2 —— 实验性 FE6 语音兜底（默认关，仅 FE6 生效）：
        //   外测 20261029-203743 定案推翻 vc196：β 形态下 read 第二次即永久阻塞，挂在
        //   「每秒 level window」上的声道升级天然不可达（全日志 channel promotion 零命中）；
        //   矩阵 L7–L12 又证明 `源6` 全配置 BLOCKED（readCalls=0），能读的 src0/44.1k、
        //   src1/24k 全 peak=0 ⇒ 配置维度穷尽，换声道/换参数这条路正式关闭。
        //   加上「车机自带语音助手正常收音」，静音只能出在 AudioFlinger 给第三方 record
        //   track 加填充这一层。于是改两条：
        //   ① 采集/发送解耦（FrameRing + pacer 线程，缺数据补静音帧）—— 解决 read 阻塞
        //      导致「一个包都不发 ⇒ iPhone 2s TEARDOWN」的「一叫 Siri 就断线」；
        //   ② root 直采兜底（diplay-pcmcap -W 裸 PCM 直出 → su 管道 → 上行）—— 绕开
        //      AudioFlinger，从 ALSA 采集节点拿真音。
        //   两者由设置页「FE6 车型测试 → 实验性：FE6 语音兜底」控制，**默认关**，
        //   且非 FE6 机型完全不出现、不生效（发送路径与历史版本逐字节一致）。
        //
        // vc198（1.2.30.fe6auth）—— 修 root 授权链路 + 首读指纹 + 关混淆：
        //   vc197 外测（日志 20261029-212840）证明保活只是治了「不发包」，救不了 Siri：
        //   上行全静音 ⇒ Siri 约 3s 静默超时 ⇒ 自行收尾（vc192 零发包时也活 3.31s，
        //   满发包 3.15s，「收不到包才 TEARDOWN」被证伪）。root 直采全程 root=off。
        //   ① root 探测改回 `su -c id` + contains("uid=0")（与体检逐字对齐）；vc197 用的
        //      `su -c id -u` + trim()=="0" 在老版 SuperSU 上必判失败 —— 有 root 也报未授权；
        //   ② su 候选遍历 PATH + /system/xbin/su、/system/bin/su、/sbin/su、/su/bin/su、
        //      /magisk/bin/su、/data/local/tmp/su；suExec 记 exitCode+stdout+stderr+耗时，
        //      不再 catch 成空串；
        //   ③ 设置页新增「① 授权并自检 root」：建链前、前台可见时授权（投屏全屏时授权框
        //      弹不出来），当场走「授权 → 部署二进制 → 试采 1 秒」，peak>0 即证明直采有真音；
        //   ④ 首读指纹成一等日志：`Microphone FIRST read sinceUplink=Xms peak=Y ⇒ 判定`。
        //      依据是 FE6 唯一成功那次（272ms / peak=5）与失败那次（5ms / peak=0）的对照；
        //   ⑤ 按用户决策关闭 R8（isMinifyEnabled / isShrinkResources = false）：
        //      此前 mapping.txt 42k 行、Fe6Device 被内联改名，dex 里查不到类名，
        //      排障与验收都得靠字符串字面量绕。代价是包变大，换来可直接核验。
        // ─────────────────────────────────────────────────────────────
        // vc199（1.2.31.fe6su）—— 修 vc198 自己踩的 API 兼容 bug + 统一直采参数：
        //   vc198 外测（日志 20261031-105043，FE6-A 4.4.9(By_Sapphire)，有线 NCM）：
        //   root 探测 28 次全灭，但**不是授权问题** —— Fe6RootMicSource.suExec 用了
        //   `Process.waitFor(long, TimeUnit)`（Java 8 API，Android 只有 **API 26+**），
        //   api22 上直接抛 NoSuchMethodError，被 `runCatching{}.getOrDefault(false)`
        //   静默吞成"超时" ⇒ 每次都 15~41ms 就返回 exit=-1，却写着「等待授权超时 20000ms」。
        //   铁证：同车同进程的体检（Fe6VoiceProbe.suExec）与 usbfx（WiredUsbForensics.suExec）
        //   都用「轮询 exitValue」的写法，分别拿到 uid=0(root) 和 450 行 root dmesg。
        //   ① 新增 shared/SuShell：全项目唯一的 su 执行器，超时一律轮询 exitValue()，
        //      Result 记 exitCode/stdout/stderr/耗时/launchError/waitError/timedOut，
        //      异常一律带类名出去（NoSuchMethodError 这类再也不会伪装成业务失败）；
        //   ② 三处 suExec（Fe6RootMicSource / Fe6VoiceProbe / WiredUsbForensics）全部
        //      改走 SuShell —— 三次分叉已经踩了两次坑，不再留第四份；
        //   ③ 直采探测窗口 1200ms → **400ms**、总预算 6s → 2.5s：vc198 实测
        //      `root fallback #1` 触发到 iPhone TEARDOWN 只剩 276ms，探测快比探测全重要；
        //   ④ selfTest 命中/可读的参数存为 `cachedSpec`，会话里 open() 直接用它当第一档
        //      —— 把「选节点」从会话中挪到用户主动点按钮时；
        //   ⑤ 体检 R1a 扫描不再写死 48000/1ch（那是 17 节点 hw_params 全 EINVAL 的真因）：
        //      先按实物参数 16000/2ch 扫，全不通才依次降级 48000/1 → 24000/1 → 16000/1；
        //      R1b/R1c 用命中的那组参数真采，不再各写一套；
        //   ⑥ 首读文案改准确：删掉「已采集但静默（此刻没人说话）」这种把"没采"说成"安静"
        //      的说法；保活打点在 readCalls 停在 1 时直接标出「β 形态：首块后 Xms 无新数据」。
        // ─────────────────────────────────────────────────────────────
        // vc200（1.2.32.fe6su）—— FE6 直采预热 + 有线 VPN 清理下移：
        //   A. FE6 root 直采**预热**（vc199 实测的结构性死结）：
        //      vc199 里 `root fallback #1` 被触发时，距离 iPhone TEARDOWN **只剩 419ms**，
        //      而 `su 探测 359ms + 二进制部署 371ms = 730ms` —— 光这两步就已超时，
        //      isAlive 修好了也照样一毫秒都用不上，**root 直采至今 0 次真正运行**。
        //      ① MicrophoneUplink.start() 一进来就起 `carplay-mic-preheat` 线程做
        //         su 授权 + 二进制部署（原本要等 fallback 触发才做），并行于 AudioRecord
        //         主路径，不阻塞、不影响非 FE6 机型（两个前置条件都不满足即空转）；
        //      ② 授权+部署就绪后若见 AudioRecord 仍无真音，顺带把 `-W` 直采**先开好**
        //         （AutoRecord 已经出真音则不开，省掉抢节点）；
        //      ③ fallback 触发时**优先接管预热流**，省掉 su+部署+试开整串；
        //         会话结束/预热没用上时在 release() 里收掉子进程，不挂在进程里。
        //      ④ Fe6RootMicSource.isAvailable() 加锁：预热线程与 fallback 线程会并发进来，
        //         不加锁会各起一次 su+cp/chmod，白烧 730ms —— 恰恰是这窗口最缺的。
        //   B. 有线 VPN：清理下移 + 确认框删除（详见 WiredVpnService/MainActivity）：
        //      ① `ensureCleanVpnState()` 从 onCreate 一次性动作**下移到 connect("usb") 入口**
        //         （长驻车机 Activity 是 singleTask + configChanges 全覆盖，onCreate 只跑一次，
        //         「上次异常结束没拔线」的残留 TUN fd 在下次连接前无人复查）；
        //      ② 新增 `WiredVpnService.hasResidual()` 守卫：无残留整段跳过，不再无条件
        //         `stopService()`（低 API/EMUI 上 stopService 紧跟 startService 有被丢弃先例）；
        //      ③ establish() 返回 null → 先 `prepare()` 复核成因，仍放行则**同参重开一次**；
        //         控制器侧再兜一层「整轮重开」（4s 短超时，只在失败路径触发）；
        //      ④ 删除 app 内「VPN 连接确认」框：它从未承载清理职责（「确定」只是 startSession），
        //         且 establish 为 null 时点了也没用（改变不了 mPackage）。实测省 1.0s + 一次点击。
        // ─────────────────────────────────────────────────────────────
        // vc201（20261102，待办源码修复批，不动交互/实测数值）：
        //      ① 并发/泄漏：Opus 编解码 closed 加 @Volatile；NtpClock IPv6 bind 失败回落补 close；
        //         音频解码器/OpusEncoder 创建异常路径补 release；MicrophoneUplink 预热流交接
        //         判活+赋值包进 synchronized；AEC/NS 换源回滚补效果重挂；controller 拆栈恢复加
        //         CAS 门禁、隧道装字段前补代次护栏、close() 补 detachUi；mic 延时重试任务体
        //         加起点门禁（cancel(false) 拦不住 in-flight，防孤儿 uplink）。
        //      ② 健壮性：Realtek 固件 size 恰为 252 整数倍时不再多发零长度结束片；
        //         WiredVpnService/BarService 裸 waitFor 改轮询超时；Fe6RootMicSource
        //         invalidate 加 @Synchronized、root 直采档位被全零兜底占用时放掉兜底重试一次。
        // ─────────────────────────────────────────────────────────────
        // vc202（20261102，无线建链加速批，依据竞品逆向 EasyPlay-Lynk preview29）：
        //      ① handoff 判据加「已鉴权 AirPlay 帧」兜底（wirelessEarlyHandoffOnFrame，
        //         默认开）：已渲染帧 + handoffRequested + 隧道未就绪 → 提前按 degraded
        //         完成 handoff，隧道继续后台补齐；degraded 语义与拆栈 CAS 门禁不动；
        //      ② 车机热点（仅 MANUAL/MANUAL_IPV4）启动前踢 2.4G STA 强制 5G
        //         （wirelessStaKickFor5g，默认开）：wifi.disconnect() 失败/读不出频段均
        //         只落日志按原流程走；RELAY/WIFI_P2P 路径不经此分支；
        //      ③ SESSION_ACTIVE 增 handoffMs/handoffReason/wifiBand/
        //         tunnelReadyAfterActiveMs 字段，staKick 全程落 staKickSkipped 判据日志。
        // versionName 自本版起按指定格式 "1.3.0-vc<N>"（弃用 1.2.$patch.fe6su）；
        // ciRelease 归档命名 DiPlay-<versionName>-cluster1-vc<code>.apk 不变，自动跟随。
        // vc231（20261104，主线程时序修复批，依据 vc230 实测 standby publish 后 slot 被清 + 主线程 teardown 不再触发自动连接）：
        //      vc230 蓝牙链路已全通（SDP 双向 + RFCOMM PSM3 + PN + DLC 2 open），但：
        //      ① standby publish 后 HANDOFF_WAIT 60s 超时就关 stream 清 slot → 主线程下一次 awaitStream
        //         拿不到；改 120s + 超时后不关 stream 保持 slot（keepAlive=true 时 loop 阻塞等）。
        //      ② onUsbBtLinkReady 里 phase!=IDLE 就 return → FAILED 状态（上一轮 teardown 遗留）
        //         不触发自动连接；改 CONNECTING/ACTIVE 才拦截（IDLE/FAILED 都允许）。
        // vc232（20261104，standby 所有权交接批，依据 vc231 实测「iPhone 手动连上 → 点立即连接卡在网络链路中」）：
        //      根因：standby.loop publish 流 → 主线程 CarPlayController 取走 slot 的同一瞬间，
        //      awaitTaken 返回 true → loop 立刻进入下一轮 openDirect，forceClaim 把正在跑
        //      RFCOMM/iAP2 的 driver 顶掉（vc231 日志：54.993 publish → 55.133 重开 dongle → ACL 断）。
        //      ① UsbBtStandby 加 HandoffStream 记 close，流被取走后 loop 暂停迭代，等控制器 close
        //         才恢复常驻广播（代际 + isClosed 双判，stop() 不强关已交出的流）；
        //      ② UsbBtHost 加「链路已在/page 在飞就不 page」：requestActiveConnect + connectTo 双保险，
        //         incoming ConnectionRequest 到达时消费 paging 并送 CreateConnectionCancel（角色=被叫方）；
        //      ③ 打点走 diagSink：流已交出/流 close 恢复广播/角色（主叫方·被叫方）。
        // vc233（20261104，两个小改）：
        //      ① UsbBtBootstrapStream：固件加载 Failed 显式中止（此前只打日志继续跑，与
        //         FIRMWARE.md:41 承诺不符，且把"固件没灌进去"伪装成"host 初始化超时"）；
        //         只有 Failed 中止，Uploaded/Skipped(already patched)/NoFirmwareNeeded(CSR) 不误杀。
        //      ② UsbBtStandby：控制器 close 后、重开 openDirect 前静默 500ms（BT-232-02），
        //         消除 vc232 实测「旧 driver release 与新实例 forceClaim 只隔 17ms →
        //         ReadLocalVersion 白等 3000ms」。
        // vc234（20261104，BT-232-02 真修）：UsbBtStandby.HandoffStream 的 isClosed 由
        //   「delegate.close() 之前」移到「之后」，幂等改用独立 closing(AtomicBoolean CAS)。
        //   vc233 实测（6 段会话/7 次重开）证据：新 open 抢在旧 `driver stopped` 之前
        //   8~57ms 时 100% 复现 `open 抢占` + `0x1001 await timeout 3000ms`（5/7 轮）；
        //   旧实例先释放的 3 轮（R4/R6/R8）两项均为 0。修后静默窗从"真正释放"起算。
        // vc235（20261104，VID-234-01 / A0，纯观测零行为变更）：
        //   VideoStats 增 superseded / dueGated 两个归因计数，`video stats` 行多输出这两项；
        //   AndroidMediaSink.drainOutput 只在两处既有的丢弃路径旁各 +1——
        //   superseded = 新帧到达使旧 pending 被 releaseOutputBuffer(index,false) 丢弃；
        //   dueGated   = 该上屏时 16ms 门控（RENDER_MIN_INTERVAL_NS）未到期而未上屏。
        //   未动 RENDER_MIN_INTERVAL_NS / renderHold / 合并策略 / 解码器配置。
        // vc236（20261104）三处：
        //   A2（VID-234-01）：视频渲染改 VSYNC(Choreographer) 节拍，CarPlayRuntimeConfig
        //     videoVsyncPacing 开关默认关（关=16ms 时间门控不变），设置页可勾选，重连生效；
        //   BT-235-01：UsbBtStandby 跨代先等上一代交接流 close（≤15s）+500ms 静默再 open，
        //     覆盖 Activity 销毁重建路径；
        //   BT-235-02：DISCONNECT(0x0406) 改只发不等（异步命令无 Command Complete）。
        // vc237（20261104）：
        //   A1（VID-234-01）：AndroidMediaSink 增 videoNoCoalesce 开关（默认关）——开启后
        //     待渲帧不再被后到帧取代，按 VSYNC 序号逐帧上屏、允许积压；关闭时合并策略与
        //     vc236 逐字节等价。设置页「视频帧不合并（实验，重连生效）」。
        //   BT-235-01 观测：15s 等待保持原值，等待开始/结束/超时三行均带 elapsed。
        // vc238（20261104）：版本号迭代，未含新逻辑改动。
        // vc239（20261105）：只做「让日志能证实结论」的打点修复（LOG-238-02/03、VID-234-03、
        //   MIC-238-01、BT-238-01、LOG-262-01），零功能行为变更。
        // vc240（20261105）：上传合并改版——只保留设置页「运行日志（含 dongle 链路）」一个入口，
        //   四段式（头区/骨架区/滚动区/尾区），总上限 768KB，上传总超时 60s；删除配对页 dongle
        //   独立上传入口 UI（组装能力保留为 skeleton 兜底）。
        // vc241（20261105）：删除「优先用外置蓝牙广播」设置项（固定走外置 dongle 广播）；
        //   设置页文案「运行日志（含 dongle 链路）」回退为「运行日志」（四段上传逻辑不变）。
        // vc242（20261105）：修 vc241 上传件骨架裁剪缺陷——旧策略「保首个 gen + 最近 3 个 gen」
        //   会让中间 gen（实测 vc241 的 gen 7~9）整段消失，与「骨架用于链路分诊」的目标冲突。
        //   改为按 gen 分组、每个 gen 强制保留关键锚点行（ANCHOR/SESSION_ACTIVE/scan mode ->/
        //   gate-off|on/ACL 链路建立/配对事件），段内优先省略 HCI init 明细，保留头尾若干行，
        //   每段落一行省略计数，顶部落总体统计（保留 gen 数/每段行数/省略行数/抽样规则）。
        // vc243（20261105）：T3（Android 4.4 / API19）闪退修复——移除 5 处 addSuppressed 失败
        //   聚合（API19 的 Throwable.printStackTrace 对抑制链无环保护，遇环会自递归到
        //   StackOverflowError），失败路径统一改走 diag.BoundedThrowable.boundedTrace()
        //   （异常类型 + message + 前 5 帧的固定上限格式），同类隐患一并收口。
        // vc244（20261105）：按链路分档隔离机内蓝牙——选外置 dongle 时，系统蓝牙设置页跳转
        //   改为二次确认、诊断取证不再反射 setScanMode(DISCOVERABLE)；车机蓝牙链路零改动。
        //   【vc246 更正】原前提（打开蓝牙设置页会让机内 framework 适配器进入可被发现状态）
        //   不成立：本机 framework 蓝牙栈未编入内核（config 无任何 CONFIG_BT*），
        //   t3-p1 为设备型号名、适配器名下无 HCI，不存在该状态；PIN 由杰理模块触发。
        //   两处改动保留无害。
        // vc245（20261105）：外置 dongle 兜底——启动有界自检（3s 看门狗、三态日志：
        //   不在列表/未授权/打开超时/打开失败）+ 主页面「重连蓝牙」手动入口（必要时 root
        //   sysfs authorized 软复位，已于 vc247 按 B-1 删除）+ DETACHED 接收器补 dongle(0a12) 分支。
        //   天花板：system_server D 态卡在 usbdev_open 时 App 层无解，只能重启车机。
        // vc246（20261105）：USB 稳定性整版——
        //   ① USB-246-01 三套重连节律合并为 1/2/4/8s（封顶），连续 5 次失败即停并上屏；
        //   ② USB-246-02 配对入口 ensureUsbBtStandby 等待上限 5s，超时跳过并上屏；
        //   ③ USB-246-03 任一 USB binder 调用 >3s → 粘性 D 态门禁（永久禁止再 open）+
        //      sysfs 旁证 + 重启提示；DETACHED 1.0s / ATTACHED 1.5s 静默期；
        //   ④ USB-246-04 六字段判别日志 layer/trigger/holder/gen/present/next，
        //      bulk read -1 只计数、不判定、不单列层；
        //   ⑤ USB-246-05 摘除「重连蓝牙」UI 入口（自动重连本体保留）；
        //   ⑥ USB-246-08 旧前提注释/文案清理（代码行为不动）；
        //   ⑦ 删除 UsbBtDriver KK 常态 port reset（仅保留 init 超时一次性救援）；
        //   ⑧ 自动连接撞常驻广播窗口根修：host ready 前置 + 等待期补踢 + onDestroy 仅
        //      finish 才停；RecoveryHint / First TCP 看门狗在「链路未交出」阶段不再误报；
        //   ⑨ 自检成功判据升级为 HCI init 应答（原 softReset 的 su 超时项已按 B-1 删除）。
        // vc247（20261106）：配对单台语义与外置链路自愈——
        //   V1 usb filter 增 0a12；V2 自检 SKIPPED + 进程级失败计数（阈值 2 上屏）；
        //   V3 断开拆「断开连接」/「停止外置蓝牙」两入口；V4 去掉 autoInit 开关（判据固化）；
        //   V5/V6 持久化单台原子写 + 存量多台首次启动清空（只清 dongle 表，不碰系统蓝牙）；
        //   V7~V11 单台门禁/顶替/status6 自愈限频；V12 配对窗生命周期；V13 scan 档位 60s 缓冲
        //   （// vc247 TEST，待 K60 真机验证）；V14 bonded vs 本轮认证区分。
        // vc249（20261107）：
        //   ① 设备码改明文蓝牙 MAC（用户拍板，撤销哈希派生）——日志文件名/日志头/设置页/启动日志；
        //     上传脱敏只放行「设备码」落点，其余 MAC 仍打码；取不到 MAC 时输出 unknown 并标原因。
        //   ② 重建 jniLibs 的 libusb_host_compat.so（armv7/arm64），真正编入 restoreKernelDriver。
        //   ③ 内核 NCM 失败拆两类：build（native 符号/库不匹配）与 machine（内核无 cdc_ncm），
        //     界面文案与日志锚点（class=）均可区分。
        //   ④ 保留有线内核 NCM 实验开关（不做 TUN 兜底）。
        // vc250（20261111）：
        //   ① 交棒看门狗补「首个 AirPlay TCP accept」锚点；握手期 RTSP 零字节 30s 看门狗（运行期仍阻塞）；
        //   ② 凭据三态 full / cached / none（0x5703 发送瞬间快照 accept 计数）；
        //   ③ ControlProof / MediaProof 拆分：控制面死亡在媒体成立时只降级，不拆栈、不消耗重试；
        //   ④ 四段耗时边界重指：credential=0x5703 成功、wifi-join=首个 TCP accept、
        //      airplay-establish=onSessionActive（旧键名保留）；
        //   ⑤ LONG_OUTAGE / SLOW_FIRST_DISPLAY 分型与慢轮节奏（60s / 5min）；
        //   ⑥ summary 与上传骨架标签补 session=<进程短 id> 绝对锚。
        // vc251（20261112）：
        //   ① 修 vc250-05 回归：Iap2WirelessControlClient 的 sendRealCredential() 局部函数
        //      调用自身（K60/ac8317 收到 0x5702 即 StackOverflowError → 进程闪退），
        //      改回 send(accessoryWiFiConfiguration(endpoint), deadlineNanos)。
        //   ② 其余行为零改动。
        // vc252（20261112）：凭证锚点重建——0x5703 下发后 8s 无任何 AirPlay TCP accept
        //   即整代重建（复用既有 retry 路径），替代"每 5s 重发凭证"的无效空等；
        //   summary 增加 failureKind=credential-no-join（iPhone 未入网，非"慢"）。
        // vc253（20261112）：vc252 实测三个缺口一起修，另补掉帧取证（主版本不动，仅 vc+1）：
        //   ① 凭证锚点 8s → 12s（冷路径首轮 8139ms 只差 139ms 被判死）；
        //   ② 新增静默轮判据：控制面建立后 20s 内无 0x5702/隧道鉴权/首 TCP 即整代重建，
        //      不再空等 First TCP 看门狗 60s+35s（vc252 单轮白占 95s）；
        //      summary 增加 failureKind=credential-no-auth；
        //   ③ 热点侧 STA 关联观测（/proc/net/arp 只读，凭据下发后/判死时各记一次）；
        //   ④ 首帧后 60s 内 video stats 行并入 Wi-Fi 侧只读采样（RSSI/link speed），
        //      用于区分编码侧降档与射频侧丢包，不改任何控制逻辑。
        // vc254（20261112）：闪退现场取证（主版本不动，仅 vc+1）：
        //   ① 崩溃处理器 flushNow()——此前未捕获异常只有 1 行、8 条批量 flush 等不到，崩溃栈必丢；
        //   ② 上传件默认打包最近 3 个进程会话（每会话 256KB 配额，总限仍 768KB），
        //      滚动/尾窗口按该文件最后一行时间锚定，旧会话不再得到空窗口；文件名加 -s3；
        //   ③ 启动时三态判决上一会话（crash/clean/abnormal）并落日志 + toast 提示；
        //   ④ 脱敏补 ssid='…'/name='…' 引号分支 + IPv4 掩成前两段；
        //   ⑤ 日志目录保留策略：最近 10 个会话且合计 ≤64MB，先删最旧（含 -old）。
        // vc255（20261112）：冷态首联判死策略修正（只落代码，主版本不动，仅 vc+1）：
        //   首轮凭证锚点 12s → 45s，重试轮仍 12s；20s 静默判据不变且首轮同样生效。
        //   依据：K60 冷态从未低于 21.7s、成功集中在 21.7~38.3s，12s 在成功途中打断
        //   （vc253 首轮 12192ms 判死 + 20s 静默，总 58.88s，比早期 38~43s 更差）。
        // vc256（20261112）：0x4301 下发真实热点信道（主版本不动，仅 vc+1）：
        //   ① SoftApCallback.onInfoChanged 反射读取热点真实 frequency（API30+，NewApi=0），
        //      K60 实测热点在 5GHz/5745MHz，此前却按兜底 6 谎报 2.4G 信道；
        //   ② 信道未知时不再猜 6——0x4301 省略 channel 字段（endpoint 目录中该字段非 required，
        //      iAP2 TLV 可缺省），iPhone 全信道扫描；其它观测源（配置/STA/扫描）仍优先使用；
        //   ③ 诊断日志拆分 config/expected/observed/send 与各自 source，去掉无依据数值；
        //   ④ video stats 的 Wi-Fi 采样前缀 wifi= → sta=（那是车机 STA 连外网路由的链路，
        //      不是 CarPlay 热点链路）。
        // vc257（20261112）：0x5703 信道宣告不再吸附（主版本不动，仅 vc+1）：
        //   ① vc256 实测 K60 日志 expectedChannel=2 → sendChannel=1（snapHotspotChannel 把 2.4G
        //      就近吸附到 1/6/11），而 SoftAP 实际频点 2417MHz=ch2，宣告值与真实热点不符；
        //   ② 结果：iPhone 全新入网（不在热点上）时 cred→wifi-join 固定 31~37s，
        //      表现就是「慢的要死」；已在网时 0.2~0.4s；
        //   ③ 改成原样下发 observation/expected/memory 的信道（不再吸附），0 表示未知照旧省略；
        //      吸附只适用于「配置热点」，不适用于「描述已有热点」。
        // vc258（20261113）：mDNS 连接提速实验（三态开关，默认关闭）：
        //   ① K60 同条件实测：0x4301→iPhone 关联 1.08s，但 AirPlay TCP 要 35.6s；
        //      同时 CatPlay 只需 1.95s。差异点 = JmDNS 广播就绪（7.5s）晚于 iPhone 入网，
        //      iOS 首次服务发现扑空，下一次要等 ~35s；
        //   ② 模式 1「等待广播」：首个 0x5703 前等广播就绪（≤10s，失败/超时放行；
        //      iPhone 已关联热点时不等）；
        //   ③ 模式 2「等待+预热」：在此之上进程内复用 JmDNS（跨代只重注册），摊销 7.5s create。
        // vc259（20261113）：实验加第 3 档「IPv6 广播」：
        //   同条件实测 vc258 模式 1 出现两态（2.9s / 36.2s），而 CatPlay 稳定 2.3~3.8s；
        //   两边唯一稳定差异 = 地址族（CatPlay 监听 fe80 + mDNS AAAA，我们 IPv4 + A）。
        //   该档关闭 IPv4 优先，选址/监听/广播全走 fe80 link-local（带 scope id），仅实验。
        // vc261（20261113）：探针扩到端口维度。同配置（模式1+探针）实测出现快慢交替
        //   （21:06 1.64s / 21:13 35.37s），排除低数据模式与屏幕状态；vc260 探针证明
        //   iPhone 未尝试 fe80。本项目既有注释记载「监听已死而广报仍在 → iPhone 先撞死
        //   端口、等缓存过期 ~40s」，且 CatPlay 用 7000 端口。故本版在另一地址族 + 7000
        //   端口同时开探针，实测 iPhone 的 SYN 目标地址与端口。
        // vc262（20261113）：地址族守卫。启动时自检一次本机地址族能力并缓存：
        //   1 类 = 可 IPv6（/proc/net/if_inet6 非空 + :: 绑定探针成功 + 接口有可用 v6）；
        //   2 类 = 仅 IPv4。2 类时强制 IPv4 选址，不再走 IPv6 广播/监听分支。
        //   注意：这是车机侧分类，不是 iPhone 侧；对端地址族只能看 TCP 源地址。
        // vc263（20261114）：同 vc262，自检扫描按接口优先级排序（wlan/ap/p2p/eth 优先，
        //   移动数据 rmnet/ccmni/ppp 最后），避免日志误报蜂窝 v6 地址。
        // vc264（20261114）：0x4301 入网闸门。实测 iOS 16：0x4301 早于 iPhone 关联 → 35s；
        //   晚于关联 → 0.2~1s。故在收到 0x4300 后、回 0x4301 前，按 SoftAp 客户端数等待
        //   iPhone 入网（最多 8s；已关联/未知直接放行，warm 零延迟）。不碰端口/地址族/mDNS。
        // vc265（20261114）：修 associated=false。SoftApCallback 客户端数改为取"首个集合参数"
        //   （兼容 ROM 参数顺序差异），并在客户端数变化时打诊断行；闸门上限 8s→5s。
        // vc266（20261114）：MIUI 拒绝第三方读热点客户端（SecurityException），改时间启发式：
        //   10 分钟内有过 AirPlay TCP = warm 跳过闸门；否则 cold 最多等 3.5s。
        // vc267（20261114）：warm 时刻改进程级静态（每次连接新建 controller，实例字段会丢）；
        //   闸门只作用于第一次 0x4301，重发不再等待（vc266 每 5s 重发都被拖 3.6s）。
        // vc268（20261114）：冷轮闸门上限恢复 8s（iOS 16 入网时间有波动，3.5s 有时不够）；
        //   保留 warm 直接放行（热轮实测 0.199s / 9.59s）。
        // vc269（20261114）：MX6 无线取证包（主版本 1.5.4）。①上传件本轮**不脱敏**——要看清
        //   Bonjour 广播的 A 记录与各网卡地址，改回脱敏只需把 UPLOAD_REDACTION_ENABLED 置 true；
        //   ②新增 mDNS 广播地址诊断行（localHost/server/bound/各网卡地址），定位「iPhone 已入网
        //   却 0 TCP」是否广播指错网卡（usb0 10.x vs 热点 wlan0 192.168.43.1）；
        //   ③状态栏「回弹自动重设」两套循环已删除，本轮不动、只看现场表现。
        // vc270（20261114）：清理收尾。①修 video stats 的 IP 倒序——`WifiInfo.getIpAddress()`
        //   是小端 int，此前按大端取，日志打成 `ip=120.88.168.192`（真实 192.168.88.120）；
        //   ②摘除状态栏探针（StatusBarProbe 整个删除 + MainActivity 两处 / 会话服务一处调用），
        //   它每次进页面都要 spawn `sh -c dumpsys window`，顶栏结论已定，属纯开销；
        //   ③上传件继续保持不脱敏——MX6 取证（mDNS 广播地址探针）还没拿到日志，取完再改回。
        // vc271（20261114）：T3-B 无线取证包（在 vc270 基础上增加，其余内容不变）。T3-B 现场
        //   没有电脑，只能靠 App 自己调用 root 取证：连接发起 10s 后抓 ARP / IGMP / maddr /
        //   addr / route / rule / udp / tcp 监听表 / iptables / iw station dump，并用 tcpdump
        //   抓 5353（mDNS）与 7100（AirPlay SYN）各 15s，全部写 t3probe 标签随日志上传。
        //   仅本包启用（T3NetProbe.ENABLED=true），正式包置 false。
        // vc272（20261114）：TS7 热点出画修复包（三件套）。
        //   ①切桌面回来花屏（方案 A）：AndroidMediaSink changeSurface 重建解码器分支补
        //     renderHold=true——与 setOutputSurface 分支对齐，重建后丢帧直到首个 IDR，
        //     花屏变一瞬黑屏（TS7 api27 + KK api19 通吃）。
        //   ②热点 mDNS 单播通告（WirelessMdnsAnnouncer 新文件）：TS7 软 AP 不把自身多播
        //     announce 转给已关联 STA（tcpdump 本地可见、iPhone 收不到、tcpAccepted=0），
        //     中继模式经上游路由器多播正常——同代码仅热点路径死。热点会话 Bonjour start
        //     成功后，把 _carplay-ctrl/_airplay 的 PTR/SRV/TXT/A 手组 DNS response 单播
        //     直发已关联 iPhone 的 5353（sta-arp 观测注入目标，每 2s 一轮共 30 轮）。
        //     API16 兼容：纯 java.net + 手写字节序。
        //   ③mdns advertise 探针补挂绑接口分支（vc269 只挂了 API<21 分支，K60/TS7/MX6
        //     全走绑接口分支看不到广播地址）。
        //   T3NetProbe 维持启用（T3-B 复测仍需抓包）。
        // vc273（20261114）：热点单播通告修正（vc272 首测未出画，两个自造 bug）。
        //   ①PTR rdata 按 DNS label 编码（vc272 误写 ASCII 点分串 → tcpdump 报
        //     `PTR <ELT 8>[|domain]`，iPhone 解析失败直接丢包）。
        //   ②发送 socket 绑 192.168.43.1:5353（RFC 6762 §6.7：源端口不是 5353 必须丢弃；
        //     vc272 实测源端口 52675，iPhone 全部忽略）。SO_REUSEADDR 与 JmDNS 共存，
        //     绑不上降级临时端口并记日志。
        //   ③启动时立刻读 /proc/net/arp 播种目标（vc272 前 5 轮 sent=0，白等 10s）。
        // vc274（20261114）：热点"无线链路建立中"真因回归修复——0x4301 缺 channel 字段。
        //   实测对照（同机 TS7 / 同热点 / 同 dongle）：
        //     vc245：0x4301 frame=173B（含 channel=6 默认值）→ 0.2s 建 TCP → 出画；
        //     vc273：frame=168B（vc256 改成 optionalU8，channel=0 时省略）→ 36s 零连接。
        //     差 5 字节 = TLV 头 4 + 值 1，与"少发一个子参数"完全吻合；且 iPhone 不依赖
        //     mDNS（vc245 的 Bonjour 比 TCP 晚 2s 才 started）。
        //   ①Iap2Messages：0x4301 无线组 channel 恢复无条件 u8（与 0x5703 同源）。
        //   ②ManualHotspotManager：信道完全无观测时按频段给默认值——2.4G/未知→6，
        //     5G→149（中国区 UNII-3 非 DFS；DFS 信道软AP自动选时通常避开），
        //     日志标 assumed-2.4g / assumed-5g。
        //   ③RelayClientManager：中继侧同样不留 0，未知时默认 6（assumed-2.4g）。
        //   ④ConfirmedChannelMemory 只记忆实测/配置信道，默认值不回灌（防自证循环）。
        //   其余（renderHold 花屏修复、mDNS 单播通告器、t3probe）本包不动。
        // vc275（20261114）：TS7 回归包（vc274 基础上只做"降噪 + 误导项标注"）。
        //   ①关掉 mDNS 单播通告器（WirelessMdnsAnnouncer.ENABLED=false）——vc245/vc274 实测
        //     iPhone 不依赖广播，建链由 0x4301 驱动；代码保留供排障，正式包不启用。
        //   ②keepalive 静默在 observe-only 闸位下不再写 failureClass（原先会把健康会话的
        //     日志判决刷成 tunnel-timeout），降级为 stage 诊断并注明"降级模式预期"。
        //   ③handoff degraded 行补 note=expected(experimental-MFi-identity)，说明控制面留在
        //     蓝牙是实验档的正常形态，不是故障。
        //   ④video stats 的 sta= 段在 AP 模式（无 STA 上行）补 note=ap-mode，避免 ip=0.0.0.0
        //     被误读成链路故障。
        //   功能面与 vc274 完全一致，0x4301 channel 修复保留。
        // vc276（20261114）：TS7 回归问题二修。
        //   ①慢（模式①②各慢 8s）：`start-session gate: associated=false waitedMs=8034` ——
        //     该 ROM SoftAp 客户端数恒报 0，vc264 把「明确 0」当未入网而空等满额。改为
        //     客户端数 0 时回看 /proc/net/arp：ARP 有 peer 即放行（实测 iPhone 早已关联）。
        //   ②切桌面回来仍花屏：renderHold 原先在「IDR 入队」解除，但解码进度领先输入，
        //     此刻输出队列里还压着 IDR 之前的 P 帧（无参考 → 花屏）。改为在「关键帧**输出**」
        //     解除；并加 1.5s 兜底（部分 OMX 不给输出打 KEY_FRAME 标志，宁可短暂花屏不黑屏）。
        // vc277（1.5.5 发行）：①花屏三次定案——抑制期只让 IDR 进解码器（P 帧直接丢弃、
        //     不进 codec），输出队列因此不可能残留脏帧，IDR 入队即解除抑制，不依赖 SPRD OMX
        //     的 BUFFER_FLAG_KEY_FRAME（实测不打该标志）；兜底时限 1.5s→8s（有线实测天然 IDR
        //     约 5s 才到，兜底必须大于该窗口，否则会在 IDR 前放行 P 帧）。
        //     ②恢复上传件脱敏（vc269 的临时取证开关关闭）。
        //     待办（本版不做）：切后台预请求 IDR + 后台保留解码器 / 标准 RTSP 关键帧请求。
        versionCode = 277
        versionName = "1.5.5-vc277"
    }



    // 日志上云 token 脱敏（公开源码前置条件）：不再硬编码进源码，改由 local.properties
    // 注入（GITEE_LOG_TOKEN=...，该文件已被 .gitignore 排除，永不入仓）。
    // 未配置时为空串：Gitee 上传按钮自动降级为「生成文件后手动发送」路径。
    defaultConfig {
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "GITEE_LOG_TOKEN", "\"${localProps.getProperty("GITEE_LOG_TOKEN") ?: ""}\"")
        // vc77 定案：有线 NCM 守护恒关（vc76 guard-off 裁决通过，无 root 正式支持）。
        buildConfigField("boolean", "WIRED_NCM_GUARD_ENABLED", "false")
        // vc193：FE6 root 语音通路取证开关。**只在本诊断包为 true**；正式 dongle 包
        // 必须置 false（层 B/C 全部入口由它包住，避免给普通用户暴露 root 写操作）。
        buildConfigField("boolean", "FE6_ROOT_PROBE", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 外发测试签名：vc154（⑦ CI 化构建 task·签名固化）。
    // 环境变量优先（CI/正式发布密钥可覆盖）；缺省回落 Android SDK 标准 debug keystore
    // （~/.android/debug.keystore，alias=androiddebugkey，口令均为 android）——即
    // docs/BUILD.md 手动打包约定的固化：任何环境 ./gradlew ciRelease 无需记环境变量
    // 直接产出已签名 APK。debug 凭据为 SDK 通用值，无个人路径/机密入仓，开源分支同步
    // 安全；keystore 文件不存在且未配环境变量时保持未签名（纯源码环境语义不变）。
    // vc202+1：路径判据已上移到文件顶部（releaseKeystorePath/releaseKeystoreUsable），
    // ciRelease 对「keystore 缺失」硬失败、对「回落 debug key」醒目告警，不再静默。
    signingConfigs {
        if (releaseKeystoreUsable) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            // vc198：按用户长期决策**关闭混淆与资源压缩**（此前多次强调「不做混淆、不做加密加固」）。
            // 关掉之后 dex 里能直接查到 Fe6Device / MicrophoneUplink 等原类名，排障与验收都不必
            // 再靠字符串字面量绕；代价是包体积变大（R8 之前砍掉的那部分会回来）。
            // ⚠️ proguard-rules.pro 仍然保留且 proguardFiles 不删：将来若重新开启，native keep 规则
            // （xcertplay / LegacyUsbHostNative / BouncyCastle / JmDNS）必须在位，否则 NoSuchMethodError。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystoreUsable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        abortOnError = false
        // M6k24：release 构建跳过 lintVital（targetSdk=19 会触发 ExpiredTargetSdkVersion fatal）
        checkReleaseBuilds = false
        // M1（A4）：app lint 全量扫描 shared 依赖源码，NewApi 兜底。
        // 此前未开启时 shared 里的 API 21+ 调用（如 AudioAttributes）完全不在扫描范围。
        checkDependencies = true
        // vc200：API16 兼容门禁。minSdk=16 下任何「高版本才有的平台 API」都升为 error 级别
        // （只提高报告严重度，不阻断构建——abortOnError 仍为 false）。
        // 出包前核 `app/build/reports/lint-results-release.xml` 里 NewApi 计数应为 0。
        // 依据：工程文档/审查-20261031-API16兼容性全量审查.md
        error += "NewApi"
    }
}

dependencies {
    // 零 AndroidX / 零 Compose —— 轻量化红线（设计文档 §2.2）
    implementation(project(":shared"))
}

// CarPlay 身份资产「构建期外部注入」（2026-10-21 整顿）。
// 背景：identity.pk8 / certificate.p7b 曾被直接跟踪入 Git 并随源码同步到公开仓，导致
// MFi 身份私钥进入历史（谁 clone 到旧提交都能取出，且与现役密钥为同一把）。现改为：
//   · 源码树内的 app/src/main/assets/offline-mfi/ 由 .gitignore 排除，永不入仓；
//   · 构建时从外部目录注入：优先环境变量 DIPLAY_IDENTITY_DIR，缺省回落 ../auth/offline-mfi
//     （工程本级私有目录，同样不入库）；
//   · 白名单仅两个文件，缺失即报错、多余不予理会，避免误塞其它资产；
//   · 外部目录不存在时按「纯源码环境」继续构建（产出 APK 不带身份，代码仍可正常编译），
//     与 release 签名「keystore 缺省即未签名」的处理保持一致。
val runtimeIdentityDir = System.getenv("DIPLAY_IDENTITY_DIR")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../auth/offline-mfi").absolutePath
val runtimeIdentityTarget = file("src/main/assets/offline-mfi")
val runtimeIdentityFiles = listOf("identity.pk8", "certificate.p7b")

tasks.register("injectRuntimeIdentity") {
    group = "build"
    description = "Copy allowed CarPlay identity assets from an external dir into app assets (never committed)"
    val srcDir = File(runtimeIdentityDir)
    val dstDir = runtimeIdentityTarget
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn(
                "未找到 CarPlay 身份资产目录 $srcDir —— 将产出不带身份的 APK（纯源码构建语义）。" +
                    "需打包请把 identity.pk8 与 certificate.p7b 放入该目录，或设置环境变量 DIPLAY_IDENTITY_DIR。"
            )
            return@doLast
        }
        // vc188：vc187 的守卫只判 isFile（文件是否存在），但 File.isFile 对
        // 0 字节文件同样返回 true ⇒ 空文件能过检并被 copyTo 拷进 assets，
        // 运行时 readBounded() 必然抛 "Local identity file is empty"。
        // 这正是 220020 的失效形态——守卫恰好漏掉了它本该拦住的那一种情况。
        val present = runtimeIdentityFiles.filter {
            File(srcDir, it).let { file -> file.isFile && file.length() > 0 }
        }
        if (present.size != runtimeIdentityFiles.size) {
            throw GradleException(
                "身份资产不完整或为空：$srcDir 中应有 $runtimeIdentityFiles（且非空），实得 $present"
            )
        }
        dstDir.mkdirs()
        present.forEach { name -> File(srcDir, name).copyTo(File(dstDir, name), overwrite = true) }
        logger.lifecycle("已注入 CarPlay 身份资产 $present（来源：$srcDir）")
    }
}

// vc190：外置 USB 蓝牙固件（Realtek 等 blob，linux-firmware 再分发许可）。
// 与 offline-mfi 同策略：源文件放私有目录 auth/usbbt_fw（被 .gitignore 排除，永不入公开仓），
// 构建期注入 app/src/main/assets/usbbt_fw（该目录同样 gitignore），缺失则告警纯源码构建。
val usbFirmwareDir = System.getenv("DIPLAY_USB_FIRMWARE_DIR")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../auth/usbbt_fw").absolutePath
val usbFirmwareTarget = file("src/main/assets/usbbt_fw")

tasks.register("injectUsbbtFirmware") {
    group = "build"
    description = "Copy external Realtek/Broadcom USB Bluetooth firmware into app assets (never committed)"
    val srcDir = File(usbFirmwareDir)
    val dstDir = usbFirmwareTarget
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn(
                "未找到 USB 蓝牙固件目录 $srcDir —— 将产出不带固件的 APK（纯源码构建语义）。" +
                    "需支持 Realtek/Broadcom ROM 模式 dongle（如 CM390）请把固件放入该目录，" +
                    "或设置环境变量 DIPLAY_USB_FIRMWARE_DIR（来源见 FIRMWARE.md）。"
            )
            return@doLast
        }
        // auth/usbbt_fw 为本仓受控私有源，整体递归拷贝（含 rtl_bt/ + manifest.json + LICENCE + README）。
        dstDir.mkdirs()
        srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(srcDir).path
            File(dstDir, rel).parentFile?.mkdirs()
            f.copyTo(File(dstDir, rel), overwrite = true)
        }
        logger.lifecycle("已注入 USB 蓝牙固件（来源：$srcDir → $dstDir）")
    }
}

// vc193：root PCM 直采二进制（diplay-pcmcap，源码与产物同在 auth/tinycap，被 .gitignore
// 排除，永不入公开仓）。用途：绕开 AudioFlinger/AudioPolicy 由 root 直接开
// /dev/snd/pcmC?D?c，回答「车机物理麦克风到底能不能采到非零 PCM」——App 侧 AudioRecord
// 被 HAL/策略卡住时，这是唯一能分开「设备没音」与「框架不给音」的实验。
// 缺失时告警并产出不带该产物的 APK（纯源码构建语义）；层 C 的 R1 档届时落 SKIP。
val pcmCapDir = System.getenv("DIPLAY_PCMCAP_DIR")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("../auth/tinycap/bin").absolutePath
val pcmCapTarget = file("src/main/assets/tinycap")

tasks.register("injectTinycap") {
    group = "build"
    description = "Copy external diplay-pcmcap binaries (root PCM capture) into app assets (never committed)"
    val srcDir = File(pcmCapDir)
    val dstDir = pcmCapTarget
    doLast {
        if (!srcDir.isDirectory) {
            logger.warn(
                "未找到 PCM 直采二进制目录 $srcDir —— 产出不带 R1 root 直采档的诊断包（layer C R1=SKIP）。" +
                    "需要该能力请先执行 auth/tinycap/build.sh，或设置 DIPLAY_PCMCAP_DIR。"
            )
            return@doLast
        }
        dstDir.mkdirs()
        srcDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            f.copyTo(File(dstDir, f.name), overwrite = true)
        }
        logger.lifecycle("已注入 PCM 直采二进制（来源：$srcDir → $dstDir）")
    }
}

tasks.named("preBuild").configure {
    dependsOn("injectRuntimeIdentity", "injectUsbbtFirmware", "injectTinycap")
}

// vc154（⑦ CI 化构建 task）：一键「签名 release 构建 + APK 归档」。
// 用法：./gradlew ciRelease（无需任何环境变量，签名走上方固化缺省）。
// 产物复制到仓库 apks/DiPlay-<versionName>-cluster1-vc<versionCode>.apk（与既有
// 归档命名一致）；归档目录可用 DIPLAY_APK_ARCHIVE_DIR 覆盖（独立 clone 无上级 apks 时）。
// vc202+1：签名「静默回落」改「大声失败」——
//   · keystore 缺失（未设 ANDROID_KEYSTORE_PATH 且缺省 debug.keystore 不存在）→ 硬失败，
//     不再静默产出未签名包；纯源码编译请改用 assembleRelease（语义不变）；
//   · 回落 debug key（未设 ANDROID_KEYSTORE_PATH 但 debug.keystore 存在）→ 醒目告警，
//     这是 docs/BUILD.md 固化的开发约定，不失败；产物名不变（归档命名被 apks/ 历史引用）。
tasks.register("ciRelease") {
    group = "build"
    description = "Signed release build + archive APK to repo apks/ (vc154 signing-solidified CI task)"
    dependsOn("assembleRelease")
    doLast {
        if (!releaseKeystoreUsable) {
            throw GradleException(
                "ciRelease：缺少签名 keystore，拒绝产出未签名的对外包。\n" +
                    "  现状：ANDROID_KEYSTORE_PATH 未设置，且缺省 ${releaseKeystorePath} 不存在。\n" +
                    "  配置方法：设置 ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD / " +
                    "ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD 四个环境变量指向正式密钥；" +
                    "开发机也可放一份 SDK debug.keystore 到上述缺省路径。\n" +
                    "  若只是纯源码编译验证（不需要签名），请改用 assembleRelease。"
            )
        }
        if (!releaseKeystoreEnvSet) {
            println(
                "⚠️⚠️⚠️ ciRelease：未设置 ANDROID_KEYSTORE_PATH，本包用 SDK debug key 签名" +
                    "（$releaseKeystorePath）——仅限开发/外测分发，正式发布请配置正式密钥环境变量。 ⚠️⚠️⚠️"
            )
        }
        val versionName = android.defaultConfig.versionName as String
        val versionCode = android.defaultConfig.versionCode
        val output = file("build/outputs/apk/release/app-release.apk")
        if (!output.exists()) {
            throw GradleException("ciRelease：未找到 ${output.absolutePath}（assembleRelease 产物缺失）")
        }
        val archiveDir = System.getenv("DIPLAY_APK_ARCHIVE_DIR")
            ?: rootProject.file("../apks").absolutePath
        val target = File(archiveDir, "DiPlay-$versionName-cluster1-vc$versionCode.apk")
        target.parentFile.mkdirs()
        output.copyTo(target, overwrite = true)
        println("ciRelease：APK 已归档 ${target.absolutePath}")
    }
}

// vc250（2026-11-07）：版本号自检——不合规就让构建在配置阶段直接失败，不靠人记。
// 规则唯一源：工程文档/10-同步规范.md §八。覆盖两条：
//   ① versionName 必须匹配 a.b.c-vcN（a/b/c 各一位 0–9，N 为十进制）；
//   ② 名字里的 vcN 必须等于 versionCode。
// 放在脚本末尾、配置阶段执行：assembleDebug / assembleRelease / ciRelease / tasks
// 等任何构建都会触发，无需额外手动跑任务。
run {
    val checkedName = android.defaultConfig.versionName ?: ""
    val checkedCode = android.defaultConfig.versionCode
    val versionNamePattern = Regex("""^\d\.\d\.\d-vc\d+$""")
    if (!versionNamePattern.matches(checkedName)) {
        throw GradleException(
            "VERSION CHECK FAILED: versionName=\"$checkedName\" does not match a.b.c-vcN.\n" +
                "  expected: each of a/b/c is ONE digit 0-9, then \"-vc<versionCode>\" (e.g. 1.5.1-vc250)\n" +
                "  actual:   versionName=\"$checkedName\", versionCode=$checkedCode\n" +
                "  rule: sync spec section 8 (工程文档/10-同步规范.md §8)"
        )
    }
    val nameCode = checkedName.substringAfter("-vc").toIntOrNull()
    if (nameCode != checkedCode) {
        throw GradleException(
            "VERSION CHECK FAILED: vc in versionName (${nameCode ?: "unparsable"}) != versionCode ($checkedCode).\n" +
                "  expected: versionName ends with \"-vc$checkedCode\"\n" +
                "  actual:   versionName=\"$checkedName\", versionCode=$checkedCode\n" +
                "  rule: sync spec section 8 (工程文档/10-同步规范.md §8)"
        )
    }
}
