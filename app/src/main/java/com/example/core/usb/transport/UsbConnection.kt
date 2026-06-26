package com.example.core.usb.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Thread-safe container representing an active low-level Android USB connection.
 */
class UsbConnection(
    val device: UsbDevice,
    val rawConnection: UsbDeviceConnection
)
