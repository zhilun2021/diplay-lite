package com.shilapi.xcertplay.orchestration

/**
 * vc250-06：把原来「鉴权 ∧ 渲染」的单一体拆成两条独立事实。
 *
 * - [ControlProof]：控制面成立（AirPlay 会话已激活 + iAP2 隧道鉴权完成）。
 * - [MediaProof]：媒体面成立（本代本会话确有视频帧上屏）。
 *
 * 拆开的理由：控制面（蓝牙 RFCOMM / type-130 隧道）死掉时，只要媒体面还在，会话
 * 仍然可用（音视频照常），不能按「会话失败」拆栈；反过来媒体面死掉（断流）才是
 * 真正的会话死亡。热点确认仍要求两者同时成立，由 CarPlayController 在两处事件里
 * 交叉判定（authenticated 时看 rendered，rendered 时看 authenticated）。
 *
 * 两类的 generation/session 护栏与旧实现一致：任一代更替/会话换对象，旧事件自动作废。
 */
internal class ControlProof<S : Any> {
    private var generation = -1
    private var session: S? = null
    private var authenticated = false

    @Synchronized
    fun begin(generation: Int) {
        clear()
        this.generation = generation
    }

    @Synchronized
    fun activate(generation: Int, session: S) {
        if (this.generation != generation || this.session === session) return
        this.session = session
        authenticated = false
    }

    @Synchronized
    fun authenticated(generation: Int) {
        if (this.generation != generation || session == null) return
        authenticated = true
    }

    @Synchronized
    fun hasAuthenticatedControl(generation: Int): Boolean =
        this.generation == generation && session != null && authenticated

    @Synchronized
    fun end(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        this.session = null
        authenticated = false
    }

    @Synchronized
    fun clear() {
        generation = -1
        session = null
        authenticated = false
    }
}

/** vc250-06：媒体面事实（有帧上屏）+ 热点确认回调（鉴权 ∧ 渲染，由调用方交叉判定）。 */
internal class MediaProof<S : Any> {
    private var generation = -1
    private var session: S? = null
    private var rendered = false
    private var confirmation: (() -> Unit)? = null

    @Synchronized
    fun begin(generation: Int, confirmation: () -> Unit) {
        clear()
        this.generation = generation
        this.confirmation = confirmation
    }

    @Synchronized
    fun activate(generation: Int, session: S) {
        if (this.generation != generation || this.session === session) return
        this.session = session
        rendered = false
    }

    @Synchronized
    fun rendered(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        rendered = true
    }

    @Synchronized
    fun hasRenderedFrame(generation: Int): Boolean =
        this.generation == generation && session != null && rendered

    /**
     * 鉴权 ∧ 渲染都成立时触发一次热点确认（旧 WirelessConnectionProof 的 confirmIfReady）。
     * 幂等：回调取出后置空，重复调用不会重复确认。
     */
    @Synchronized
    fun confirm(generation: Int) {
        if (this.generation != generation || session == null || !rendered) return
        val callback = confirmation ?: return
        confirmation = null
        callback()
    }

    @Synchronized
    fun end(generation: Int, session: S) {
        if (this.generation != generation || this.session !== session) return
        this.session = null
        rendered = false
    }

    @Synchronized
    fun clear() {
        generation = -1
        session = null
        rendered = false
        confirmation = null
    }
}
