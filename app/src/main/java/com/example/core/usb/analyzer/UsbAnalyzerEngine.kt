package com.example.core.usb.analyzer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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
    val colorCode: String // "Green", "Yellow", "Red", "Blue"
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

    // Thread-safe queues
    private val packetQueue = ConcurrentLinkedQueue<UsbPacket>()
    private val eventQueue = ConcurrentLinkedQueue<UsbEvent>()

    private val packetIdCounter = AtomicInteger(0)
    private val eventIdCounter = AtomicInteger(0)

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

    fun logEvent(eventType: String, details: String, colorCode: String = "Blue") {
        val event = UsbEvent(
            id = eventIdCounter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            details = details,
            colorCode = colorCode
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
