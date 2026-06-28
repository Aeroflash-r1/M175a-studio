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

    override fun getActiveDevice(): android.hardware.usb.UsbDevice? {
        return connectionManager.getActiveDevice()
    }

    override fun getDeviceInfo(): com.example.core.usb.UsbDeviceInfo? {
        val device = connectionManager.getActiveDevice() ?: return null
        return com.example.core.usb.UsbDeviceParser.parseDevice(device)
    }

    override suspend fun probeMassStorage(interfaceId: Int): com.example.core.usb.MsdProbeResult {
        val deviceInfo = getDeviceInfo()
            ?: return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "",
                product = "",
                revision = "",
                rawHexResponse = "",
                errorDetails = "No USB device is currently connected."
            )

        val interf = deviceInfo.configurations.flatMap { it.interfaces }.find { it.id == interfaceId }
            ?: return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "",
                product = "",
                revision = "",
                rawHexResponse = "",
                errorDetails = "Interface $interfaceId not found on device."
            )

        val bulkInAddr = interf.endpoints.find { it.type == "BULK" && it.direction == "IN" }?.address
            ?: return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "",
                product = "",
                revision = "",
                rawHexResponse = "",
                errorDetails = "Interface $interfaceId has no Bulk IN endpoint."
            )

        val bulkOutAddr = interf.endpoints.find { it.type == "BULK" && it.direction == "OUT" }?.address
            ?: return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "",
                product = "",
                revision = "",
                rawHexResponse = "",
                errorDetails = "Interface $interfaceId has no Bulk OUT endpoint."
            )

        val claimSuccess = claimInterface(interfaceId)
        if (!claimSuccess) {
            return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "",
                product = "",
                revision = "",
                rawHexResponse = "",
                errorDetails = "Failed to claim Interface $interfaceId."
            )
        }

        try {
            // 1. Build standard SCSI INQUIRY Command Block Wrapper (31 bytes)
            val cbw = ByteArray(31)
            cbw[0] = 0x55.toByte() // Signature 'USBC'
            cbw[1] = 0x53.toByte()
            cbw[2] = 0x42.toByte()
            cbw[3] = 0x43.toByte()

            cbw[4] = 0x01.toByte() // Tag
            cbw[5] = 0x02.toByte()
            cbw[6] = 0x03.toByte()
            cbw[7] = 0x04.toByte()

            cbw[8] = 0x24.toByte() // Data transfer length LSB (36 bytes)
            cbw[9] = 0x00.toByte()
            cbw[10] = 0x00.toByte()
            cbw[11] = 0x00.toByte()

            cbw[12] = 0x80.toByte() // Direction Bit 7 = 1 (Device to Host)
            cbw[13] = 0x00.toByte() // LUN = 0
            cbw[14] = 0x06.toByte() // SCSI CB length (6 bytes)

            // SCSI CB Opcode: INQUIRY (0x12)
            cbw[15] = 0x12.toByte()
            cbw[16] = 0x00.toByte() // EVPD = 0
            cbw[17] = 0x00.toByte() // Page Code = 0
            cbw[18] = 0x00.toByte() // Allocation Length MSB = 0
            cbw[19] = 0x24.toByte() // Allocation Length LSB = 36 bytes
            cbw[20] = 0x00.toByte() // Control = 0

            // 2. Transmit CBW to Bulk OUT
            val writeResult = writeBulk(bulkOutAddr, cbw)
            if (writeResult !is UsbTransferResult.Success) {
                return com.example.core.usb.MsdProbeResult(
                    interfaceId = interfaceId,
                    behavesAsMsd = false,
                    vendor = "",
                    product = "",
                    revision = "",
                    rawHexResponse = "",
                    errorDetails = "CBW write failed: ${(writeResult as? UsbTransferResult.Failure)?.errorMessage ?: "Timeout"}"
                )
            }

            // 3. Read SCSI INQUIRY response from Bulk IN (36 bytes)
            val readResult = readBulk(bulkInAddr, 36)
            if (readResult !is UsbTransferResult.Success) {
                return com.example.core.usb.MsdProbeResult(
                    interfaceId = interfaceId,
                    behavesAsMsd = false,
                    vendor = "",
                    product = "",
                    revision = "",
                    rawHexResponse = "",
                    errorDetails = "INQUIRY data read failed: ${(readResult as? UsbTransferResult.Failure)?.errorMessage ?: "Timeout"}"
                )
            }

            val data = readResult.data
            val rawHex = data.joinToString("") { String.format("%02X", it) }

            // 4. Read CSW status block from Bulk IN (13 bytes) to keep state machine clean
            val cswResult = readBulk(bulkInAddr, 13)
            val cswHex = if (cswResult is UsbTransferResult.Success) {
                cswResult.data.joinToString("") { String.format("%02X", it) }
            } else {
                "No CSW block received"
            }

            if (data.size < 36) {
                return com.example.core.usb.MsdProbeResult(
                    interfaceId = interfaceId,
                    behavesAsMsd = false,
                    vendor = "",
                    product = "",
                    revision = "",
                    rawHexResponse = "Raw Inquiry Hex: $rawHex\nCSW: $cswHex",
                    errorDetails = "Inquiry response too short: only ${data.size} bytes received."
                )
            }

            val vendor = try {
                String(data, 8, 8, Charsets.US_ASCII).trim()
            } catch (e: Exception) {
                "Unknown"
            }

            val product = try {
                String(data, 16, 16, Charsets.US_ASCII).trim()
            } catch (e: Exception) {
                "Unknown"
            }

            val revision = try {
                String(data, 32, 4, Charsets.US_ASCII).trim()
            } catch (e: Exception) {
                "Unknown"
            }

            return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = true,
                vendor = vendor,
                product = product,
                revision = revision,
                rawHexResponse = "Raw Inquiry Hex: $rawHex\nCSW: $cswHex",
                errorDetails = null
            )
        } catch (e: Exception) {
            return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "",
                product = "",
                revision = "",
                rawHexResponse = "",
                errorDetails = "Exception during probe: ${e.message}"
            )
        } finally {
            releaseInterface(interfaceId)
        }
    }
}
