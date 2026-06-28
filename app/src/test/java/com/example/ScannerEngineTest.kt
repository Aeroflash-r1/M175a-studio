package com.example

import com.example.core.logging.Logger
import com.example.domain.repository.UsbCommunicationRepository
import com.example.scanner.engine.*
import com.example.scanner.protocol.ScannerProtocolLogger
import com.example.scanner.protocol.ScannerProtocolValidator
import com.example.scanner.protocol.ScannerProtocolRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ScannerEngineTest {

    private class FakeLogger : Logger {
        val logs = mutableListOf<String>()
        override fun d(tag: String, message: String) { logs.add("DEBUG: $message") }
        override fun i(tag: String, message: String) { logs.add("INFO: $message") }
        override fun w(tag: String, message: String, throwable: Throwable?) { logs.add("WARN: $message") }
        override fun e(tag: String, message: String, throwable: Throwable?) { logs.add("ERROR: $message") }
    }

    private class FakeUsbCommRepository : UsbCommunicationRepository {
        override val transportStats = kotlinx.coroutines.flow.MutableStateFlow(com.example.core.usb.transport.UsbTransportStats())

        override fun startSession(): Boolean = true
        override fun endSession() {}

        override suspend fun claimInterface(interfaceId: Int): Boolean = true
        override suspend fun releaseInterface(interfaceId: Int): Boolean = true

        override suspend fun writeBulk(endpointAddress: Int, data: ByteArray, timeoutMs: Int): com.example.core.usb.transport.UsbTransferResult {
            return com.example.core.usb.transport.UsbTransferResult.Success(data.size, data, 10L)
        }

        override suspend fun readBulk(endpointAddress: Int, bufferSize: Int, timeoutMs: Int): com.example.core.usb.transport.UsbTransferResult {
            return com.example.core.usb.transport.UsbTransferResult.Success(0, ByteArray(0), 10L)
        }

        override suspend fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            data: ByteArray?,
            length: Int,
            timeoutMs: Int
        ): com.example.core.usb.transport.UsbTransferResult {
            val actualData = data ?: ByteArray(0)
            return com.example.core.usb.transport.UsbTransferResult.Success(actualData.size, actualData, 10L)
        }

        override suspend fun recoverConnection(): Boolean = true

        override fun getActiveDevice(): android.hardware.usb.UsbDevice? {
            return null
        }

        override fun getDeviceInfo(): com.example.core.usb.UsbDeviceInfo? {
            return null
        }

        override suspend fun probeMassStorage(interfaceId: Int): com.example.core.usb.MsdProbeResult {
            return com.example.core.usb.MsdProbeResult(
                interfaceId = interfaceId,
                behavesAsMsd = false,
                vendor = "Fake",
                product = "Fake MSD",
                revision = "1.0",
                rawHexResponse = ""
            )
        }
    }

    private val fakeLogger = FakeLogger()
    private val engineLogger = ScannerEngineLogger(fakeLogger)
    private val protocolLogger = ScannerProtocolLogger(fakeLogger)
    private val validator = ScannerProtocolValidator(protocolLogger)
    private val protocolRepository = ScannerProtocolRepository(validator, protocolLogger)
    private val usbCommRepository = FakeUsbCommRepository()

    @Test
    fun testScannerStateMachineDeterministicTransitions() {
        val stateMachine = ScannerStateMachine(engineLogger)
        assertEquals(ScannerState.Idle, stateMachine.state.value)

        // Valid transitions
        stateMachine.transitionTo(ScannerState.Initializing)
        assertEquals(ScannerState.Initializing, stateMachine.state.value)

        stateMachine.transitionTo(ScannerState.GettingCapabilities)
        assertEquals(ScannerState.GettingCapabilities, stateMachine.state.value)

        stateMachine.transitionTo(ScannerState.CreatingJob)
        assertEquals(ScannerState.CreatingJob, stateMachine.state.value)

        // Invalid transition - should log a warning but safeguard transition
        stateMachine.transitionTo(ScannerState.Idle)
        assertEquals(ScannerState.Idle, stateMachine.state.value)
        assertTrue(fakeLogger.logs.any { it.contains("Disallowed or unusual transition") })
    }

    @Test
    fun testScannerEngineSimulationExecution() = runBlocking {
        val engine = ScannerEngine(
            usbCommRepository = usbCommRepository,
            protocolRepository = protocolRepository,
            logger = engineLogger
        )
        // Ensure simulation mode is active by default
        engine.setSimulationMode(true)

        val repository = ScannerEngineRepository(engine, engineLogger)

        // Trigger simulated scan
        val result = repository.startScan(resolutionDpi = 300, colorMode = "Color")

        // Assert success results
        if (result is ScannerResult.Failure) {
            fail("Scan failed with: ${result.message}, throwable: ${result.throwable?.message}")
        }
        assertTrue(result is ScannerResult.Success)
        val success = result as ScannerResult.Success

        // Verify generated JPEG SOI (FFD8) and EOI (FFD9) markers in the retrieved binary
        val bytes = success.imageData
        assertTrue(bytes.size >= 2)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[bytes.size - 2])
        assertEquals(0xD9.toByte(), bytes[bytes.size - 1])

        // Verify metadata fields
        assertEquals(300, success.info.resolutionDpi)
        assertEquals(2550, success.info.widthPx)
        assertEquals(3500, success.info.heightPx)
        assertTrue(success.info.fileSize > 0)
        assertNotNull(success.info.captureTime)
    }
}
