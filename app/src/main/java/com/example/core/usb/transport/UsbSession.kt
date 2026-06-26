package com.example.core.usb.transport

/**
 * Manages an active generic USB communication session.
 * Automatically cleans up claimed interfaces and endpoints when closed.
 */
class UsbSession(
    val connection: UsbConnection,
    val logger: UsbPacketLogger
) {
    val interfaceManager = UsbInterfaceManager(connection, logger)
    val endpointManager = UsbEndpointManager(connection, logger)
    val bulkTransferManager = UsbBulkTransferManager(connection, endpointManager, logger)
    val controlTransferManager = UsbControlTransferManager(connection, logger)

    private var isValid = true

    /**
     * Checks if this session is open and valid.
     */
    @Synchronized
    fun isValid(): Boolean = isValid

    /**
     * Safely closes the session, releasing all interfaces, endpoints, and native connections.
     */
    @Synchronized
    fun close() {
        if (!isValid) return
        isValid = false

        logger.logConnectionClose(connection.device.deviceName)

        try {
            // 1. Release claimed interfaces
            interfaceManager.releaseAll()

            // 2. Release tracked endpoints
            endpointManager.closeAll()

            // 3. Close native Android connection
            connection.rawConnection.close()
        } catch (e: Exception) {
            logger.logTransferFailure("CLOSE_SESSION", 0, "Error closing raw connection: ${e.message}", 0)
        }
    }
}
