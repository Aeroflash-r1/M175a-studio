package com.example.printer.protocol

import com.example.core.usb.transport.UsbTransferResult
import com.example.domain.repository.UsbCommunicationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles communication with the HP LaserJet M175a Printer subsystem using PJL.
 */
class PrinterProtocolRepository(
    private val usbCommRepo: UsbCommunicationRepository
) {
    private val PRINTER_INTERFACE_ID = 1
    private val BULK_OUT_EP = 0x01
    private val BULK_IN_EP = 0x81

    /**
     * Sends a PJL INFO ID command to verify printer connectivity and retrieve model info.
     */
    suspend fun getPrinterId(): String = withContext(Dispatchers.IO) {
        try {
            // 1. Claim Printer Interface
            if (!usbCommRepo.claimInterface(PRINTER_INTERFACE_ID)) {
                return@withContext "Error: Could not claim printer interface"
            }

            // 2. Send INFO ID command
            val command = PjlCommands.createInfoIdCommand()
            val writeResult = usbCommRepo.writeBulk(BULK_OUT_EP, command)

            if (writeResult !is UsbTransferResult.Success) {
                return@withContext "Error: Failed to send PJL command: ${writeResult}"
            }

            // 3. Read response
            val readResult = usbCommRepo.readBulk(BULK_IN_EP, 1024)
            
            // 4. Release Interface
            usbCommRepo.releaseInterface(PRINTER_INTERFACE_ID)

            if (readResult is UsbTransferResult.Success) {
                return@withContext String(readResult.data ?: byteArrayOf()).trim()
            } else {
                return@withContext "Error: Failed to read PJL response: ${readResult}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
