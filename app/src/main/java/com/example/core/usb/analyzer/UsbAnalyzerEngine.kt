package com.example.core.usb.analyzer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import android.hardware.usb.UsbDevice
import android.os.Build
import java.util.UUID

data class UsbPacket(
    val id: Int,
    val timestamp: Long,
    val direction: String, // "IN", "OUT"
    val interfaceId: Int, // Can be -1 if unknown
    val endpointAddress: Int,
    val type: String, // "Bulk", "Control", "Interrupt"
    val length: Int,
    val data: ByteArray, // Raw payload
    val status: String, // "SUCCESS", "TIMEOUT", "ERROR"
    val durationMs: Long,
    val callerStackTrace: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UsbPacket
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return id
    }
}

data class UsbEvent(
    val id: Int,
    val timestamp: Long,
    val eventType: String,
    val details: String,
    val colorCode: String, // "Green", "Yellow", "Red", "Blue"
    val durationMs: Long = 0,
    val threadName: String = "",
    val callerMethod: String = ""
)

data class UsbAnalyzerStats(
    val totalPackets: Int = 0,
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,
    val errors: Int = 0,
    val timeouts: Int = 0,
    val stallCount: Int = 0
)

/**
 * Professional USB Traffic Analyzer Engine.
 * Must be totally decoupled from business logic and run in background.
 */
class UsbAnalyzerEngine {
    private val _packets = MutableStateFlow<List<UsbPacket>>(emptyList())
    val packets: StateFlow<List<UsbPacket>> = _packets.asStateFlow()

    private val _events = MutableStateFlow<List<UsbEvent>>(emptyList())
    val events: StateFlow<List<UsbEvent>> = _events.asStateFlow()

    private val _stats = MutableStateFlow(UsbAnalyzerStats())
    val stats: StateFlow<UsbAnalyzerStats> = _stats.asStateFlow()

    private val _sessionReports = MutableStateFlow<List<UsbSessionReport>>(emptyList())
    val sessionReports: StateFlow<List<UsbSessionReport>> = _sessionReports.asStateFlow()

    private var currentDeviceInfo: DeviceInfo? = null
    private var currentInterfaces: List<InterfaceInfo> = emptyList()
    private var currentConnectionHash: Int? = null

    // Thread-safe queues
    private val packetQueue = ConcurrentLinkedQueue<UsbPacket>()
    private val eventQueue = ConcurrentLinkedQueue<UsbEvent>()
    private val claimQueue = ConcurrentLinkedQueue<ClaimAnalysis>()

    private val packetIdCounter = AtomicInteger(0)
    private val eventIdCounter = AtomicInteger(0)
    
