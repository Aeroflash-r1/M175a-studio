package com.m175astudio.print

import org.junit.Assert.*
import org.junit.Test

/**
 * The 300dpi off-center bug: BeginSession sets UnitsPerMeasure=(dpi,dpi),
 * so SetPageOrigin/SetCursor values are in dpi units. The driver-exact
 * 100,100 / 0,40 values were captured at 600 dpi — sent raw at 300 dpi
 * they are TWICE the physical offset and the page sits down-right.
 */
class PclxlGeometryTest {

    private fun bytesOf(
        dpi: Int, mono: Boolean,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val geom = PclxlPage.Geometry.of(dpi)
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        if (mono) {
            PclxlPage.writePageMono1Bit(out, payload, 8, 1, geom)
        } else {
            PclxlPage.writePage(out, payload, 8, 1, geom, grayscale = false)
        }
        return out.toByteArray()
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // sint16_xy encoding: 0xD3 + LE(x) + LE(y) + 0xF8 + attr
    private fun originBytes(x: Int, y: Int) = byteArrayOf(
        0xD3.toByte(), (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
        (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
        0xF8.toByte(), 42,
    )

    private fun cursorBytes(x: Int, y: Int) = byteArrayOf(
        0xD3.toByte(), (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
        (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
        0xF8.toByte(), 76,
    )

    @Test
    fun originScalesWithDpi_jpeg() {
        assertTrue(contains(bytesOf(600, false), originBytes(100, 100)))
        assertTrue(contains(bytesOf(300, false), originBytes(50, 50)))
    }

    @Test
    fun cursorScalesWithDpi_jpeg() {
        assertTrue(contains(bytesOf(600, false), cursorBytes(0, 40)))
        assertTrue(contains(bytesOf(300, false), cursorBytes(0, 20)))
    }

    @Test
    fun originAndCursorScale_mono() {
        val b300 = bytesOf(300, true)
        assertTrue(contains(b300, originBytes(50, 50)))
        assertTrue(contains(b300, cursorBytes(0, 20)))
        val b600 = bytesOf(600, true)
        assertTrue(contains(b600, originBytes(100, 100)))
        assertTrue(contains(b600, cursorBytes(0, 40)))
    }

    @Test
    fun samePhysicalOffsetBothDpis() {
        // Physical offset = units / dpi. Must be identical at 300 and 600.
        val o300 = PclxlPage.originXY(300)
        val o600 = PclxlPage.originXY(600)
        assertEquals(o600.first / 600.0, o300.first / 300.0, 1e-9)
        assertEquals(o600.second / 600.0, o300.second / 300.0, 1e-9)
        val c300 = PclxlPage.cursorXY(300)
        val c600 = PclxlPage.cursorXY(600)
        assertEquals(c600.second / 600.0, c300.second / 300.0, 1e-9)
    }
}
