package com.example.scanner.protocol

/**
 * Handles translation between WSCN data models and XML representations.
 */
object WscnMessageTranslator {

    /**
     * Converts a request [WscnMessage] to its corresponding XML representation.
     */
    fun toXmlElement(message: WscnMessage): XmlElement {
        return when (message) {
            is GetScannerElements -> {
                XmlElement(name = "GetScannerElementsRequest", namespacePrefix = "wscn")
            }
            is CreateScanJob -> {
                val resolutionEl = XmlElement(
                    name = "Resolution",
                    namespacePrefix = "wscn",
                    children = listOf(
                        XmlElement("Width", "wscn", text = message.resolution.xResolution.toString()),
                        XmlElement("Height", "wscn", text = message.resolution.yResolution.toString())
                    )
                )
                val settingsEl = XmlElement(
                    name = "ScanSettings",
                    namespacePrefix = "wscn",
                    children = listOf(
                        resolutionEl,
                        XmlElement("InputSource", "wscn", text = message.inputSource),
                        XmlElement("ColorMode", "wscn", text = message.colorMode),
                        XmlElement("Format", "wscn", text = message.format)
                    )
                )
                XmlElement(
                    name = "CreateScanJobRequest",
                    namespacePrefix = "wscn",
                    children = listOf(settingsEl)
                )
            }
            is RetrieveImage -> {
                XmlElement(
                    name = "RetrieveImageRequest",
                    namespacePrefix = "wscn",
                    children = listOf(XmlElement("JobId", "wscn", text = message.jobId))
                )
            }
            is GetJobInfo -> {
                XmlElement(
                    name = "GetJobInfoRequest",
                    namespacePrefix = "wscn",
                    children = listOf(XmlElement("JobId", "wscn", text = message.jobId))
                )
            }
            is DestroyScanJob -> {
                XmlElement(
                    name = "DestroyScanJobRequest",
                    namespacePrefix = "wscn",
                    children = listOf(XmlElement("JobId", "wscn", text = message.jobId))
                )
            }
            else -> throw IllegalArgumentException("Unsupported request message type: ${message.javaClass.simpleName}")
        }
    }

    /**
     * Parses a response XML element into its typed WSCN message.
     */
    fun fromXmlElement(element: XmlElement, attachmentData: ByteArray? = null): WscnMessage {
        val name = element.localName()
        return when (name) {
            "GetScannerElementsResponse" -> {
                val scannerElements = element.findChild("ScannerElements")
                    ?: throw XmlException("Missing <ScannerElements> inside GetScannerElementsResponse")
                
                var status = "Idle"
                var manufacturer = "HP"
                var model = "LaserJet 100 color MFP M175a"
                var serialNumber = "CN123456"
                var adfLoaded = false
                val resolutions = mutableListOf<ScanResolution>()
                val colorModes = mutableListOf<String>()

                scannerElements.children.forEach { child ->
                    when (child.localName()) {
                        "ScannerStatus" -> status = child.text ?: status
                        "ScannerInfo" -> {
                            child.findChild("Manufacturer")?.text?.let { manufacturer = it }
                            child.findChild("Model")?.text?.let { model = it }
                            child.findChild("SerialNumber")?.text?.let { serialNumber = it }
                        }
                        "ScannerCapabilities" -> {
                            val rList = child.findChild("SupportedResolutions")
                            rList?.findChildren("Resolution")?.forEach { r ->
                                val w = r.findChild("Width")?.text?.toIntOrNull() ?: 300
                                val h = r.findChild("Height")?.text?.toIntOrNull() ?: 300
                                resolutions.add(ScanResolution(w, h))
                            }
                            val cList = child.findChild("SupportedColorModes")
                            cList?.findChildren("ColorMode")?.forEach { c ->
                                c.text?.let { colorModes.add(it) }
                            }
                        }
                        "AdfLoaded" -> adfLoaded = child.text?.lowercase() == "true"
                    }
                }

                if (resolutions.isEmpty()) {
                    resolutions.add(ScanResolution(300, 300))
                    resolutions.add(ScanResolution(600, 600))
                }
                if (colorModes.isEmpty()) {
                    colorModes.add("Color")
                    colorModes.add("Grayscale")
                }

                ScannerElements(
                    status = status,
                    manufacturer = manufacturer,
                    model = model,
                    serialNumber = serialNumber,
                    adfLoaded = adfLoaded,
                    supportedResolutions = resolutions,
                    supportedColorModes = colorModes
                )
            }
            "CreateScanJobResponse" -> {
                val jobId = element.findChild("JobId")?.text
                    ?: throw XmlException("Missing <JobId> inside CreateScanJobResponse")
                val status = element.findChild("JobStatus")?.text ?: "Pending"
                CreateScanJobResponse(jobId, status)
            }
            "RetrieveImageResponse" -> {
                val jobId = element.findChild("JobId")?.text
                    ?: throw XmlException("Missing <JobId> inside RetrieveImageResponse")
                RetrieveImageResponse(jobId, attachmentData ?: ByteArray(0))
            }
            "GetJobInfoResponse" -> {
                val summary = element.findChild("JobSummary")
                    ?: throw XmlException("Missing <JobSummary> inside GetJobInfoResponse")
                val jobId = summary.findChild("JobId")?.text
                    ?: throw XmlException("Missing <JobId> inside JobSummary")
                val state = summary.findChild("JobState")?.text ?: "Completed"
                val reason = summary.findChild("JobStateReason")?.text ?: "None"
                val completed = summary.findChild("PagesCompleted")?.text?.toIntOrNull() ?: 0
                JobSummaryType(jobId, state, reason, completed)
            }
            "DestroyScanJobResponse" -> {
                val jobId = element.findChild("JobId")?.text
                    ?: throw XmlException("Missing <JobId> inside DestroyScanJobResponse")
                val success = element.findChild("Success")?.text?.lowercase() == "true"
                DestroyScanJobResponse(jobId, success)
            }
            else -> throw XmlException("Unknown WSCN XML response element: <$name>")
        }
    }
}
