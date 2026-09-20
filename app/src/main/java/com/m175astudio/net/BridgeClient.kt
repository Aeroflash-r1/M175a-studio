package com.m175astudio.net

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wi-Fi client for the windows-bridge PC gateway (windows-bridge/m175_bridge).
 *
 * Lets an old phone — or any phone on the same Wi-Fi / hotspot — use the
 * M175a as a *network* printer with EVERY function, while the PC owns USB:
 *  - Print: IPP Print-Job (PDF/JPEG) -> bridge renders via Windows GDI spooler
 *  - Scan:  eSCL ScanJobs -> bridge drives WIA and returns JPEG
 *  - Status: /api/status JSON (state/detail/toner/jobs)
 *
 * No third-party HTTP lib: HttpURLConnection only, so this adds zero deps.
 * Timeouts are generous (scan/print take minutes on this engine).
 */
class BridgeClient(val host: String, val port: Int = 8080) {

    fun baseUrl(): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http")) "$h" else "http://$h:$port"
    }

    data class BridgeStatus(
        val state: String,
        val detail: String,
        val jobsSent: Int,
        val raw: String,
    )

    // ------------------------------------------------------------ status

    fun getStatus(timeoutMs: Int = 8_000): BridgeStatus? {
        return try {
            val c = (URL("${baseUrl()}/api/status").openConnection() as HttpURLConnection)
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.connect()
            if (c.responseCode != 200) null
            else {
                val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                BridgeStatus(
                    state = jsonStr(body, "state") ?: "unknown",
                    detail = jsonStr(body, "detail") ?: "",
                    jobsSent = jsonStr(body, "jobs_sent")?.toIntOrNull() ?: 0,
                    raw = body.take(2000),
                )
            }
        } catch (_: Exception) { null }
    }

    fun testConnection(): Boolean = getStatus() != null

    // ------------------------------------------------------------ print

    /**
     * IPP Print-Job upload. [documentFormat] must be one the bridge
     * advertises: application/pdf or application/octet-stream (JPEG/PNG).
     * Returns true when the bridge replies status successful-ok (0x0000).
     */
    fun printDocument(doc: ByteArray, documentFormat: String,
                      jobName: String = "Android Wi-Fi Job",
                      timeoutMs: Int = 120_000): Boolean {
        return try {
            val req = buildIppPrintJob(doc, documentFormat, jobName)
            val c = (URL("${baseUrl()}/ipp/print").openConnection() as HttpURLConnection)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/ipp")
            c.connectTimeout = 10_000
            c.readTimeout = timeoutMs
            c.outputStream.use { it.write(req) }
            if (c.responseCode != 200) false
            else {
                val resp = c.inputStream.use { it.readBytes() }
                resp.size >= 4 && resp[2] == 0.toByte() && resp[3] == 0.toByte()
            }
        } catch (_: Exception) { false }
    }

    fun printPdf(pdf: ByteArray, jobName: String = "Android PDF"): Boolean {
        return printDocument(pdf, "application/pdf", jobName)
    }

    fun printImage(img: ByteArray, jobName: String = "Android Image"): Boolean {
        return printDocument(img, "application/octet-stream", jobName)
    }

    private fun buildIppPrintJob(doc: ByteArray, format: String, jobName: String): ByteArray {
        val out = ByteArrayOutputStream(512 + doc.size)
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) {
            out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
            out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
        }
        fun attr(tag: Int, name: String, value: String) {
            out.write(tag)
            val nb = name.toByteArray(Charsets.UTF_8)
            u16(nb.size); out.write(nb)
            val vb = value.toByteArray(Charsets.UTF_8)
            u16(vb.size); out.write(vb)
        }
        out.write(1); out.write(1) // IPP 1.1
        u16(0x0002) // Print-Job
        u32(1) // request-id
        out.write(0x01) // operation-attributes group
        attr(0x47, "attributes-charset", "utf-8")
        attr(0x48, "attributes-natural-language", "en")
        attr(0x45, "printer-uri", "${baseUrl()}/ipp/print")
        attr(0x42, "requesting-user-name", "android")
        attr(0x42, "job-name", jobName)
        attr(0x49, "document-format", format)
        out.write(0x03) // end-of-attributes
        out.write(doc)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ scan (eSCL)

    /**
     * Full eSCL scan: POST ScanJobs -> follow Location -> GET JPEG.
     * [dpi] one of 75/100/150/200/300/600 (bridge caps), [colorMode] one of
     * RGB24 / Grayscale8 / BlackAndWhite1.
     */
    fun scan(dpi: Int = 300, colorMode: String = "RGB24",
             timeoutMs: Int = 180_000): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
 <pwg:Version>1.0</pwg:Version>
 <scan:Intent>Document</scan:Intent>
 <pwg:ScanRegions><pwg:ScanRegion><pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
 <pwg:Width>2550</pwg:Width><pwg:Height>3508</pwg:Height></pwg:ScanRegion></pwg:ScanRegions>
 <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
 <scan:ColorMode>$colorMode</scan:ColorMode>
 <scan:XResolution><pwg:Number>$dpi</pwg:Number></scan:XResolution>
 <scan:YResolution><pwg:Number>$dpi</pwg:Number></scan:YResolution>
