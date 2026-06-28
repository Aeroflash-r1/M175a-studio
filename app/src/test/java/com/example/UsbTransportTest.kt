package com.example

import com.example.core.logging.Logger
import com.example.core.usb.transport.*
import org.junit.Assert.*
import org.junit.Test

class UsbTransportTest {

    private class FakeLogger : Logger {
        val messages = mutableListOf<String>()

        override fun d(tag: String, message: String) {
            messages.add("DEBUG: $tag - $message")
        }

        override fun i(tag: String, message: String) {
            messages.add("INFO: $tag - $message")
        }

        override fun w(tag: String, message: String, throwable: Throwable?) {
            messages.add("WARN: $tag - $message")
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            messages.add("ERROR: $tag - $message")
        }
    }

    @Test
    fun testUsbTransferResultEquality() {
        val success1 = UsbTransferResult.Success(10, byteArrayOf(1, 2, 3), 100)
        val success2 = UsbTransferResult.Success(10, byteArrayOf(1, 2, 3), 100)
        val success3 = UsbTransferResult.Success(5, byteArrayOf(1, 2), 100)

        assertEquals(success1, success2)
        assertNotEquals(success1, success3)
        assertEquals(success1.hashCode(), success2.hashCode())
    }

    @Test
    fun testUsbTransferRequestEquality() {
        val bulk1 = UsbTransferRequest.Bulk(0x01, byteArrayOf(0x55, 0xAA.toByte()), 2, 1000)
        val bulk2 = UsbTransferRequest.Bulk(0x01, byteArrayOf(0x55, 0xAA.toByte()), 2, 1000)
        val bulk3 = UsbTransferRequest.Bulk(0x02, byteArrayOf(0x55), 1, 1000)

        assertEquals(bulk1, bulk2)
        assertNotEquals(bulk1, bulk3)
        assertEquals(bulk1.hashCode(), bulk2.hashCode())
    }

    @Test
    fun testUsbPacketLoggerOutputs() {
        val fakeLogger = FakeLogger()
        val analyzerEngine = com.example.core.usb.analyzer.UsbAnalyzerEngine()
        val transportLogger = UsbPacketLogger(fakeLogger, analyzerEngine)

        // transportLogger.logConnectionOpen("test_device")
        transportLogger.logInterfaceClaimed(1)
        transportLogger.logEndpointOpened(0x02, "Bulk", "OUT")
        transportLogger.logBulkWrite(0x02, 10)
        transportLogger.logTransferSuccess("BULK_WRITE", 0x02, 10, 50)
        // transportLogger.logConnectionClose("test_device")

        assertEquals(4, fakeLogger.messages.size)
        // assertTrue(fakeLogger.messages[0].contains("CONNECTION OPEN"))
        assertTrue(fakeLogger.messages[0].contains("INTERFACE CLAIMED"))
        assertTrue(fakeLogger.messages[1].contains("ENDPOINT OPEN"))
        assertTrue(fakeLogger.messages[2].contains("BULK WRITE REQUEST"))
        assertTrue(fakeLogger.messages[3].contains("TRANSFER SUCCESS"))
        // assertTrue(fakeLogger.messages[5].contains("CONNECTION CLOSE"))
    }

    @Test
    fun testUsbTransportStatsDefaults() {
        val stats = UsbTransportStats()
        assertFalse(stats.isCommunicationReady)
        assertFalse(stats.isConnectionOpen)
        assertTrue(stats.claimedInterfaces.isEmpty())
        assertTrue(stats.readyEndpoints.isEmpty())
        assertEquals("Idle", stats.transferStatus)
        assertEquals("None", stats.lastTransferDetails)
        assertEquals(0L, stats.bytesSent)
        assertEquals(0L, stats.bytesReceived)
        assertEquals(0L, stats.connectionDurationSec)
        assertEquals("Normal", stats.transportHealth)
    }
}
