package com.example.domain.repository

import com.example.core.usb.UsbConnectionResult
import com.example.core.usb.UsbConnectionState
import com.example.core.usb.UsbDeviceInfo
import com.example.core.usb.UsbDeviceSummary
import com.example.core.usb.UsbEvent
import com.example.core.usb.UsbHostManager
import com.example.core.usb.UsbMonitor
import kotlinx.coroutines.flow.Flow

/**
 * Production-ready repository implementing [UsbRepository] using [UsbHostManager] and [UsbMonitor].
 */
class UsbDeviceRepository(
    private val hostManager: UsbHostManager,
    private val usbMonitor: UsbMonitor
) : UsbRepository {

    init {
        usbMonitor.startMonitoring()
    }

    override val discoveredDevices: Flow<List<UsbDeviceSummary>> = hostManager.discoveredDevices

    override val selectedDeviceInfo: Flow<UsbDeviceInfo?> = hostManager.selectedDeviceInfo

    override val connectionState: Flow<UsbConnectionState> = hostManager.connectionState

    override val usbEvents: Flow<UsbEvent> = usbMonitor.usbEvents

    override fun refreshDevices() {
        hostManager.refreshDevices()
    }

    override fun hasPermission(deviceName: String): Boolean {
        return hostManager.hasPermission(deviceName)
    }

    override fun requestPermission(deviceName: String) {
        hostManager.requestPermission(deviceName)
    }

    override fun connectToDevice(deviceName: String): UsbConnectionResult {
        return hostManager.connectToDevice(deviceName)
    }

    override fun disconnect() {
        hostManager.disconnect()
    }
}
