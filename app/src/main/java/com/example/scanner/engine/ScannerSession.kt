package com.example.scanner.engine

import com.example.domain.repository.UsbCommunicationRepository
import com.example.scanner.protocol.*
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * Handles communication session operations over Interface 0 for the HP LaserJet M175a.
 * Supports both physical USB bulk endpoints and dynamic protocol-compliant simulations.
 */
class ScannerSession(
    private val usbCommRepository: UsbCommunicationRepository,
    private val protocolRepository: ScannerProtocolRepository,
    private val logger: ScannerEngineLogger,
    private val isSimulation: Boolean
) {

    /**
     * Initializes and claims Interface 0 for scanning operations.
     */
    suspend fun open(): Boolean {
        if (isSimulation) {
            logger.logRecovery("ℹ️ [SESSION] Initializing high-fidelity simulated scanner session.")
            return true
        }

        logger.logRecovery("🔌 [SESSION] Opening USB scanner session. Claiming Interface 0...")
        val sessionStarted = usbCommRepository.startSession()
        if (!sessionStarted) {
            logger.logError("Failed to start USB transport session")
            return false
        }

        val claimed = usbCommRepository.claimInterface(0)
        if (!claimed) {
            logger.logError("Failed to claim Scanner interface 0")
            usbCommRepository.endSession()
            return false
        }

        return true
    }

    /**
     * Releases Interface 0 and closes the USB session.
     */
    suspend fun close() {
        if (isSimulation) {
            logger.logRecovery("ℹ️ [SESSION] Closing simulated scanner session.")
            return
        }

        logger.logRecovery("🔌 [SESSION] Closing USB scanner session. Releasing Interface 0...")
        usbCommRepository.releaseInterface(0)
        usbCommRepository.endSession()
    }

    /**
     * Sends a SOAP XML request and returns the parsed SOAP response XML string.
     */
    suspend fun sendRequest(soapXml: String): String {
        if (isSimulation) {
            delay(400) // Simulate processing latency
            return generateSimulatedResponse(soapXml)
        }

        logger.logRecovery("📡 [USB OUT] Transferring ${soapXml.length} characters of SOAP XML...")
        val writeResult = usbCommRepository.writeBulk(0x03, soapXml.toByteArray(Charsets.UTF_8))
        if (writeResult !is com.example.core.usb.transport.UsbTransferResult.Success) {
            throw Exception("USB bulk write failed: $writeResult")
        }

        logger.logRecovery("📡 [USB IN] Awaiting XML response payload from EP3...")
        val readResult = usbCommRepository.readBulk(0x83, 65536)
        if (readResult !is com.example.core.usb.transport.UsbTransferResult.Success) {
            throw Exception("USB bulk read failed: $readResult")
        }

        val response = String(readResult.data, Charsets.UTF_8)
        return response
    }

    /**
     * Sends a RetrieveImage request and receives the multi-part DIME binary stream.
     */
    suspend fun sendDimeRequest(soapXml: String): ByteArray {
        if (isSimulation) {
            delay(800) // Simulate image reading latency
            return generateSimulatedDimeResponse(soapXml)
        }

        logger.logRecovery("📡 [USB OUT] Sending SOAP RetrieveImage request...")
        val writeResult = usbCommRepository.writeBulk(0x03, soapXml.toByteArray(Charsets.UTF_8))
        if (writeResult !is com.example.core.usb.transport.UsbTransferResult.Success) {
            throw Exception("USB bulk write failed: $writeResult")
        }

        logger.logRecovery("📡 [USB IN] Fetching multi-megabyte binary DIME stream...")
        val largeBuffer = 4 * 1024 * 1024 // 4MB buffer for scan payload
        val readResult = usbCommRepository.readBulk(0x83, largeBuffer, timeoutMs = 15000)
        if (readResult !is com.example.core.usb.transport.UsbTransferResult.Success) {
            throw Exception("USB bulk read failed: $readResult")
        }

        return readResult.data
    }

    private fun generateSimulatedResponse(requestXml: String): String {
        return when {
            requestXml.contains("GetScannerElements") -> {
                """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                  <soap:Header>
                    <wsa:Action xmlns:wsa="http://www.w3.org/2005/08/addressing">http://schemas.hp.com/imaging/escl/2008/02/05/GetScannerElementsResponse</wsa:Action>
                  </soap:Header>
                  <soap:Body>
                    <wscn:GetScannerElementsResponse>
                      <wscn:ScannerElements>
                        <wscn:ScannerStatus>Idle</wscn:ScannerStatus>
                        <wscn:ScannerInfo>
                          <wscn:Manufacturer>HP</wscn:Manufacturer>
                          <wscn:Model>LaserJet 100 color MFP M175a</wscn:Model>
                          <wscn:SerialNumber>CN667788</wscn:SerialNumber>
                        </wscn:ScannerInfo>
                        <wscn:ScannerCapabilities>
                          <wscn:SupportedResolutions>
                            <wscn:Resolution>
                              <wscn:Width>300</wscn:Width>
                              <wscn:Height>300</wscn:Height>
                            </wscn:Resolution>
                            <wscn:Resolution>
                              <wscn:Width>600</wscn:Width>
                              <wscn:Height>600</wscn:Height>
                            </wscn:Resolution>
                          </wscn:SupportedResolutions>
                          <wscn:SupportedColorModes>
                            <wscn:ColorMode>Color</wscn:ColorMode>
                            <wscn:ColorMode>Grayscale</wscn:ColorMode>
                          </wscn:SupportedColorModes>
                        </wscn:ScannerCapabilities>
                        <wscn:AdfLoaded>false</wscn:AdfLoaded>
                      </wscn:ScannerElements>
                    </wscn:GetScannerElementsResponse>
                  </soap:Body>
                </soap:Envelope>
                """.trimIndent()
            }
            requestXml.contains("CreateScanJob") -> {
                val jobId = "job-sim-${(1000..9999).random()}"
                """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                  <soap:Header>
                    <wsa:Action xmlns:wsa="http://www.w3.org/2005/08/addressing">http://schemas.hp.com/imaging/escl/2008/02/05/CreateScanJobResponse</wsa:Action>
                  </soap:Header>
                  <soap:Body>
                    <wscn:CreateScanJobResponse>
                      <wscn:JobId>$jobId</wscn:JobId>
                      <wscn:JobStatus>Started</wscn:JobStatus>
                    </wscn:CreateScanJobResponse>
                  </soap:Body>
                </soap:Envelope>
                """.trimIndent()
            }
            requestXml.contains("GetJobInfo") || requestXml.contains("JobSummaryType") -> {
                val jobIdMatch = "JobId>([^<]+)".toRegex().find(requestXml)
                val jobId = jobIdMatch?.groupValues?.get(1) ?: "job-sim-default"
                """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                  <soap:Header>
                    <wsa:Action xmlns:wsa="http://www.w3.org/2005/08/addressing">http://schemas.hp.com/imaging/escl/2008/02/05/GetJobInfoResponse</wsa:Action>
                  </soap:Header>
                  <soap:Body>
                    <wscn:GetJobInfoResponse>
                      <wscn:JobSummary>
                        <wscn:JobId>$jobId</wscn:JobId>
                        <wscn:JobState>Completed</wscn:JobState>
                        <wscn:JobStateReason>None</wscn:JobStateReason>
                        <wscn:PagesCompleted>1</wscn:PagesCompleted>
                      </wscn:JobSummary>
                    </wscn:GetJobInfoResponse>
                  </soap:Body>
                </soap:Envelope>
                """.trimIndent()
            }
            requestXml.contains("DestroyScanJob") -> {
                """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                  <soap:Header>
                    <wsa:Action xmlns:wsa="http://www.w3.org/2005/08/addressing">http://schemas.hp.com/imaging/escl/2008/02/05/DestroyScanJobResponse</wsa:Action>
                  </soap:Header>
                  <soap:Body>
                    <wscn:DestroyScanJobResponse>
                      <wscn:JobId>job-sim-default</wscn:JobId>
                      <wscn:Success>true</wscn:Success>
                    </wscn:DestroyScanJobResponse>
                  </soap:Body>
                </soap:Envelope>
                """.trimIndent()
            }
            else -> {
                """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                  <soap:Header>
                    <wsa:Action xmlns:wsa="http://www.w3.org/2005/08/addressing">http://schemas.hp.com/imaging/escl/2008/02/05/DestroyScanJobResponse</wsa:Action>
                  </soap:Header>
                  <soap:Body>
                    <wscn:DestroyScanJobResponse>
                      <wscn:JobId>generic</wscn:JobId>
                      <wscn:Success>true</wscn:Success>
                    </wscn:DestroyScanJobResponse>
                  </soap:Body>
                </soap:Envelope>
                """.trimIndent()
            }
        }
    }

    private fun generateSimulatedDimeResponse(requestXml: String): ByteArray {
        val jobIdMatch = "JobId>([^<]+)".toRegex().find(requestXml)
        val jobId = jobIdMatch?.groupValues?.get(1) ?: "job-sim-default"

        // Discover scan settings by scanning request XML
        val resolution = if (requestXml.contains("600")) 600 else 300
        val colorMode = if (requestXml.contains("Grayscale") || requestXml.contains("Gray")) "Grayscale" else "Color"

        // Generate a beautiful programmatic JPEG!
        val jpegBytes = generateMockJpeg(resolution, colorMode)

        val soapXml = """
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
          <soap:Header>
            <wsa:Action xmlns:wsa="http://www.w3.org/2005/08/addressing">http://schemas.hp.com/imaging/escl/2008/02/05/RetrieveImageResponse</wsa:Action>
          </soap:Header>
          <soap:Body>
            <wscn:RetrieveImageResponse>
              <wscn:JobId>$jobId</wscn:JobId>
            </wscn:RetrieveImageResponse>
          </soap:Body>
        </soap:Envelope>
        """.trimIndent()

        // Wrap into DIME
        val imagePart = DimePart(
            id = "scanned-image-0",
            type = "image/jpeg",
            data = jpegBytes,
            typeScheme = DimeTypeScheme.MIME
        )

        return protocolRepository.buildDimeMessage(soapXml, listOf(imagePart))
    }

    private fun generateMockJpeg(resolutionDpi: Int, colorMode: String): ByteArray {
        return try {
            val width = if (resolutionDpi == 600) 1200 else 600
            val height = if (resolutionDpi == 600) 1600 else 800
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            val paint = android.graphics.Paint()
            paint.color = if (colorMode.contains("Gray", ignoreCase = true)) 0xFFE0E0E0.toInt() else 0xFFE0F7FA.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            paint.style = android.graphics.Paint.Style.STROKE
            paint.color = 0xFF006064.toInt()
            paint.strokeWidth = 10f
            canvas.drawRect(15f, 15f, width - 15f, height - 15f, paint)

            paint.strokeWidth = 2f
            paint.color = 0xFFB2EBF2.toInt()
            for (i in 0..width step 80) {
                canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), paint)
            }
            for (i in 0..height step 80) {
                canvas.drawLine(0f, i.toFloat(), width.toFloat(), i.toFloat(), paint)
            }

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFF006064.toInt()
            paint.textSize = 28f
            paint.isFakeBoldText = true
            canvas.drawText("HP LASERJET M175A SCANNER ENGINE", 50f, 100f, paint)

            paint.textSize = 20f
            paint.isFakeBoldText = false
            canvas.drawText("RESOLUTION: $resolutionDpi DPI", 50f, 160f, paint)
            canvas.drawText("COLOR SPACE: $colorMode", 50f, 200f, paint)
            canvas.drawText("TIMESTAMP: ${LocalDateTime.now()}", 50f, 240f, paint)
            canvas.drawText("STATUS: ACQUIRED VIA DIGITAL MULTIPART STREAM", 50f, 280f, paint)

            paint.color = 0xFF00796B.toInt()
            canvas.drawRect(50f, 320f, 320f, 400f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = 22f
            paint.isFakeBoldText = true
            canvas.drawText("SCAN VERIFIED ✓", 75f, 370f, paint)

            val bos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
            bos.toByteArray()
        } catch (e: Throwable) {
            // Fallback for non-Android JVM environments
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte())
        }
    }
}
