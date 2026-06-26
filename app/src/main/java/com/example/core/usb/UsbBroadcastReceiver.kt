package com.example.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Listens for OS broadcast intents regarding USB state changes and device permissions,
 * translating them into structured [UsbEvent] emissions.
 */
class UsbBroadcastReceiver(
    private val onUsbEvent: (UsbEvent) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device: UsbDevice? = getParcelableDevice(intent)

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                device?.let {
                    onUsbEvent(UsbEvent.Attached(it.deviceName, it.vendorId, it.productId))
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                device?.let {
                    onUsbEvent(UsbEvent.Detached(it.deviceName))
                }
            }
            UsbPermissionManager.ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                device?.let {
                    onUsbEvent(UsbEvent.PermissionStatus(it.deviceName, granted))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableDevice(intent: Intent): UsbDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
