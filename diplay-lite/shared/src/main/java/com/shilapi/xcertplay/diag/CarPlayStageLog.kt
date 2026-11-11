package com.shilapi.xcertplay.diag

/**
 * vc250：共享层（USB/iAP2 等）到 App 日志出口的窄桥。
 *
 * 只做阶段转发，不承载业务日志；由 App 进程启动时安装，
 * 保证会话服务尚未拉起时（USB 插入/枚举/授权）也能落 `LiteLog.stage()`。
 */
object CarPlayStageLog {
    @Volatile
    private var sink: ((stage: String, detail: String) -> Unit)? = null

    @Volatile
    private var failureSink: ((stage: String, failureClass: String, detail: String) -> Unit)? = null

    // vc250-05：真凭证（0x5703）下发与 AirPlay TCP 到达是「凭据三态」的两个源头事实，
    // 只回传布尔与先后关系，不携带 SSID/密码。
    @Volatile
    private var credentialSink: ((beforeAirPlayTcp: Boolean) -> Unit)? = null

    @Volatile
    private var tcpAcceptedSink: (() -> Unit)? = null

    fun install(sink: ((stage: String, detail: String) -> Unit)?) {
        this.sink = sink
    }

    fun installFailure(sink: ((stage: String, failureClass: String, detail: String) -> Unit)?) {
        this.failureSink = sink
    }

    fun installCredential(
        credential: ((beforeAirPlayTcp: Boolean) -> Unit)?,
        tcpAccepted: (() -> Unit)?,
    ) {
        credentialSink = credential
        tcpAcceptedSink = tcpAccepted
    }

    /** vc250-05：真凭证 0x5703 成功写出；[beforeAirPlayTcp]=发送瞬间 iPhone 尚无 AirPlay TCP。 */
    fun credentialIssued(beforeAirPlayTcp: Boolean) {
        credentialSink?.invoke(beforeAirPlayTcp)
    }

    /** vc250-05：AirPlay TCP 首次/再次 accept（用于区分「下发即入网」与「缓存入网」）。 */
    fun airPlayTcpAccepted() {
        tcpAcceptedSink?.invoke()
    }

    fun stage(stage: String, detail: String) {
        sink?.invoke(stage, detail)
    }

    fun failure(stage: String, failureClass: String, detail: String) {
        failureSink?.invoke(stage, failureClass, detail)
    }
}
