package com.example.core.usb

/**
 * Represents the permission state of a USB device.
 */
sealed interface UsbPermissionState {
    object None : UsbPermissionState
    object Pending : UsbPermissionState
    object Granted : UsbPermissionState
    data class Denied(val reason: String) : UsbPermissionState
}

/**
 * Represents the connection state of a USB device.
 */
sealed interface UsbConnectionState {
    object Disconnected : UsbConnectionState
    object Connecting : UsbConnectionState
    data class Connected(val deviceName: String) : UsbConnectionState
    data class Error(val message: String) : UsbConnectionState
}

/**
 * Represents parsed USB Endpoint information.
 */
data class UsbEndpointInfo(
    val address: Int,
    val direction: String, // "IN" or "OUT"
    val type: String,      // "BULK", "INTERRUPT", "CONTROL", "ISOCHRONOUS"
    val maxPacketSize: Int,
    val interval: Int
)

/**
 * Represents parsed USB Interface information.
 */
data class UsbInterfaceInfo(
    val id: Int,
    val alternateSetting: Int,
    val classId: Int,
    val className: String,
    val subclassId: Int,
    val protocol: Int,
    val endpoints: List<UsbEndpointInfo>
)

/**
 * Represents parsed USB Configuration information.
 */
data class UsbConfigurationInfo(
    val id: Int,
    val maxPower: Int,
    val isSelfPowered: Boolean,
    val isRemoteWakeup: Boolean,
    val interfaces: List<UsbInterfaceInfo>
)

/**
 * Represents detailed parsed information of a USB Device.
 */
data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val usbVersion: String,
    val deviceClass: Int,
    val deviceClassName: String,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val configurations: List<UsbConfigurationInfo>,
    val powerSource: String,
    val maxPower: Int
)

/**
 * A lightweight summary of a connected USB device for listings.
 */
data class UsbDeviceSummary(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val hasPermission: Boolean
)

/**
 * Represents asynchronous USB events.
 */
sealed interface UsbEvent {
    data class Attached(val deviceName: String, val vendorId: Int, val productId: Int) : UsbEvent
    data class Detached(val deviceName: String) : UsbEvent
    data class PermissionStatus(val deviceName: String, val granted: Boolean) : UsbEvent
}

/**
 * Generic USB operational errors.
 */
sealed class UsbError(val message: String, val cause: Throwable? = null) {
    class DeviceNotFound(deviceName: String) : UsbError("Device $deviceName not found.")
    class PermissionDenied(deviceName: String) : UsbError("Permission denied for device $deviceName.")
    class ConnectionFailed(deviceName: String, cause: Throwable?) : UsbError("Failed to connect to device $deviceName.", cause)
    class ParseError(message: String) : UsbError(message)
}

/**
 * Result of a USB connection operation.
 */
sealed interface UsbConnectionResult {
    data class Success(val info: UsbDeviceInfo) : UsbConnectionResult
    data class Failure(val error: UsbError) : UsbConnectionResult
}

/**
 * Result of a USB Mass Storage SCSI INQUIRY probe operation.
 */
data class MsdProbeResult(
    val interfaceId: Int,
    val behavesAsMsd: Boolean,
    val vendor: String,
    val product: String,
    val revision: String,
    val rawHexResponse: String,
    val errorDetails: String? = null
)

