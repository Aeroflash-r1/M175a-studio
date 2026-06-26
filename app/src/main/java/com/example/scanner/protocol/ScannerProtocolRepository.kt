package com.example.scanner.protocol

/**
 * Repository in charge of building, parsing, and validating HP Scanner Protocol messages.
 * Does NOT contain any USB communication, interface claims, endpoint transfers, or polling flows.
 * It is completely isolated and pure.
 */
class ScannerProtocolRepository(
    private val validator: ScannerProtocolValidator,
    private val logger: ScannerProtocolLogger
) {

    /**
     * Builds a formatted SOAP XML request string for a given [WscnMessage].
     * @param request The request model (e.g. [CreateScanJob]).
     * @param messageId An optional UUID/message identifier for SOAP addressing.
     */
    fun buildRequest(request: WscnMessage, messageId: String = "uuid:default-id"): String {
        try {
            logger.logSoapBuild(request.action)
            val payload = WscnMessageTranslator.toXmlElement(request)
            val envelope = SoapEnvelope(
                header = SoapHeader(
                    action = request.action,
                    messageId = messageId,
                    to = "http://localhost/eSCL"
                ),
                body = SoapBody(payload = payload)
            )

            // Validate structured model
            validator.validateSoapEnvelope(envelope)

            val xml = SoapSerializer.serialize(envelope)
            logger.logXmlBuild(payload.name)
            
            // Validate generated XML
            validator.validateXml(xml)
            return xml
        } catch (e: Exception) {
            logger.logProtocolError("Failed to build SOAP request for action: ${request.action}", e)
            throw e
        }
    }

    /**
     * Parses a raw SOAP XML response string into a typed [WscnMessage].
     * Throws [SoapFault] if the device returned a SOAP level fault.
     */
    fun parseResponse(xmlResponse: String): WscnMessage {
        try {
            // Validate raw XML correctness
            validator.validateXml(xmlResponse)

            val envelope = SoapParser.parse(xmlResponse)
            logger.logSoapParse(envelope.header?.action ?: "Unknown")

            // Validate SOAP constraints
            validator.validateSoapEnvelope(envelope)

            val body = envelope.body
            if (body.fault != null) {
                logger.logProtocolError("SOAP Fault returned from scanner: ${body.fault.reason}")
                throw body.fault
            }

            val payload = body.payload ?: throw XmlException("SOAP Body has no payload")
            logger.logXmlParse(payload.name)

            val wscnMessage = WscnMessageTranslator.fromXmlElement(payload)
            validator.validateWscnMessage(wscnMessage)

            return wscnMessage
        } catch (e: Exception) {
            logger.logProtocolError("Failed to parse SOAP response", e)
            throw e
        }
    }

    /**
     * Builds a multipart DIME binary payload wrapping a SOAP XML string and auxiliary attachments.
     */
    fun buildDimeMessage(soapXml: String, attachments: List<DimePart> = emptyList()): ByteArray {
        try {
            val soapBytes = soapXml.toByteArray(Charsets.UTF_8)
            val soapPart = DimePart(
                id = "soap-payload",
                type = "application/soap+xml",
                data = soapBytes,
                typeScheme = DimeTypeScheme.MIME,
                isFirst = true,
                isLast = attachments.isEmpty()
            )

            val allParts = mutableListOf(soapPart)
            attachments.forEachIndexed { index, part ->
                val isLast = index == attachments.size - 1
                allParts.add(
                    part.copy(
                        isFirst = false,
                        isLast = isLast
                    )
                )
            }

            val dimeMessage = DimeMessage(allParts)
            validator.validateDimeMessage(dimeMessage)

            val serialized = DimeSerializer.serialize(dimeMessage)
            logger.logDimeBuild(allParts.size, serialized.size)
            return serialized
        } catch (e: Exception) {
            logger.logProtocolError("Failed to serialize DIME multipart message", e)
            throw e
        }
    }

    /**
     * Parses a multipart DIME binary payload, returning the parsed WSCN response and list of attachments.
     */
    fun parseDimeResponse(dimeBytes: ByteArray): Pair<WscnMessage, List<DimePart>> {
        try {
            val dimeMessage = DimeParser.parse(dimeBytes)
            logger.logDimeParse(dimeMessage.parts.size)

            validator.validateDimeMessage(dimeMessage)

            val soapPart = dimeMessage.getSoapPart()
                ?: throw DimeException("DIME message lacks a primary SOAP XML attachment part")

            val xmlResponse = String(soapPart.data, Charsets.UTF_8)
            validator.validateXml(xmlResponse)

            val envelope = SoapParser.parse(xmlResponse)
            val body = envelope.body
            if (body.fault != null) {
                throw body.fault
            }

            val payload = body.payload ?: throw XmlException("SOAP Body inside DIME has no payload")

            // Gather all auxiliary attachment parts (e.g. image/jpeg)
            val attachments = dimeMessage.parts.filter { it != soapPart }
            
            // Pass the primary image binary payload if this is a RetrieveImageResponse
            val primaryImageData = dimeMessage.getImageParts().firstOrNull()?.data

            val responseMessage = WscnMessageTranslator.fromXmlElement(payload, primaryImageData)
            validator.validateWscnMessage(responseMessage)

            return Pair(responseMessage, attachments)
        } catch (e: Exception) {
            logger.logProtocolError("Failed to parse DIME response", e)
            throw e
        }
    }
}
