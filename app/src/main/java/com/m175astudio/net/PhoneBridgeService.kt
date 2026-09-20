package com.m175astudio.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.m175astudio.MainActivity
import com.m175astudio.print.JobControl
import com.m175astudio.print.PagePlacement
import com.m175astudio.print.PageRenderer
import com.m175astudio.print.Paper
import com.m175astudio.print.PclxlPage
import com.m175astudio.print.PrintConnectionBridge
import com.m175astudio.print.PrintTransmitter
import com.m175astudio.scan.LedmScanClient
import com.m175astudio.scan.ScanAutoLevels
import com.m175astudio.scan.ScanControl
import com.m175astudio.scan.WscnScanClient
import com.m175astudio.usb.UsbPrinterConnection
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hosts the phone bridge in the background.
 *
 * This is what removes the old "keep the app open and charging" limit:
 *  - foreground service (persistent notification + Stop action) so Android
 *    does not kill the server when the screen goes off or the app is
 *    swiped away,
 *  - partial wake lock + high-perf Wi-Fi lock so the CPU and radio stay up
 *    for the whole hosted session,
 *  - owns the single USB claim while hosting (the activity hands it over),
 *  - owns the [EngineQueue] worker: remote print/scan jobs run FIFO, one
 *    at a time, and later phones wait their turn instead of getting an
 *    instant "busy" rejection.
 *
 * Start via ACTION_START (port extra, default 8080); stop via ACTION_STOP
 * or the notification button. State is mirrored in the companion for the
 * Setup UI (same process, no binder needed).
 */
class PhoneBridgeService : Service() {

    companion object {
        const val ACTION_START = "com.m175astudio.bridge.START"
        const val ACTION_STOP = "com.m175astudio.bridge.STOP"
        const val EXTRA_PORT = "port"

        const val CH_BRIDGE = "m175_bridge"
        const val NOTIF_ID = 1001

        /** True while the server socket is bound and serving. */
        @Volatile var isHosting: Boolean = false
            private set

        /** e.g. "http://192.168.43.1:8080" once serving. */
        @Volatile var servingUrl: String? = null
            private set

        /** Last fatal start/serve error for the Setup UI. */
        @Volatile var lastError: String? = null
            private set

        /** Jobs waiting in the lane (excludes the running one). */
        @Volatile var queueDepth: Int = 0
            private set

        /** What the engine is doing right now, or null when idle. */
        @Volatile var currentJob: String? = null
            private set

        /** Remote jobs fully served since start. */
        @Volatile var jobsServed: Int = 0
            private set

        /**
         * Last remote HTTP hit, e.g. "POST /ipp/print printed 820KB".
         * The key bridge diagnostic: if the client taps Test/print and this
         * never changes, packets never arrive (wrong IP / hotspot isolation);
         * if it shows REJECTED/FAILED, traffic arrives and the app refused it.
         */
        @Volatile var lastRemoteHit: String? = null
            private set
    }

