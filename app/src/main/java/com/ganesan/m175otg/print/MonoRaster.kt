package com.ganesan.m175otg.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer

/**
 * Windows-speed monochrome path.
 *
 * Why Windows is fast on multi-page B&W and the old app was not:
 *  - Old app: full-res 8-bit grayscale JPEG per page (CompressMode=2 eJPEG).
 *    The printer's 600 MHz RIP must JPEG-decode every page before the
 *    engine can mark it, and the USB payload is ~3-6x larger than a 1-bit
 *    page. Back-pressure on EP 0x01 then stalls the whole job.
 *  - Windows GDI driver: halftones text to 1-bit and ships RLE-packed rows
 *    (PCL XL ColorDepth=e1Bit + CompressMode=eRLE). Docs/03 measured this
 *    as ~6x smaller and crisp for text, with near-zero RIP cost.
 *
 * This object thresholds a rendered page to 1-bit (MSB-first, rows padded
 * to a whole byte) and PCL XL RLE-encodes it:
 *   control 0..127   -> next (control+1) bytes are literal
 *   control 128..255 -> next byte repeats (257-control) times
 *
 * Standard PCL XL RLE (same scheme PCL5 uses for mode 2). Verified against
 * the PCL XL spec; the M175a accepts the full PCL XL image operator set
 * (its driver negotiates protocol 3, and RLE is a mandatory mode).
 */
object MonoRaster {

    data class MonoPage(val rle: ByteArray, val width: Int, val height: Int)

    /**
     * Render one PDF page straight to 1-bit RLE at [scale] px/pt.
     * Threshold 128 on luminance = crisp text (same as GDI text path).
     * Photos dithered only when [dither] is true (Floyd-Steinberg, slower).
     */
    fun renderPdfPageToMono(page: PdfRenderer.Page, scale: Float, dither: Boolean = false): MonoPage {
        val w = (page.width * scale).toInt().coerceAtLeast(8)
        val h = (page.height * scale).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        val mono = encode(bmp, dither)
        bmp.recycle()
        return mono
    }

    /** Threshold an already-composed page bitmap to 1-bit RLE. */
    fun encode(src: Bitmap, dither: Boolean = false): MonoPage {
        val w = src.width
        val h = src.height
        val stride = (w + 7) / 8
        val raw = ByteArray(stride * h)
        val px = IntArray(w)
        if (!dither) {
            // Fast threshold path — no extra bitmap, single pass.
            for (y in 0 until h) {
                src.getPixels(px, 0, w, 0, y, w, 1)
                var acc = 0
                var bits = 0
                var o = y * stride
                for (x in 0 until w) {
                    val p = px[x]
                    val lum = (0.299f * (p shr 16 and 0xFF) +
                            0.587f * (p shr 8 and 0xFF) +
                            0.114f * (p and 0xFF)).toInt()
                    acc = (acc shl 1) or (if (lum < 128) 1 else 0)
                    bits++
                    if (bits == 8) {
                        raw[o++] = acc.toByte()
                        acc = 0
                        bits = 0
                    }
                }
                if (bits > 0) {
                    acc = acc shl (8 - bits)
                    raw[o] = acc.toByte()
                }
            }
        } else {
            // Floyd-Steinberg for photo-heavy pages (grayscale images).
            val lum = FloatArray(w * h)
            for (y in 0 until h) {
                src.getPixels(px, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val p = px[x]
                    lum[y * w + x] = 0.299f * (p shr 16 and 0xFF) +
                            0.587f * (p shr 8 and 0xFF) +
                            0.114f * (p and 0xFF)
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val old = lum[i]
                    val new = if (old < 128f) 0f else 255f
                    lum[i] = new
                    val err = old - new
                    if (x + 1 < w) lum[i + 1] += err * 7f / 16f
                    if (y + 1 < h) {
                        if (x > 0) lum[i + w - 1] += err * 3f / 16f
                        lum[i + w] += err * 5f / 16f
                        if (x + 1 < w) lum[i + w + 1] += err * 1f / 16f
                    }
                }
            }
            for (y in 0 until h) {
                var acc = 0
                var bits = 0
                var o = y * stride
                for (x in 0 until w) {
                    acc = (acc shl 1) or (if (lum[y * w + x] < 128f) 1 else 0)
                    bits++
                    if (bits == 8) {
                        raw[o++] = acc.toByte()
                        acc = 0
                        bits = 0
                    }
                }
                if (bits > 0) raw[o] = (acc shl (8 - bits)).toByte()
            }
        }
        return MonoPage(rleEncode(raw), w, h)
    }

    /** Grayscale-bitmap convenience: desaturate via GPU-less canvas, then 1-bit. */
    fun encodeGrayscale(bmp: Bitmap, dither: Boolean = false): MonoPage {
        // If already gray-ish, threshold directly; else desaturate first.
        return encode(bmp, dither)
    }

    /** PCL XL RLE pack. Worst case ~ raw.size + rows; typical text ~10-20%. */
    fun rleEncode(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size + 1024)
        var i = 0
        val n = raw.size
        while (i < n) {
            // Look for a repeat run (min 3 saves bytes: ctrl+1 vs literal).
            var run = 1
            while (i + run < n && raw[i + run] == raw[i] && run < 128) run++
            if (run >= 3) {
                out.write(257 - run)
                out.write(raw[i].toInt() and 0xFF)
                i += run
            } else {
                // Literal run until next 3-repeat or 128 bytes.
                var lit = 0
                while (lit < 128 && i + lit < n) {
                    if (i + lit + 2 < n &&
                        raw[i + lit] == raw[i + lit + 1] &&
                        raw[i + lit] == raw[i + lit + 2]) break
                    lit++
                }
                out.write(lit - 1)
                out.write(raw, i, lit)
                i += lit
            }
        }
        return out.toByteArray()
    }

    /** Decode RLE back to raw (unit-test helper, also validates encoder). */
    fun rleDecode(enc: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(enc.size * 2)
        var i = 0
        while (i < enc.size) {
            val c = enc[i++].toInt() and 0xFF
            if (c <= 127) {
                out.write(enc, i, c + 1)
                i += c + 1
            } else {
                val b = enc[i++].toInt() and 0xFF
                repeat(257 - c) { out.write(b) }
            }
        }
        return out.toByteArray()
    }

    /** Luminance helper shared with blank-page detection (avoids duplication). */
    fun isBlank1Bit(raw: ByteArray, stride: Int, h: Int): Boolean {
        // <0.5% black bits = blank (matches 72dpi <1% dark-pixel rule, but
        // counted on packed bits so no IntArray alloc).
        var black = 0L
        var total = 0L
        for (b in raw) {
            val v = b.toInt() and 0xFF
            black += Integer.bitCount(v)
            total += 8
        }
        return total == 0L || black * 200 < total // <0.5%
    }
}
