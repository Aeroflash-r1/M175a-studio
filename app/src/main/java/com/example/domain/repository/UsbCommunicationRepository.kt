package com.example.domain.repository

import com.example.core.usb.transport.UsbTransferResult
import com.example.core.usb.transport.UsbTransportStats
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository handling protocol-agnostic, generic USB bulk and control packet transmissions.
 */
interface UsbCommunicationRepository {
    /**
     * Exposes live stream of low-level USB transfer stats.
     */
    val transportStats: StateFlow<UsbTransportStats>

    /**
     * Initializes a generic transport session using the active connection.
     */
    fun startSession(): Boolean

    /**
     * Disposes and tears down the active generic transport session.
     */
    fun endSession()

    /**
     * Claims the specified USB interface.
     */
    suspend fun claimInterface(interfaceId: Int): Boolean

    /**
     * Releases the specified USB interface.
     */
    suspend fun releaseInterface(interfaceId: Int): Boolean

    /**
     * Performs raw bulk OUT transfer.
     */
    suspend fun writeBulk(endpointAddress: Int, data: ByteArray, timeoutMs: Int = 5000): UsbTransferResult

    /**
     * Performs raw bulk IN transfer.
     */
    suspend fun readBulk(endpointAddress: Int, bufferSize: Int, timeoutMs: Int = 5000): UsbTransferResult

    /**
     * Performs standard or vendor control transfer.
     */
    suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int = 5000
    ): UsbTransferResult

    /**
     * Recovers from active USB errors.
     */
    suspend fun recoverConnection(): Boolean

    /**
     * Retrieves the active UsbDevice if connected.
     */
    fun getActiveDevice(): android.hardware.usb.UsbDevice?

    /**
     * Retrieves the parsed info of the active device.
     */
    fun getDeviceInfo(): com.example.core.usb.UsbDeviceInfo?
}
