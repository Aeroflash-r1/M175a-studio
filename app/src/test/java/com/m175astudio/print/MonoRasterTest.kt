package com.m175astudio.print

import org.junit.Assert.*
import org.junit.Test

class MonoRasterTest {

    @Test
    fun rleRoundTrip() {
        val raw = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x01, 0x02, 0x02, 0x02, 0x02, 0x55)
        val enc = MonoRaster.rleEncode(raw)
        assertArrayEquals(raw, MonoRaster.rleDecode(enc))
        // repeat run must compress (3xFF -> 2 bytes, not 3)
        assertTrue(enc.size < raw.size + 2)
    }

    @Test
    fun rleAllWhitePageCompressesHard() {
        // A4 300dpi blank = ~1MB raw 1-bit -> RLE must be tiny (long repeats).
        val stride = (2480 + 7) / 8
        val raw = ByteArray(stride * 3508) // all zero = white
        val enc = MonoRaster.rleEncode(raw)
        assertTrue("white page RLE ${enc.size}B too big", enc.size < raw.size / 10)
        assertArrayEquals(raw, MonoRaster.rleDecode(enc))
    }

    @Test
    fun rleLongRepeatCappedAt128() {
        val raw = ByteArray(500) { 0xAA.toByte() }
        val enc = MonoRaster.rleEncode(raw)
        assertArrayEquals(raw, MonoRaster.rleDecode(enc))
    }
}
