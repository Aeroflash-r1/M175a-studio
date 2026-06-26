package com.example.scanner.engine

import java.time.LocalDateTime

/**
 * Metadata info about the acquired scan image.
 */
data class ScanImageInfo(
    val resolutionDpi: Int,
    val widthPx: Int,
    val heightPx: Int,
    val fileSize: Int,
    val captureTime: LocalDateTime
)

/**
 * Sealed class representing the terminal result of a scan workflow.
 */
sealed interface ScannerResult {
    data class Success(val imageData: ByteArray, val info: ScanImageInfo) : ScannerResult
    data class Failure(val message: String, val throwable: Throwable? = null) : ScannerResult
    object Cancelled : ScannerResult
}

/**
 * Models an active scan job in progress.
 */
data class ScannerJob(
    val jobId: String,
    val resolutionDpi: Int,
    val colorMode: String,
    val inputSource: String = "Platen",
    val status: String = "Created"
)
