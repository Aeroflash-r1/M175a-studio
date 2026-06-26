package com.example.domain.repository

import com.example.core.usb.UsbConnectionResult
import com.example.core.usb.UsbConnectionState
import com.example.core.usb.UsbDeviceInfo
import com.example.core.usb.UsbDeviceSummary
import com.example.core.usb.UsbEvent
import com.example.domain.model.ResultWrapper
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for USB device interaction.
 */
interface UsbRepository {
    /**
     * Emits a list of all currently discovered USB device summaries.
     */
    val discoveredDevices: Flow<List<UsbDeviceSummary>>

    /**
     * Emits the currently selected/connected USB device detailed descriptors.
     */
    val selectedDeviceInfo: Flow<UsbDeviceInfo?>

    /**
     * Emits the current USB device connection state.
     */
    val connectionState: Flow<UsbConnectionState>

    /**
     * Emits real-time reactive USB events (Attached, Detached, Permission changes).
     */
    val usbEvents: Flow<UsbEvent>

    /**
     * Re-scans the USB bus.
     */
    fun refreshDevices()

    /**
     * Checks if we have permission to open the given device.
     */
    fun hasPermission(deviceName: String): Boolean

    /**
     * Requests OS permission to open the given device.
     */
    fun requestPermission(deviceName: String)

    /**
     * Attempts to connect, open, and fully parse descriptor tables on the specified device.
     */
    fun connectToDevice(deviceName: String): UsbConnectionResult

    /**
     * Disconnects from any active USB device session.
     */
    fun disconnect()
}

/**
 * Repository interface for Printer interaction.
 */
interface PrinterRepository {
    // Placeholder for future printer logic
}

/**
 * Repository interface for Scanner interaction.
 */
interface ScannerRepository {
    // Placeholder for future scanner logic
}

/**
 * Repository interface for History data.
 */
interface HistoryRepository {
    // Placeholder for future history logic
}

/**
 * Repository interface for App Settings.
 */
interface SettingsRepository {
    // Placeholder for future settings logic
}

/**
 * Repository interface for File Storage.
 */
interface StorageRepository {
    // Placeholder for future storage logic
}

/**
 * Repository interface for Diagnostics.
 */
interface DiagnosticsRepository {
    // Placeholder for future diagnostics logic
}
