package com.m175astudio.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone-as-bridge: turns the OLD phone (OTG-cabled to the M175a) into a
 * Wi-Fi print/scan server for OTHER phones — no PC needed.
 *
 * Speaks the same three protocols as windows-bridge so the existing
 * [BridgeClient] works unchanged against either host:
 *  - IPP Print-Job on /ipp/print        -> [Handlers.printDocument] (USB)
 *  - eSCL ScanJobs flow on /eSCL/...    -> [Handlers.scan] (USB)
 *  - JSON on /api/status + a human / page
 *
 * Dependency-free: raw ServerSocket HTTP + Android NsdManager for mDNS
 * (`_ipp._tcp` / `_uscan._tcp`) so system print dialogs can also see it.
 * One job at a time — the USB cable is half-duplex for our purposes and
 * the engine is single-tasked anyway.
 */
class PhoneBridgeServer(
    private val context: Context,
    private val port: Int = 8080,
    private val handlers: Handlers,
) {

    data class Handlers(
        /** True when the OTG printer claim is up. */
        val isUsbReady: () -> Boolean,
        /** True while a local or remote job owns USB/the engine. */
        val isBusy: () -> Boolean,
        /** Jobs waiting in the FIFO lane (shown in /api/status). */
        val queueDepth: () -> Int = { 0 },
        /** Jobs fully served (shown in /api/status). */
        val jobsServed: () -> Int = { 0 },
        /**
         * Print raw document bytes over USB. [formatHint] is "pdf", "jpeg"
         * or "raw" (sniffed from magic + IPP attrs). Blocking; returns true
         * when the engine accepted the job.
         */
        val printDocument: (doc: ByteArray, formatHint: String) -> Boolean,
        /** Scan one page over USB at [dpi]/[colorMode] (eSCL names). */
        val scan: (dpi: Int, colorMode: String) -> ByteArray,
    )

    /** Lazy eSCL session: created by POST, scanned page-by-page on GET. */
    private data class ScanSession(val dpi: Int, val colorMode: String,
                                   var pages: Int = 0)

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    private val jobsSent = AtomicInteger(0)
    private val scanSessions =
        java.util.Collections.synchronizedMap(HashMap<String, ScanSession>())
    /** Pages one eSCL session may pull (multi-page feeder-style clients). */
    private val maxSessionPages = 20
    private val nsdUnregisters = ArrayList<() -> Unit>()

    @Volatile var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    /** Binds + starts the accept loop. Returns the bound port. */
    @Synchronized
    fun start(): Int {
        if (running.get()) return boundPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        boundPort = ss.localPort
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val sock = ss.accept()
                    Thread({ handle(sock) }, "phone-bridge-conn").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "phone-bridge-accept")
        acceptThread!!.isDaemon = true
        acceptThread!!.start()
        advertiseNsd(boundPort)
        return boundPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        boundPort = -1
        scanSessions.clear()
        val undos = ArrayList(nsdUnregisters)
        nsdUnregisters.clear()
        for (u in undos) runCatching { u() }
    }

    // ------------------------------------------------------------ HTTP core

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun readLine(ins: java.io.InputStream): String? {
        val out = ByteArrayOutputStream(256)
        while (out.size() < 65536) {
            val b = ins.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun readExact(ins: java.io.InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(n.coerceAtLeast(0))
        val buf = ByteArray(32768)
        var left = n
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size, left))
            if (r < 0) throw EOFException("body truncated ($left left)")
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 120_000
            val ins = sock.getInputStream()
            val reqLine = readLine(ins) ?: run { sock.close(); return }
            val parts = reqLine.split(" ")
            if (parts.size < 2) {
                respond(sock, 400, "bad request".toByteArray(), "text/plain")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                val c = h.indexOf(':')
                if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
            }
            val chunked = headers["transfer-encoding"]
                ?.lowercase()?.contains("chunked") == true
            val body = when {
                chunked -> HttpChunked.decodeBody(ins)
                else -> {
                    val len = headers["content-length"]?.toIntOrNull()
                        ?.coerceIn(0, 100 * 1024 * 1024) ?: 0
                    if (len > 0) readExact(ins, len) else ByteArray(0)
                }
            }
            route(sock, Request(method, path, headers, body))
        } catch (_: Exception) {
            runCatching { sock.close() }
        }
    }

    private fun respond(sock: Socket, code: Int, body: ByteArray,
                        ctype: String, extra: Map<String, String> = emptyMap()) {
        try {
            val out = sock.getOutputStream()
            val text = when (code) {
                200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"
                404 -> "Not Found"; 500 -> "Server Error"; 503 -> "Busy"
                else -> "OK"
            }
            val head = StringBuilder()
            head.append("HTTP/1.1 $code $text\r\n")
            head.append("Content-Type: $ctype\r\n")
            head.append("Content-Length: ${body.size}\r\n")
            head.append("Connection: close\r\n")
            for ((k, v) in extra) head.append("$k: $v\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } finally {
            runCatching { sock.close() }
        }
    }

    // ------------------------------------------------------------ routes

    private fun route(sock: Socket, r: Request) {
        when {
            r.method == "GET" && (r.path == "/" || r.path == "/index.html") ->
                respond(sock, 200, indexHtml().toByteArray(), "text/html; charset=utf-8")
            r.method == "GET" && r.path == "/api/status" ->
                respond(sock, 200, statusJson().toByteArray(), "application/json")
            r.method == "POST" && r.path.startsWith("/ipp") ->
                handleIpp(sock, r.body)
            r.method == "GET" && (r.path == "/eSCL" || r.path == "/eSCL/ScannerCapabilities") ->
                respond(sock, 200, capsXml().toByteArray(), "text/xml; charset=utf-8")
            r.method == "GET" && r.path == "/eSCL/ScannerStatus" ->
                respond(sock, 200, statusXml().toByteArray(), "text/xml; charset=utf-8")
            r.method == "POST" && r.path == "/eSCL/ScanJobs" ->
                handleScanJobs(sock, r.body)
            r.method == "GET" && r.path.startsWith("/eSCL/ScanJobs/") && r.path.endsWith("/NextDocument") ->
                handleNextDocument(sock, r.path)
            r.method == "DELETE" && r.path.startsWith("/eSCL/ScanJobs/") -> {
                val id = r.path.substringAfter("/eSCL/ScanJobs/").substringBefore("/")
                if (scanSessions.remove(id) != null) respond(sock, 200, ByteArray(0), "text/plain")
                else respond(sock, 404, "no such job".toByteArray(), "text/plain")
            }
            else -> respond(sock, 404, "not found".toByteArray(), "text/plain")
        }
    }

    private fun indexHtml(): String {
        val ready = handlers.isUsbReady()
        val busy = handlers.isBusy()
        val state = when {
            !ready -> "printer not connected (plug OTG)"
            busy -> "busy"
            else -> "idle — ready for Wi-Fi jobs"
        }
        val served = maxOf(jobsSent.get(), handlers.jobsServed())
        val queue = handlers.queueDepth()
        return """<html><head><meta name=viewport content="width=device-width,initial-scale=1">
<title>M175a Phone Bridge</title></head><body style="font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 1em">
<h1>M175a Phone Bridge</h1><p>State: <b>$state</b> | jobs served: $served | queue: $queue</p>
<ul><li>Print: <code>POST /ipp/print</code> (IPP Print-Job, PDF/JPEG)</li>
<li>Scan: <code>POST /eSCL/ScanJobs</code> then <code>GET .../NextDocument</code></li>
<li>Status: <code>GET /api/status</code></li></ul>
<p>On the second phone: M175 app → Setup → Wi-Fi bridge → enter this phone's address.</p>
</body></html>"""
    }

    private fun statusJson(): String {
        val ready = handlers.isUsbReady()
        val busy = handlers.isBusy()
        val queue = handlers.queueDepth()
        val state = when {
            !ready -> "offline"
            busy -> "printing"
            else -> "idle"
        }
        val detail = when {
            !ready -> "printer not connected via OTG"
            busy && queue > 0 -> "job in flight, $queue waiting"
            busy -> "job in flight"
            else -> "HP Color LaserJet MFP M175a (phone bridge)"
        }
        val served = maxOf(jobsSent.get(), handlers.jobsServed())
        return """{"state":"$state","detail":"$detail","jobs_sent":$served,"queue":$queue}"""
    }

    // ---- IPP (minimal Print-Job, mirrors windows-bridge/httpd.py)

    private fun ippDocument(body: ByteArray): ByteArray {
        // Walk attribute groups by length fields (never raw-search 0x03 —
        // it occurs inside binary documents too).
        var pos = 8
        while (pos < body.size) {
            val tag = body[pos].toInt() and 0xFF
            if (tag == 0x03) return body.copyOfRange(pos + 1, body.size)
            if (tag <= 0x0F) {
                pos += 1
                continue
            }
            if (pos + 3 > body.size) break
            val nlen = ((body[pos + 1].toInt() and 0xFF) shl 8) or (body[pos + 2].toInt() and 0xFF)
            pos += 3 + nlen
            if (pos + 2 > body.size) break
            val vlen = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            pos += 2 + vlen
        }
        return body
    }

    private fun ippReply(reqId: Int, ok: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u32(v: Int) {
            out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
            out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
        }
        fun attr(tag: Int, name: String, value: String) {
            out.write(tag)
            val nb = name.toByteArray()
            u16(nb.size); out.write(nb)
            val vb = value.toByteArray()
            u16(vb.size); out.write(vb)
        }
        out.write(1); out.write(1)
        u16(if (ok) 0x0000 else 0x0500)
        u32(reqId)
        attr(0x47, "attributes-charset", "utf-8")
        attr(0x48, "attributes-natural-language", "en")
        out.write(0x02) // job group
        fun attrInt(tag: Int, name: String, v: Int) {
            out.write(tag)
            val nb = name.toByteArray()
            u16(nb.size); out.write(nb)
            u16(4)
            u32(v)
        }
        attrInt(0x21, "job-id", 1)
        attrInt(0x23, "job-state", 9)
        attr(0x44, "job-state-reasons", "none")
        out.write(0x03)
        return out.toByteArray()
    }

    private fun handleIpp(sock: Socket, body: ByteArray) {
        val reqId = if (body.size >= 8)
            ((body[4].toInt() and 0xFF) shl 24) or ((body[5].toInt() and 0xFF) shl 16) or
                    ((body[6].toInt() and 0xFF) shl 8) or (body[7].toInt() and 0xFF)
        else 1
        if (!handlers.isUsbReady()) {
            respond(sock, 200, ippReply(reqId, false), "application/ipp")
            return
        }
        // No busy rejection: the handler queues behind the running job and
        // this HTTP thread waits for our FIFO turn (office-queue behavior).
        val doc = ippDocument(body)
        if (doc.isEmpty()) {
            respond(sock, 200, ippReply(reqId, false), "application/ipp")
            return
        }
        val hint = when {
            doc.size >= 4 && doc[0] == '%'.code.toByte() && doc[1] == 'P'.code.toByte() -> "pdf"
            doc.size >= 2 && doc[0] == 0xFF.toByte() && doc[1] == 0xD8.toByte() -> "jpeg"
            else -> "raw"
        }
        val ok = runCatching { handlers.printDocument(doc, hint) }.getOrDefault(false)
        if (ok) jobsSent.incrementAndGet()
        respond(sock, 200, ippReply(reqId, ok), "application/ipp")
    }

    // ---- eSCL (mirrors windows-bridge/httpd.py caps)

    private fun capsXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScannerCapabilities xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm" version="1.0">
 <pwg:Version>1.0</pwg:Version>
 <pwg:MakeAndManufacturer>HP</pwg:MakeAndManufacturer>
 <pwg:ModelName>HP Color LaserJet MFP M175a (phone bridge)</pwg:ModelName>
 <pwg:SerialNumber>M175PHONE</pwg:SerialNumber>
 <scan:SettingProfile>
  <scan:ColorModes>
   <scan:ColorMode>BlackAndWhite1</scan:ColorMode>
   <scan:ColorMode>Grayscale8</scan:ColorMode>
   <scan:ColorMode>RGB24</scan:ColorMode>
  </scan:ColorModes>
  <scan:DocumentFormats>
   <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
  </scan:DocumentFormats>
  <scan:SupportedResolutions>
   <scan:Resolution><scan:XResolution><pwg:Number>75</pwg:Number></scan:XResolution><scan:YResolution><pwg:Number>75</pwg:Number></scan:YResolution></scan:Resolution>
   <scan:Resolution><scan:XResolution><pwg:Number>100</pwg:Number></scan:XResolution><scan:YResolution><pwg:Number>100</pwg:Number></scan:YResolution></scan:Resolution>
   <scan:Resolution><scan:XResolution><pwg:Number>150</pwg:Number></scan:XResolution><scan:YResolution><pwg:Number>150</pwg:Number></scan:YResolution></scan:Resolution>
   <scan:Resolution><scan:XResolution><pwg:Number>200</pwg:Number></scan:XResolution><scan:YResolution><pwg:Number>200</pwg:Number></scan:YResolution></scan:Resolution>
   <scan:Resolution><scan:XResolution><pwg:Number>300</pwg:Number></scan:XResolution><scan:YResolution><pwg:Number>300</pwg:Number></scan:YResolution></scan:Resolution>
   <scan:Resolution><scan:XResolution><pwg:Number>600</pwg:Number></scan:XResolution><scan:YResolution><pwg:Number>600</pwg:Number></scan:YResolution></scan:Resolution>
  </scan:SupportedResolutions>
  <scan:Platen>
   <scan:PlatenInputCaps>
    <scan:MinWidth>8</scan:MinWidth><scan:MaxWidth>2550</scan:MaxWidth>
    <scan:MinHeight>8</scan:MinHeight><scan:MaxHeight>3508</scan:MaxHeight>
    <scan:MaxScanRegions>1</scan:MaxScanRegions>
   </scan:PlatenInputCaps>
  </scan:Platen>
 </scan:SettingProfile>
</scan:ScannerCapabilities>"""

    private fun statusXml(): String {
        val state = if (handlers.isBusy()) "Processing" else "Idle"
        return """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScannerStatus xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
 <pwg:State>$state</pwg:State>
 <scan:AdfState>ScannerAdfEmpty</scan:AdfState>
</scan:ScannerStatus>"""
    }

    /**
     * Lazy sessions (multi-page fix): POST only records dpi/mode and returns
     * 201 fast. Each GET NextDocument scans ONE page live, so Mopria-style
     * clients can pull page 1..N with repeated GETs and finish with DELETE.
     * Single-page clients (our BridgeClient) just GET once.
     */
    private fun handleScanJobs(sock: Socket, body: ByteArray) {
        if (!handlers.isUsbReady()) {
            respond(sock, 503, "offline".toByteArray(), "text/plain")
            return
        }
        if (scanSessions.size > 50) scanSessions.clear()
        val xml = runCatching { body.toString(Charsets.UTF_8) }.getOrDefault("")
        val dpi = Regex("<(?:pwg:)?Number>(\\d+)</(?:pwg:)?Number>")
            .find(xml)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(75, 600) ?: 300
        val colorMode = when {
            "BlackAndWhite1" in xml -> "BlackAndWhite1"
            "Grayscale8" in xml -> "Grayscale8"
            else -> "RGB24"
        }
        val id = seq.incrementAndGet().toString()
        scanSessions[id] = ScanSession(dpi, colorMode)
        respond(sock, 201, ByteArray(0), "text/plain",
            mapOf("Location" to "/eSCL/ScanJobs/$id/NextDocument"))
    }

    private fun handleNextDocument(sock: Socket, path: String) {
        val id = path.substringAfter("/eSCL/ScanJobs/").substringBefore("/")
        val session = scanSessions[id]
        if (session == null) {
            respond(sock, 404, "no such job".toByteArray(), "text/plain")
            return
        }
        if (session.pages >= maxSessionPages) {
            scanSessions.remove(id)
            respond(sock, 404, "session complete".toByteArray(), "text/plain")
            return
        }
        // The handler blocks on the FIFO lane: a second phone's page simply
        // waits behind the running job instead of failing.
        val jpeg = runCatching { handlers.scan(session.dpi, session.colorMode) }.getOrNull()
        if (jpeg == null || jpeg.size < 1000) {
            respond(sock, 500, "scan failed".toByteArray(), "text/plain")
            return
        }
        session.pages++
        respond(sock, 200, jpeg, "image/jpeg")
    }

    // ------------------------------------------------------------ mDNS

    private fun advertiseNsd(port: Int) {
        // Best-effort: manual IP entry always works even if mDNS is blocked
        // (many hotspots drop multicast). Never let NSD break the server.
        try {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return
            registerNsd(nsd, "_ipp._tcp.", "M175a-Phone", port,
                mapOf("rp" to "ipp/print", "pdl" to "application/pdf,image/jpeg",
                    "Color" to "T", "Duplex" to "F", "Scan" to "T"))
            registerNsd(nsd, "_uscan._tcp.", "M175a-Phone-Scan", port,
                mapOf("rp" to "eSCL", "pdl" to "image/jpeg"))
        } catch (_: Exception) {}
    }

    private fun registerNsd(nsd: NsdManager, type: String, name: String,
                            port: Int, txt: Map<String, String>) {
        try {
            val info = NsdServiceInfo()
            info.serviceName = name
            info.serviceType = type
            info.port = port
            for ((k, v) in txt) runCatching { info.setAttribute(k, v) }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) {}
                override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(si: NsdServiceInfo) {}
                override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdUnregisters.add { runCatching { nsd.unregisterService(listener) } }
        } catch (_: Exception) {}
    }
}
