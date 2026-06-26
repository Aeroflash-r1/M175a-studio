package com.example.scanner.protocol

/**
 * Represents a SOAP 1.2 Envelope containing standard Headers and a Body.
 */
data class SoapEnvelope(
    val namespaces: Map<String, String> = DEFAULT_NAMESPACES,
    val header: SoapHeader? = null,
    val body: SoapBody
) {
    companion object {
        val SOAP_NS = "http://www.w3.org/2003/05/soap-envelope"
        val WSA_NS = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
        val WSD_NS = "http://schemas.xmlsoap.org/ws/2006/02/devprof"
        val WSCN_NS = "http://schemas.hp.com/imaging/escl/2008/02/05"

        val DEFAULT_NAMESPACES = mapOf(
            "soap" to SOAP_NS,
            "wsa" to WSA_NS,
            "wscn" to WSCN_NS
        )
    }
}

/**
 * Represents the Header block of a SOAP Envelope, which contains routing, security, or addressing metadata.
 */
data class SoapHeader(
    val action: String? = null,
    val messageId: String? = null,
    val relatesTo: String? = null,
    val to: String? = null,
    val customHeaders: List<XmlElement> = emptyList()
)

/**
 * Represents the Body block of a SOAP Envelope, containing either a successful payload or a [SoapFault].
 */
data class SoapBody(
    val payload: XmlElement? = null,
    val fault: SoapFault? = null
)

/**
 * Represents a standard SOAP Fault block detailing errors during message processing.
 */
data class SoapFault(
    val code: String,
    val reason: String,
    val detail: String? = null
) : Exception("SOAP Fault [$code]: $reason")
