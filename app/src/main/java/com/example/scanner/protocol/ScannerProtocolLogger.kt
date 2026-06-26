package com.example.scanner.protocol

import com.example.core.logging.Logger

/**
 * Dedicated logger for HP LaserJet M175a Scanner Protocol events.
 * Logs SOAP, XML, and DIME construction/parsing events.
 * Strictly forbidden from logging transport-level details (like endpoints, raw USB transfers).
 */
class ScannerProtocolLogger(private val logger: Logger) {

    private val tag = "ScannerProtocol"

    fun logSoapBuild(action: String) {
        logger.i(tag, "🧼 [SOAP BUILD] Constructed SOAP request envelope for action: $action")
    }

    fun logSoapParse(action: String) {
        logger.i(tag, "🧼 [SOAP PARSE] Successfully parsed SOAP response envelope for action: $action")
    }

    fun logXmlBuild(rootTag: String) {
        logger.d(tag, "📄 [XML BUILD] Serialized XML payload with root element: <$rootTag>")
    }

    fun logXmlParse(rootTag: String) {
        logger.d(tag, "📄 [XML PARSE] Deserialized XML payload from root element: <$rootTag>")
    }

    fun logDimeBuild(partCount: Int, totalBytes: Int) {
        logger.i(tag, "📦 [DIME BUILD] Encapsulated $partCount parts into DIME binary stream ($totalBytes bytes)")
    }

    fun logDimeParse(partCount: Int) {
        logger.i(tag, "📦 [DIME PARSE] Decapsulated $partCount parts from DIME binary stream")
    }

    fun logValidationSuccess(message: String) {
        logger.i(tag, "🛡️ [VALIDATION SUCCESS] $message")
    }

    fun logValidationFailure(message: String) {
        logger.e(tag, "❌ [VALIDATION FAILURE] $message")
    }

    fun logProtocolError(message: String, throwable: Throwable? = null) {
        logger.e(tag, "💥 [PROTOCOL ERROR] $message", throwable)
    }
}
