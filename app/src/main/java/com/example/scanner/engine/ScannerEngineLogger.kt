package com.example.scanner.engine

import com.example.core.logging.Logger

/**
 * Dedicated logger for the HP LaserJet 100 color MFP M175a Scanner Engine.
 */
class ScannerEngineLogger(private val logger: Logger) {

    private val tag = "ScannerEngine"

    fun logWorkflowStarted() {
        logger.i(tag, "🚀 [WORKFLOW] Scanner workflow execution started.")
    }

    fun logCapabilitiesRetrieved(manufacturer: String, model: String, adf: Boolean) {
        logger.i(tag, "📊 [CAPABILITIES] Retrieved capabilities: Manufacturer=$manufacturer, Model=$model, ADF Loaded=$adf")
    }

    fun logJobCreated(jobId: String, resolution: Int, colorMode: String) {
        logger.i(tag, "🆔 [JOB] Job created with ID: $jobId. Settings: Res=${resolution}DPI, Color=$colorMode")
    }

    fun logRetrieveImageStarted() {
        logger.i(tag, "📥 [RETRIEVE] RetrieveImage request started. Requesting DIME/SOAP multipart scan payload.")
    }

    fun logJpegReceived(sizeBytes: Int) {
        logger.i(tag, "📦 [RECEIVE] Successfully received raw JPEG attachment of size: $sizeBytes bytes.")
    }

    fun logJpegValidated(soiPassed: Boolean, eoiPassed: Boolean, size: Int) {
        logger.i(tag, "🛡️ [VALIDATE] JPEG validation complete: SOI Marker Passed=$soiPassed, EOI Marker Passed=$eoiPassed, Size=$size bytes.")
    }

    fun logJobCompleted(jobId: String) {
        logger.i(tag, "✅ [COMPLETED] Scan job $jobId completed successfully.")
    }

    fun logJobDestroyed(jobId: String) {
        logger.i(tag, "🧹 [DESTROY] Job $jobId successfully destroyed on the scanner.")
    }

    fun logWorkflowFinished(success: Boolean) {
        val status = if (success) "SUCCESS" else "FAILURE"
        logger.i(tag, "🏁 [WORKFLOW] Scanner workflow finished with status: $status")
    }

    fun logError(message: String, throwable: Throwable? = null) {
        logger.e(tag, "💥 [ERROR] $message", throwable)
    }

    fun logTimeout(message: String) {
        logger.w(tag, "⏱️ [TIMEOUT] $message")
    }

    fun logRecovery(message: String) {
        logger.i(tag, "🩹 [RECOVERY] $message")
    }

    fun logStateTransition(oldState: ScannerState, newState: ScannerState) {
        logger.i(tag, "🔄 [STATE CHANGE] Transitioned from ${oldState.javaClass.simpleName} -> ${newState.javaClass.simpleName}")
    }

    fun logWarning(message: String) {
        logger.w(tag, message)
    }
}
