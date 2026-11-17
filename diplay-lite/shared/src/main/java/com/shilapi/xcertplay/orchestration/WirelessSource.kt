package com.shilapi.xcertplay.orchestration

/**
 * vc308：`decide()` 的 source 主键（`工程文档/15-编号表` §7.1，26 项，只增不改）。
 *
 * `wire` 必须与旧版第二实参字面量逐字一致（含连字符、小写）——它是日志 `source=` 的取值，
 * vc307 及以前的日志要对得上。编号不在这里：同一 source 既可换代也可判死，
 * 由 `(source, action)` 在 `codeFor()` 里查 `EventCode`。
 */
enum class WirelessSource(val wire: String) {
    FirstTcp("first-tcp"),
    MediaStall("media-stall"),
    StaWatch("sta-watch"),
    HandoffWatchdog("handoff-watchdog"),
    PreStreamEof("pre-stream-eof"),
    RunCatch("run-catch"),
    RetryExhausted("retry-exhausted"),
    Restart("restart"),
    BringupStart("bringup-start"),
    StaleHotspot("stale-hotspot"),
    StaleAttach("stale-attach"),
    StaleBonjour("stale-bonjour"),
    StaleRfcomm("stale-rfcomm"),
    StaleTunnel("stale-tunnel"),
    StaleControl("stale-control"),
    Close("close"),
    Mfi("mfi"),
    WiredBootstrap("wired-bootstrap"),
    WiredHost("wired-host"),
    WiredConfig("wired-config"),
    WiredTransition("wired-transition"),
    Reenumeration("reenumeration"),
    WiredControl("wired-control"),
    WiredIap2("wired-iap2"),
    WiredKernelNcm("wired-kernel-ncm"),
    WiredRun("wired-run"),
    ;

    /** 编号与 action 决定，见 `CarPlayController.codeFor()`。 */
}

/**
 * vc308：`(source, action)` → 编号 + 日志人话（`15-编号表` §3.3 / §3.4 / §3.6）。
 *
 * **`human` 逐字照抄 15 号表，一字不许改写**（编号表「日志人话只增不改」契约）。
 * 人话永不含数字——可变数值只出现在 `[verdict]` 的 `|` 左侧字段里。
 */
enum class EventCode(val id: String, val human: String) {
    W201("W-201", "首 TCP 看门狗到点，本代作废重建"),
    W202("W-202", "会话在但视频静默，重建"),
    W203("W-203", "iPhone 离开热点子网，重建"),
    W204("W-204", "握手看门狗到点，重建"),
    W205("W-205", "起流前控制面 EOF 达阈值，重建"),
    W206("W-206", "无线主流程异常，本代作废"),
    W208("W-208", "主动重启无线栈"),
    W209("W-209", "起链前清理残留栈"),
    W210("W-210", "清理残留热点会话"),
    W211("W-211", "清理残留 attach"),
    W212("W-212", "清理残留 Bonjour"),
    W213("W-213", "清理残留 RFCOMM"),
    W214("W-214", "清理残留隧道"),
    W215("W-215", "清理残留控制面"),
    W216("W-216", "通用关闭无线栈"),
    W301("W-301", "宽限期满仍零 TCP，本代判死"),
    W302("W-302", "媒体停滞且不可重试，判死"),
    W303("W-303", "iPhone 离线且不可重试，判死"),
    W304("W-304", "握手超时且不可重试，判死"),
    W305("W-305", "MFi 证书失败，判死"),
    W306("W-306", "主流程异常且不可重试，判死"),
    W307("W-307", "重试额度用尽，判死"),
    U201("U-201", "起链前清理有线栈"),
    U301("U-301", "本机不支持 USB 主机模式"),
    U302("U-302", "无法切换 iPhone USB 配置"),
    U303("U-303", "USB 配置切换失败"),
    U304("U-304", "iPhone 未重新枚举"),
    U305("U-305", "有线控制通道建立失败"),
    U306("U-306", "有线 iAP2 握手失败"),
    U307("U-307", "内核 NCM 被车机系统占用"),
    U308("U-308", "有线主流程异常"),
    W299("W-299", "未登记的 source/action 组合"),
    ;
}
