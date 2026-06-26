package com.example.core.usb.transport

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles generic raw USB control transfers (IN and OUT) on a background thread dispatcher.
 * Supports standard, class, and vendor requests.
 */
class UsbControlTransferManager(
    private val usbConnection: UsbConnection,
    private val logger: UsbPacketLogger
) {

    /**
     * Performs a Control transfer (IN or OUT depending on requestType).
     */
    suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int = 5000
    ): UsbTransferResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val isDirectionIn = (requestType and 0x80) != 0

        if (isDirectionIn) {
            logger.logControlRead(requestType, request, value, index, length)
        } else {
            logger.logControlWrite(requestType, request, value, index, length ?: 0)
        }

        val buffer = data ?: ByteArray(length.coerceAtLeast(0))

        try {
            val result = performNativeControlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
            val duration = System.currentTimeMillis() - startTime

            if (result >= 0) {
                logger.logTransferSuccess("CONTROL", 0, result, duration)
                val responseData = if (isDirectionIn) {
                    buffer.sliceArray(0 until result)
                } else {
                    buffer
                }
                return@withContext UsbTransferResult.Success(result, responseData, duration)
            } else {
                val err = "Native controlTransfer returned -1"
                logger.logTransferFailure("CONTROL", 0, err, duration)
                return@withContext UsbTransferResult.Failure(err, null, duration)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val err = "Control transfer exception: ${e.message}"
            logger.logTransferFailure("CONTROL", 0, err, duration)
            return@withContext UsbTransferResult.Failure(err, e, duration)
        }
    }

    private fun performNativeControlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int
    ): Int {
        val conn = usbConnection.rawConnection
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            conn.controlTransfer(requestType, request, value, index, buffer, 0, length, timeoutMs)
        } else {
            conn.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
        }
    }
}
