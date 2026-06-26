package com.example.core.usb

import com.example.core.logging.Logger

/**
 * Dedicated structured logger for the USB Generic Host Framework.
 */
class UsbLogger(private val logger: Logger) {

    private val tag = "UsbFramework"

    fun logAttached(deviceSummary: UsbDeviceSummary) {
        logger.i(tag, "📥 USB Device Attached: ${deviceSummary.productName ?: "Unknown"} [VID: ${formatHex(deviceSummary.vendorId)}, PID: ${formatHex(deviceSummary.productId)}]")
    }

    fun logDetached(deviceName: String) {
        logger.i(tag, "📤 USB Device Detached: $deviceName")
    }

    fun logPermissionRequested(deviceName: String) {
        logger.d(tag, "🔑 Permission requested for device: $deviceName")
    }

    fun logPermissionGranted(deviceName: String) {
        logger.i(tag, "✅ Permission GRANTED for device: $deviceName")
    }

    fun logPermissionDenied(deviceName: String) {
        logger.w(tag, "❌ Permission DENIED for device: $deviceName")
    }

    fun logDeviceConnected(deviceName: String) {
        logger.i(tag, "🔌 Device connected and connection opened: $deviceName")
    }

    fun logDeviceDisconnected(deviceName: String) {
        logger.i(tag, "🔌 Device connection closed: $deviceName")
    }

    fun logDeviceInfo(info: UsbDeviceInfo) {
        val sb = StringBuilder()
        sb.append("\n======================================================\n")
        sb.append("Device Name: ${info.deviceName}\n")
        sb.append("Vendor ID: ${formatHex(info.vendorId)}\n")
        sb.append("Product ID: ${formatHex(info.productId)}\n")
        sb.append("Manufacturer: ${info.manufacturerName ?: "N/A"}\n")
        sb.append("Product: ${info.productName ?: "N/A"}\n")
        sb.append("Serial: ${info.serialNumber ?: "N/A"}\n")
        sb.append("Class: ${info.deviceClassName} (0x${Integer.toHexString(info.deviceClass)})\n")
        sb.append("Power Source: ${info.powerSource} (Max Power: ${info.maxPower}mA)\n")
        sb.append("------------------------------------------------------\n")
        
        info.configurations.forEach { config ->
            sb.append("  Configuration ${config.id}:\n")
            sb.append("    MaxPower: ${config.maxPower}mA\n")
            sb.append("    SelfPowered: ${config.isSelfPowered}\n")
            sb.append("    RemoteWakeup: ${config.isRemoteWakeup}\n")
            
            config.interfaces.forEach { interf ->
                sb.append("    Interface ${interf.id} (Alt: ${interf.alternateSetting}):\n")
                sb.append("      Class: ${interf.className} (0x${Integer.toHexString(interf.classId)})\n")
                sb.append("      Subclass: 0x${Integer.toHexString(interf.subclassId)}\n")
                sb.append("      Protocol: 0x${Integer.toHexString(interf.protocol)}\n")
                
                interf.endpoints.forEach { ep ->
                    sb.append("      Endpoint 0x${Integer.toHexString(ep.address)}:\n")
                    sb.append("        Direction: ${ep.direction}\n")
                    sb.append("        Type: ${ep.type}\n")
                    sb.append("        MaxPacketSize: ${ep.maxPacketSize} bytes\n")
                    sb.append("        Interval: ${ep.interval}ms\n")
                }
            }
        }
        sb.append("======================================================\n")
        logger.i(tag, sb.toString())
    }

    fun logError(error: UsbError) {
        logger.e(tag, "🚨 USB Error: ${error.message}", error.cause)
    }

    private fun formatHex(value: Int): String {
        return "0x" + String.format("%04X", value)
    }
}
