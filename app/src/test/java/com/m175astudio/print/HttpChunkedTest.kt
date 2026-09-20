package com.m175astudio.print

import com.m175astudio.net.HttpChunked
import org.junit.Assert.*
import org.junit.Test

class HttpChunkedTest {

    @Test
    fun decodesMultiChunkWithExtensionAndTrailers() {
        val raw = ("4\r\nWiki\r\n" +
                "5;ext=1\r\npedia\r\n" +
                "E\r\n in\r\n\r\nchunks.\r\n" +
                "0\r\n" +
                "X-Trailer: yes\r\n" +
                "\r\n").toByteArray(Charsets.ISO_8859_1)
        val out = HttpChunked.decodeBody(raw.inputStream())
        assertEquals("Wikipedia in\r\n\r\nchunks.", out.toString(Charsets.UTF_8))
    }

    @Test
    fun emptyBody() {
        val out = HttpChunked.decodeBody("0\r\n\r\n".byteInputStream())
        assertEquals(0, out.size)
    }

    @Test
    fun binarySafe() {
        val payload = ByteArray(70000) { (it * 31 and 0xFF).toByte() }
        val wire = java.io.ByteArrayOutputStream()
        // Split across odd chunk sizes incl. CRLF-looking bytes.
        var off = 0
        for (size in intArrayOf(1, 32768, 37229, 2)) {
            wire.write("${size.toString(16)}\r\n".toByteArray())
            wire.write(payload, off, size)
            wire.write("\r\n".toByteArray())
            off += size
        }
        wire.write("0\r\n\r\n".toByteArray())
        assertArrayEquals(payload, HttpChunked.decodeBody(wire.toByteArray().inputStream()))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsBadHeader() {
        HttpChunked.decodeBody("ZZZ\r\nabc\r\n0\r\n\r\n".byteInputStream())
    }
}
