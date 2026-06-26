package com.example.scanner.protocol

/**
 * Parses valid XML strings into structured [SoapEnvelope] models.
 * Validates envelope structure and handles SOAP Fault parsing and extraction.
 */
object SoapParser {

    /**
     * Parses an XML string into a [SoapEnvelope].
     * Throws [XmlException] or [IllegalArgumentException] on malformed content.
     */
    fun parse(xml: String): SoapEnvelope {
        val root = XmlParser.parse(xml)
        
        // Validate root tag is Envelope
        if (root.localName() != "Envelope") {
            throw XmlException("Invalid SOAP Envelope root element: <${root.name}>")
        }

        // Parse Namespaces
        val namespaces = root.namespaces

        // Parse Header and Body
        var soapHeader: SoapHeader? = null
        var soapBody: SoapBody? = null

        for (child in root.children) {
            when (child.localName()) {
                "Header" -> soapHeader = parseHeader(child)
                "Body" -> soapBody = parseBody(child)
            }
        }

        if (soapBody == null) {
            throw XmlException("SOAP Envelope is missing a <Body> element")
        }

        return SoapEnvelope(
            namespaces = namespaces,
            header = soapHeader,
            body = soapBody
        )
    }

    private fun parseHeader(headerElement: XmlElement): SoapHeader {
        var action: String? = null
        var messageId: String? = null
        var relatesTo: String? = null
        var to: String? = null
        val customHeaders = mutableListOf<XmlElement>()

        for (child in headerElement.children) {
            when (child.localName()) {
                "Action" -> action = child.text
                "MessageID" -> messageId = child.text
                "RelatesTo" -> relatesTo = child.text
                "To" -> to = child.text
                else -> customHeaders.add(child)
            }
        }

        return SoapHeader(
            action = action,
            messageId = messageId,
            relatesTo = relatesTo,
            to = to,
            customHeaders = customHeaders
        )
    }

    private fun parseBody(bodyElement: XmlElement): SoapBody {
        // Look for SOAP Fault
        val faultElement = bodyElement.findChild("Fault")
        if (faultElement != null) {
            val fault = parseFault(faultElement)
            return SoapBody(fault = fault)
        }

        // Extract first child as successful payload
        val payload = bodyElement.children.firstOrNull()
        return SoapBody(payload = payload)
    }

    private fun parseFault(faultElement: XmlElement): SoapFault {
        var code = "soap:Receiver"
        var reason = "Unknown Fault"
        var detail: String? = null

        // Parse Code
        val codeElem = faultElement.findChild("Code")
        if (codeElem != null) {
            val valElem = codeElem.findChild("Value")
            if (valElem != null) {
                code = valElem.text ?: code
            }
        }

        // Parse Reason
        val reasonElem = faultElement.findChild("Reason")
        if (reasonElem != null) {
            val textElem = reasonElem.findChild("Text")
            if (textElem != null) {
                reason = textElem.text ?: reason
            } else if (reasonElem.text != null) {
                reason = reasonElem.text ?: reason
            }
        }

        // Parse Detail
        val detailElem = faultElement.findChild("Detail")
        if (detailElem != null) {
            detail = detailElem.text
        }

        return SoapFault(code, reason, detail)
    }
}
