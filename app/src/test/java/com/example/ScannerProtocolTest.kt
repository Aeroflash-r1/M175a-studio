package com.example

import com.example.core.logging.Logger
import com.example.scanner.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ScannerProtocolTest {

    private class FakeLogger : Logger {
        val logs = mutableListOf<String>()
        override fun d(tag: String, message: String) { logs.add("DEBUG: $message") }
        override fun i(tag: String, message: String) { logs.add("INFO: $message") }
        override fun w(tag: String, message: String, throwable: Throwable?) { logs.add("WARN: $message") }
        override fun e(tag: String, message: String, throwable: Throwable?) { logs.add("ERROR: $message") }
    }

    private val fakeLogger = FakeLogger()
    private val protocolLogger = ScannerProtocolLogger(fakeLogger)
    private val validator = ScannerProtocolValidator(protocolLogger)
    private val repository = ScannerProtocolRepository(validator, protocolLogger)

    // =========================================================================
    // XML SUPPORT TESTS
    // =========================================================================

    @Test
    fun testXmlSerialization() {
        val root = XmlElement(
            name = "test",
            namespaces = mapOf("" to "http://default", "ns" to "http://namespace"),
            attributes = mapOf("id" to "123"),
            text = "content",
            children = listOf(
                XmlElement(name = "child", namespacePrefix = "ns", text = "child_text")
            )
        )

        val xml = XmlSerializer.serialize(root, includeDeclaration = false, pretty = false)
        assertTrue(xml.contains("xmlns=\"http://default\""))
        assertTrue(xml.contains("xmlns:ns=\"http://namespace\""))
        assertTrue(xml.contains("id=\"123\""))
        assertTrue(xml.contains("<ns:child>child_text</ns:child>"))
        assertTrue(xml.endsWith("</test>"))
    }

    @Test
    fun testXmlParsingSuccess() {
        val xml = """
            <root xmlns="http://default" attr="val">
                <child id="456">nested text</child>
                <self-closing />
            </root>
        """.trimIndent()

        val parsed = XmlParser.parse(xml)
        assertEquals("root", parsed.name)
        assertEquals("val", parsed.attributes["attr"])
        assertEquals("http://default", parsed.namespaces[""])

        val child = parsed.findChild("child")
        assertNotNull(child)
        assertEquals("child", child?.name)
        assertEquals("456", child?.attributes["id"])
        assertEquals("nested text", child?.text)

        val selfClosing = parsed.findChild("self-closing")
        assertNotNull(selfClosing)
        assertEquals("self-closing", selfClosing?.name)
        assertTrue(selfClosing?.children?.isEmpty() == true)
    }

    @Test
    fun testXmlParsingTagMismatchThrows() {
        val malformedXml = "<root><child>text</root></child>"
        assertThrows(XmlException::class.java) {
            XmlParser.parse(malformedXml)
        }
    }

    @Test
    fun testXmlUnescaping() {
        val xml = "<text>&amp; &lt; &gt; &quot; &apos;</text>"
        val parsed = XmlParser.parse(xml)
        assertEquals("& < > \" '", parsed.text)
    }

    // =========================================================================
    // SOAP SUPPORT TESTS
    // =========================================================================

    @Test
    fun testSoapEnvelopeSerialization() {
        val request = CreateScanJob(
            resolution = ScanResolution(300, 300),
            inputSource = "Platen",
            colorMode = "Color"
        )
        val xml = repository.buildRequest(request, "uuid:test-job-create")
        
        assertTrue(xml.contains("<soap:Envelope"))
        assertTrue(xml.contains("<soap:Header"))
        assertTrue(xml.contains("<wsa:Action>http://schemas.hp.com/imaging/escl/2008/02/05/CreateScanJob</wsa:Action>"))
        assertTrue(xml.contains("<wsa:MessageID>uuid:test-job-create</wsa:MessageID>"))
        assertTrue(xml.contains("<wscn:CreateScanJobRequest>"))
        assertTrue(xml.contains("<wscn:InputSource>Platen</wscn:InputSource>"))
        assertTrue(xml.contains("<wscn:Width>300</wscn:Width>"))
    }

    @Test
    fun testSoapEnvelopeParsingSuccess() {
        val responseXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                <soap:Header>
                    <wsa:Action>http://schemas.hp.com/imaging/escl/2008/02/05/CreateScanJobResponse</wsa:Action>
                    <wsa:RelatesTo>uuid:test-job-create</wsa:RelatesTo>
                </soap:Header>
                <soap:Body>
                    <wscn:CreateScanJobResponse>
                        <wscn:JobId>job-xyz-789</wscn:JobId>
                        <wscn:JobStatus>Started</wscn:JobStatus>
                    </wscn:CreateScanJobResponse>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val response = repository.parseResponse(responseXml)
        assertTrue(response is CreateScanJobResponse)
        val typedResponse = response as CreateScanJobResponse
        assertEquals("job-xyz-789", typedResponse.jobId)
        assertEquals("Started", typedResponse.jobStatus)
    }

    @Test
    fun testSoapFaultParsing() {
        val faultXml = """
            <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
                <soap:Body>
                    <soap:Fault>
                        <soap:Code>
                            <soap:Value>soap:Sender</soap:Value>
                        </soap:Code>
                        <soap:Reason>
                            <soap:Text xml:lang="en">Device is currently busy</soap:Text>
                        </soap:Reason>
                        <soap:Detail>PaperJamException</soap:Detail>
                    </soap:Fault>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        val exception = assertThrows(SoapFault::class.java) {
            repository.parseResponse(faultXml)
        }
        assertEquals("soap:Sender", exception.code)
        assertEquals("Device is currently busy", exception.reason)
        assertEquals("PaperJamException", exception.detail)
    }

    // =========================================================================
    // DIME MULTIPART TESTS
    // =========================================================================

    @Test
    fun testDimeSerializationAndParsing() {
        val jpegData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte())
        val attachment = DimePart(
            id = "scanned-image-1",
            type = "image/jpeg",
            data = jpegData,
            typeScheme = DimeTypeScheme.MIME
        )

        val soapXml = "<test-soap-message/>"
        val dimeBytes = repository.buildDimeMessage(soapXml, listOf(attachment))

        // Decapsulate DIME
        val parsed = DimeParser.parse(dimeBytes)
        assertEquals(2, parsed.parts.size)

        val soapPart = parsed.parts[0]
        assertTrue(soapPart.isFirst)
        assertFalse(soapPart.isLast)
        assertEquals("application/soap+xml", soapPart.type)
        assertEquals(soapXml, String(soapPart.data, Charsets.UTF_8))

        val imgPart = parsed.parts[1]
        assertFalse(imgPart.isFirst)
        assertTrue(imgPart.isLast)
        assertEquals("scanned-image-1", imgPart.id)
        assertEquals("image/jpeg", imgPart.type)
        assertArrayEquals(jpegData, imgPart.data)
    }

    @Test
    fun testDimePaddingAlignment() {
        // Build parts of odd sizes to force padding and verify offsets match
        val oddData = byteArrayOf(1, 2, 3) // length 3 -> needs 1 padding
        val part = DimePart("a", "b", oddData)
        val msg = DimeMessage(listOf(part.copy(isFirst = true, isLast = true)))
        val bytes = DimeSerializer.serialize(msg)

        // DIME header is 12 bytes
        // ID length is 1, padded with 3 bytes to 4 boundary -> 4 bytes
        // Type length is 1, padded with 3 bytes to 4 boundary -> 4 bytes
        // Data length is 3, padded with 1 byte to 4 boundary -> 4 bytes
        // Expected total size = 12 (header) + 4 (id+pad) + 4 (type+pad) + 4 (data+pad) = 24 bytes
        assertEquals(24, bytes.size)

        val parsedMsg = DimeParser.parse(bytes)
        assertEquals(1, parsedMsg.parts.size)
        val parsedPart = parsedMsg.parts[0]
        assertEquals("a", parsedPart.id)
        assertEquals("b", parsedPart.type)
        assertArrayEquals(oddData, parsedPart.data)
    }

    // =========================================================================
    // PROTOCOL MODELS & VALIDATION TESTS
    // =========================================================================

    @Test
    fun testWscnMessageValidation() {
        val validJob = CreateScanJob(ScanResolution(600, 600))
        assertTrue(validator.validateWscnMessage(validJob))

        val invalidJob = CreateScanJob(ScanResolution(-1, 300))
        assertFalse(validator.validateWscnMessage(invalidJob))

        val validResponse = CreateScanJobResponse("job-111", "Started")
        assertTrue(validator.validateWscnMessage(validResponse))

        val invalidResponse = CreateScanJobResponse("", "Started")
        assertFalse(validator.validateWscnMessage(invalidResponse))
    }

    @Test
    fun testGetScannerElementsTranslation() {
        val elements = ScannerElements(
            status = "Idle",
            manufacturer = "HP",
            model = "LaserJet 100 color MFP M175a",
            serialNumber = "CN667788",
            adfLoaded = true,
            supportedResolutions = listOf(ScanResolution(300, 300), ScanResolution(600, 600)),
            supportedColorModes = listOf("Color", "Grayscale")
        )

        val elementXml = """
            <wscn:GetScannerElementsResponse xmlns:wscn="http://schemas.hp.com/imaging/escl/2008/02/05">
                <wscn:ScannerElements>
                    <wscn:ScannerStatus>Idle</wscn:ScannerStatus>
                    <wscn:ScannerInfo>
                        <wscn:Manufacturer>HP</wscn:Manufacturer>
                        <wscn:Model>LaserJet 100 color MFP M175a</wscn:Model>
                        <wscn:SerialNumber>CN667788</wscn:SerialNumber>
                    </wscn:ScannerInfo>
                    <wscn:ScannerCapabilities>
                        <wscn:SupportedResolutions>
                            <wscn:Resolution><wscn:Width>300</wscn:Width><wscn:Height>300</wscn:Height></wscn:Resolution>
                            <wscn:Resolution><wscn:Width>600</wscn:Width><wscn:Height>600</wscn:Height></wscn:Resolution>
                        </wscn:SupportedResolutions>
                        <wscn:SupportedColorModes>
                            <wscn:ColorMode>Color</wscn:ColorMode>
                            <wscn:ColorMode>Grayscale</wscn:ColorMode>
                        </wscn:SupportedColorModes>
                    </wscn:ScannerCapabilities>
                    <wscn:AdfLoaded>true</wscn:AdfLoaded>
                </wscn:ScannerElements>
            </wscn:GetScannerElementsResponse>
        """.trimIndent()

        val parsed = WscnMessageTranslator.fromXmlElement(XmlParser.parse(elementXml))
        assertTrue(parsed is ScannerElements)
        val typedParsed = parsed as ScannerElements
        assertEquals("Idle", typedParsed.status)
        assertEquals("HP", typedParsed.manufacturer)
        assertEquals("LaserJet 100 color MFP M175a", typedParsed.model)
        assertEquals("CN667788", typedParsed.serialNumber)
        assertTrue(typedParsed.adfLoaded)
        assertEquals(2, typedParsed.supportedResolutions.size)
        assertEquals(300, typedParsed.supportedResolutions[0].xResolution)
        assertEquals(600, typedParsed.supportedResolutions[1].xResolution)
        assertEquals(2, typedParsed.supportedColorModes.size)
        assertEquals("Color", typedParsed.supportedColorModes[0])
    }
}
