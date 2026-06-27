package com.example.core.usb

import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Monitors active USB system broadcasts and exposes them reactive-style via [SharedFlow].
 */
class UsbMonitor(
    private val context: Context,
    private val usbLogger: UsbLogger
) {

    private val _usbEvents = MutableSharedFlow<UsbEvent>(extraBufferCapacity = 64)
    val usbEvents: SharedFlow<UsbEvent> = _usbEvents.asSharedFlow()

    private var broadcastReceiver: UsbBroadcastReceiver? = null
    private var isRegistered = false

    /**
     * Starts listening to USB events by registering the broadcast receiver.
     */
    @Synchronized
    fun startMonitoring() {
        if (isRegistered) return

        val receiver = UsbBroadcastReceiver { event ->
            // Log the event before emitting
            when (event) {
                is UsbEvent.Attached -> usbLogger.logAttached(
                    UsbDeviceSummary(event.deviceName, event.vendorId, event.productId, null, null, false)
                )
                is UsbEvent.Detached -> usbLogger.logDetached(event.deviceName)
                is UsbEvent.PermissionStatus -> {
                    if (event.granted) {
                        usbLogger.logPermissionGranted(event.deviceName)
                    } else {
                        usbLogger.logPermissionDenied(event.deviceName)
                    }
                }
            }
            _usbEvents.tryEmit(event)
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbPermissionManager.ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        broadcastReceiver = receiver
        isRegistered = true
        usbLogger.logDeviceConnected("System USB Monitoring Started")
    }

    /**
     * Stops listening to USB events and cleans up receiver registration.
     */
    @Synchronized
    fun stopMonitoring() {
        if (!isRegistered) return

        broadcastReceiver?.let {
            context.unregisterReceiver(it)
        }
        broadcastReceiver = null
        isRegistered = false
        usbLogger.logDeviceDisconnected("System USB Monitoring Stopped")
    }
}
