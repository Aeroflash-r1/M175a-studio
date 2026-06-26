package com.example.scanner.protocol

/**
 * Serializes [SoapEnvelope] models into valid, formatted XML string payloads.
 */
object SoapSerializer {

    /**
     * Converts [SoapEnvelope] to an [XmlElement] and serializes it using [XmlSerializer].
     */
    fun serialize(envelope: SoapEnvelope, pretty: Boolean = true): String {
        val root = toXmlElement(envelope)
        return XmlSerializer.serialize(root, includeDeclaration = true, pretty = pretty)
    }

    private fun toXmlElement(envelope: SoapEnvelope): XmlElement {
        val children = mutableListOf<XmlElement>()

        // Serialize SoapHeader if present
        envelope.header?.let { header ->
            val headerChildren = mutableListOf<XmlElement>()
            header.action?.let {
                headerChildren.add(XmlElement("Action", "wsa", text = it))
            }
            header.messageId?.let {
                headerChildren.add(XmlElement("MessageID", "wsa", text = it))
            }
            header.relatesTo?.let {
                headerChildren.add(XmlElement("RelatesTo", "wsa", text = it))
            }
            header.to?.let {
                headerChildren.add(XmlElement("To", "wsa", text = it))
            }
            headerChildren.addAll(header.customHeaders)

            children.add(
                XmlElement(
                    name = "Header",
                    namespacePrefix = "soap",
                    children = headerChildren
                )
            )
        }

        // Serialize SoapBody
        val bodyChildren = mutableListOf<XmlElement>()
        val body = envelope.body
        if (body.fault != null) {
            val faultChildren = mutableListOf<XmlElement>()
            
            // SOAP 1.2 Fault Code
            val codeVal = XmlElement("Value", "soap", text = body.fault.code)
            faultChildren.add(XmlElement("Code", "soap", children = listOf(codeVal)))

            // SOAP 1.2 Fault Reason
            val reasonText = XmlElement(
                name = "Text",
                namespacePrefix = "soap",
                attributes = mapOf("xml:lang" to "en"),
                text = body.fault.reason
            )
            faultChildren.add(XmlElement("Reason", "soap", children = listOf(reasonText)))

            // SOAP 1.2 Fault Detail
            body.fault.detail?.let { detailText ->
                faultChildren.add(XmlElement("Detail", "soap", text = detailText))
            }

            bodyChildren.add(
                XmlElement(
                    name = "Fault",
                    namespacePrefix = "soap",
                    children = faultChildren
                )
            )
        } else if (body.payload != null) {
            bodyChildren.add(body.payload)
        }

        children.add(
            XmlElement(
                name = "Body",
                namespacePrefix = "soap",
                children = bodyChildren
            )
        )

        return XmlElement(
            name = "Envelope",
            namespacePrefix = "soap",
            namespaces = envelope.namespaces,
            children = children
        )
    }
}
