package com.example.core.usb.analyzer

data class SessionHeader(
    val appVersion: String,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
    val kernelVersion: String,
    val usbHostApiVersion: String
)

data class DeviceInfo(
    val vid: Int,
    val pid: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val usbVersion: String?,
    val configurationCount: Int
)

data class InterfaceInfo(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpointCount: Int,
    val endpoints: List<EndpointInfo>
)

data class EndpointInfo(
    val address: Int,
    val direction: String,
    val transferType: String,
    val maxPacketSize: Int,
    val interval: Int
)

data class ClaimAnalysis(
    val timestamp: Long,
    val interfaceNumber: Int,
    val force: Boolean,
    val result: Boolean,
    val durationMs: Long,
    val exception: String?,
    val caller: String,
    val connectionHash: Int?,
    val threadName: String,
    val failureDiagnostic: String?
)

data class SessionSummary(
    val deviceOpenPass: Boolean,
    val descriptorParsingPass: Boolean,
    val interfaceEnumerationPass: Boolean,
    val interfaceClaimPass: Boolean,
    val endpointReadyPass: Boolean,
    val bulkTransfers: Int,
    val controlTransfers: Int,
    val reason: String
)

data class UsbSessionReport(
    val id: String,
    val timestamp: Long,
    val header: SessionHeader,
    val deviceInfo: DeviceInfo?,
    val interfaces: List<InterfaceInfo>,
    val timeline: List<UsbEvent>,
    val claims: List<ClaimAnalysis>,
    val transfers: List<UsbPacket>,
    val connectionHash: Int?,
    val summary: SessionSummary
)
