package com.shilapi.xcertplay.media

/** vc327：一次成功触摸发送的分段耗时快照（主视频窗口整组读取）。 */
internal data class TouchPhaseSnapshot(
    val queueWaitMaxNs: Long,
    val lockWaitMaxNs: Long,
    val postLockMaxNs: Long,
    val socketWriteMaxNs: Long,
    val sentSamples: Int,
    val slowCount: Int,
)

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    /**
     * vc327：慢发送阈值 50ms。`AirPlaySession` 的慢 MOVE 日志与本对象计数**共用同一常量**，
     * 避免两处阈值漂移。
     */
    internal const val SLOW_SEND_THRESHOLD_NS = 50_000_000L

    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L

    /**
     * vc327：分段聚合。四个最大值与样本/慢次数必须整组读清——分多个 @Volatile 读写会丢跨窗口更新。
     */
    private val phaseLock = Any()
    private var maxQueueWaitNs = 0L
    private var maxLockWaitNs = 0L
    private var maxPostLockNs = 0L
    private var maxSocketWriteNs = 0L
    private var sentSamples = 0
    private var slowCount = 0

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /**
     * vc327：一次**成功**发送调用一次。计时值必须来自实际打点（不得由总耗时反推）；
     * 四个最大值只反映尖峰，样本数与慢次数用来数清慢发送比例。
     */
    fun onSendPhases(
        queueWaitNs: Long,
        lockWaitNs: Long,
        postLockNs: Long,
        socketWriteNs: Long,
        totalNs: Long,
    ) {
        synchronized(phaseLock) {
            sentSamples++
            if (totalNs >= SLOW_SEND_THRESHOLD_NS) slowCount++
            if (queueWaitNs > maxQueueWaitNs) maxQueueWaitNs = queueWaitNs
            if (lockWaitNs > maxLockWaitNs) maxLockWaitNs = lockWaitNs
            if (postLockNs > maxPostLockNs) maxPostLockNs = postLockNs
            if (socketWriteNs > maxSocketWriteNs) maxSocketWriteNs = socketWriteNs
        }
    }

    /** vc327：整组读取并清零；只由主视频窗口（`label==""`）调用。 */
    fun readAndResetPhases(): TouchPhaseSnapshot = synchronized(phaseLock) {
        val snapshot = TouchPhaseSnapshot(
            queueWaitMaxNs = maxQueueWaitNs,
            lockWaitMaxNs = maxLockWaitNs,
            postLockMaxNs = maxPostLockNs,
            socketWriteMaxNs = maxSocketWriteNs,
            sentSamples = sentSamples,
            slowCount = slowCount,
        )
        maxQueueWaitNs = 0L
        maxLockWaitNs = 0L
        maxPostLockNs = 0L
        maxSocketWriteNs = 0L
        sentSamples = 0
        slowCount = 0
        snapshot
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }
}
