package com.example

import com.example.core.usb.HpDeviceClassifier
import com.example.core.usb.UsbDeviceInfo
import com.example.core.usb.UsbConfigurationInfo
import com.example.core.usb.UsbInterfaceInfo
import com.example.core.usb.UsbEndpointInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HpDeviceClassifierTest {

    @Test
    fun testIsHpTargetDevice() {
        // HP LaserJet 100 color MFP M175a: VID 0x03F0, PID 0x062A
        assertTrue(HpDeviceClassifier.isHpTargetDevice(0x03F0, 0x062A))
        
        // Random device
        assertFalse(HpDeviceClassifier.isHpTargetDevice(0x1234, 0x5678))
    }

    @Test
    fun testGenerateCapabilityReportForHp() {
        val endpoints0 = listOf(
            UsbEndpointInfo(0x03, "OUT", "Bulk", 64, 0),
            UsbEndpointInfo(0x83, "IN", "Bulk", 64, 0),
            UsbEndpointInfo(0x84, "IN", "Interrupt", 16, 10)
        )
        val intf0 = UsbInterfaceInfo(
            id = 0,
            alternateSetting = 0,
            classId = 0xFF,
            className = "Vendor Specific",
            subclassId = 0,
            protocol = 0,
            endpoints = endpoints0
        )

        val endpoints1 = listOf(
            UsbEndpointInfo(0x01, "OUT", "Bulk", 64, 0),
            UsbEndpointInfo(0x81, "IN", "Bulk", 64, 0)
        )
        val intf1 = UsbInterfaceInfo(
            id = 1,
            alternateSetting = 0,
            classId = 0x07,
            className = "Printer Class",
            subclassId = 0,
            protocol = 0,
            endpoints = endpoints1
        )

        val endpoints2 = listOf(
            UsbEndpointInfo(0x09, "OUT", "Bulk", 64, 0),
            UsbEndpointInfo(0x89, "IN", "Bulk", 64, 0),
            UsbEndpointInfo(0x8A, "IN", "Interrupt", 16, 10)
        )
        val intf2 = UsbInterfaceInfo(
            id = 2,
            alternateSetting = 0,
            classId = 0xFF,
            className = "Vendor Specific",
            subclassId = 0,
            protocol = 0,
            endpoints = endpoints2
        )

        val config = UsbConfigurationInfo(
            id = 1,
            maxPower = 400,
            isSelfPowered = true,
            isRemoteWakeup = false,
            interfaces = listOf(intf0, intf1, intf2)
        )
        
        val hpDeviceInfo = UsbDeviceInfo(
            deviceName = "hp_m175a",
            vendorId = 0x03F0,
            productId = 0x062A,
            manufacturerName = "HP",
            productName = "HP LaserJet 100 color MFP M175a",
            serialNumber = "CN123456",
            usbVersion = "2.00",
            deviceClass = 0,
            deviceClassName = "Miscellaneous",
            deviceSubclass = 0,
            deviceProtocol = 0,
            configurations = listOf(config),
            powerSource = "Self-Powered",
            maxPower = 400
        )

        val report = HpDeviceClassifier.generateCapabilityReport(hpDeviceInfo)

        assertTrue(report.isDeviceSupported)
        assertTrue(report.isScannerInterfacePresent)
        assertTrue(report.isPrinterInterfacePresent)
        assertTrue(report.isVendorInterfacePresent)
        assertTrue(report.areRequiredEndpointsPresent)
        assertTrue(report.isUsbLayoutValid)
        assertTrue(report.isCommunicationReady)
    }

    @Test
    fun testGenerateDiagnosticsForHp() {
        val endpoints0 = listOf(
            UsbEndpointInfo(0x03, "OUT", "Bulk", 64, 0),
            UsbEndpointInfo(0x83, "IN", "Bulk", 64, 0),
            UsbEndpointInfo(0x84, "IN", "Interrupt", 16, 10)
        )
        val intf0 = UsbInterfaceInfo(
            id = 0,
            alternateSetting = 0,
            classId = 0xFF,
            className = "Vendor Specific",
            subclassId = 0,
            protocol = 0,
            endpoints = endpoints0
        )

        val endpoints1 = listOf(
            UsbEndpointInfo(0x01, "OUT", "Bulk", 64, 0),
            UsbEndpointInfo(0x81, "IN", "Bulk", 64, 0)
        )
        val intf1 = UsbInterfaceInfo(
            id = 1,
            alternateSetting = 0,
            classId = 0x07,
            className = "Printer Class",
            subclassId = 0,
            protocol = 0,
            endpoints = endpoints1
        )

        val endpoints2 = listOf(
            UsbEndpointInfo(0x09, "OUT", "Bulk", 64, 0),
            UsbEndpointInfo(0x89, "IN", "Bulk", 64, 0),
            UsbEndpointInfo(0x8A, "IN", "Interrupt", 16, 10)
        )
        val intf2 = UsbInterfaceInfo(
            id = 2,
            alternateSetting = 0,
            classId = 0xFF,
            className = "Vendor Specific",
            subclassId = 0,
            protocol = 0,
            endpoints = endpoints2
        )

        val config = UsbConfigurationInfo(
            id = 1,
            maxPower = 400,
            isSelfPowered = true,
            isRemoteWakeup = false,
            interfaces = listOf(intf0, intf1, intf2)
        )
        
        val hpDeviceInfo = UsbDeviceInfo(
            deviceName = "hp_m175a",
            vendorId = 0x03F0,
            productId = 0x062A,
            manufacturerName = "HP",
            productName = "HP LaserJet 100 color MFP M175a",
            serialNumber = "CN123456",
            usbVersion = "2.00",
            deviceClass = 0,
            deviceClassName = "Miscellaneous",
            deviceSubclass = 0,
            deviceProtocol = 0,
            configurations = listOf(config),
            powerSource = "Self-Powered",
            maxPower = 400
        )

        val diags = HpDeviceClassifier.generateDiagnostics(hpDeviceInfo, true, "Connected")

        assertTrue(diags.usbPermission)
        assertEquals("Connected", diags.connectionState)
        assertTrue(diags.deviceHealth.contains("Excellent"))
        assertTrue(diags.interfaceValidation.contains("Pass"))
        assertTrue(diags.endpointValidation.contains("Pass"))
        assertEquals("Ready for Communication", diags.protocolReadiness)
    }

    @Test
    fun testEndpointPurposeMapping() {
        val scannerOutPurpose = HpDeviceClassifier.getEndpointPurpose(0, 0x03)
        assertTrue(scannerOutPurpose.contains("Scanner Command Channel"))

        val scannerInPurpose = HpDeviceClassifier.getEndpointPurpose(0, 0x83)
        assertTrue(scannerInPurpose.contains("Scanner Response Channel"))

        val scannerIntPurpose = HpDeviceClassifier.getEndpointPurpose(0, 0x84)
        assertTrue(scannerIntPurpose.contains("Scanner Interrupt Channel"))

        val printerOutPurpose = HpDeviceClassifier.getEndpointPurpose(1, 0x01)
        assertTrue(printerOutPurpose.contains("Printer Data Channel"))

        val printerInPurpose = HpDeviceClassifier.getEndpointPurpose(1, 0x81)
        assertTrue(printerInPurpose.contains("Printer Status Channel"))

        val vendorOutPurpose = HpDeviceClassifier.getEndpointPurpose(2, 0x09)
        assertTrue(vendorOutPurpose.contains("Vendor Channel"))

        val vendorIntPurpose = HpDeviceClassifier.getEndpointPurpose(2, 0x8A)
        assertTrue(vendorIntPurpose.contains("Vendor Interrupt Channel"))
    }
}
