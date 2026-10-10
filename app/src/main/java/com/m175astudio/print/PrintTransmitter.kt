package com.m175astudio.print

import android.graphics.BitmapFactory
import com.m175astudio.usb.UsbPrinterConnection
import java.io.InputStream
import java.io.OutputStream

/**
 * Streams a print job to EP 0x01 in 16 KB chunks (Android bulk-transfer max).
 * This is exactly what the Windows driver does on the wire.
 *
 * 12-page-stall fix: the printer STOPS draining EP 0x01 for many seconds
 * while it renders earlier pages. The Windows driver blocks indefinitely;
 * a naive bulkTransfer(5s) returns -1 and the old app abandoned the stream
 * mid-job — leaving the printer waiting forever (your "job stuck on
 * display" observation). sendPrint() now retries through back-pressure.
 */
object PrintTransmitter {

    sealed class Result {
        data class Ok(val bytesSent: Long) : Result()
        data class Failed(val at: Long, val reason: String) : Result()
        data class Cancelled(val at: Long) : Result()
    }

    fun send(
        usb: UsbPrinterConnection,
        stream: InputStream,
        totalBytes: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        val buf = ByteArray(UsbPrinterConnection.CHUNK)
        var sent = 0L
        while (true) {
            if (JobControl.isCancelled) return Result.Cancelled(sent)
            val n = stream.read(buf)
            if (n < 0) break
            if (n == 0) continue
            var off = 0
            while (off < n) {
                val w = usb.sendPrint(buf, off, n - off)
                if (w == -2 || JobControl.isCancelled) return Result.Cancelled(sent)
                if (w < 0) {
                    return Result.Failed(sent, "bulkTransfer failed at $sent bytes")
                }
                off += w
                sent += w
                onProgress(sent, totalBytes)
            }
        }
        return Result.Ok(sent)
    }

    /** A rendered page plus the count of pages already streamed before it. */
    class RenderedPage(val jpeg: ByteArray, val width: Int, val height: Int,
                       val pageNumber: Int, val totalPages: Int)

    /** Live callback while a page renders (for progress UI). */
    fun interface PageSource {
        /** Render + return page [pageNumber] (1-based), or null when done. */
        fun next(pageNumber: Int): RenderedPage?
    }

    /**
     * STREAMING multi-page job: renders page N, transmits it, releases it,
     * renders page N+1 — RAM holds ONE page bitmap at a time instead of
     * all 12 (which OOM-killed the app after page 2 and left the printer
     * mid-session: your 12-page stall).
     *
     * Payload is always 8-bit JPEG (eRGB/eGray, CompressMode=2): the only
     * raster the engine accepts — a 1-bit RLE attempt dies on the panel
     * with "PCL XL ERROR / Subsystem: image / cheetraster.e".
     *
     * For MONO printing: Uses PCL5 with 1-bit RLE (*b#W) which is 3-5x faster
     * than PCL XL grayscale JPEG. This matches the official HP plugin speed.
     */
    fun sendPages(
        usb: UsbPrinterConnection,
        dpi: Int,
        grayscale: Boolean,
        jobName: String,
        source: PageSource,
        onStatus: (String) -> Unit = {},
        landscape: Boolean = false,
        paper: Paper = Paper.A4,
        copies: Int = 1,
    ): Result {
        val geom = PclxlPage.Geometry.of(dpi, paper)

        // Render page 1 first to learn the true pixel size of the job.
        val first = source.next(1) ?: return Result.Failed(0, "no pages")
        var sent = 0L
        var pageStart = System.currentTimeMillis()
        
        // FAST PATH: Use PCL5 mono for black & white printing
        if (grayscale) {
            return sendMonoPages(usb, dpi, jobName, source, onStatus, landscape, paper, copies)
        }
        
        try {
            if (JobControl.isCancelled) return Result.Cancelled(0)
            usb.sendPrint(buildHeader(dpi, grayscale, jobName, copies))
            PclxlPage.writePage(
                usb.printStream(), first.jpeg, first.width, first.height,
                geom, grayscale, landscape, mediaName = paper.pclName,
            )
            sent += first.jpeg.size
            val copySuffix = if (copies > 1) " x$copies (printer)" else ""
            onStatus("page 1/${first.totalPages} sent$copySuffix")

            for (p in 2..first.totalPages) {
                if (JobControl.isCancelled) return Result.Cancelled(sent)
                val page = source.next(p)
                    ?: return Result.Failed(sent, "renderer stopped at page $p")
                PclxlPage.writePage(
                    usb.printStream(), page.jpeg, page.width, page.height,
                    geom, grayscale, landscape, mediaName = paper.pclName,
                )
                sent += page.jpeg.size
                val ms = System.currentTimeMillis() - pageStart
                pageStart = System.currentTimeMillis()
                android.util.Log.d("M175", "send page $p/${page.totalPages}: " +
                        "${ms}ms (${page.jpeg.size / 1024}KB)")
                onStatus("page $p/${page.totalPages} sent")
            }

            if (JobControl.isCancelled) return Result.Cancelled(sent)
            usb.sendPrint(buildFooter(jobName))
            return Result.Ok(sent)
        } catch (e: com.m175astudio.usb.PrintCancelledException) {
            // user pressed Cancel while pages were being streamed
            return Result.Cancelled(sent)
        } catch (e: java.io.IOException) {
            // transfer died mid-session -> surface as Failed so the caller
            // can rescue the printer (otherwise the PCL XL session stays open
            // and the LCD hangs on the dead job)
            return Result.Failed(sent, "transfer failed: ${e.message}")
        } finally {
            // release the page payload as we go
            first.jpeg.fill(0)
        }
    }

