package com.example.core.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants

/**
 * Utility parser that maps Android SDK USB constructs to clean, human-readable domain representation models.
 */
object UsbDeviceParser {

    /**
     * Parses a [UsbDevice] into our structured [UsbDeviceInfo].
     */
    fun parseDevice(device: UsbDevice): UsbDeviceInfo {
        val configurations = mutableListOf<UsbConfigurationInfo>()
        for (i in 0 until device.configurationCount) {
            val config = device.getConfiguration(i)
            configurations.add(parseConfiguration(config))
        }

        val totalMaxPower = configurations.maxOfOrNull { it.maxPower } ?: 0
        val powerSource = if (configurations.any { it.isSelfPowered }) "Self-Powered" else "Bus-Powered"

        return UsbDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = device.manufacturerName,
            productName = device.productName,
            serialNumber = device.serialNumber,
            usbVersion = "2.0 (Detected)", // Default fallback for USB 2.0 Host API
            deviceClass = device.deviceClass,
            deviceClassName = getUsbClassName(device.deviceClass),
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            configurations = configurations,
            powerSource = powerSource,
            maxPower = totalMaxPower
        )
    }

    /**
     * Parses a [UsbConfiguration] into [UsbConfigurationInfo].
     */
    private fun parseConfiguration(config: UsbConfiguration): UsbConfigurationInfo {
        val interfaces = mutableListOf<UsbInterfaceInfo>()
        for (i in 0 until config.interfaceCount) {
            val interf = config.getInterface(i)
            interfaces.add(parseInterface(interf))
        }

        return UsbConfigurationInfo(
            id = config.id,
            maxPower = config.maxPower,
            isSelfPowered = config.isSelfPowered,
            isRemoteWakeup = config.isRemoteWakeup,
            interfaces = interfaces
        )
    }

    /**
     * Parses a [UsbInterface] into [UsbInterfaceInfo].
     */
    private fun parseInterface(interf: UsbInterface): UsbInterfaceInfo {
        val endpoints = mutableListOf<UsbEndpointInfo>()
        for (i in 0 until interf.endpointCount) {
            val endpoint = interf.getEndpoint(i)
            endpoints.add(parseEndpoint(endpoint))
        }

        return UsbInterfaceInfo(
            id = interf.id,
            alternateSetting = interf.alternateSetting,
            classId = interf.interfaceClass,
            className = getUsbClassName(interf.interfaceClass),
            subclassId = interf.interfaceSubclass,
            protocol = interf.interfaceProtocol,
            endpoints = endpoints
        )
    }

    /**
     * Parses a [UsbEndpoint] into [UsbEndpointInfo].
     */
    private fun parseEndpoint(endpoint: UsbEndpoint): UsbEndpointInfo {
        val direction = when (endpoint.direction) {
            UsbConstants.USB_DIR_IN -> "IN"
            UsbConstants.USB_DIR_OUT -> "OUT"
            else -> "UNKNOWN"
        }

        val type = when (endpoint.type) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            else -> "UNKNOWN"
        }

        return UsbEndpointInfo(
            address = endpoint.address,
            direction = direction,
            type = type,
            maxPacketSize = endpoint.maxPacketSize,
            interval = endpoint.interval
        )
    }

    /**
     * Maps a USB Class ID to its official industry-standard name representation.
     */
    fun getUsbClassName(classId: Int): String {
        return when (classId) {
            0x00 -> "Use class info from Interface"
            0x01 -> "Audio"
            0x02 -> "Communications and CDC Control"
            0x03 -> "HID (Human Interface Device)"
            0x05 -> "Physical"
            0x06 -> "Image (Scanner / Camera)"
            0x07 -> "Printer"
            0x08 -> "Mass Storage"
            0x09 -> "Hub"
            0x0a -> "CDC-Data"
            0x0b -> "Smart Card"
            0x0d -> "Content Security"
            0x0e -> "Video"
            0x0f -> "Personal Healthcare"
            0xdc -> "Diagnostic Device"
            0xe0 -> "Wireless Controller"
            0xef -> "Miscellaneous"
            0xfe -> "Application Specific"
            0xff -> "Vendor Specific"
            else -> "Unknown Class ($classId)"
        }
    }
}