</scan:ScanSettings>""".toByteArray(Charsets.UTF_8)
        val post = (URL("${baseUrl()}/eSCL/ScanJobs").openConnection() as HttpURLConnection)
        post.requestMethod = "POST"
        post.doOutput = true
        post.setRequestProperty("Content-Type", "text/xml")
        post.connectTimeout = 10_000
        post.readTimeout = timeoutMs
        post.outputStream.use { it.write(xml) }
        if (post.responseCode != 201) {
            val err = runCatching {
                post.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            }.getOrNull() ?: ""
            throw java.io.IOException("bridge scan failed HTTP ${post.responseCode} $err")
        }
        val loc = post.getHeaderField("Location")
            ?: throw java.io.IOException("bridge scan: no Location header")
        val get = (URL(if (loc.startsWith("http")) loc else baseUrl() + loc)
            .openConnection() as HttpURLConnection)
        get.connectTimeout = 10_000
        get.readTimeout = timeoutMs
        get.connect()
        if (get.responseCode != 200) throw java.io.IOException("bridge scan doc HTTP ${get.responseCode}")
        val jpeg = get.inputStream.use { it.readBytes() }
        // Best-effort cleanup (bridge tolerates missing DELETE).
        runCatching {
            val jobId = loc.substringAfter("/eSCL/ScanJobs/").substringBefore("/")
            (URL("${baseUrl()}/eSCL/ScanJobs/$jobId").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 5_000
                readTimeout = 5_000
                connect()
                inputStream?.close()
            }
        }
        if (jpeg.size < 1000) throw java.io.IOException("bridge scan returned ${jpeg.size}B")
        return jpeg
    }

    // ------------------------------------------------------------ duplex over Wi-Fi

    /**
     * Wi-Fi duplex = two IPP jobs with a flip in between (the M175a has no
     * duplexer; the bridge has no phone-upload duplex endpoint, so the app
     * splits the PDF client-side with PdfDocument and sends pass1 then pass2).
     * Caller shows the flip prompt between the two calls.
     */
    fun printDuplexPass(pdfPass: ByteArray, passLabel: String): Boolean =
        printPdf(pdfPass, "Wi-Fi Duplex $passLabel")

    companion object {
        /** Minimal flat-JSON string/int extractor (avoids org.json on old APIs). */
        fun jsonStr(json: String, key: String): String? {
            val i = json.indexOf("\"$key\"")
            if (i < 0) return null
            val c = json.indexOf(':', i)
            if (c < 0) return null
            var j = c + 1
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length) return null
            return if (json[j] == '"') {
                val k = json.indexOf('"', j + 1)
                if (k < 0) null else json.substring(j + 1, k)
            } else {
                var k = j
                while (k < json.length && json[k] != ',' && json[k] != '}') k++
                json.substring(j, k).trim().takeIf { it.isNotEmpty() }
            }
        }

        fun colorModeFor(gray: Boolean, lineart: Boolean): String = when {
            lineart -> "BlackAndWhite1"
            gray -> "Grayscale8"
            else -> "RGB24"
        }
    }
}
