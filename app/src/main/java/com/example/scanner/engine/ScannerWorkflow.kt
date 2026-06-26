package com.example.scanner.engine

import com.example.scanner.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime

/**
 * Executes the complete reverse-engineered 5-step HP M175a scanning workflow.
 */
class ScannerWorkflow(
    private val session: ScannerSession,
    private val protocolRepository: ScannerProtocolRepository,
    private val stateMachine: ScannerStateMachine,
    private val logger: ScannerEngineLogger
) {

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _activeJob = MutableStateFlow<ScannerJob?>(null)
    val activeJob = _activeJob.asStateFlow()

    /**
     * Executes the sequential workflow from capabilities gathering to clean disposal.
     */
    suspend fun execute(resolutionDpi: Int, colorMode: String): ScannerResult {
        logger.logWorkflowStarted()
        _progress.value = 0.0f
        var jobId: String? = null

        try {
            // -----------------------------------------------------------------
            // STEP 1: GetScannerElements & Capability Validation
            // -----------------------------------------------------------------
            stateMachine.transitionTo(ScannerState.Initializing)
            _progress.value = 0.05f

            if (!session.open()) {
                throw Exception("Could not open communications session to scanner interface")
            }

            stateMachine.transitionTo(ScannerState.GettingCapabilities)
            _progress.value = 0.15f

            val getElementsReq = GetScannerElements
            val getElementsXml = protocolRepository.buildRequest(getElementsReq)
            val getElementsRespXml = session.sendRequest(getElementsXml)
            val getElementsResp = protocolRepository.parseResponse(getElementsRespXml)

            if (getElementsResp !is ScannerElements) {
                throw Exception("Expected GetScannerElementsResponse, but received: ${getElementsResp.javaClass.simpleName}")
            }

            logger.logCapabilitiesRetrieved(
                manufacturer = getElementsResp.manufacturer,
                model = getElementsResp.model,
                adf = getElementsResp.adfLoaded
            )

            // Validate requested capabilities are supported
            val supportsRes = getElementsResp.supportedResolutions.any { it.xResolution == resolutionDpi }
            if (!supportsRes) {
                logger.logWarning("⚠️ Requested resolution $resolutionDpi DPI is not advertised in device capabilities. Proceeding with caution.")
            }
            val supportsColor = getElementsResp.supportedColorModes.contains(colorMode)
            if (!supportsColor) {
                logger.logWarning("⚠️ Requested color mode $colorMode is not advertised in device capabilities. Proceeding with caution.")
            }

            _progress.value = 0.30f

            // -----------------------------------------------------------------
            // STEP 2: CreateScanJob
            // -----------------------------------------------------------------
            stateMachine.transitionTo(ScannerState.CreatingJob)
            logger.logRecovery("🆔 Initializing Scan Job with settings: ${resolutionDpi}DPI, ColorMode=$colorMode")

            val createJobReq = CreateScanJob(
                resolution = ScanResolution(resolutionDpi, resolutionDpi),
                colorMode = colorMode,
                inputSource = "Platen"
            )
            val createJobXml = protocolRepository.buildRequest(createJobReq)
            val createJobRespXml = session.sendRequest(createJobXml)
            val createJobResp = protocolRepository.parseResponse(createJobRespXml)

            if (createJobResp !is CreateScanJobResponse) {
                throw Exception("Expected CreateScanJobResponse, received: ${createJobResp.javaClass.simpleName}")
            }

            jobId = createJobResp.jobId
            if (jobId.isBlank()) {
                throw Exception("Scanner returned an empty job ID")
            }

            val job = ScannerJob(
                jobId = jobId,
                resolutionDpi = resolutionDpi,
                colorMode = colorMode,
                status = "Started"
            )
            _activeJob.value = job
            logger.logJobCreated(jobId, resolutionDpi, colorMode)

            _progress.value = 0.45f

            // -----------------------------------------------------------------
            // STEP 3: RetrieveImage (DIME Stream Transfer)
            // -----------------------------------------------------------------
            stateMachine.transitionTo(ScannerState.WaitingForImage)
            logger.logRetrieveImageStarted()

            val retrieveImgReq = RetrieveImage(jobId = jobId)
            val retrieveImgXml = protocolRepository.buildRequest(retrieveImgReq)

            stateMachine.transitionTo(ScannerState.ReceivingImage)
            val dimeBytes = session.sendDimeRequest(retrieveImgXml)

            val (parsedResponse, attachments) = protocolRepository.parseDimeResponse(dimeBytes)
            if (parsedResponse !is RetrieveImageResponse) {
                throw Exception("DIME parsed response type mismatch: expected RetrieveImageResponse, got ${parsedResponse.javaClass.simpleName}")
            }

            val jpegData = parsedResponse.imageData
            if (jpegData.isEmpty()) {
                throw Exception("Acquired scan attachment contains empty binary JPEG data")
            }

            logger.logJpegReceived(jpegData.size)

            // Validate JPEG SOI/EOI markers
            val hasSoi = jpegData.size >= 2 && jpegData[0] == 0xFF.toByte() && jpegData[1] == 0xD8.toByte()
            val hasEoi = jpegData.size >= 2 && jpegData[jpegData.size - 2] == 0xFF.toByte() && jpegData[jpegData.size - 1] == 0xD9.toByte()
            logger.logJpegValidated(hasSoi, hasEoi, jpegData.size)

            if (!hasSoi || !hasEoi) {
                throw Exception("JPEG attachment is corrupt: missing valid FFD8 SOI or FFD9 EOI markers")
            }

            _progress.value = 0.75f

            // -----------------------------------------------------------------
            // STEP 4: GetJobInfo (Poll until completed, failed, or cancelled)
            // -----------------------------------------------------------------
            stateMachine.transitionTo(ScannerState.PollingJob)
            logger.logRecovery("🔄 Polling status of job $jobId on scanner...")

            var pollAttempts = 0
            var jobFinished = false
            var finalState = "Completed"

            while (pollAttempts < 15 && !jobFinished) {
                pollAttempts++
                delay(500) // Poll interval

                val jobInfoReq = GetJobInfo(jobId = jobId)
                val jobInfoXml = protocolRepository.buildRequest(jobInfoReq)
                val jobInfoRespXml = session.sendRequest(jobInfoXml)
                val jobInfoResp = protocolRepository.parseResponse(jobInfoRespXml)

                if (jobInfoResp is JobSummaryType) {
                    val state = jobInfoResp.jobState
                    logger.logRecovery("🔄 Job Poll status: ID=$jobId, State=$state, Reason=${jobInfoResp.jobStateReason}")
                    
                    if (state == "Completed" || state == "Canceled" || state == "Aborted" || state == "Failed") {
                        finalState = state
                        jobFinished = true
                    }
                } else {
                    // Fail-safe completed if parser returns other valid structures
                    jobFinished = true
                }
            }

            if (finalState == "Canceled") {
                stateMachine.transitionTo(ScannerState.Cancelled)
                logger.logWorkflowFinished(false)
                return ScannerResult.Cancelled
            } else if (finalState == "Failed" || finalState == "Aborted") {
                throw Exception("Scan job failed or was aborted on the device")
            }

            logger.logJobCompleted(jobId)
            stateMachine.transitionTo(ScannerState.Completed)
            _progress.value = 0.90f

            // Calculate mock height/width based on typical A4/Letter bounds
            val widthPx = if (resolutionDpi == 600) 5100 else 2550
            val heightPx = if (resolutionDpi == 600) 7000 else 3500

            val imageInfo = ScanImageInfo(
                resolutionDpi = resolutionDpi,
                widthPx = widthPx,
                heightPx = heightPx,
                fileSize = jpegData.size,
                captureTime = LocalDateTime.now()
            )

            val successResult = ScannerResult.Success(jpegData, imageInfo)
            logger.logWorkflowFinished(true)
            return successResult

        } catch (e: Exception) {
            logger.logError("Error occurred during scanning workflow: ${e.message}", e)
            stateMachine.transitionTo(ScannerState.Error(e.message ?: "Unknown scanning error"))
            logger.logWorkflowFinished(false)
            return ScannerResult.Failure(e.message ?: "Scanning workflow failed", e)
        } finally {
            // -----------------------------------------------------------------
            // STEP 5: DestroyScanJob (Resource release)
            // -----------------------------------------------------------------
            stateMachine.transitionTo(ScannerState.CleaningUp)
            if (jobId != null) {
                try {
                    logger.logRecovery("🧹 Sending DestroyScanJob request for job $jobId...")
                    val destroyJobReq = DestroyScanJob(jobId = jobId)
                    val destroyJobXml = protocolRepository.buildRequest(destroyJobReq)
                    session.sendRequest(destroyJobXml)
                    logger.logJobDestroyed(jobId)
                } catch (e: Exception) {
                    logger.logError("Failed to cleanly destroy job $jobId on the scanner: ${e.message}")
                }
            }

            session.close()
            _activeJob.value = null
            _progress.value = 1.0f
            stateMachine.transitionTo(ScannerState.Idle)
        }
    }
}