    private var conn: UsbPrinterConnection? = null
    private var server: PhoneBridgeServer? = null
    private val engine = EngineQueue()
    private var worker: Thread? = null
    @Volatile private var workerRunning = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val served = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 8080)?.coerceIn(1, 65535) ?: 8080
                if (!isHosting) {
                    try {
                        startHosting(port)
                    } catch (e: Exception) {
                        lastError = e.message ?: "start failed"
                        shutdown()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                return START_STICKY
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    private fun startHosting(port: Int) {
        lastError = null
        ensureBridgeChannel()
        // Foreground FIRST (required within ~5s of startForegroundService).
        refreshNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        }

        // USB claim: the app must have been granted permission before
        // (permission is per-app UID, so the service inherits it).
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        val c = UsbPrinterConnection(applicationContext)
        val dev = c.findPrinter()
            ?: throw java.io.IOException("printer not found on OTG — plug it in first")
        if (!um.hasPermission(dev)) {
            throw SecurityException("USB permission missing — open the app once and accept it")
        }
        if (!c.open(dev)) throw java.io.IOException("could not claim printer USB")
        conn = c
        PrintConnectionBridge.registerAppConnection(c)

        // Keep CPU + radio awake for the whole session.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "M175:bridge").apply {
            acquire(12 * 60 * 60 * 1000L) // 12h cap; released on stop
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "M175:bridge").apply {
            acquire()
        }

        // Engine worker: single lane, FIFO.
        workerRunning = true
        worker = Thread({
            while (workerRunning) {
                currentJob = engine.headLabel
                queueDepth = engine.waiting
                refreshNotification(currentJob)
                if (!engine.step()) break
                currentJob = null
                queueDepth = engine.waiting
                refreshNotification(null)
            }
        }, "phone-bridge-engine")
        worker!!.isDaemon = true
        worker!!.start()

        // HTTP front door.
        val srv = PhoneBridgeServer(
            applicationContext, port,
            PhoneBridgeServer.Handlers(
                isUsbReady = { conn?.isOpen == true },
                isBusy = { JobControl.active || ScanControl.active || engine.waiting > 0 },
                queueDepth = { engine.waiting },
                jobsServed = { served.get() },
                onRequest = { m, p, n ->
                    lastRemoteHit = "$m $p $n".trim()
                },
                printDocument = { doc, hint ->
                    // Block the HTTP thread until our FIFO turn comes
                    // (office queue waits instead of failing fast).
                    engine.submit<Boolean>("print ${doc.size / 1024}KB") {
                        doPrint(doc, hint)
                    }.get(30, TimeUnit.MINUTES)
                },
                scan = { dpi, mode ->
                    engine.submit<ByteArray>("scan ${dpi}dpi $mode") {
                        doScan(dpi, mode)
                    }.get(10, TimeUnit.MINUTES)
                },
            )
        )
        val bound = srv.start()
        server = srv
        val ip = DeviceIp.best() ?: "this-phone"
        servingUrl = "http://$ip:$bound"
        isHosting = true
        refreshNotification(null)
        android.util.Log.d("M175", "phone bridge hosting on $servingUrl")
    }

    private fun shutdown() {
        workerRunning = false
        worker?.interrupt()
        worker = null
        runCatching { server?.stop() }
        server = null
        conn?.let { PrintConnectionBridge.unregisterAppConnection(it) }
        runCatching { conn?.close() }
        conn = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        isHosting = false
        servingUrl = null
        currentJob = null
        queueDepth = 0
        lastRemoteHit = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ engine work
    // Runs on the single worker thread. Blocking is intended.

    private fun settings(): Map<String, Any> {
        val p = getSharedPreferences("m175", MODE_PRIVATE)
        return mapOf(
            "printDpi" to p.getInt("printDpi", 300),
            "printGray" to p.getBoolean("printGray", false),
            "paperName" to (p.getString("paperName", null) ?: Paper.A4.name),
            "placeFit" to p.getInt("placeFit", 0),
            "placeOrient" to p.getInt("placeOrient", 0),
            "placeMargin" to p.getInt("placeMargin", 0),
            "placePos" to p.getInt("placePos", 4),
            "wscn" to p.getBoolean("wscn", true),
        )
    }

    private fun placementOf(s: Map<String, Any>): PagePlacement.Placement {
        val fit = when (s["placeFit"] as Int) {
            1 -> PagePlacement.FitMode.ACTUAL_SIZE
            2 -> PagePlacement.FitMode.SHRINK_FIT
            else -> PagePlacement.FitMode.FIT_PAGE
        }
        val orient = if ((s["placeOrient"] as Int) == 1)
            PagePlacement.Orientation.LANDSCAPE else PagePlacement.Orientation.PORTRAIT
        val m = when (s["placeMargin"] as Int) {
            1 -> PagePlacement.MarginsMm.all(10)
            2 -> PagePlacement.MarginsMm.all(20)
            3 -> PagePlacement.MarginsMm.all(25)
            else -> PagePlacement.MarginsMm.NONE
        }
        val pos = s["placePos"] as Int
        return PagePlacement.Placement(fit, orient, m, (pos % 3) / 2f, (pos / 3) / 2f)
    }

    private fun doPrint(doc: ByteArray, hint: String): Boolean {
        val usb = conn?.takeIf { it.isOpen } ?: return false
        usb.cancelRequested = false // never inherit a stale cancel latch
        val isPdf = hint == "pdf" ||
                (doc.size >= 4 && doc[0] == '%'.code.toByte() && doc[1] == 'P'.code.toByte())
        return try {
            JobControl.begin()
            val ok = if (isPdf) {
                val f = File(cacheDir, "bridge-print.pdf")
                f.writeBytes(doc)
                printPdfFile(usb, f)
            } else {
                printImageBytes(usb, doc)
            }
            if (ok) {
                served.incrementAndGet()
                jobsServed = served.get()
            }
            ok
        } catch (e: Exception) {
            android.util.Log.d("M175", "phoneBridge print FAILED: ${e.message}")
            try { usb.resetJobState() } catch (_: Exception) {}
            false
        } finally {
            try { JobControl.end() } catch (_: Exception) {}
        }
    }

    private fun printPdfFile(usb: UsbPrinterConnection, pdf: File): Boolean {
        val s = settings()
        val dpi = s["printDpi"] as Int
        val gray = s["printGray"] as Boolean
        val paper = Paper.fromSaved(s["paperName"] as String)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val total = renderer.pageCount
                if (total <= 0) return false
                // JPEG-only raster: 1-bit RLE dies on the panel with
                // "PCL XL ERROR / Subsystem: image" (cheetraster.e).
                PageRenderer.multipageHint = total > 3 && gray
                try {
                    val scale = dpi / 72f
                    var idx = 0
                    val res = PrintTransmitter.sendPages(
                        usb, dpi, gray, "PHONE-BRIDGE", paper = paper,
                        source = { _ ->
                            if (idx >= total) null
                            else renderer.openPage(idx).use { page ->
                                idx++
                                val rp = PageRenderer.renderPageToJpeg(page, scale, gray)
                                PrintTransmitter.RenderedPage(
                                    rp.jpeg, rp.width, rp.height, idx, total)
                            }
                        },
                    )
                    return res is PrintTransmitter.Result.Ok
                } finally {
                    PageRenderer.multipageHint = false
                }
            }
        }
    }

    private fun printImageBytes(usb: UsbPrinterConnection, doc: ByteArray): Boolean {
        val s = settings()
        val dpi = s["printDpi"] as Int
        val gray = s["printGray"] as Boolean
        val paper = Paper.fromSaved(s["paperName"] as String)
        val src = BitmapFactory.decodeByteArray(doc, 0, doc.size) ?: return false
        try {
            val (dw, dh) = paper.pagePx(dpi, false)
            val placed = PagePlacement.render(src, dw, dh, placementOf(s), dpi)
            src.recycle()
            val page = PageRenderer.finishPlacedPage(placed, gray)
            val stream = PclxlPage.buildStream(
                listOf(page.jpeg), page.width, page.height,
                dpi = dpi, grayscale = gray, jobName = "PHONE-BRIDGE", paper = paper)
            return PageRenderer.transmit(usb, stream) is PrintTransmitter.Result.Ok
        } finally {
            runCatching { src.recycle() }
        }
    }

    private fun doScan(dpi: Int, colorMode: String): ByteArray {
        val usb = conn?.takeIf { it.isOpen }
            ?: throw java.io.IOException("printer not connected")
        val gray = colorMode != "RGB24"
        val lineart = colorMode == "BlackAndWhite1"
        // Native-lattice rule (same as local scans): coherent at 300,
        // resample below, 600 is the real optical step (1200 removed).
        val engineDpi = if (dpi > 300) 600 else 300
        ScanControl.begin()
        try {
            val wscn = settings()["wscn"] as Boolean
            val jpeg: ByteArray = if (wscn) {
                WscnScanClient.dumpDir = getExternalFilesDir(null)
                val wc = WscnScanClient(usb)
                try {
                    wc.scanFlatbed(engineDpi, if (gray) "GrayScale8" else "RGB24",
                        inputSource = "Platen",
                        log = { android.util.Log.d("M175", it) })
                } catch (e: Exception) {
                    runCatching { wc.cancelActive { android.util.Log.d("M175", it) } }
                    throw e
                }
            } else {
                LedmScanClient(usb).scanFlatbed(engineDpi,
                    if (gray) "Grayscale" else "RGB24",
                    inputSource = "Platen",
                    log = { android.util.Log.d("M175", it) })
            }
            val working = if (dpi < 300) ScanAutoLevels.downsampleToDpi(jpeg, dpi, 300) else jpeg
            val leveled = ScanAutoLevels.fix(working)
            return if (lineart) ScanAutoLevels.toLineart(leveled) else leveled
        } finally {
            try { ScanControl.end() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------ notification

    private fun ensureBridgeChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_BRIDGE, "Wi-Fi bridge hosting",
                NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(state: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PhoneBridgeService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val url = servingUrl
        val text = listOfNotNull(
            url,
            state ?: currentJob,
            if (queueDepth > 0) "queue: $queueDepth" else null,
            "${served.get()} served",
        ).joinToString(" • ")
        return NotificationCompat.Builder(this, CH_BRIDGE)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("M175a bridge hosting")
            .setContentText(text.ifBlank { "starting…" })
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun refreshNotification(state: String?) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(state))
        }
    }
}
