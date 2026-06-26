package com.example.scanner.protocol

/**
 * High-level interface representing a generic WSCN scanner protocol message.
 */
sealed interface WscnMessage {
    val action: String
}

/**
 * Action values matching the standard HP WSCN WSDL.
 */
object WscnActions {
    const val GET_SCANNER_ELEMENTS = "http://schemas.hp.com/imaging/escl/2008/02/05/GetScannerElements"
    const val GET_SCANNER_ELEMENTS_RESPONSE = "http://schemas.hp.com/imaging/escl/2008/02/05/GetScannerElementsResponse"
    
    const val CREATE_SCAN_JOB = "http://schemas.hp.com/imaging/escl/2008/02/05/CreateScanJob"
    const val CREATE_SCAN_JOB_RESPONSE = "http://schemas.hp.com/imaging/escl/2008/02/05/CreateScanJobResponse"
    
    const val RETRIEVE_IMAGE = "http://schemas.hp.com/imaging/escl/2008/02/05/RetrieveImage"
    const val RETRIEVE_IMAGE_RESPONSE = "http://schemas.hp.com/imaging/escl/2008/02/05/RetrieveImageResponse"
    
    const val GET_JOB_INFO = "http://schemas.hp.com/imaging/escl/2008/02/05/GetJobInfo"
    const val GET_JOB_INFO_RESPONSE = "http://schemas.hp.com/imaging/escl/2008/02/05/GetJobInfoResponse"
    
    const val DESTROY_SCAN_JOB = "http://schemas.hp.com/imaging/escl/2008/02/05/DestroyScanJob"
    const val DESTROY_SCAN_JOB_RESPONSE = "http://schemas.hp.com/imaging/escl/2008/02/05/DestroyScanJobResponse"
}

// ==========================================
// REQUEST MODELS
// ==========================================

/**
 * Sent to retrieve detailed device information, including status, capabilities, and settings.
 */
object GetScannerElements : WscnMessage {
    override val action: String = WscnActions.GET_SCANNER_ELEMENTS
}

/**
 * Configures scan resolution options (typically 300 or 600 DPI).
 */
data class ScanResolution(
    val xResolution: Int = 300,
    val yResolution: Int = 300
)

/**
 * Sent to establish a scan job on the device with custom settings.
 */
data class CreateScanJob(
    val resolution: ScanResolution = ScanResolution(),
    val inputSource: String = "Platen", // "Platen" or "Adf"
    val colorMode: String = "Color", // "Color", "Grayscale", "Monochrome"
    val format: String = "image/jpeg"
) : WscnMessage {
    override val action: String = WscnActions.CREATE_SCAN_JOB
}

/**
 * Sent to retrieve the binary scanned payload for a specified active job.
 */
data class RetrieveImage(
    val jobId: String
) : WscnMessage {
    override val action: String = WscnActions.RETRIEVE_IMAGE
}

/**
 * Sent to poll the status, completion state, or failure reasons of a scan job.
 */
data class GetJobInfo(
    val jobId: String
) : WscnMessage {
    override val action: String = WscnActions.GET_JOB_INFO
}

/**
 * Sent to release resources of an active or completed scan job.
 */
data class DestroyScanJob(
    val jobId: String
) : WscnMessage {
    override val action: String = WscnActions.DESTROY_SCAN_JOB
}

// ==========================================
// RESPONSE MODELS
// ==========================================

/**
 * Response carrying comprehensive scanner hardware capability and diagnostic status.
 */
data class ScannerElements(
    val status: String, // "Idle", "Scanning", "Processing", "Down"
    val manufacturer: String, // "HP"
    val model: String, // "LaserJet 100 color MFP M175a"
    val serialNumber: String,
    val adfLoaded: Boolean,
    val supportedResolutions: List<ScanResolution>,
    val supportedColorModes: List<String>
) : WscnMessage {
    override val action: String = WscnActions.GET_SCANNER_ELEMENTS_RESPONSE
}

/**
 * Response returned upon successful creation of a scan job.
 */
data class CreateScanJobResponse(
    val jobId: String,
    val jobStatus: String // "Pending", "Started"
) : WscnMessage {
    override val action: String = WscnActions.CREATE_SCAN_JOB_RESPONSE
}

/**
 * Response wrapping retrieved scan document attachments.
 */
data class RetrieveImageResponse(
    val jobId: String,
    val imageData: ByteArray,
    val mimeType: String = "image/jpeg"
) : WscnMessage {
    override val action: String = WscnActions.RETRIEVE_IMAGE_RESPONSE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RetrieveImageResponse
        if (jobId != other.jobId) return false
        if (!imageData.contentEquals(other.imageData)) return false
        if (mimeType != other.mimeType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = jobId.hashCode()
        result = 31 * result + imageData.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * Current summary state details of a scanner job.
 */
data class JobSummaryType(
    val jobId: String,
    val jobState: String, // "Pending", "Processing", "Completed", "Canceled", "Aborted"
    val jobStateReason: String, // "None", "PaperJam", "DeviceBusy", "Canceled"
    val pagesCompleted: Int
) : WscnMessage {
    override val action: String = WscnActions.GET_JOB_INFO_RESPONSE
}

/**
 * Simple response after releasing/destroying a scan job.
 */
data class DestroyScanJobResponse(
    val jobId: String,
    val success: Boolean
) : WscnMessage {
    override val action: String = WscnActions.DESTROY_SCAN_JOB_RESPONSE
}
