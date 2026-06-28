package com.example.core.usb.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level, completely protocol-agnostic transport layer orchestrating USB discovery sessions.
 */
class UsbTransport(
    private val logger: UsbPacketLogger
) {
    private var activeSession: UsbSession? = null

    private val _stats = MutableStateFlow(UsbTransportStats())
    val stats: StateFlow<UsbTransportStats> = _stats.asStateFlow()

    private var bytesSentTotal: Long = 0
    private var bytesReceivedTotal: Long = 0
    private var startTimeMs: Long = 0

    /**
     * Instantiates and opens a fresh communication session.
     */
    @Synchronized
    fun openSession(device: UsbDevice, rawConnection: UsbDeviceConnection): UsbSession {
        closeSession()
        startTimeMs = System.currentTimeMillis()
        
        val deviceHashCode = System.identityHashCode(device)
        val connHashCode = System.identityHashCode(rawConnection)
        logger.logRecoveryInitiated("[FORENSIC LIFECYCLE] Device Opened. DevHash: $deviceHashCode, ConnHash: $connHashCode")

        // RUN EXHAUSTIVE DIAGNOSTIC
        UsbExhaustiveDiagnostic(logger).runDiagnostics(device, rawConnection)

        val connection = UsbConnection(device, rawConnection)
        val session = UsbSession(connection, logger)
        activeSession = session
        
        val sessionHashCode = System.identityHashCode(session)
        logger.logRecoveryInitiated("[FORENSIC LIFECYCLE] Session Ready. SessionHash: $sessionHashCode")
        
        logger.logConnectionOpen(device, rawConnection)
        updateStats("Session Opened", "Connected to ${device.deviceName}")
        return session
    }

    /**
     * Safely disposes and cleans up the active session.
     */
    @Synchronized
    fun closeSession() {
        activeSession?.let {
            it.close()
            activeSession = null
        }
        bytesSentTotal = 0
        bytesReceivedTotal = 0
        startTimeMs = 0
        updateStats("Disconnected", "None")
    }

    /**
     * Returns the active session if valid.
     */
    @Synchronized
    fun getActiveSession(): UsbSession? {
        val session = activeSession
        if (session != null && session.isValid()) {
            return session
        }
        return null
    }

    /**
     * Reports true if there is an open, functional USB transport session.
     */
    fun isCommunicationReady(): Boolean {
        val session = getActiveSession() ?: return false
        return session.isValid()
    }

    /**
     * Performs a low-level raw bulk OUT data write to the specified endpoint.
     */
    suspend fun writeBulk(endpointAddress: Int, data: ByteArray, timeoutMs: Int = 5000): UsbTransferResult {
        val session = getActiveSession() ?: return UsbTransferResult.Failure("No active USB communication session", null, 0)

        updateStats("Writing Bulk Data", "Sending ${data.size} bytes to EP 0x${Integer.toHexString(endpointAddress)}")
        val result = session.bulkTransferManager.write(endpointAddress, data, timeoutMs)

        when (result) {
            is UsbTransferResult.Success -> {
                bytesSentTotal += result.bytesTransferred
                updateStats("Idle", "Bulk OUT Success: ${result.bytesTransferred} bytes")
            }
            is UsbTransferResult.Failure -> {
                updateStats("Error", "Bulk OUT Failure: ${result.errorMessage}")
            }
            is UsbTransferResult.Timeout -> {
                updateStats("Error", "Bulk OUT Timeout")
            }
        }
        return result
    }

    /**
     * Performs a low-level raw bulk IN data read from the specified endpoint.
     */
    suspend fun readBulk(endpointAddress: Int, bufferSize: Int, timeoutMs: Int = 5000): UsbTransferResult {
        val session = getActiveSession() ?: return UsbTransferResult.Failure("No active USB communication session", null, 0)

        updateStats("Reading Bulk Data", "Reading up to $bufferSize bytes from EP 0x${Integer.toHexString(endpointAddress)}")
        val result = session.bulkTransferManager.read(endpointAddress, bufferSize, timeoutMs)

        when (result) {
            is UsbTransferResult.Success -> {
                bytesReceivedTotal += result.bytesTransferred
                updateStats("Idle", "Bulk IN Success: ${result.bytesTransferred} bytes")
            }
            is UsbTransferResult.Failure -> {
                updateStats("Error", "Bulk IN Failure: ${result.errorMessage}")
            }
            is UsbTransferResult.Timeout -> {
                updateStats("Error", "Bulk IN Timeout")
            }
        }
        return result
    }

    /**
     * Performs a raw control transfer.
     */
    suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int = 5000
    ): UsbTransferResult {
        val session = getActiveSession() ?: return UsbTransferResult.Failure("No active USB communication session", null, 0)

        updateStats("Control Transfer", "Request: 0x${Integer.toHexString(request)}")
        val result = session.controlTransferManager.controlTransfer(requestType, request, value, index, data, length, timeoutMs)

        when (result) {
            is UsbTransferResult.Success -> {
                val isDirectionIn = (requestType and 0x80) != 0
                if (isDirectionIn) {
                    bytesReceivedTotal += result.bytesTransferred
                } else {
                    bytesSentTotal += result.bytesTransferred
                }
                updateStats("Idle", "Control Success: ${result.bytesTransferred} bytes")
            }
            is UsbTransferResult.Failure -> {
                updateStats("Error", "Control Failure: ${result.errorMessage}")
            }
            is UsbTransferResult.Timeout -> {
                updateStats("Error", "Control Timeout")
            }
        }
        return result
    }

    /**
     * Claims the specified interface.
     */
    suspend fun claimInterface(interfaceId: Int, force: Boolean = true): Boolean {
        val session = getActiveSession() ?: return false
        val success = session.interfaceManager.claimInterface(interfaceId, force)
        updateStats("Interface Claimed", "Claimed Interface $interfaceId status: $success")
        return success
    }

    /**
     * Releases the specified interface.
     */
    suspend fun releaseInterface(interfaceId: Int): Boolean {
        val session = getActiveSession() ?: return false
        val success = session.interfaceManager.releaseInterface(interfaceId)
        updateStats("Interface Released", "Released Interface $interfaceId status: $success")
        return success
    }

    /**
     * Attempts a non-blocking hardware soft recovery by releasing and re-claiming interfaces.
     */
    suspend fun recoverConnection(): Boolean {
        logger.logRecoveryInitiated("Active session recovery requested")
        val session = activeSession
        if (session != null) {
            try {
                val claimed = session.interfaceManager.getClaimedInterfaces()
                session.interfaceManager.releaseAll()
                var success = true
                for (id in claimed) {
                    if (!session.interfaceManager.claimInterface(id)) {
                        success = false
                    }
                }
                if (success) {
                    logger.logRecoverySuccess()
                    updateStats("Recovered", "All interfaces re-claimed successfully")
                    return true
                }
            } catch (e: Exception) {
                logger.logRecoveryFailure(e.message ?: "Unknown error")
            }
        }
        updateStats("Recovery Failed", "Could not recover connection")
        return false
    }

    private fun updateStats(status: String, lastTransfer: String) {
        val session = activeSession
        val durationSec = if (startTimeMs > 0) (System.currentTimeMillis() - startTimeMs) / 1000 else 0

        _stats.value = UsbTransportStats(
            isCommunicationReady = session != null && session.isValid(),
            isConnectionOpen = session != null,
            claimedInterfaces = session?.interfaceManager?.getClaimedInterfaces() ?: emptyList(),
            readyEndpoints = session?.endpointManager?.getActiveEndpoints() ?: emptyList(),
            transferStatus = status,
            lastTransferDetails = lastTransfer,
            bytesSent = bytesSentTotal,
            bytesReceived = bytesReceivedTotal,
            connectionDurationSec = durationSec,
            transportHealth = if (session != null) "Excellent" else "Normal"
        )
    }
}
