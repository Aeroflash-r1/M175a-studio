package com.example.core.usb.transport

import com.example.core.logging.Logger

/**
 * Dedicated transport-level USB packet and lifecycle logger.
 * Focuses strictly on connection, interface, endpoint, and raw transfer metadata.
 * Does NOT decode protocol data payloads to ensure strict protocol-agnosticism.
 */
class UsbPacketLogger(private val logger: Logger) {

    private val tag = "UsbTransport"

    fun logConnectionOpen(deviceName: String) {
        logger.i(tag, "🔌 [CONNECTION OPEN] Connected to device: $deviceName")
    }

    fun logConnectionClose(deviceName: String) {
        logger.i(tag, "🔌 [CONNECTION CLOSE] Disconnected from device: $deviceName")
    }

    fun logInterfaceClaimed(interfaceId: Int) {
        logger.i(tag, "🛡️ [INTERFACE CLAIMED] Claimed USB Interface: $interfaceId")
    }

    fun logInterfaceReleased(interfaceId: Int) {
        logger.i(tag, "🔓 [INTERFACE RELEASED] Released USB Interface: $interfaceId")
    }

    fun logEndpointOpened(address: Int, type: String, direction: String) {
        logger.d(tag, "🛫 [ENDPOINT OPEN] Opened EP: 0x${Integer.toHexString(address)} (Type: $type, Direction: $direction)")
    }

    fun logEndpointClosed(address: Int) {
        logger.d(tag, "🛬 [ENDPOINT CLOSE] Closed EP: 0x${Integer.toHexString(address)}")
    }

    fun logBulkRead(address: Int, requestedBytes: Int) {
        logger.d(tag, "📥 [BULK READ REQUEST] Reading up to $requestedBytes bytes from EP 0x${Integer.toHexString(address)}")
    }

    fun logBulkWrite(address: Int, bytesCount: Int) {
        logger.d(tag, "📤 [BULK WRITE REQUEST] Writing $bytesCount bytes to EP 0x${Integer.toHexString(address)}")
    }

    fun logControlRead(requestType: Int, request: Int, value: Int, index: Int, requestedBytes: Int) {
        logger.d(tag, "📥 [CONTROL READ REQUEST] ReqType: 0x${Integer.toHexString(requestType)}, Req: 0x${Integer.toHexString(request)}, Val: $value, Idx: $index, Bytes: $requestedBytes")
    }

    fun logControlWrite(requestType: Int, request: Int, value: Int, index: Int, bytesCount: Int) {
        logger.d(tag, "📤 [CONTROL WRITE REQUEST] ReqType: 0x${Integer.toHexString(requestType)}, Req: 0x${Integer.toHexString(request)}, Val: $value, Idx: $index, Bytes: $bytesCount")
    }

    fun logTransferSuccess(type: String, address: Int, bytesTransferred: Int, durationMs: Long) {
        logger.i(tag, "✅ [TRANSFER SUCCESS] $type Transfer on 0x${Integer.toHexString(address)}: $bytesTransferred bytes transferred in ${durationMs}ms")
    }

    fun logTransferFailure(type: String, address: Int, errorMessage: String, durationMs: Long) {
        logger.e(tag, "❌ [TRANSFER FAILURE] $type Transfer on 0x${Integer.toHexString(address)} failed after ${durationMs}ms: $errorMessage")
    }

    fun logTransferTimeout(type: String, address: Int, durationMs: Long) {
        logger.w(tag, "⏳ [TRANSFER TIMEOUT] $type Transfer on 0x${Integer.toHexString(address)} timed out after ${durationMs}ms")
    }

    fun logRecoveryInitiated(reason: String) {
        logger.w(tag, "🛡️ [RECOVERY INITIATED] Recovering transport layer. Reason: $reason")
    }

    fun logRecoverySuccess() {
        logger.i(tag, "✅ [RECOVERY SUCCESS] Transport layer recovery completed successfully.")
    }

    fun logRecoveryFailure(errorMessage: String) {
        logger.e(tag, "❌ [RECOVERY FAILURE] Transport layer recovery failed: $errorMessage")
    }
}
