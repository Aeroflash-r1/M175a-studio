package com.example.core.usb.transport

import com.example.core.logging.Logger
import com.example.core.usb.analyzer.UsbAnalyzerEngine

/**
 * Dedicated transport-level USB packet and lifecycle logger.
 * Focuses strictly on connection, interface, endpoint, and raw transfer metadata.
 * Does NOT decode protocol data payloads to ensure strict protocol-agnosticism.
 */
class UsbPacketLogger(
    private val logger: Logger,
    private val analyzerEngine: UsbAnalyzerEngine
) {

    private val tag = "UsbTransport"

    fun logConnectionOpen(device: android.hardware.usb.UsbDevice, rawConnection: android.hardware.usb.UsbDeviceConnection) {
        val deviceName = device.deviceName
        logger.i(tag, "🔌 [CONNECTION OPEN] Connected to device: $deviceName")
        analyzerEngine.startSession(device, System.identityHashCode(rawConnection))
        analyzerEngine.logEvent("CONNECTION OPEN", "Connected to device: $deviceName", "Green")
    }

    fun logConnectionClose(deviceName: String) {
        logger.i(tag, "🔌 [CONNECTION CLOSE] Disconnected from device: $deviceName")
        analyzerEngine.logEvent("CONNECTION CLOSE", "Disconnected from device: $deviceName", "Yellow")
        analyzerEngine.endSession()
    }

    fun logInterfaceClaimed(interfaceId: Int) {
        logger.i(tag, "🛡️ [INTERFACE CLAIMED] Claimed USB Interface: $interfaceId")
        analyzerEngine.logEvent("INTERFACE CLAIMED", "Claimed USB Interface: $interfaceId", "Green")
    }

    fun logClaimAttempt(interfaceId: Int, force: Boolean, result: Boolean, durationMs: Long, errorMessage: String? = null) {
        analyzerEngine.logClaimAttempt(
            interfaceNumber = interfaceId,
            force = force,
            result = result,
            durationMs = durationMs,
            exception = errorMessage,
            failureDiagnostic = if (!result) "Android claimInterface returned false." else null
        )
    }

    fun logInterfaceReleased(interfaceId: Int) {
        logger.i(tag, "🔓 [INTERFACE RELEASED] Released USB Interface: $interfaceId")
        analyzerEngine.logEvent("INTERFACE RELEASED", "Released USB Interface: $interfaceId", "Yellow")
    }

    fun logEndpointOpened(address: Int, type: String, direction: String) {
        logger.d(tag, "🛫 [ENDPOINT OPEN] Opened EP: 0x${Integer.toHexString(address)} (Type: $type, Direction: $direction)")
        analyzerEngine.logEvent("ENDPOINT OPEN", "Opened EP: 0x${Integer.toHexString(address)} (Type: $type, Direction: $direction)", "Blue")
    }

    fun logEndpointClosed(address: Int) {
        logger.d(tag, "🛬 [ENDPOINT CLOSE] Closed EP: 0x${Integer.toHexString(address)}")
        analyzerEngine.logEvent("ENDPOINT CLOSE", "Closed EP: 0x${Integer.toHexString(address)}", "Blue")
    }

    fun logBulkRead(address: Int, requestedBytes: Int) {
        logger.d(tag, "📥 [BULK READ REQUEST] Reading up to $requestedBytes bytes from EP 0x${Integer.toHexString(address)}")
        analyzerEngine.logEvent("BULK READ REQUEST", "Reading up to $requestedBytes bytes from EP 0x${Integer.toHexString(address)}", "Blue")
    }

    fun logBulkWrite(address: Int, bytesCount: Int) {
        logger.d(tag, "📤 [BULK WRITE REQUEST] Writing $bytesCount bytes to EP 0x${Integer.toHexString(address)}")
        analyzerEngine.logEvent("BULK WRITE REQUEST", "Writing $bytesCount bytes to EP 0x${Integer.toHexString(address)}", "Blue")
    }

    fun logControlRead(requestType: Int, request: Int, value: Int, index: Int, requestedBytes: Int) {
        logger.d(tag, "📥 [CONTROL READ REQUEST] ReqType: 0x${Integer.toHexString(requestType)}, Req: 0x${Integer.toHexString(request)}, Val: $value, Idx: $index, Bytes: $requestedBytes")
        analyzerEngine.logEvent("CONTROL READ REQUEST", "ReqType: 0x${Integer.toHexString(requestType)}, Req: 0x${Integer.toHexString(request)}, Val: $value, Idx: $index, Bytes: $requestedBytes", "Blue")
    }

    fun logControlWrite(requestType: Int, request: Int, value: Int, index: Int, bytesCount: Int) {
        logger.d(tag, "📤 [CONTROL WRITE REQUEST] ReqType: 0x${Integer.toHexString(requestType)}, Req: 0x${Integer.toHexString(request)}, Val: $value, Idx: $index, Bytes: $bytesCount")
        analyzerEngine.logEvent("CONTROL WRITE REQUEST", "ReqType: 0x${Integer.toHexString(requestType)}, Req: 0x${Integer.toHexString(request)}, Val: $value, Idx: $index, Bytes: $bytesCount", "Blue")
    }

    fun logTransferSuccess(type: String, address: Int, bytesTransferred: Int, durationMs: Long, rawData: ByteArray? = null, direction: String = "UNKNOWN") {
        logger.i(tag, "✅ [TRANSFER SUCCESS] $type Transfer on 0x${Integer.toHexString(address)}: $bytesTransferred bytes transferred in ${durationMs}ms")
        if (rawData != null) {
            analyzerEngine.logPacket(
                direction = direction,
                interfaceId = -1, // Will be improved if we pass it
                endpointAddress = address,
                type = type,
                length = bytesTransferred,
                data = rawData,
                status = "SUCCESS",
                durationMs = durationMs
            )
        }
    }

    fun logTransferFailure(type: String, address: Int, errorMessage: String, durationMs: Long, direction: String = "UNKNOWN") {
        logger.e(tag, "❌ [TRANSFER FAILURE] $type Transfer on 0x${Integer.toHexString(address)} failed after ${durationMs}ms: $errorMessage")
        analyzerEngine.logPacket(
            direction = direction,
            interfaceId = -1,
            endpointAddress = address,
            type = type,
            length = 0,
            data = ByteArray(0),
            status = if (errorMessage.contains("cancel", ignoreCase = true) || errorMessage.contains("time", ignoreCase = true)) "TIMEOUT" else "ERROR",
            durationMs = durationMs
        )
    }

    fun logTransferTimeout(type: String, address: Int, durationMs: Long) {
        logger.w(tag, "⏳ [TRANSFER TIMEOUT] $type Transfer on 0x${Integer.toHexString(address)} timed out after ${durationMs}ms")
    }

    fun logRecoveryInitiated(reason: String) {
        logger.w(tag, "🛡️ [RECOVERY INITIATED] Recovering transport layer. Reason: $reason")
        analyzerEngine.logEvent("RECOVERY INITIATED", reason, "Red")
    }

    fun logRecoverySuccess() {
        logger.i(tag, "✅ [RECOVERY SUCCESS] Transport layer recovery completed successfully.")
        analyzerEngine.logEvent("RECOVERY SUCCESS", "Transport layer recovery completed successfully.", "Green")
    }

    fun logRecoveryFailure(errorMessage: String) {
        logger.e(tag, "❌ [RECOVERY FAILURE] Transport layer recovery failed: $errorMessage")
        analyzerEngine.logEvent("RECOVERY FAILURE", errorMessage, "Red")
    }
}
