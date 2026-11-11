package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.CarPlayFailureClass
import com.shilapi.xcertplay.ClassifiedFailure
import java.io.IOException

/**
 * iAP2 会话通用异常（Lite：从 IphoneUsbHost.kt 抽出独立保留）。
 * 历史命名含 "Usb"，但无线（蓝牙 RFCOMM）链路的识别/控制/会话建立同样使用本异常。
 *
 * vc250：新增可选的 failureClass（默认 Unknown），文案与既有调用完全不变；
 * 需要在源头归类的调用点传第三参即可。
 */
sealed class IphoneUsbException(
    message: String,
    cause: Throwable? = null,
    override val failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
) : IOException(message, cause), ClassifiedFailure {
    class PermissionDenied(
        message: String,
        cause: Throwable? = null,
        failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
    ) : IphoneUsbException(message, cause, failureClass)

    class DeviceUnavailable(
        message: String,
        cause: Throwable? = null,
        failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
    ) : IphoneUsbException(message, cause, failureClass)

    class TimedOut(
        message: String,
        cause: Throwable? = null,
        failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
    ) : IphoneUsbException(message, cause, failureClass)

    class Protocol(
        message: String,
        failureClass: CarPlayFailureClass = CarPlayFailureClass.Unknown,
    ) : IphoneUsbException(message, failureClass = failureClass)
}
