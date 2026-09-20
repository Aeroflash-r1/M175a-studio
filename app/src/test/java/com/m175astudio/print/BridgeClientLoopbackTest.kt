package com.m175astudio.print

import com.m175astudio.net.BridgeClient
import com.m175astudio.net.HttpChunked
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Proves the phone-bridge HTTP contract over real loopback sockets:
 * the client MUST send Content-Length (naive servers read zero body bytes
 * from chunked uploads -> empty document -> rejected job).
 */
class BridgeClientLoopbackTest {

    private data class Captured(val headers: Map<String, String>, val body: ByteArray)

    private fun captureOne(ss: ServerSocket, box: ArrayBlockingQueue<Captured>) {
        val sock = ss.accept()
        sock.soTimeout = 15_000
        val ins = sock.getInputStream()
        check(HttpChunked.readLine(ins)?.startsWith("POST /ipp/print") == true)
        val headers = HashMap<String, String>()
        while (true) {
            val h = HttpChunked.readLine(ins) ?: break
            if (h.isEmpty()) break
            val c = h.indexOf(':')
            if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: -1
        // Minimal IPP successful-ok reply (version 1.1, status 0x0000, req 1).
        val reply = byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1)
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\n" +
                "Content-Length: ${reply.size}\r\nConnection: close\r\n\r\n")
            .toByteArray())
        out.write(reply)
        out.flush()
        // Read body AFTER replying (client already sent it; socket buffered).
        val body = if (len > 0) {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = ins.read(buf, off, len - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        } else ByteArray(0)
        sock.close()
        box.offer(Captured(headers, body))
    }

    @Test
    fun ippPostCarriesContentLengthAndFullBody() {
        val ss = ServerSocket(0)
        val box = ArrayBlockingQueue<Captured>(1)
        val t = Thread({ captureOne(ss, box) }, "loopback-fake-bridge")
        t.isDaemon = true
        t.start()
        val doc = "%PDF-1.4 loopback-probe".toByteArray()
        val ok = BridgeClient("127.0.0.1", ss.localPort).printPdf(doc, "probe")
        t.join(15_000)
        ss.close()
        assertTrue("client should accept the 0x0000 reply", ok)
        val got = box.poll(1, TimeUnit.SECONDS)
            ?: throw AssertionError("no request captured")
        val len = got.headers["content-length"]?.toIntOrNull() ?: -1
        assertTrue("Content-Length header missing (chunked upload?)", len > 0)
        assertEquals("truncated body", len, got.body.size)
        assertTrue("document must survive inside the IPP body",
            got.body.toString(Charsets.ISO_8859_1).endsWith("%PDF-1.4 loopback-probe"))
    }
}
