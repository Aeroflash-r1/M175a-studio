package com.m175astudio.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * HTTP/1.1 chunked transfer decoding (request bodies).
 *
 * HttpURLConnection posts with doOutput and no fixed length arrive as
 * `Transfer-Encoding: chunked` — a Content-Length-only server reads ZERO
 * body bytes and the job silently fails (this once broke ALL phone-bridge
 * printing: empty IPP body -> empty document -> 0x0500). Pure JVM so it is
 * unit-tested.
 */
object HttpChunked {

    /** Reads one CRLF-terminated ASCII line (no length limit issues here). */
    fun readLine(ins: InputStream): String? {
        val out = ByteArrayOutputStream(256)
        while (out.size() < 65536) {
            val b = ins.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    /** Decodes a chunked body: hex-size lines, 0-size terminator, trailers. */
    @Throws(java.io.IOException::class)
    fun decodeBody(ins: InputStream, maxBytes: Int = 100 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(ins) ?: throw EOFException("chunk header truncated")
            val size = line.trim().split(";")[0].trim().toIntOrNull(16)
                ?: throw java.io.IOException("bad chunk header: $line")
            if (size == 0) {
                // Consume optional trailers + final CRLF.
                while (true) {
                    val t = readLine(ins) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            if (size < 0 || out.size() + size > maxBytes) {
                throw java.io.IOException("chunk too large: $size")
            }
            var left = size
            val buf = ByteArray(32768)
            while (left > 0) {
                val r = ins.read(buf, 0, minOf(buf.size, left))
                if (r < 0) throw EOFException("chunk data truncated")
                out.write(buf, 0, r)
                left -= r
            }
            // Consume the CRLF after chunk data.
            val cr = ins.read()
            val lf = ins.read()
            if (cr != '\r'.code || lf != '\n'.code) {
                throw java.io.IOException("chunk missing CRLF")
            }
        }
        return out.toByteArray()
    }
}
