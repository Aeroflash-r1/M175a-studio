package com.example.core.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Handles checking and asynchronously requesting USB device permission from the Android OS.
 */
class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val usbLogger: UsbLogger
) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
    }

    /**
     * Checks if the application currently has permission to access the [UsbDevice].
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Triggers the Android system dialog to request access permission for the [UsbDevice].
     */
    fun requestPermission(device: UsbDevice) {
        if (hasPermission(device)) {
            usbLogger.logPermissionGranted(device.deviceName)
            return
        }

        usbLogger.logPermissionRequested(device.deviceName)
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            // Android S+ requires specifying package or component to secure broadcasts
            setPackage(context.packageName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )

        usbManager.requestPermission(device, pendingIntent)
    }
}
