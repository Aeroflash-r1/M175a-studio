package com.example.scanner.protocol

/**
 * Validates the integrity, structural constraints, namespaces, and required fields of XML, SOAP, and DIME payloads.
 */
class ScannerProtocolValidator(private val logger: ScannerProtocolLogger) {

    /**
     * Validates raw XML correctness by dry-run parsing it.
     */
    fun validateXml(xml: String): Boolean {
        return try {
            XmlParser.parse(xml)
            logger.logValidationSuccess("XML syntax and integrity check passed")
            true
        } catch (e: Exception) {
            logger.logValidationFailure("XML syntax check failed: ${e.message}")
            false
        }
    }

    /**
     * Validates SOAP structure, addressing headers, and essential namespaces.
     */
    fun validateSoapEnvelope(envelope: SoapEnvelope): Boolean {
        val body = envelope.body
        if (body.payload == null && body.fault == null) {
            logger.logValidationFailure("SOAP Body must contain either a payload or a fault")
            return false
        }

        // Validate namespaces are declared
        val envelopeNs = envelope.namespaces["soap"]
        if (envelopeNs != SoapEnvelope.SOAP_NS) {
            logger.logValidationFailure("Invalid or missing SOAP 1.2 namespace: expected ${SoapEnvelope.SOAP_NS}")
            return false
        }

        // If headers exist, validate they are not corrupt
        envelope.header?.let { header ->
            if (header.action.isNullOrBlank()) {
                logger.logValidationFailure("SOAP Header is missing WSA Action")
                return false
            }
        }

        logger.logValidationSuccess("SOAP Envelope structure and namespaces validated successfully")
        return true
    }

    /**
     * Validates a complete DIME multipart message, ensuring MB/ME flag integrity and attachment health.
     */
    fun validateDimeMessage(message: DimeMessage): Boolean {
        val parts = message.parts
        if (parts.isEmpty()) {
            logger.logValidationFailure("DIME Message has 0 parts")
            return false
        }

        // Check first part has isFirst flag
        if (!parts.first().isFirst) {
            logger.logValidationFailure("First DIME record must have MB (Message Begin) flag set")
            return false
        }

        // Check last part has isLast flag
        if (!parts.last().isLast) {
            logger.logValidationFailure("Last DIME record must have ME (Message End) flag set")
            return false
        }

        // Validate JPEG image parts if any exist
        val imageParts = message.getImageParts()
        for (part in imageParts) {
            if (part.data.isEmpty()) {
                logger.logValidationFailure("DIME Image part (${part.id}) contains empty binary payload")
                return false
            }
            // Simple validation of JPEG header bytes (SOI: 0xFF, 0xD8)
            if (part.data.size < 4 || part.data[0] != 0xFF.toByte() || part.data[1] != 0xD8.toByte()) {
                logger.logValidationFailure("DIME Image part (${part.id}) lacks valid JPEG binary header")
                return false
            }
        }

        logger.logValidationSuccess("DIME multipart message integrity and attachments validated successfully")
        return true
    }

    /**
     * Validates that high-level message models contain valid required fields.
     */
    fun validateWscnMessage(message: WscnMessage): Boolean {
        return when (message) {
            is CreateScanJob -> {
                if (message.resolution.xResolution <= 0 || message.resolution.yResolution <= 0) {
                    logger.logValidationFailure("CreateScanJob: Resolution must be greater than zero")
                    false
                } else {
                    logger.logValidationSuccess("CreateScanJob model constraints passed")
                    true
                }
            }
            is CreateScanJobResponse -> {
                if (message.jobId.isBlank()) {
                    logger.logValidationFailure("CreateScanJobResponse: Job ID cannot be empty")
                    false
                } else {
                    logger.logValidationSuccess("CreateScanJobResponse model constraints passed")
                    true
                }
            }
            is RetrieveImage -> {
                if (message.jobId.isBlank()) {
                    logger.logValidationFailure("RetrieveImage: Job ID cannot be empty")
                    false
                } else {
                    logger.logValidationSuccess("RetrieveImage model constraints passed")
                    true
                }
            }
            is RetrieveImageResponse -> {
                if (message.jobId.isBlank() || message.imageData.isEmpty()) {
                    logger.logValidationFailure("RetrieveImageResponse: Job ID is empty or attachment data is empty")
                    false
                } else {
                    logger.logValidationSuccess("RetrieveImageResponse model constraints passed")
                    true
                }
            }
            is JobSummaryType -> {
                if (message.jobId.isBlank()) {
                    logger.logValidationFailure("JobSummaryType: Job ID cannot be empty")
                    false
                } else {
                    logger.logValidationSuccess("JobSummaryType model constraints passed")
                    true
                }
            }
            is ScannerElements -> {
                if (message.status.isBlank() || message.manufacturer.isBlank() || message.model.isBlank()) {
                    logger.logValidationFailure("ScannerElements: Required status/manufacturer/model properties are missing")
                    false
                } else {
                    logger.logValidationSuccess("ScannerElements model constraints passed")
                    true
                }
            }
            else -> {
                logger.logValidationSuccess("${message.javaClass.simpleName} verified successfully")
                true
            }
        }
    }
}