    fun startSession(device: UsbDevice, connectionHash: Int?) {
        clear() // clear previous live data
        currentConnectionHash = connectionHash
        currentDeviceInfo = DeviceInfo(
            vid = device.vendorId,
            pid = device.productId,
            manufacturerName = device.manufacturerName,
            productName = device.productName,
            serialNumber = device.serialNumber,
            usbVersion = device.version,
            configurationCount = device.configurationCount
        )
        
        val interfaces = mutableListOf<InterfaceInfo>()
        for (i in 0 until device.configurationCount) {
            val config = device.getConfiguration(i)
            for (j in 0 until config.interfaceCount) {
                val intf = config.getInterface(j)
                val endpoints = mutableListOf<EndpointInfo>()
                for (k in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(k)
                    val dir = if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "Bulk"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "Control"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "Interrupt"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC -> "Isochronous"
                        else -> "Unknown"
                    }
                    endpoints.add(EndpointInfo(ep.address, dir, type, ep.maxPacketSize, ep.interval))
                }
                interfaces.add(
                    InterfaceInfo(
                        intf.id,
                        intf.alternateSetting,
                        intf.interfaceClass,
                        intf.interfaceSubclass,
                        intf.interfaceProtocol,
                        intf.endpointCount,
                        endpoints
                    )
                )
            }
        }
        currentInterfaces = interfaces
    }

    fun endSession() {
        val packetsList = packetQueue.toList()
        val eventsList = eventQueue.toList()
        val claimsList = claimQueue.toList()
        
        val summary = SessionSummary(
            deviceOpenPass = currentDeviceInfo != null,
            descriptorParsingPass = currentInterfaces.isNotEmpty(),
            interfaceEnumerationPass = currentInterfaces.isNotEmpty(),
            interfaceClaimPass = claimsList.any { it.result },
            endpointReadyPass = claimsList.any { it.result },
            bulkTransfers = packetsList.count { it.type.contains("Bulk", ignoreCase = true) },
            controlTransfers = packetsList.count { it.type.contains("Control", ignoreCase = true) },
            reason = if (claimsList.isEmpty()) "Session closed without claiming interface." 
                     else if (claimsList.all { !it.result }) "claimInterface() returned false."
                     else if (packetsList.isEmpty()) "Claim successful, but no transfers occurred."
                     else "Session ended normally."
        )

        val header = SessionHeader(
            appVersion = "1.0",
            androidVersion = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            kernelVersion = System.getProperty("os.version") ?: "Unknown",
            usbHostApiVersion = Build.VERSION.SDK_INT.toString()
        )

        val report = UsbSessionReport(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            header = header,
            deviceInfo = currentDeviceInfo,
            interfaces = currentInterfaces,
            timeline = eventsList,
            claims = claimsList,
            transfers = packetsList,
            connectionHash = currentConnectionHash,
            summary = summary
        )

        _sessionReports.value = _sessionReports.value + report
    }

    fun logClaimAttempt(
        interfaceNumber: Int,
        force: Boolean,
        result: Boolean,
        durationMs: Long,
        exception: String?,
        failureDiagnostic: String?
    ) {
        val stack = Thread.currentThread().stackTrace
        val caller = if (stack.size > 3) stack[3].toString() else ""
        
        val claim = ClaimAnalysis(
            timestamp = System.currentTimeMillis(),
            interfaceNumber = interfaceNumber,
            force = force,
            result = result,
            durationMs = durationMs,
            exception = exception,
            caller = caller,
            connectionHash = currentConnectionHash,
            threadName = Thread.currentThread().name,
            failureDiagnostic = failureDiagnostic
        )
        claimQueue.add(claim)
    }

    fun logPacket(
        direction: String,
        interfaceId: Int = -1,
        endpointAddress: Int,
        type: String,
        length: Int,
        data: ByteArray,
        status: String,
        durationMs: Long
    ) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).joinToString("\n") { it.toString() }
        val packet = UsbPacket(
            id = packetIdCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            direction = direction,
            interfaceId = interfaceId,
            endpointAddress = endpointAddress,
            type = type,
            length = length,
            data = data,
            status = status,
            durationMs = durationMs,
            callerStackTrace = stackTrace
        )
        packetQueue.add(packet)
        if (packetQueue.size > 10000) {
            packetQueue.poll()
        }
        
        updateStatsFromPacket(packet)
        _packets.value = packetQueue.toList().reversed()
    }

    fun logEvent(
        eventType: String, 
        details: String, 
        colorCode: String = "Blue",
        durationMs: Long = 0
    ) {
        val stack = Thread.currentThread().stackTrace
        val caller = if (stack.size > 3) stack[3].toString() else ""
        
        val event = UsbEvent(
            id = eventIdCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            details = details,
            colorCode = colorCode,
            durationMs = durationMs,
            threadName = Thread.currentThread().name,
            callerMethod = caller
        )
        eventQueue.add(event)
        if (eventQueue.size > 2000) {
            eventQueue.poll()
        }
        _events.value = eventQueue.toList().reversed()
    }

    private fun updateStatsFromPacket(packet: UsbPacket) {
        val current = _stats.value
        val newBytesSent = if (packet.direction == "OUT") current.totalBytesSent + packet.length else current.totalBytesSent
        val newBytesReceived = if (packet.direction == "IN") current.totalBytesReceived + packet.length else current.totalBytesReceived
        
        val newErrors = if (packet.status == "ERROR" || packet.status == "FAILURE") current.errors + 1 else current.errors
        val newTimeouts = if (packet.status == "TIMEOUT") current.timeouts + 1 else current.timeouts
        val newStalls = if (packet.status == "STALL") current.stallCount + 1 else current.stallCount

        _stats.value = current.copy(
            totalPackets = current.totalPackets + 1,
            totalBytesSent = newBytesSent,
            totalBytesReceived = newBytesReceived,
            errors = newErrors,
            timeouts = newTimeouts,
            stallCount = newStalls
        )
    }

    fun clear() {
        packetQueue.clear()
        eventQueue.clear()
        _packets.value = emptyList()
        _events.value = emptyList()
        _stats.value = UsbAnalyzerStats()
    }
}
