package com.example.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages active USB connections, ensuring connections are safely opened, queried, and closed.
 */
class UsbConnectionManager(
    private val usbManager: UsbManager,
    private val usbLogger: UsbLogger
) {

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private var activeConnection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null

    /**
     * Attempts to open a safe connection to the [UsbDevice].
     */
    fun connect(device: UsbDevice): UsbConnectionResult {
        closeConnection()
        _connectionState.value = UsbConnectionState.Connecting

        return try {
            val connection = usbManager.openDevice(device)
            if (connection != null) {
                activeConnection = connection
                activeDevice = device
                _connectionState.value = UsbConnectionState.Connected(device.deviceName)
                usbLogger.logDeviceConnected(device.deviceName)

                val parsedInfo = UsbDeviceParser.parseDevice(device)
                usbLogger.logDeviceInfo(parsedInfo)

                UsbConnectionResult.Success(parsedInfo)
            } else {
                val error = UsbError.ConnectionFailed(device.deviceName, Exception("Android UsbManager returned a null connection."))
                _connectionState.value = UsbConnectionState.Error(error.message)
                usbLogger.logError(error)
                UsbConnectionResult.Failure(error)
            }
        } catch (e: Exception) {
            val error = UsbError.ConnectionFailed(device.deviceName, e)
            _connectionState.value = UsbConnectionState.Error(error.message)
            usbLogger.logError(error)
            UsbConnectionResult.Failure(error)
        }
    }

    /**
     * Closes any active USB device connection and cleans up local resources.
     */
    fun disconnect() {
        val device = activeDevice
        if (device != null) {
            usbLogger.logDeviceDisconnected(device.deviceName)
        }
        closeConnection()
    }

    private fun closeConnection() {
        activeConnection?.close()
        activeConnection = null
        activeDevice = null
        _connectionState.value = UsbConnectionState.Disconnected
    }

    /**
     * Retrieves the current open connection if available.
     */
    fun getActiveConnection(): UsbDeviceConnection? = activeConnection

    /**
     * Retrieves the current connected device if available.
     */
    fun getActiveDevice(): UsbDevice? = activeDevice
}
