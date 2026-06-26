package com.example.core.usb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Handles generic raw USB bulk transfers (IN and OUT) on a background thread dispatcher.
 * Supports cancellation, timeout, retries, and clean buffer management.
 */
class UsbBulkTransferManager(
    private val usbConnection: UsbConnection,
    private val endpointManager: UsbEndpointManager,
    private val logger: UsbPacketLogger
) {

    /**
     * Performs a Bulk OUT transfer (writing data to the device).
     */
    suspend fun write(
        endpointAddress: Int,
        data: ByteArray,
        timeoutMs: Int = 5000,
        retries: Int = 2
    ): UsbTransferResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        logger.logBulkWrite(endpointAddress, data.size)

        val endpoint = endpointManager.openEndpoint(
            endpointAddress,
            UsbConstants.USB_ENDPOINT_XFER_BULK,
            UsbConstants.USB_DIR_OUT
        )

        if (endpoint == null) {
            val duration = System.currentTimeMillis() - startTime
            val err = "Failed to open output endpoint: 0x${Integer.toHexString(endpointAddress)}"
            logger.logTransferFailure("BULK_WRITE", endpointAddress, err, duration)
            return@withContext UsbTransferResult.Failure(err, null, duration)
        }

        var attempt = 0
        var bytesWritten = 0
        var lastException: Throwable? = null

        while (attempt <= retries && coroutineContext.isActive) {
            try {
                val result = performNativeTransfer(endpoint, data, data.size, timeoutMs)
                if (result >= 0) {
                    bytesWritten = result
                    val duration = System.currentTimeMillis() - startTime
                    logger.logTransferSuccess("BULK_WRITE", endpointAddress, bytesWritten, duration)
                    return@withContext UsbTransferResult.Success(bytesWritten, data.sliceArray(0 until bytesWritten), duration)
                } else {
                    attempt++
                }
            } catch (e: Exception) {
                lastException = e
                attempt++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        if (!coroutineContext.isActive) {
            logger.logTransferFailure("BULK_WRITE", endpointAddress, "Transfer cancelled", duration)
            return@withContext UsbTransferResult.Failure("Transfer cancelled", null, duration)
        }

        logger.logTransferFailure("BULK_WRITE", endpointAddress, "Write failed after $attempt attempts", duration)
        return@withContext UsbTransferResult.Failure("Bulk write failed after $attempt attempts", lastException, duration)
    }

    /**
     * Performs a Bulk IN transfer (reading data from the device).
     */
    suspend fun read(
        endpointAddress: Int,
        bufferSize: Int,
        timeoutMs: Int = 5000,
        retries: Int = 2
    ): UsbTransferResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        logger.logBulkRead(endpointAddress, bufferSize)

        val endpoint = endpointManager.openEndpoint(
            endpointAddress,
            UsbConstants.USB_ENDPOINT_XFER_BULK,
            UsbConstants.USB_DIR_IN
        )

        if (endpoint == null) {
            val duration = System.currentTimeMillis() - startTime
            val err = "Failed to open input endpoint: 0x${Integer.toHexString(endpointAddress)}"
            logger.logTransferFailure("BULK_READ", endpointAddress, err, duration)
            return@withContext UsbTransferResult.Failure(err, null, duration)
        }

        val buffer = ByteArray(bufferSize)
        var attempt = 0
        var lastException: Throwable? = null

        while (attempt <= retries && coroutineContext.isActive) {
            try {
                val result = performNativeTransfer(endpoint, buffer, bufferSize, timeoutMs)
                if (result >= 0) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.logTransferSuccess("BULK_READ", endpointAddress, result, duration)
                    val readData = buffer.sliceArray(0 until result)
                    return@withContext UsbTransferResult.Success(result, readData, duration)
                } else {
                    attempt++
                }
            } catch (e: Exception) {
                lastException = e
                attempt++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        if (!coroutineContext.isActive) {
            logger.logTransferFailure("BULK_READ", endpointAddress, "Transfer cancelled", duration)
            return@withContext UsbTransferResult.Failure("Transfer cancelled", null, duration)
        }

        logger.logTransferFailure("BULK_READ", endpointAddress, "Read failed after $attempt attempts", duration)
        return@withContext UsbTransferResult.Failure("Bulk read failed after $attempt attempts", lastException, duration)
    }

    private fun performNativeTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int
    ): Int {
        val conn = usbConnection.rawConnection
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            conn.bulkTransfer(endpoint, buffer, 0, length, timeoutMs)
        } else {
            conn.bulkTransfer(endpoint, buffer, length, timeoutMs)
        }
    }
}
