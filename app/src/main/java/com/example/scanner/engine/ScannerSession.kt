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

    private var scannerInterfaceId: Int = -1
    private var bulkInEndpoint: Int = -1
    private var bulkOutEndpoint: Int = -1

    /**
     * Initializes and dynamically discovers the scanner interface and endpoints.
     */
    suspend fun open(): Boolean {
        if (isSimulation) {
            logger.logRecovery("ℹ️ [SESSION] Initializing high-fidelity simulated scanner session.")
            return true
        }

        logger.logRecovery("🔌 [SESSION] Opening USB scanner session. Starting dynamic discovery and active protocol validation...")
        val sessionStarted = usbCommRepository.startSession()
        if (!sessionStarted) {
            logger.logError("❌ Failed to start USB transport session. No device connection available.")
            return false
        }

        if (!discoverAndProbeScannerInterface()) {
            logger.logError("❌ Could not open communications session to scanner interface: Active probe validation failed for all candidates.")
            usbCommRepository.endSession()
            return false
        }

        return true
    }

    private suspend fun discoverAndProbeScannerInterface(): Boolean {
        val deviceInfo = usbCommRepository.getDeviceInfo()
        if (deviceInfo == null) {
            logger.logError("❌ Cannot perform discovery: Device info is null.")
            return false
        }

        logger.logRecovery("🔍 Enumerating USB interfaces for device: ${deviceInfo.productName ?: "HP Multifunction Device"}...")

        val allInterfaces = deviceInfo.configurations.flatMap { it.interfaces }
        
        // Detailed logging of every interface and endpoint found
        allInterfaces.forEach { interf ->
            logger.logRecovery("📊 Discovered Intf ${interf.id}: Class=0x${Integer.toHexString(interf.classId)} (${interf.className}), Subclass=${interf.subclassId}, Protocol=${interf.protocol}, Endpoints=${interf.endpoints.size}")
            interf.endpoints.forEach { ep ->
                logger.logRecovery("   ↳ EP 0x${Integer.toHexString(ep.address)} [${ep.direction}]: Type=${ep.type}, MaxPacketSize=${ep.maxPacketSize}")
            }
        }

        // Rule 2: Protocol-based filtering. Reject Class 0x08 (Mass Storage) and 0x07 (Printer).
        // Candidate interfaces must contain at least one Bulk IN and at least one Bulk OUT endpoint.
        val candidates = allInterfaces.filter { interf ->
            val hasBulkIn = interf.endpoints.any { it.type == "BULK" && it.direction == "IN" }
            val hasBulkOut = interf.endpoints.any { it.type == "BULK" && it.direction == "OUT" }
            val isExcluded = interf.classId == 7 || interf.classId == 8
            hasBulkIn && hasBulkOut && !isExcluded
        }

        if (candidates.isEmpty()) {
            logger.logError("❌ Filtered Candidate list is empty! No interface has both Bulk IN and Bulk OUT endpoints without being Mass Storage or Printer.")
            return false
        }

        // Score candidates based on preferred classes (0xFF Vendor-Specific or 0x06 Image) and lower interface IDs
        val sortedCandidates = candidates.sortedWith(compareByDescending<com.example.core.usb.UsbInterfaceInfo> { interf ->
            var score = 0
            if (interf.classId == 0xFF) score += 20
            if (interf.classId == 0x06) score += 20
            // Prefer lower interface ID (typically 0 on standard MFP configurations is scanner)
            score += (10 - interf.id)
            score
        })

        logger.logRecovery("🎯 Filtered candidates sorted by score: ${sortedCandidates.map { "Intf ${it.id} (Score: ${if (it.classId == 0xFF || it.classId == 0x06) 20 + 10 - it.id else 10 - it.id})" }}")

        // Try candidate interfaces one by one and perform runtime protocol handshake verification
        for (candidate in sortedCandidates) {
            val candidateId = candidate.id
            val bulkInAddr = candidate.endpoints.find { it.type == "BULK" && it.direction == "IN" }?.address ?: -1
            val bulkOutAddr = candidate.endpoints.find { it.type == "BULK" && it.direction == "OUT" }?.address ?: -1

            if (bulkInAddr == -1 || bulkOutAddr == -1) {
                logger.logWarning("⚠️ Candidate Interface $candidateId skipped due to invalid endpoint resolution.")
                continue
            }

            logger.logRecovery("🔌 [PROBE] Testing candidate Interface $candidateId. Claiming interface...")
            val claimed = usbCommRepository.claimInterface(candidateId)
            if (!claimed) {
                logger.logWarning("❌ [PROBE] Failed to claim candidate Interface $candidateId (claimInterface returned false). Trying next...")
                continue
            }

            // Bind the active session endpoints and interface ID so sendRequest works during handshake probe
            scannerInterfaceId = candidateId
            bulkInEndpoint = bulkInAddr
            bulkOutEndpoint = bulkOutAddr

            logger.logRecovery("📊 [PROBE] Bound parameters for Interface $candidateId: Bulk IN = 0x${Integer.toHexString(bulkInEndpoint)}, Bulk OUT = 0x${Integer.toHexString(bulkOutEndpoint)}")

            try {
                logger.logRecovery("📡 [PROBE] Sending GetScannerElements validation SOAP request...")
                val getElementsReq = GetScannerElements
                val getElementsXml = protocolRepository.buildRequest(getElementsReq)
                
                val responseXml = sendRequest(getElementsXml)
                val response = protocolRepository.parseResponse(responseXml)

                if (response is ScannerElements) {
                    logger.logRecovery("✅ [PROBE] Handshake succeeded! Interface $candidateId accepted. Manufacturer=${response.manufacturer}, Model=${response.model}, Status=${response.status}")
                    return true // Keeps the interface claimed and active!
                } else {
                    logger.logWarning("⚠️ [PROBE] Response parsed successfully but returned unexpected message class: ${response.javaClass.simpleName}")
                    throw Exception("Unexpected parsed message type")
                }
            } catch (e: Exception) {
                logger.logWarning("❌ [PROBE] Active handshake probe failed on candidate Interface $candidateId: ${e.message}")
                logger.logRecovery("🔌 [PROBE] Releasing Interface $candidateId and cleaning up state...")
                usbCommRepository.releaseInterface(candidateId)
                
                // Reset bound parameters
                scannerInterfaceId = -1
                bulkInEndpoint = -1
                bulkOutEndpoint = -1
            }
        }

        logger.logError("❌ All suitable candidate interfaces failed active protocol negotiation.")
        return false
    }

    /**
     * Releases the scanner interface and closes the USB session.
     */
    suspend fun close() {
        if (isSimulation) {
            logger.logRecovery("ℹ️ [SESSION] Closing simulated scanner session.")
            return
        }

        if (scannerInterfaceId != -1) {
            logger.logRecovery("🔌 [SESSION] Closing USB scanner session. Releasing Interface $scannerInterfaceId...")
            usbCommRepository.releaseInterface(scannerInterfaceId)
        }
        usbCommRepository.endSession()
        scannerInterfaceId = -1
    }

    /**
     * Sends a SOAP XML request and returns the parsed SOAP response XML string.
     */
    suspend fun sendRequest(soapXml: String): String {
        if (isSimulation) {
            delay(400) // Simulate processing latency
            return generateSimulatedResponse(soapXml)
        }

        if (bulkOutEndpoint == -1 || bulkInEndpoint == -1) {
            throw Exception("USB session endpoints not initialized. Discovery may have failed.")
        }

        logger.logRecovery("📡 [USB OUT] Transferring ${soapXml.length} characters to EP 0x${Integer.toHexString(bulkOutEndpoint)}...")
        val writeResult = usbCommRepository.writeBulk(bulkOutEndpoint, soapXml.toByteArray(Charsets.UTF_8))
        if (writeResult !is com.example.core.usb.transport.UsbTransferResult.Success) {
            throw Exception("USB bulk write failed: $writeResult")
        }

        logger.logRecovery("📡 [USB IN] Awaiting XML response payload from EP 0x${Integer.toHexString(bulkInEndpoint)}...")
        val readResult = usbCommRepository.readBulk(bulkInEndpoint, 65536)
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

        if (bulkOutEndpoint == -1 || bulkInEndpoint == -1) {
            throw Exception("USB session endpoints not initialized. Discovery may have failed.")
        }

        logger.logRecovery("📡 [USB OUT] Sending SOAP RetrieveImage request to EP 0x${Integer.toHexString(bulkOutEndpoint)}...")
        val writeResult = usbCommRepository.writeBulk(bulkOutEndpoint, soapXml.toByteArray(Charsets.UTF_8))
        if (writeResult !is com.example.core.usb.transport.UsbTransferResult.Success) {
            throw Exception("USB bulk write failed: $writeResult")
        }

        logger.logRecovery("📡 [USB IN] Fetching multi-megabyte binary DIME stream from EP 0x${Integer.toHexString(bulkInEndpoint)}...")
        val largeBuffer = 4 * 1024 * 1024 // 4MB buffer for scan payload
        val readResult = usbCommRepository.readBulk(bulkInEndpoint, largeBuffer, timeoutMs = 15000)
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
