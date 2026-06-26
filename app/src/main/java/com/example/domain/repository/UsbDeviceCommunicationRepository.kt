package com.example.domain.repository

import com.example.core.usb.UsbConnectionManager
import com.example.core.usb.transport.UsbTransferResult
import com.example.core.usb.transport.UsbTransport
import com.example.core.usb.transport.UsbTransportStats
import kotlinx.coroutines.flow.StateFlow

/**
 * Concrete implementation of [UsbCommunicationRepository] bridging the lower-level [UsbTransport]
 * with live device connection sessions from [UsbConnectionManager].
 */
class UsbDeviceCommunicationRepository(
    private val connectionManager: UsbConnectionManager,
    private val usbTransport: UsbTransport
) : UsbCommunicationRepository {

    override val transportStats: StateFlow<UsbTransportStats> = usbTransport.stats

    override fun startSession(): Boolean {
        val device = connectionManager.getActiveDevice()
        val connection = connectionManager.getActiveConnection()
        if (device != null && connection != null) {
            usbTransport.openSession(device, connection)
            return true
        }
        return false
    }

    override fun endSession() {
        usbTransport.closeSession()
    }

    override suspend fun claimInterface(interfaceId: Int): Boolean {
        return usbTransport.claimInterface(interfaceId)
    }

    override suspend fun releaseInterface(interfaceId: Int): Boolean {
        return usbTransport.releaseInterface(interfaceId)
    }

    override suspend fun writeBulk(endpointAddress: Int, data: ByteArray, timeoutMs: Int): UsbTransferResult {
        return usbTransport.writeBulk(endpointAddress, data, timeoutMs)
    }

    override suspend fun readBulk(endpointAddress: Int, bufferSize: Int, timeoutMs: Int): UsbTransferResult {
        return usbTransport.readBulk(endpointAddress, bufferSize, timeoutMs)
    }

    override suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        return usbTransport.controlTransfer(requestType, request, value, index, data, length, timeoutMs)
    }

    override suspend fun recoverConnection(): Boolean {
        return usbTransport.recoverConnection()
    }
}
