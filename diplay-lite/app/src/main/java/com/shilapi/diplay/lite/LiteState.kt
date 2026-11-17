package com.shilapi.diplay.lite

/**
 * 进程内共享状态：前台服务与 UI 之间的最轻量通道（不用广播/不用 AndroidX）。
 * M2 接入 CarPlayController 后，状态源改为其 onStatus 回调。
 */
object LiteState {
    enum class Phase { IDLE, CONNECTING, ACTIVE, FAILED }

    /**
     * vc308：粗状态 5 档（`工程文档/15-编号表` §5）。
     *
     * 标识符一律 ASCII：文案可改、编号不可改，禁止把文案焊进常量名。
     */
    enum class CoarseState { C0, C1, C2, C3, C4 }

    /**
     * vc308：细状态编号 28 条（`15-编号表` §2，`S-01~S-16` 首版 + `S-17~S-28` 本版补齐）。
     *
     * `statusText` 仍是并列字段（文案不动），本枚举只给「判据」用——渲染层不再按
     * 文案 `contains(...)` 判断（治 G-6）。
     */
    enum class UiStateCode(val code: String, val coarse: CoarseState) {
        S01("S-01", CoarseState.C0),
        S02("S-02", CoarseState.C1),
        S03("S-03", CoarseState.C1),
        S04("S-04", CoarseState.C1),
        S05("S-05", CoarseState.C1),
        S06("S-06", CoarseState.C1),
        S07("S-07", CoarseState.C1),
        S08("S-08", CoarseState.C2),
        S09("S-09", CoarseState.C2),
        S10("S-10", CoarseState.C2),
        S11("S-11", CoarseState.C3),
        S12("S-12", CoarseState.C0),
        S13("S-13", CoarseState.C3),
        S14("S-14", CoarseState.C0),
        S15("S-15", CoarseState.C0),
        S16("S-16", CoarseState.C2),
        S17("S-17", CoarseState.C1),
        S18("S-18", CoarseState.C1),
        S19("S-19", CoarseState.C2),
        S20("S-20", CoarseState.C1),
        S21("S-21", CoarseState.C1),
        S22("S-22", CoarseState.C1),
        S23("S-23", CoarseState.C2),
        S24("S-24", CoarseState.C2),
        S25("S-25", CoarseState.C2),
        S26("S-26", CoarseState.C2),
        S27("S-27", CoarseState.C4),
        S28("S-28", CoarseState.C2),
    }

    @Volatile var running = false
    @Volatile var phase = Phase.IDLE
    @Volatile var statusText = "未连接"
    /** vc308：当前细状态编号（与 [statusText] 同步写入）。 */
    @Volatile var statusCode: UiStateCode = UiStateCode.S01
    /** vc308：当前粗状态（= [statusCode].coarse，供 INV-1/INV-2 判定）。 */
    @Volatile var coarse: CoarseState = CoarseState.C0
    /** vc314（INV-308-01）：本次失败的归因分型，供失败卡选动作；`Unknown` 表示没分出来。 */
    @Volatile var failureClass: LiteLog.FailureClass = LiteLog.FailureClass.Unknown
    /** vc176（K60 实测）：controller 正处 0x52 重枚举等待期。此时旧 USB 实例的
     *  DETACHED 是重枚举的必经步骤、不是用户拔线——MainActivity 的拔线监听据此
     *  忽略，避免 stopService 杀掉重枚举兜底、误弹「控制通道已结束」。 */
    @Volatile var reenumerating = false

    val active: Boolean get() = phase == Phase.ACTIVE
}
