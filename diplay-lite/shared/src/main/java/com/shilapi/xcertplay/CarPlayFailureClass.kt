package com.shilapi.xcertplay

import java.io.IOException

/**
 * vc250：失败分类（链接路源头标注，供上层结构化日志直接读取）。
 *
 * 只承载分类，不改变任何既有报错文案；无标注时统一走 [Unknown]，
 * 由 App 层 `classifyFailure` 做兜底猜测。
 */
enum class CarPlayFailureClass(val wire: String) {
    BtNoResponse("bt-no-response"),
    ClaimBusy("claim-busy"),
    CredTimeout("cred-timeout"),
    TunnelTimeout("tunnel-timeout"),
    KeyframeStall("keyframe-stall"),
    Iap2Timeout("iap2-timeout"),
    Unknown("unknown"),
}

/** 带失败分类的异常；文案保持原样，仅多带一个结构化字段。 */
interface ClassifiedFailure {
    val failureClass: CarPlayFailureClass
}

/** 通用已分类异常（USB/BT/NCM 等不适配 IphoneUsbException 的层级使用）。 */
class CarPlayClassifiedException(
    message: String,
    override val failureClass: CarPlayFailureClass,
    cause: Throwable? = null,
) : IOException(message, cause), ClassifiedFailure

/**
 * 读取源头分类：优先当前异常，其次沿 cause 链向上找（兼容 Future/ExecutionException 包装）。
 * [default] 用于调用方明确知道当前在跑哪一段链路时兜底。
 */
fun Throwable.carPlayFailureClass(
    default: CarPlayFailureClass = CarPlayFailureClass.Unknown,
): CarPlayFailureClass {
    var current: Throwable? = this
    repeat(8) {
        val error = current ?: return default
        if (error is ClassifiedFailure && error.failureClass != CarPlayFailureClass.Unknown) {
            return error.failureClass
        }
        current = error.cause
    }
    return default
}
