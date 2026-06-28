package com.example.core.usb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build

/**
 * Exhaustive USB diagnostic runner.
 * Systematically tests all configurations, interfaces, alternate settings,
 * endpoints, and standard control requests to determine why communication fails.
 */
class UsbExhaustiveDiagnostic(
    private val logger: UsbPacketLogger
) {
    private val sb = java.lang.StringBuilder()

    private fun log(msg: String) {
        sb.appendLine(msg)
        logger.logRecoveryInitiated("[DIAGNOSTIC] $msg")
    }

    fun runDiagnostics(device: UsbDevice, rawConnection: UsbDeviceConnection) {
        log("=== START EXHAUSTIVE USB DIAGNOSTICS ===")

        // 7. Android USB State
        log("--- 7. Android USB State ---")
        log("Android Version: ${Build.VERSION.RELEASE}")
        log("USB Host API: ${Build.VERSION.SDK_INT}")
        log("Device Name: ${device.deviceName}")
        log("Connection Hash: ${System.identityHashCode(rawConnection)}")
        log("File Descriptor: ${rawConnection.fileDescriptor}")

        // 1. Enumerate EVERYTHING
        log("--- 1. Enumerate EVERYTHING ---")
        for (i in 0 until device.configurationCount) {
            val config = device.getConfiguration(i)
            log("Configuration Index: $i, Value: ${config.id}, Interface Count: ${config.interfaceCount}")
            for (j in 0 until config.interfaceCount) {
                val intf = config.getInterface(j)
                log("  Interface Number: ${intf.id}, Alt Setting: ${intf.alternateSetting}")
                log("  Class: ${intf.interfaceClass}, Subclass: ${intf.interfaceSubclass}, Protocol: ${intf.interfaceProtocol}")
                log("  Endpoint Count: ${intf.endpointCount}")
                for (k in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(k)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "Bulk"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "Control"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "Interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "Isochronous"
                        else -> "Unknown"
                    }
                    log("    Endpoint Address: 0x${Integer.toHexString(ep.address)}, Dir: $dir, Type: $type, MaxPacket: ${ep.maxPacketSize}, Interval: ${ep.interval}")
                }
            }
        }

        // 2. Configuration Selection (via Standard Control Request)
        log("--- 2. Configuration Selection ---")
        try {
            val buffer = ByteArray(1)
            val result = rawConnection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or 0x00,
                0x08, // GET_CONFIGURATION
                0, 0, buffer, 1, 1000
            )
            if (result >= 0) {
                log("Active Configuration (GET_CONFIGURATION): ${buffer[0].toInt() and 0xFF}")
            } else {
                log("GET_CONFIGURATION failed with result $result")
            }
        } catch (e: Exception) {
            log("GET_CONFIGURATION exception: ${e.message}")
        }

        // 3 & 4. Interface Claim Matrix and Alternate Settings
        log("--- 3 & 4. Interface Claim Matrix ---")
        var firstFailingStage = "None"
        val claimedInterfaces = mutableListOf<UsbInterface>()

        for (i in 0 until device.configurationCount) {
            val config = device.getConfiguration(i)
            for (j in 0 until config.interfaceCount) {
                val intf = config.getInterface(j)
                
                // Test force=false
                val startFalse = System.currentTimeMillis()
                val claimFalse = rawConnection.claimInterface(intf, false)
                val durationFalse = System.currentTimeMillis() - startFalse
                log("Interface ${intf.id} (Alt ${intf.alternateSetting}) force=false -> ${if (claimFalse) "SUCCESS" else "FAILED"} (${durationFalse}ms)")
                if (claimFalse) {
                    claimedInterfaces.add(intf)
                    rawConnection.releaseInterface(intf)
                } else if (firstFailingStage == "None") {
                    firstFailingStage = "Interface Claim Failed (force=false)"
                }

                // Test force=true
                val startTrue = System.currentTimeMillis()
                val claimTrue = rawConnection.claimInterface(intf, true)
                val durationTrue = System.currentTimeMillis() - startTrue
                log("Interface ${intf.id} (Alt ${intf.alternateSetting}) force=true -> ${if (claimTrue) "SUCCESS" else "FAILED"} (${durationTrue}ms)")
                if (claimTrue) {
                    if (!claimedInterfaces.contains(intf)) {
                        claimedInterfaces.add(intf)
                    }
                    rawConnection.releaseInterface(intf)
                }
            }
        }

        // 5. Endpoint Validation
        log("--- 5. Endpoint Validation ---")
        for (intf in claimedInterfaces) {
            if (rawConnection.claimInterface(intf, true)) {
                log("Validating endpoints for Interface ${intf.id} (Alt ${intf.alternateSetting})...")
                for (k in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(k)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            val res = rawConnection.bulkTransfer(ep, ByteArray(0), 0, 1000)
                            log("  Bulk OUT (0 bytes) EP 0x${Integer.toHexString(ep.address)} -> Result: $res")
                        } else {
                            val res = rawConnection.bulkTransfer(ep, ByteArray(ep.maxPacketSize), ep.maxPacketSize, 100)
                            log("  Bulk IN (timeout test) EP 0x${Integer.toHexString(ep.address)} -> Result: $res")
                        }
                    }
                }
                rawConnection.releaseInterface(intf)
            }
        }

        // 6. Control Transfers
        log("--- 6. Standard Control Transfers ---")
        val standardRequests = listOf(
            Pair("GET_STATUS (Device)", 0x00),
            Pair("GET_DESCRIPTOR (Device)", 0x06)
        )
        for (req in standardRequests) {
            try {
                val buffer = ByteArray(256)
                // For GET_DESCRIPTOR, value = 0x0100 (Device Descriptor)
                val value = if (req.second == 0x06) 0x0100 else 0
                val result = rawConnection.controlTransfer(
                    UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or 0x00,
                    req.second,
                    value, 0, buffer, buffer.size, 1000
                )
                log("Control ${req.first} -> Result: $result")
            } catch (e: Exception) {
                log("Control ${req.first} Exception: ${e.message}")
            }
        }

        // 8. Final Diagnostic
        log("--- 8. Final Diagnostic Report ---")
        if (claimedInterfaces.isEmpty()) {
            log("Conclusion: Interface claim failed entirely. Possible Android USB Host limitation or vendor initialization required.")
        } else {
            log("Conclusion: Interface claim succeeded for some interfaces. First failing stage was: $firstFailingStage")
        }
        
        log("=== END EXHAUSTIVE USB DIAGNOSTICS ===")
    }
}
