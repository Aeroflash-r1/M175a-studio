package com.example.core.usb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint

/**
 * Thread-safe controller managing USB endpoints and verifying transfer characteristics.
 */
class UsbEndpointManager(
    private val usbConnection: UsbConnection,
    private val logger: UsbPacketLogger
) {
    private val activeEndpoints = mutableSetOf<Int>()

    /**
     * Searches for a native [UsbEndpoint] by address.
     */
    @Synchronized
    fun findEndpoint(endpointAddress: Int): UsbEndpoint? {
        val device = usbConnection.device
        for (i in 0 until device.interfaceCount) {
            val interf = device.getInterface(i)
            for (j in 0 until interf.endpointCount) {
                val endpoint = interf.getEndpoint(j)
                if (endpoint.address == endpointAddress) {
                    return endpoint
                }
            }
        }
        return null
    }

    /**
     * Validates and opens an endpoint for bulk or interrupt communication.
     */
    @Synchronized
    fun openEndpoint(endpointAddress: Int, expectedType: Int, expectedDirection: Int): UsbEndpoint? {
        val endpoint = findEndpoint(endpointAddress)
        if (endpoint == null) {
            logger.logTransferFailure("OPEN_EP", endpointAddress, "Endpoint not found on device configurations", 0)
            return null
        }

        if (endpoint.direction != expectedDirection) {
            logger.logTransferFailure(
                "OPEN_EP",
                endpointAddress,
                "Direction mismatch. Expected: $expectedDirection, Actual: ${endpoint.direction}",
                0
            )
            return null
        }

        if (endpoint.type != expectedType) {
            logger.logTransferFailure(
                "OPEN_EP",
                endpointAddress,
                "Transfer type mismatch. Expected: $expectedType, Actual: ${endpoint.type}",
                0
            )
            return null
        }

        if (endpoint.maxPacketSize <= 0) {
            logger.logTransferFailure("OPEN_EP", endpointAddress, "Invalid maxPacketSize: ${endpoint.maxPacketSize}", 0)
            return null
        }

        activeEndpoints.add(endpointAddress)

        val typeStr = when (expectedType) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "Bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "Interrupt"
            else -> "Other"
        }
        val dirStr = if (expectedDirection == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        logger.logEndpointOpened(endpointAddress, typeStr, dirStr)
        return endpoint
    }

    /**
     * Closes an active endpoint.
     */
    @Synchronized
    fun closeEndpoint(endpointAddress: Int) {
        if (activeEndpoints.remove(endpointAddress)) {
            logger.logEndpointClosed(endpointAddress)
        }
    }

    /**
     * Validates direction and transfer type of a given endpoint address.
     */
    @Synchronized
    fun validateEndpoint(endpointAddress: Int, type: Int, direction: Int): Boolean {
        val endpoint = findEndpoint(endpointAddress) ?: return false
        return endpoint.type == type && endpoint.direction == direction
    }

    /**
     * Returns a list of currently active endpoint addresses.
     */
    @Synchronized
    fun getActiveEndpoints(): List<Int> = activeEndpoints.toList()

    /**
     * Closes and cleans up all tracked active endpoints.
     */
    @Synchronized
    fun closeAll() {
        val copy = activeEndpoints.toList()
        for (addr in copy) {
            closeEndpoint(addr)
        }
    }
}
