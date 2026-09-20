package com.m175astudio.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

/**
 * Print-preview renderer: low-res thumbnails of the exact pages that will
 * print (after range/parity/reverse filtering), so the user sees the
 * document BEFORE any USB/IPP bytes move.
 *
 * Thumbnails render at ~100 dpi (scale 100/72) in RGB_565 — ~8x cheaper
 * than print bitmaps — capped at [maxPages] so a 200-page PDF previews
 * instantly. Full-res rendering still happens only after Confirm.
 */
object PreviewHelper {

    data class Preview(val thumbs: List<Bitmap>, val totalPlanned: Int,
                       val shown: Int, val truncated: Boolean)

    fun renderPreview(pdf: File, plannedPages: List<Int>,
                      maxPages: Int = 6): Preview {
        if (plannedPages.isEmpty()) return Preview(emptyList(), 0, 0, false)
        val scale = 100f / 72f
        val thumbs = ArrayList<Bitmap>(minOf(maxPages, plannedPages.size))
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (p in plannedPages.take(maxPages)) {
                    if (p - 1 < 0 || p - 1 >= renderer.pageCount) continue
                    renderer.openPage(p - 1).use { page ->
                        val w = (page.width * scale).roundToInt().coerceAtLeast(32)
                        val h = (page.height * scale).roundToInt().coerceAtLeast(32)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        thumbs.add(bmp)
                    }
                }
            }
        }
        return Preview(thumbs, plannedPages.size, thumbs.size,
            plannedPages.size > thumbs.size)
    }

    fun recycle(p: Preview?) {
        p?.thumbs?.forEach { runCatching { it.recycle() } }
    }
}
