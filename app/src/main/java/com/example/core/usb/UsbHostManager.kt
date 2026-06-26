package com.example.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cohesive controller coordinating USB discovery, permission flows, and active session configurations.
 */
class UsbHostManager(
    private val usbManager: UsbManager,
    private val permissionManager: UsbPermissionManager,
    private val connectionManager: UsbConnectionManager,
    private val usbLogger: UsbLogger
) {

    private val _discoveredDevices = MutableStateFlow<List<UsbDeviceSummary>>(emptyList())
    val discoveredDevices: StateFlow<List<UsbDeviceSummary>> = _discoveredDevices.asStateFlow()

    private val _selectedDeviceInfo = MutableStateFlow<UsbDeviceInfo?>(null)
    val selectedDeviceInfo: StateFlow<UsbDeviceInfo?> = _selectedDeviceInfo.asStateFlow()

    val connectionState: StateFlow<UsbConnectionState> = connectionManager.connectionState

    init {
        refreshDevices()
    }

    /**
     * Re-scans the USB bus and updates the published list of available devices.
     */
    fun refreshDevices() {
        try {
            val deviceMap = usbManager.deviceList
            val summaries = deviceMap.values.map { device ->
                UsbDeviceSummary(
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    manufacturerName = device.manufacturerName,
                    productName = device.productName,
                    hasPermission = permissionManager.hasPermission(device)
                )
            }
            _discoveredDevices.value = summaries
        } catch (e: Exception) {
            usbLogger.logError(UsbError.ParseError("Failed to enumerate USB devices: ${e.message}"))
            _discoveredDevices.value = emptyList()
        }
    }

    /**
     * Checks if we have OS permission to open the given device.
     */
    fun hasPermission(deviceName: String): Boolean {
        val device = findNativeDevice(deviceName) ?: return false
        return permissionManager.hasPermission(device)
    }

    /**
     * Requests OS permission to open the given device.
     */
    fun requestPermission(deviceName: String) {
        val device = findNativeDevice(deviceName)
        if (device != null) {
            permissionManager.requestPermission(device)
        } else {
            usbLogger.logError(UsbError.DeviceNotFound(deviceName))
        }
    }

    /**
     * Attempts to connect, open, and fully parse descriptor tables on the specified device.
     */
    fun connectToDevice(deviceName: String): UsbConnectionResult {
        val device = findNativeDevice(deviceName)
        if (device == null) {
            val error = UsbError.DeviceNotFound(deviceName)
            usbLogger.logError(error)
            return UsbConnectionResult.Failure(error)
        }

        if (!permissionManager.hasPermission(device)) {
            val error = UsbError.PermissionDenied(deviceName)
            usbLogger.logError(error)
            return UsbConnectionResult.Failure(error)
        }

        val result = connectionManager.connect(device)
        if (result is UsbConnectionResult.Success) {
            _selectedDeviceInfo.value = result.info
        } else {
            _selectedDeviceInfo.value = null
        }
        return result
    }

    /**
     * Disconnects from any active USB device session.
     */
    fun disconnect() {
        connectionManager.disconnect()
        _selectedDeviceInfo.value = null
    }

    /**
     * Helper to lookup native [UsbDevice] by system path/name.
     */
    private fun findNativeDevice(deviceName: String): UsbDevice? {
        return usbManager.deviceList[deviceName]
    }
}