    /**
     * FAST MONO PATH: Uses PCL5 with 1-bit RLE compression (*b#W).
     * This is 3-5x faster than PCL XL grayscale JPEG and matches
     * the official HP plugin's speed for black & white printing.
     * 
     * Note: The M175a firmware REJECTS PCL XL with 1-bit RLE but ACCEPTS
     * PCL5 with *b#W. This is the key to fast mono printing.
     */
    private fun sendMonoPages(
        usb: UsbPrinterConnection,
        dpi: Int,
        jobName: String,
        source: PageSource,
        onStatus: (String) -> Unit,
        landscape: Boolean,
        paper: Paper,
        copies: Int
    ): Result {
        var sent = 0L
        var pageStart = System.currentTimeMillis()
        
        try {
            // Get first page to determine total
            val first = source.next(1) ?: return Result.Failed(0, "no pages")
            val totalPages = first.totalPages
            
            if (JobControl.isCancelled) return Result.Cancelled(0)
            
            // For mono, we need to convert JPEG back to bitmap for RLE encoding
            // This is the trade-off: CPU for compression vs. tiny file size
            val firstBmp = BitmapFactory.decodeByteArray(first.jpeg, 0, first.jpeg.size)
            
            // Write PCL5 job header (only once for entire job)
            Pcl5MonoWriter.writeMonoJobHeader(usb.printStream(), dpi, copies, jobName)
            
            // Write first page
            Pcl5MonoWriter.writeMonoPage(
                usb.printStream(), firstBmp, dpi, paper
            )
            sent += estimateMonoSize(first.width, first.height)
            firstBmp.recycle()
            first.jpeg.fill(0)
            
            onStatus("page 1/$totalPages sent (PCL5 mono)")
            
            // Write remaining pages
            for (p in 2..totalPages) {
                if (JobControl.isCancelled) return Result.Cancelled(sent)
                
                val page = source.next(p) ?: return Result.Failed(sent, "renderer stopped at page $p")
                val bmp = BitmapFactory.decodeByteArray(page.jpeg, 0, page.jpeg.size)
                
                Pcl5MonoWriter.writeMonoPage(
                    usb.printStream(), bmp, dpi, paper
                )
                sent += estimateMonoSize(page.width, page.height)
                bmp.recycle()
                page.jpeg.fill(0)
                
                val ms = System.currentTimeMillis() - pageStart
                pageStart = System.currentTimeMillis()
                android.util.Log.d("M175", "send mono page $p/$totalPages: ${ms}ms")
                onStatus("page $p/$totalPages sent (PCL5 mono)")
            }
            
            // Write PCL5 job footer (only once at end)
            Pcl5MonoWriter.writeMonoJobFooter(usb.printStream(), jobName)
            
            return Result.Ok(sent)
        } catch (e: com.m175astudio.usb.PrintCancelledException) {
            return Result.Cancelled(sent)
        } catch (e: java.io.IOException) {
            return Result.Failed(sent, "mono transfer failed: ${e.message}")
        }
    }

    /** Estimate size of mono RLE data (for progress tracking) */
    private fun estimateMonoSize(width: Int, height: Int): Long {
        // PCL5 mono RLE is typically 1/6 to 1/10 of JPEG size
        // For estimation: (width * height) / 8 / 6 (average compression)
        return (width.toLong() * height * 1L) / 48
    }



    private fun buildHeader(dpi: Int, grayscale: Boolean, jobName: String,
                            copies: Int = 1): ByteArray {
        val out = java.io.ByteArrayOutputStream(512)
        // resetFirst = false: a per-job printer RESET is not what the Windows
        // driver sends and it forces the engine to re-initialize on every
        // job. RESET is reserved for the recovery path (@PJL RESET via
        // UsbPrinterConnection.resetJobState).
        PclxlPage.writeSessionOpen(out, dpi, grayscale, jobName,
            resetFirst = false, copies = copies)
        return out.toByteArray()
    }

    private fun buildFooter(jobName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(64)
        PclxlPage.writeSessionClose(out, jobName)
        return out.toByteArray()
    }
}
