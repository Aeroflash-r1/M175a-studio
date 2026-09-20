package com.ganesan.m175otg

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as GColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.ganesan.m175otg.net.BridgeClient
import com.ganesan.m175otg.net.DeviceIp
import com.ganesan.m175otg.net.PhoneBridgeService
import com.ganesan.m175otg.print.MonoRaster
import com.ganesan.m175otg.print.PreviewHelper
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.ganesan.m175otg.print.JobControl
import com.ganesan.m175otg.print.ManualDuplexPlanner
import com.ganesan.m175otg.print.PageLayout
import com.ganesan.m175otg.print.PagePlacement
import androidx.compose.foundation.horizontalScroll
import com.ganesan.m175otg.print.Paper
import com.ganesan.m175otg.print.PageRenderer
import com.ganesan.m175otg.print.PrintConnectionBridge
import com.ganesan.m175otg.print.PclxlPage
import com.ganesan.m175otg.print.PrintTransmitter
import com.ganesan.m175otg.scan.LedmScanClient
import com.ganesan.m175otg.scan.ScanCancelledException
import com.ganesan.m175otg.scan.ScanControl
import com.ganesan.m175otg.scan.ScanPdfWriter
import com.ganesan.m175otg.scan.ScannerBlockedException
import com.ganesan.m175otg.scan.WscnScanClient
import com.ganesan.m175otg.scan.ScanAutoLevels
import com.ganesan.m175otg.usb.BidiHttpClient
import com.ganesan.m175otg.usb.TonerParser
import com.ganesan.m175otg.usb.UsbPrinterConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var usb: UsbPrinterConnection
    private val bidi by lazy { BidiHttpClient(usb) }

    // -------- printer state
    private var connected by mutableStateOf(false)
    private var status by mutableStateOf("Printer: checking...")
    private var busy by mutableStateOf(false)
    private var busyLabel by mutableStateOf("")
    private var consumables by mutableStateOf(listOf<TonerParser.Detailed>())
    private var usage by mutableStateOf<BidiHttpClient.Usage?>(null)
    private var scanCaps by mutableStateOf<LedmScanClient.ScannerCaps?>(null)
    /** last successful caps read — shown when a refresh hiccups */
    private var lastGoodCaps by mutableStateOf<LedmScanClient.ScannerCaps?>(null)
    private var scannerWarning by mutableStateOf<String?>(null)
    /** set when the last scan attempt failed -> gates the scanner warning */
    private var lastScanFailed by mutableStateOf(false)

    // -------- print settings
    private var printDpi by mutableStateOf(600)
    private var grayscale by mutableStateOf(false)
    /** Windows-speed 1-bit RLE mono (default ON for greyscale). */
    private var fastMono by mutableStateOf(true)
    private var ditherMono by mutableStateOf(false)

    // -------- connection: 0 = USB OTG direct, 1 = Wi-Fi via PC bridge
    private var connMode by mutableIntStateOf(0)
    private var bridgeHost by mutableStateOf("192.168.137.1")
    private var bridgePort by mutableStateOf(8080)
    private var bridgeStatusText by mutableStateOf("Not tested yet")

    // -------- print preview (doc preview before printing)
    private var showPreview by mutableStateOf(false)
    private var previewTitle by mutableStateOf("")
    private var previewPlanText by mutableStateOf("")
    private var previewObj: PreviewHelper.Preview? by mutableStateOf(null)
    private var pendingPreviewPdf: File? = null
    private var pendingPreviewDuplex: Boolean = false
    private var pendingPreviewUriStr: String? = null

    private fun bridge(): BridgeClient = BridgeClient(bridgeHost.trim(), bridgePort)
    private fun useWifi(): Boolean = connMode == 1

    // ------------------------------------------------- phone bridge hosting
    // Hosting itself lives in PhoneBridgeService (foreground service with
    // wake/Wi-Fi locks + FIFO engine queue) — the activity only starts/stops
    // it and mirrors its state, so hosting survives screen-off and swiping
    // the app away.
    private var phoneBridgeRunning by mutableStateOf(false)
    private var phoneBridgeInfo by mutableStateOf("Stopped")

    private val bridgeNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPhoneBridgeService()
        else Toast.makeText(this,
            "Notifications needed for the hosting indicator — then tap Host again",
            Toast.LENGTH_LONG).show()
    }

    /** Start/stop hosting the Wi-Fi bridge on THIS phone (old-phone role). */
    private fun togglePhoneBridge() {
        if (PhoneBridgeService.isHosting || phoneBridgeRunning) {
            val stop = Intent(this, PhoneBridgeService::class.java)
                .setAction(PhoneBridgeService.ACTION_STOP)
            startService(stop)
            lifecycleScope.launch {
                kotlinx.coroutines.delay(600)
                syncBridgeUi()
                // Reclaim our own USB claim now the service released it.
                tryConnect()
            }
            phoneBridgeRunning = false
            phoneBridgeInfo = "Stopping…"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) {
            bridgeNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startPhoneBridgeService()
    }

    private fun startPhoneBridgeService() {
        if (!usb.isOpen) {
            Toast.makeText(this, "Plug the printer via OTG first",
                Toast.LENGTH_SHORT).show()
            tryConnect()
            return
        }
        // Hand the single USB claim to the service: drop ours first so its
        // claimInterface() cannot fail or steal mid-job.
        PrintConnectionBridge.unregisterAppConnection(usb)
        usb.close()
        val intent = Intent(this, PhoneBridgeService::class.java)
            .setAction(PhoneBridgeService.ACTION_START)
            .putExtra(PhoneBridgeService.EXTRA_PORT, bridgePort)
        ContextCompat.startForegroundService(this, intent)
        val ip = DeviceIp.best() ?: "this-phone"
        phoneBridgeRunning = true
        phoneBridgeInfo = "Starting on http://$ip:$bridgePort…"
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            syncBridgeUi()
            if (!PhoneBridgeService.isHosting) {
                // Start failed (no USB permission, port busy…) — take USB back.
                phoneBridgeRunning = false
                tryConnect()
            }
        }
    }

    /** Mirrors service state into Setup UI (called on resume + after toggles). */
    private fun syncBridgeUi() {
        phoneBridgeRunning = PhoneBridgeService.isHosting
        phoneBridgeInfo = when {
            PhoneBridgeService.isHosting -> {
                val url = PhoneBridgeService.servingUrl ?: "starting…"
                val extra = listOfNotNull(
                    PhoneBridgeService.currentJob,
                    if (PhoneBridgeService.queueDepth > 0)
                        "${PhoneBridgeService.queueDepth} waiting" else null,
                    if (PhoneBridgeService.jobsServed > 0)
                        "${PhoneBridgeService.jobsServed} served" else null,
                ).joinToString(" • ")
                "Serving on $url" + if (extra.isNotEmpty()) " — $extra" else "" +
                        ". Runs in background: screen can sleep, app can be swiped away."
            }
            PhoneBridgeService.lastError != null ->
                "Failed: ${PhoneBridgeService.lastError}"
            else -> "Stopped"
        }
    }

    // -------- scan settings (persisted)
    private val prefs by lazy { getSharedPreferences("m175", MODE_PRIVATE) }
    private var scanDpi by mutableIntStateOf(300)
    private var scanGray by mutableStateOf(false)
    private var scanLineart by mutableStateOf(false)
    private var wscnMode by mutableStateOf(true)
    private var adfMode by mutableStateOf(false)

    // -------- scan result (thumbnail + one-line status)
    private var lastScanBmp by mutableStateOf<Bitmap?>(null)
    private var lastScanText by mutableStateOf("No scan yet")
    private var lastScanWarn by mutableStateOf(false)

    // -------- duplex
    private var showFlipDialog by mutableStateOf(false)
    private var flipGate: CompletableDeferred<Unit>? = null
    private var pendingDuplex = false

    // -------- print options
    private var copies by mutableStateOf(1)
    private var pageRangeExpr by mutableStateOf("")
    private var reverseOrder by mutableStateOf(false)
    private var skipBlank by mutableStateOf(false)
    private var parityMode by mutableStateOf(0)   // 0=off 1=odd 2=even
    private var nUpMode by mutableStateOf(1)      // 1=normal 2=2-up 4=4-up
    private var bookletMode by mutableStateOf(false)

    // -------- page placement (scale / orientation / margins / position)
    private var placeFit by mutableStateOf(0)     // 0=fit 1=actual 2=shrink
    private var placeOrient by mutableStateOf(0)  // 0=portrait 1=landscape
    private var placeMargin by mutableStateOf(0)  // 0=none 1=10mm 2=20mm 3=25mm
    private var placePos by mutableStateOf(4)     // 3x3 grid, 4=center
    private var paperIdx by mutableStateOf(0)     // Paper.entries index

    private val paper: Paper get() = Paper.byIndex(paperIdx)

    private fun placement(): PagePlacement.Placement {
        val fit = when (placeFit) {
            1 -> PagePlacement.FitMode.ACTUAL_SIZE
            2 -> PagePlacement.FitMode.SHRINK_FIT
            else -> PagePlacement.FitMode.FIT_PAGE
        }
        val orient = if (placeOrient == 1) PagePlacement.Orientation.LANDSCAPE
                     else PagePlacement.Orientation.PORTRAIT
        val m = when (placeMargin) {
            1 -> PagePlacement.MarginsMm.all(10)
            2 -> PagePlacement.MarginsMm.all(20)
            3 -> PagePlacement.MarginsMm.all(25)
            else -> PagePlacement.MarginsMm.NONE
        }
        val px = (placePos % 3) / 2f          // 0,1,2 -> 0, 0.5, 1
        val py = (placePos / 3) / 2f          // 0..2 ->0, 3..5->.5, 6..8->1
        return PagePlacement.Placement(fit, orient, m, px, py)
    }

    // -------- multi-page scan -> single PDF
    private var pdfMode by mutableStateOf(false)
    private val scanPages = mutableListOf<ByteArray>()
    private var lastScanUri: Uri? = null

    // -------- job history (tap a PDF to reprint)
    private data class JobItem(val title: String, val sub: String,
                               val kind: String, val data: String,
                               val gray: Boolean, val dpi: Int,
                               val at: Long = System.currentTimeMillis())
    private val jobHistory = mutableStateListOf<JobItem>()

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            printPdf(it, pendingDuplex, copies, pageRangeExpr)
        }
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) printImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        usb = UsbPrinterConnection(this)
        PrintConnectionBridge.registerAppConnection(usb)
        scanDpi = prefs.getInt("scanDpi", 300)
        if (scanDpi == 1200) {
            // 1200 dpi optical step removed: unusably slow (~15+ min/page),
            // huge RAM (530 MB ARGB), marginal gain over 600 on this engine.
            scanDpi = 600
            prefs.edit().putInt("scanDpi", 600).apply()
        }
        if (scanDpi < 200) scanDpi = 200        // 150 dpi was removed (useless)
        scanGray = prefs.getBoolean("scanGray", false)
        scanLineart = prefs.getBoolean("scanLineart", false)
        wscnMode = prefs.getBoolean("wscn", true)
        adfMode = prefs.getBoolean("adf", false)
        placeFit = prefs.getInt("placeFit", 0)
        placeOrient = prefs.getInt("placeOrient", 0)
        placeMargin = prefs.getInt("placeMargin", 0)
        placePos = prefs.getInt("placePos", 4)  // center
        // print settings persist too (last-used quality — Windows-driver parity)
        printDpi = prefs.getInt("printDpi", 300)
        grayscale = prefs.getBoolean("printGray", false)
        fastMono = prefs.getBoolean("fastMono", true)
        ditherMono = prefs.getBoolean("ditherMono", false)
        connMode = prefs.getInt("connMode", 0)
        bridgeHost = prefs.getString("bridgeHost", "192.168.137.1") ?: "192.168.137.1"
        bridgePort = prefs.getInt("bridgePort", 8080)
        paperIdx = Paper.fromSaved(prefs.getString("paperName", null)).ordinal
        setContent { M175App() }
        tryConnect()
        handleIntents(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    override fun onResume() {
        super.onResume()
        syncBridgeUi()
        tryConnect()
    }

    override fun onDestroy() {
        // Hosting (if active) belongs to PhoneBridgeService and outlives the
        // activity — never stop it here. Only release OUR claim when we own
        // it (while hosting, the service owns the claim).
        if (!PhoneBridgeService.isHosting) {
            PrintConnectionBridge.unregisterAppConnection(usb)
            usb.close()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    /**
     * Wrapping chip group — the cutoff fix.
     *
     * Root cause of the border cutoff: plain Rows with 4+ FilterChips and
     * 8.dp spacing overflow a 360.dp phone (chips never shrink, so the last
     * chip draws past the card edge and clips). FlowRow wraps to a second
     * line instead, so nothing ever draws outside the card on any width.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ChipFlow(content: @Composable () -> Unit) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }

    @Composable
    private fun HelperText(text: String) {
        Text(text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun M175App() {
        val ctx = LocalContext.current
        MaterialTheme(colorScheme = dynamicLightColorScheme(ctx)) {
            val notifPerm = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx)) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            var tab by remember { mutableIntStateOf(0) }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Print, null) }, label = { Text("Print") })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Scanner, null) }, label = { Text("Scan") })
                        NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Info, null) }, label = { Text("Printer") })
                        NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Setup") })
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        0 -> PrintTab()
                        1 -> ScanTab()
                        2 -> PrinterTab()
                        3 -> SetupTab()
                    }
                }
                if (showFlipDialog) FlipDialog()
                if (showPreview) PreviewDialog()
                if (busy) BusyOverlay()
            }
        }
    }

    @Composable
    private fun PrintTab() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Print", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            // Connection banner: one glance shows USB vs Wi-Fi path.
            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (useWifi())
                        MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(
                        if (useWifi()) Color(0xFF1565C0)
                        else if (connected) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.error,
                        RoundedCornerShape(5.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (useWifi()) "Wi-Fi via PC bridge ($bridgeHost:$bridgePort)"
                        else if (connected) "USB OTG — M175a connected"
                        else "USB OTG — printer not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Quality")
                    ChipFlow {
                        FilterChip(selected = printDpi == 300,
                            onClick = {
                                printDpi = 300
                                prefs.edit().putInt("printDpi", 300).apply()
                            }, label = { Text("Draft 300") })
                        FilterChip(selected = printDpi == 600,
                            onClick = {
                                printDpi = 600
                                prefs.edit().putInt("printDpi", 600).apply()
                            }, label = { Text("Best 600") })
                    }
                    HelperText("Draft 300 is ~4x faster with identical text quality. " +
                            "Best 600 for photos/fine graphics.")
                    ChipFlow {
                        FilterChip(selected = grayscale,
                            onClick = {
                                grayscale = !grayscale
                                prefs.edit().putBoolean("printGray", grayscale).apply()
                            },
                            label = { Text(if (grayscale) "Greyscale ✓" else "Greyscale") })
                    }
                    if (grayscale) {
                        ChipFlow {
                            FilterChip(selected = fastMono,
                                onClick = {
                                    fastMono = !fastMono
                                    prefs.edit().putBoolean("fastMono", fastMono).apply()
                                },
                                label = { Text("Fast B&W 1-bit (Windows-speed)") })
                        }
                        HelperText("Fast B&W sends 1-bit RLE pages (5-10x smaller, " +
                                "no printer JPEG decode) — multi-page documents " +
                                "print at engine speed like the Windows driver. " +
                                "Turn off only for gray photos.")
                        if (fastMono) {
                            ChipFlow {
                                FilterChip(selected = ditherMono,
                                    onClick = {
                                        ditherMono = !ditherMono
                                        prefs.edit().putBoolean("ditherMono", ditherMono).apply()
                                    },
                                    label = { Text("Photo dither") })
                            }
                        }
                    }
                    HorizontalDivider()
                    SectionTitle("Document")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = copies.toString(),
                            onValueChange = { copies = it.toIntOrNull()?.coerceIn(1, 99) ?: 1 },
                            label = { Text("Copies") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = pageRangeExpr,
                            onValueChange = { pageRangeExpr = it.take(40) },
                            label = { Text("Pages") },
                            singleLine = true,
                            placeholder = { Text("1-3, 5, 8-10") },
                            modifier = Modifier.weight(2f))
                    }
                    HelperText("Pages: ranges like 1-3, 5 or empty for all")
                    HorizontalDivider()
                    SectionTitle("Page order")
                    ChipFlow {
                        FilterChip(selected = parityMode == 0,
                            onClick = { parityMode = 0 }, label = { Text("All") })
                        FilterChip(selected = parityMode == 1,
                            onClick = { parityMode = 1 }, label = { Text("Odd only") })
                        FilterChip(selected = parityMode == 2,
                            onClick = { parityMode = 2 }, label = { Text("Even only") })
                        FilterChip(selected = reverseOrder,
                            onClick = { reverseOrder = !reverseOrder },
                            label = { Text("Reverse") })
                    }
                    ChipFlow {
                        FilterChip(selected = skipBlank,
                            onClick = { skipBlank = !skipBlank },
                            label = { Text("Skip blank pages") })
                    }
                    HorizontalDivider()
                    SectionTitle("Layout")
                    ChipFlow {
                        FilterChip(selected = nUpMode == 1 && !bookletMode,
                            onClick = { nUpMode = 1; bookletMode = false },
                            label = { Text("Normal") })
                        FilterChip(selected = nUpMode == 2 && !bookletMode,
                            onClick = { nUpMode = 2; bookletMode = false },
                            label = { Text("2-up") })
                        FilterChip(selected = nUpMode == 4 && !bookletMode,
                            onClick = { nUpMode = 4; bookletMode = false },
                            label = { Text("4-up") })
                        FilterChip(selected = bookletMode,
                            onClick = { bookletMode = !bookletMode },
                            label = { Text("Booklet") })
                    }
                    HorizontalDivider()
                    SectionTitle("Page layout")
                    HelperText("Paper size")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Paper.entries.forEachIndexed { i, p ->
                            FilterChip(selected = paperIdx == i,
                                onClick = {
                                    paperIdx = i
                                    prefs.edit().putString("paperName", p.name).apply()
                                },
                                label = { Text(p.label) })
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    ChipFlow {
                        FilterChip(selected = placeFit == 0,
                            onClick = { placeFit = 0; savePlace() },
                            label = { Text("Fit page") })
                        FilterChip(selected = placeFit == 2,
                            onClick = { placeFit = 2; savePlace() },
                            label = { Text("Shrink fit") })
                        FilterChip(selected = placeFit == 1,
                            onClick = { placeFit = 1; savePlace() },
                            label = { Text("Actual size") })
                        FilterChip(selected = placeOrient == 1,
                            onClick = { placeOrient = if (placeOrient == 1) 0 else 1; savePlace() },
                            label = { Text("Landscape") })
                    }
                    HelperText("Margins")
                    ChipFlow {
                        listOf(0 to "None", 1 to "10mm", 2 to "20mm", 3 to "25mm")
                            .forEach { (v, lbl) ->
                                FilterChip(selected = placeMargin == v,
                                    onClick = { placeMargin = v; savePlace() },
                                    label = { Text(lbl) })
                            }
                    }
                    // Position picker — rebuilt. Old flaws: cell size jumped
                    // 26.dp<->30.dp on every tap (grid reflowed), 26.dp missed
                    // the 48.dp touch target, and the side-by-side
                    // "Position:" + grid + "(actual-size mode)" Row overflowed
                    // narrow phones. Now: fixed 44.dp cells, no size jump,
                    // check icon on the selected cell, stacked layout.
                    HelperText("Position (actual-size mode)")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (row in 0..2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (col in 0..2) {
                                        val cell = row * 3 + col
                                        val selected = placePos == cell
                                        val label = when (cell) {
                                            0 -> "Top left"; 1 -> "Top center"
                                            2 -> "Top right"; 3 -> "Middle left"
                                            4 -> "Center"; 5 -> "Middle right"
                                            6 -> "Bottom left"; 7 -> "Bottom center"
                                            else -> "Bottom right"
                                        }
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (selected)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(12.dp))
                                                .border(
                                                    width = if (selected) 2.dp else 1.dp,
                                                    color = if (selected)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = RoundedCornerShape(12.dp))
                                                .clickable {
                                                    placePos = cell; savePlace()
                                                }
                                                .semantics {
                                                    contentDescription = label +
                                                            if (selected) ", selected" else ""
                                                }
                                        ) {
                                            if (selected) {
                                                Icon(Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = {
                        pendingDuplex = false
                        pdfPicker.launch(arrayOf("application/pdf"))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Preview & Print PDF") }
                    Text("Every PDF shows a thumbnail preview before printing — " +
                            "confirm pages, copies and quality first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        imagePicker.launch("image/*")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Print images") }
                    OutlinedButton(onClick = {
                        pendingDuplex = true
                        pdfPicker.launch(arrayOf("application/pdf"))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Manual duplex") }
                    OutlinedButton(onClick = { cancelJob() },
                        modifier = Modifier.fillMaxWidth()) { Text("Cancel job") }
                    if (jobHistory.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Recent jobs", style = MaterialTheme.typography.titleSmall)
                        jobHistory.take(5).forEach { j ->
                            ListItem(headlineContent = { Text(j.title) },
                                supportingContent = { Text("${j.sub} | ${j.dpi} dpi") },
                                trailingContent = {
                                    if (j.kind == "pdf") TextButton(onClick = {
                                        printPdf(Uri.parse(j.data), duplex = false,
                                            copies = 1, rangeExpr = "")
                                    }) { Text("Reprint") }
                                    else TextButton(onClick = {
                                        printImages(j.data.split("\n")
                                            .mapNotNull { u -> u.toUriOrNull() })
                                    }) { Text("Reprint") }
                                })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScanTab() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Scan", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            // status card ALWAYS visible - scan errors were invisible when
            // no thumbnail existed (the text lived inside the thumbnail card)
            Card(Modifier.fillMaxWidth()) {
                Column {
                    lastScanBmp?.let { bmp ->
                        Image(bmp.asImageBitmap(), null,
                            Modifier.fillMaxWidth().heightIn(max = 420.dp)
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                            contentScale = ContentScale.FillWidth)
                    }
                    Text(lastScanText, Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lastScanWarn) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (lastScanUri != null) {
                        Row(Modifier.padding(start = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val u = lastScanUri ?: return@OutlinedButton
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = if (u.toString().endsWith(".pdf"))
                                        "application/pdf" else "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, u)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(send, "Share scan"))
                            }) { Text("Share") }
                            OutlinedButton(onClick = {
                                val u = lastScanUri ?: return@OutlinedButton
                                startActivity(Intent.createChooser(Intent(
                                    Intent.ACTION_VIEW).apply {
                                    setDataAndType(u,
                                        if (u.toString().endsWith(".pdf"))
                                            "application/pdf" else "image/jpeg")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Open scan"))
                            }) { Text("Open") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Resolution")
                    ChipFlow {
                        listOf(200, 300, 600).forEach { d ->
                            FilterChip(selected = scanDpi == d,
                                onClick = { scanDpi = d; prefs.edit().putInt("scanDpi", d).apply() },
                                label = { Text("$d dpi") })
                        }
                    }
                    HelperText("Native sensor step is 300 dpi (200 is resampled from " +
                            "it). 600 uses the glass at full optical resolution " +
                            "(feeder scans at 300). 1200 was removed — 15+ min/page " +
                            "with no visible gain on this engine." +
                            if (useWifi()) " Wi-Fi scans go through the PC bridge (WIA)."
                            else "")
                    HorizontalDivider()
                    SectionTitle("Color")
                    ChipFlow {
                        FilterChip(selected = !scanGray && !scanLineart,
                            onClick = { scanGray = false; scanLineart = false
                                prefs.edit().putBoolean("scanGray", false)
                                    .putBoolean("scanLineart", false).apply() },
                            label = { Text("Color") })
                        FilterChip(selected = scanGray && !scanLineart,
                            onClick = { scanGray = true; scanLineart = false
                                prefs.edit().putBoolean("scanGray", true)
                                    .putBoolean("scanLineart", false).apply() },
                            label = { Text("Greyscale") })
                        FilterChip(selected = scanLineart,
                            onClick = { scanGray = true; scanLineart = true
                                prefs.edit().putBoolean("scanGray", true)
                                    .putBoolean("scanLineart", true).apply() },
                            label = { Text("Black & white") })
                    }
                    HorizontalDivider()
                    ChipFlow {
                        if (scanCaps?.adfSupported == true) {
                            FilterChip(selected = adfMode,
                                onClick = { adfMode = !adfMode; prefs.edit().putBoolean("adf", adfMode).apply() },
                                label = { Text("Feeder (ADF)") })
                        }
                        FilterChip(selected = pdfMode,
                            onClick = { pdfMode = !pdfMode },
                            label = { Text("Save as PDF") })
                    }
                    Button(onClick = { scanFlow() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (adfMode) "Scan from feeder" else "Scan from glass")
                    }
                }
            }
            // Bottom of the tab: rescue actions live here so they never
            // cover the scan button (user request).
            OutlinedButton(onClick = { fixScannerFlow() },
                Modifier.fillMaxWidth()) { Text("Fix scanner") }
        }
    }

    @Composable
    private fun PrinterTab() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Printer", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(
                            if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            RoundedCornerShape(5.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { usb.close(); tryConnect() },
                        modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                }
            }
            scannerWarning?.let { ScannerWarningCard(it) }
            // low-supply alert: any toner at/below 15% (drum excluded from
            // the red alert — it is a ~23000-page part, 15% is still months)
            consumables.filter { it.type == "toner" && it.percent <= 15 }
                .takeIf { it.isNotEmpty() }
                ?.let { lows ->
                    Card(Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement =
                                Arrangement.spacedBy(4.dp)) {
                            Text("Low toner",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                lows.joinToString(", ") { c ->
                                    "${c.name.replaceFirstChar { it.uppercase() }} ${c.percent}%"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            if (consumables.isNotEmpty()) TonerCard()
            usage?.let { u ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Lifetime stats", style = MaterialTheme.typography.titleSmall)
                        Text("${u.total ?: "?"} pages printed  |  ${u.color ?: "?"} color  |  ${u.mono ?: "?"} mono",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            scanCaps?.let { ScannerInfoCard(it) }
        }
    }

    @Composable
    private fun TonerCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Consumables", style = MaterialTheme.typography.titleSmall)
                consumables.forEach { c ->
                    val label = if (c.type == "imageDrum") "Drum" else c.name.replaceFirstChar { it.uppercase() }
                    val frac = (c.percent.coerceIn(0, 100)) / 100f
                    val barColor = when {
                        c.percent <= 15 -> Color(0xFFB3261E)
                        c.percent <= 30 -> Color(0xFFB58500)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Column {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text("${c.percent}%${c.pagesLeft?.let { "  (~$it pages)" } ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (c.percent <= 15) Color(0xFFB3261E)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (c.percent <= 15) FontWeight.Bold else FontWeight.Normal)
                        }
                        LinearProgressIndicator(progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun ScannerWarningCard(msg: String) {
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Scanner needs attention", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }

    @Composable
    private fun ScannerInfoCard(c: LedmScanClient.ScannerCaps) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Scanner", style = MaterialTheme.typography.titleSmall)
                Text("State: ${c.scannerState}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Optical: ${c.opticalDpi} dpi  |  Glass: %.1f\" x %.1f\"".format(
                    c.platenMaxW / 1000f, c.platenMaxH / 1000f),
                    style = MaterialTheme.typography.bodyMedium)
                if (c.adfSupported) {
                    Text("ADF: ${c.adfFeederCapacity} sheets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun SetupTab() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Setup", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Connection")
                    ChipFlow {
                        FilterChip(selected = connMode == 0,
                            onClick = {
                                connMode = 0
                                prefs.edit().putInt("connMode", 0).apply()
                                tryConnect()
                            }, label = { Text("USB OTG") })
                        FilterChip(selected = connMode == 1,
                            onClick = {
                                connMode = 1
                                prefs.edit().putInt("connMode", 1).apply()
                            }, label = { Text("Wi-Fi bridge") })
                    }
                    Text("USB OTG: this phone is cabled to the printer. Wi-Fi bridge: " +
                            "the printer lives on a PC bridge or an old phone hosting " +
                            "the bridge — this phone then prints & scans wirelessly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (connMode == 1) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Bridge address (PC or old phone)")
                        OutlinedTextField(
                            value = bridgeHost,
                            onValueChange = {
                                bridgeHost = it.take(64)
                                prefs.edit().putString("bridgeHost", bridgeHost).apply()
                            },
                            label = { Text("Bridge address") },
                            placeholder = { Text("192.168.137.1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = bridgePort.toString(),
                            onValueChange = {
                                bridgePort = it.toIntOrNull()?.coerceIn(1, 65535) ?: 8080
                                prefs.edit().putInt("bridgePort", bridgePort).apply()
                            },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            setBusy("Testing bridge...")
                            lifecycleScope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    bridge().testConnection()
                                }
                                val st = withContext(Dispatchers.IO) {
                                    bridge().getStatus()
                                }
                                clearBusy()
                                bridgeStatusText = if (ok) {
                                    "Connected — bridge says: " +
                                            "${st?.state ?: "?"} ${st?.detail ?: ""}"
                                } else {
                                    "No bridge at $bridgeHost:$bridgePort — " +
                                            "is run.py serving on the PC, or an old phone hosting?"
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Test connection") }
                        Text(bridgeStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        SectionTitle("Make the printer Wi-Fi in 3 steps")
                        HelperText("PC way: on the PC run cd windows-bridge, install.bat, " +
                                "python run.py (:8080).\n" +
                                "No-PC way: on an old phone open this same Setup screen → " +
                                "Host bridge on this phone.\n" +
                                "Then: join the same Wi-Fi/hotspot, enter the bridge address " +
                                "above, Test, then Print/Scan — PDFs, images, greyscale, " +
                                "preview and copies all work over Wi-Fi.")
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Phone bridge — no PC needed")
                    HelperText("Leave THIS phone plugged into the printer via OTG " +
                            "and it becomes the Wi-Fi printer for other phones. " +
                            "Runs as a background service: the screen can sleep and " +
                            "the app can be swiped away. Keep the phone charging. " +
                            "Uses your current Quality settings; remote jobs queue " +
                            "FIFO so every phone gets its turn.")
                    Text(phoneBridgeInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { togglePhoneBridge() },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (phoneBridgeRunning) "Stop hosting" else "Host bridge on this phone")
                    }
                    HorizontalDivider()
                    SectionTitle("Second phone connects in 3 steps")
                    HelperText("1. Join the same Wi-Fi (or this phone's hotspot)\n" +
                            "2. On the second phone: Setup → Wi-Fi bridge → enter " +
                            "the address shown above → Test\n" +
                            "3. Print & scan wirelessly — PDF, images, greyscale, " +
                            "preview and copies all work. One job at a time.")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Speed tips", style = MaterialTheme.typography.titleSmall)
                    Text("• Greyscale + Fast B&W 1-bit = engine-speed multi-page text\n" +
                            "• Draft 300 for documents, Best 600 for photos\n" +
                            "• Copies print on the engine (one send, not N sends)\n" +
                            "• Preview every job before it leaves the phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun PreviewDialog() {
        AlertDialog(
            onDismissRequest = {
                PreviewHelper.recycle(previewObj)
                previewObj = null
                showPreview = false
            },
            title = { Text(previewTitle.ifBlank { "Print preview" }) },
            text = {
                // Scrollable: on small phones title + 6 thumbs + quality text
                // overflowed the dialog and clipped the buttons. Cap height
                // and scroll instead.
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())) {
                    Text(previewPlanText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val thumbs = previewObj?.thumbs.orEmpty()
                    if (thumbs.isEmpty()) {
                        Text("No pages to show.")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())) {
                            thumbs.forEachIndexed { i, bmp ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(bmp.asImageBitmap(), "page ${i + 1}",
                                        Modifier.width(120.dp).height(160.dp)
                                            .border(1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(8.dp))
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop)
                                    Text("p${i + 1}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if ((previewObj?.truncated) == true) {
                            Text("+${(previewObj?.totalPlanned ?: 0) - (previewObj?.shown ?: 0)} more page(s)…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("Quality: ${printDpi}dpi ${if (grayscale) "greyscale" + if (fastMono) " + fast 1-bit" else "" else "color"} · " +
                            if (useWifi()) "via Wi-Fi bridge" else "via USB OTG",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val pdf = pendingPreviewPdf
                    val duplex = pendingPreviewDuplex
                    PreviewHelper.recycle(previewObj)
                    previewObj = null
                    showPreview = false
                    if (pdf != null) executePdfPrint(pdf, duplex)
                }) { Text("Print now") }
            },
            dismissButton = {
                TextButton(onClick = {
                    PreviewHelper.recycle(previewObj)
                    previewObj = null
                    showPreview = false
                }) { Text("Cancel") }
            },
        )
    }

    @Composable
    private fun FlipDialog() {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Flip the stack now") },
            text = {
                Text(
                    "Take the sheets from the tray as they are, flip the whole " +
                    "stack over (blank sides up, same edge on top), and reload it."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showFlipDialog = false
                    flipGate?.complete(Unit)
                }) { Text("Print side 2") }
            },
            dismissButton = {},
        )
    }

    /** Progress dialog with an in-dialog Cancel (the tab is covered). */
    @Composable
    private fun BusyOverlay() {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(busyLabel) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(42.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { cancelJob() }) {
                    Text(if (ScanControl.active && !JobControl.active)
                        "Cancel scan" else "Cancel job")
                }
            },
            dismissButton = {},
        )
    }

    // ---------------------------------------------------------- connection

    private fun tryConnect() {
        if (PhoneBridgeService.isHosting) {
            // The service owns the single USB claim while hosting — a second
            // claim here would steal it mid-job. Local jobs wait for Stop.
            status = "Hosting bridge — USB owned by the service"
            connected = false
            return
        }
        if (useWifi()) {
            // Wi-Fi mode needs no USB claim — probe the bridge instead.
            status = "Wi-Fi bridge: ${bridgeHost.trim()}:$bridgePort"
            connected = false
            Thread {
                val st = try { bridge().getStatus() } catch (_: Exception) { null }
                status = if (st != null) "Wi-Fi bridge: ${st.state} ${st.detail}".trim()
                else "Wi-Fi bridge unreachable ($bridgeHost:$bridgePort)"
                connected = st != null
                if (st != null) refreshBridgeStatus(st)
            }.start()
            return
        }
        Thread {
            val dev = usb.findPrinter()
            if (dev == null) {
                status = "Not connected"
                connected = false
                return@Thread
            }
            if (!usb.isOpen) {
                usb.requestPermission(dev) { granted ->
                    if (!granted) {
                        status = "USB permission denied"
                        connected = false
                        return@requestPermission
                    }
                    val ok = usb.open(dev)
                    connected = ok
                    status = if (ok) "HP LaserJet 100 MFP M175a"
                             else "Connection failed"
                    if (ok) {
                        refreshStatus()
                        runOnUiThread { processPendingPrint() }
                    }
                }
            }
        }.start()
    }

    /** Bridge /api/status JSON -> toner bars + counters (best-effort parse). */
    private fun refreshBridgeStatus(st: BridgeClient.BridgeStatus) {
        // Bridge STATUS carries toner/state but not LEDM XML detail; surface
        // what exists and keep last USB consumables otherwise.
        usage = usage // unchanged (bridge exposes jobs_sent in status text)
        status = "Wi-Fi bridge: ${st.state} ${st.detail}".trim()
    }

    private fun refreshStatus() {
        if (useWifi()) {
            Thread {
                val st = try { bridge().getStatus() } catch (_: Exception) { null }
                if (st != null) {
                    status = "Wi-Fi bridge: ${st.state} ${st.detail}".trim()
                    connected = true
                }
            }.start()
            return
        }
        if (!usb.isOpen) return
        Thread {
            try {
                val cons = bidi.readConsumables()
                val u = bidi.readUsage()
                consumables = cons
                usage = u
                try {
                    // scanner caps: only overwrite on SUCCESS — a transient
                    // HTTP hiccup must not blank the Scanner info card
                    try {
                        LedmScanClient(usb).getScannerCapabilities()
                            .let { scanCaps = it; lastGoodCaps = it }
                    } catch (e: Exception) {
                        scanLog("refreshStatus: caps read failed (kept last): " +
                                "${e.message?.take(60)}")
                    }
                    scannerWarning = when {
                        scanCaps?.scannerState?.equals("Idle", true) == true -> null
                        scanCaps == null -> null
                        // Only warn when a scan is ALREADY known to have failed
                        // - a busy/Processing state right after a scan is
                        // normal while the lamp cools.
                        lastScanFailed -> "Scanner is not accepting jobs " +
                                "(state: ${scanCaps?.scannerState}). Power-cycle " +
                                "the printer if this persists."
                        else -> null
                    }
                } catch (e: Exception) {
                    // A failed caps read is NOT proof of a wedged engine (the
                    // channel can miss one probe). Do not alarm the user on
                    // guesswork - keep the last known good state, and let the
                    // scan flow itself report a real block.
                    scanLog("refreshStatus: scanner caps failed: ${e.message}")
                }
                val low = cons.filter {
                    (it.type == "toner") && (it.percent <= 15 ||
                            (it.pagesLeft ?: Int.MAX_VALUE) <= 75)
                }
                if (low.isNotEmpty()) {
                    Notifier.lowToner(this, low.map { c ->
                        "${c.name.replaceFirstChar { it.uppercase() }}: ${c.percent}%" +
                                (c.pagesLeft?.let { " (~$it pages left)" } ?: "")
                    })
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    // ------------------------------------------------------------- printing

    private fun setBusy(label: String) { busy = true; busyLabel = label }
    private fun clearBusy() { busy = false }

    // -------- deferred share/open-with print (USB not ready yet)
    private var pendingPrintUri: Uri? = null

    /** Share-sheet / open-with entry: print whatever arrived. */
    private fun handleIntents(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = IntentCompat.getParcelableExtra(intent,
                    Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    intent.removeExtra(Intent.EXTRA_STREAM)
                    queueOrPrint(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = IntentCompat.getParcelableArrayListExtra(intent,
                    Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    intent.removeExtra(Intent.EXTRA_STREAM)
                    if (useWifi() || usb.isOpen) printImages(uris)
                    else { pendingPrintUri = uris.first(); tryConnect() }
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let {
                val d = it; intent.setData(null); queueOrPrint(d)
            }
        }
    }

    /** Print now if the link is up, else connect first and print after. */
    private fun queueOrPrint(uri: Uri) {
        if (useWifi() || usb.isOpen) printIntentUri(uri)
        else {
            pendingPrintUri = uri
            tryConnect()
        }
    }

    /** Runs on the main thread after a successful (re)connect. */
    private fun processPendingPrint() {
        val u = pendingPrintUri ?: return
        pendingPrintUri = null
        printIntentUri(u)
    }

    private fun printIntentUri(uri: Uri) {
        val cr = contentResolver
        val type = cr.getType(uri)
        if (type != null && type.startsWith("image/")) printImages(listOf(uri))
        else printPdf(uri, duplex = false, copies = 1, rangeExpr = "")
    }

    /** Print one or more images (gallery/share) — each becomes one page. */
    private fun printImages(uris: List<Uri>) {
        if (!checkReady()) return
        if (useWifi()) {
            // Wi-Fi: upload each image as its own IPP job (bridge GDI path).
            setBusy("Sending images to bridge...")
            lifecycleScope.launch {
                try {
                    JobControl.begin()
                    var okAll = true
                    withContext(Dispatchers.IO) {
                        for (u in uris.take(20)) {
                            val bytes = contentResolver.openInputStream(u)
                                ?.use { it.readBytes() } ?: continue
                            if (!bridge().printImage(bytes, "Android Wi-Fi image")) {
                                okAll = false
                            }
                        }
                    }
                    JobControl.end()
                    if (okAll) Notifier.jobDone(this@MainActivity,
                        "Images sent (Wi-Fi)", "${uris.size} via bridge")
                    else Toast.makeText(this@MainActivity,
                        "Bridge rejected an image", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity,
                        "Wi-Fi images failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    try { JobControl.end() } catch (_: Exception) {}
                    clearBusy()
                }
            }
            return
        }
        setBusy("Rendering images...")
        val pl = placement()
        val (dw, dh) = paper.pagePx(printDpi,
            pl.orientation == PagePlacement.Orientation.LANDSCAPE)
        lifecycleScope.launch {
            var ok = false
            try {
                JobControl.begin()
                scanLog("printImages: ${uris.size} image(s) dpi=$printDpi gray=$grayscale " +
                        "fit=${pl.fitMode} orient=${pl.orientation}")
                val res = withContext(Dispatchers.IO) {
                    // decode up front (bounded by count), stream in one job
                    val pages = ArrayList<PageRenderer.RenderedPage>(uris.size)
                    for (u in uris.take(20)) {
                        val bmp = decodeImageUri(u) ?: continue
                        val placed = PagePlacement.render(bmp, dw, dh, pl, printDpi)
                        bmp.recycle()
                        pages.add(PageRenderer.finishPlacedPage(placed, grayscale))
                    }
                    if (pages.isEmpty()) return@withContext
                        PrintTransmitter.Result.Failed(0, "no readable images")
                    val streams = pages.map { p ->
                        PrintTransmitter.RenderedPage(p.jpeg, p.width, p.height, 0, pages.size)
                    }
                    var i = 0
                    PrintTransmitter.sendPages(
                        usb, printDpi, grayscale, "OTG-IMG",
                        source = { _ -> streams.getOrNull(i++) },
                        onStatus = { busyLabel = it; scanLog("printImages: $it") },
                        landscape = pl.orientation ==
                                PagePlacement.Orientation.LANDSCAPE,
                    )
                }
                JobControl.end()
                ok = res is PrintTransmitter.Result.Ok
                when (res) {
                    is PrintTransmitter.Result.Ok -> {
                        addHistory("Images", "${uris.size} image(s)",
                            "images", uris.joinToString("\n"), grayscale, printDpi)
                        Notifier.jobDone(this@MainActivity,
                            "Images printed", "${uris.size} at ${printDpi}dpi")
                    }
                    is PrintTransmitter.Result.Cancelled -> Toast.makeText(
                        this@MainActivity, "Job cancelled", Toast.LENGTH_SHORT).show()
                    is PrintTransmitter.Result.Failed -> {
                        rescuePrinter()
                        Toast.makeText(this@MainActivity,
                            "Print failed: ${res.reason}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                scanLog("printImages: FAILED ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.e("M175", "printImages failed", e)
                Toast.makeText(this@MainActivity,
                    "Print error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { clearBusy() }
        }
    }

    private fun decodeImageUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2).toLong() *
            bounds.outHeight / (sample * 2).toLong() > 24_000_000) sample *= 2
        contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null,
                BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) { null }

    private var pendingPreviewCopies: Int = 1
    private var pendingPreviewRange: String = ""

    /**
     * PREVIEW-FIRST print: copy the PDF, compute the exact page plan
     * (range/parity/reverse/blank-filter), render low-res thumbnails and
     * show the confirm dialog. Nothing prints until "Print now".
     */
    private fun printPdf(uri: Uri, duplex: Boolean, copies: Int = 1,
                         rangeExpr: String = "") {
        if (!checkReady()) return
        val wantsLayout = reverseOrder || skipBlank || parityMode != 0 ||
                nUpMode != 1 || bookletMode
        if (duplex && wantsLayout) {
            Toast.makeText(this,
                "Layout options apply to single-sided printing",
                Toast.LENGTH_SHORT).show()
        }
        // Wi-Fi share-intent fast path (no preview): print direct via IPP.
        // In-app picker always goes through the preview dialog below.
        setBusy("Preparing preview...")
        pendingPreviewDuplex = duplex
        pendingPreviewCopies = if (duplex) 1 else copies
        pendingPreviewRange = rangeExpr
        pendingPreviewUriStr = uri.toString()
        lifecycleScope.launch {
            try {
                val pdfFile = withContext(Dispatchers.IO) {
                    val f = File(cacheDir, "print-job.pdf")
                    contentResolver.openInputStream(uri)!!.use { ins ->
                        f.outputStream().use { ins.copyTo(it) }
                    }
                    f
                }
                val totalPages = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(pdfFile,
                        ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { r -> r.pageCount }
                    }
                }
                val effRange = if (duplex) "" else
                    if (rangeExpr.isBlank()) pageRangeExpr else rangeExpr
                val effCopies = if (duplex) 1 else
                    if (copies == 1) this@MainActivity.copies else copies
                pendingPreviewCopies = effCopies.coerceIn(1, 99)
                pendingPreviewRange = effRange
                var plan = withContext(Dispatchers.IO) {
                    PageLayout.filterParity(
                        if (effRange.isBlank()) (1..totalPages).toList()
                        else PageLayout.parseRange(effRange, totalPages),
                        when (parityMode) { 1 -> true; 2 -> false; else -> null },
                        totalPages,
                    ).let { if (reverseOrder && !duplex) it.reversed() else it }
                }
                if (skipBlank && !duplex && plan.isNotEmpty()) {
                    plan = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(pdfFile,
                            ParcelFileDescriptor.MODE_READ_ONLY).use { fdp ->
                            PdfRenderer(fdp).use { probe ->
                                plan.filter { p ->
                                    if (p - 1 >= probe.pageCount) return@filter false
                                    val bmp = probe.openPage(p - 1).use { page ->
                                        PageRenderer.renderPageBitmap(page, 1f)
                                    }
                                    val keep = !isBlankPage(bmp)
                                    bmp.recycle()
                                    keep
                                }
                            }
                        }
                    }
                }
                if (plan.isEmpty()) {
                    clearBusy()
                    Toast.makeText(this@MainActivity,
                        "All selected pages are blank - nothing to print",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                val thumbs = withContext(Dispatchers.IO) {
                    PreviewHelper.renderPreview(pdfFile, plan, 6)
                }
                clearBusy()
                PreviewHelper.recycle(previewObj)
                previewObj = thumbs
                pendingPreviewPdf = pdfFile
                previewTitle = if (duplex) "Duplex preview" else "Print preview"
                previewPlanText = "${plan.size} page(s)" +
                        (if (pendingPreviewCopies > 1) " x${pendingPreviewCopies} copies" else "") +
                        (if (bookletMode && !duplex) " · booklet" else "") +
                        (if (nUpMode != 1 && !duplex) " · ${nUpMode}-up" else "") +
                        " · $totalPages in document"
                showPreview = true
            } catch (e: Exception) {
                clearBusy()
                Toast.makeText(this@MainActivity,
                    "Preview failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runs AFTER the preview dialog's "Print now" — the real job. */
    private fun executePdfPrint(pdfFile: File, duplex: Boolean) {
        if (!checkReady()) return
        // Wi-Fi bridge path: upload the document via IPP (PC renders via GDI).
        if (useWifi()) {
            executeWifiPdfPrint(pdfFile, duplex)
            return
        }
        setBusy(if (duplex) "Duplex job..." else "Rendering...")
        lifecycleScope.launch {
            try {
                // Re-resolve the plan inside the job (settings may have changed
                // between preview and confirm — preview is advisory).
                val rangeExpr = pendingPreviewRange
                val copies = pendingPreviewCopies
                if (!duplex && !bookletMode) {
                    val totalPages = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(pdfFile,
                            ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { r -> r.pageCount }
                        }
                    }
                    // PAGE PLAN: range syntax -> parity filter -> reverse.
                    // The renderer streams exactly these pages, in this order.
                    val plan = PageLayout.filterParity(
                        if (rangeExpr.isBlank()) (1..totalPages).toList()
                        else PageLayout.parseRange(rangeExpr, totalPages),
                        when (parityMode) { 1 -> true; 2 -> false; else -> null },
                        totalPages,
                    ).let { if (reverseOrder) it.reversed() else it }
                    // skip blank: probe each planned page as a low-res bitmap
                    // and drop near-white ones. ONE open renderer for the
                    // whole probe (no per-page reopen, no JPEG round-trip).
                    val effective = if (skipBlank && plan.isNotEmpty()) {
                        setBusy("Checking for blank pages...")
                        ParcelFileDescriptor.open(pdfFile,
                            ParcelFileDescriptor.MODE_READ_ONLY).use { fdp ->
                            PdfRenderer(fdp).use { probe ->
                                plan.filter { p ->
                                    if (p - 1 >= probe.pageCount) return@filter false
                                    val bmp = probe.openPage(p - 1).use { page ->
                                        // 72 dpi thumbnail (scale 1.0 pt->px)
                                        PageRenderer.renderPageBitmap(page, 1f)
                                    }
                                    val keep = !isBlankPage(bmp)
                                    bmp.recycle()
                                    keep
                                }
                            }
                        }
                    } else plan
                    if (effective.isEmpty()) {
                        Toast.makeText(this@MainActivity,
                            "All selected pages are blank - nothing to print",
                            Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val plan2 = effective
                    // PRINTER-SIDE COPIES (Windows parity): the engine repeats
                    // the job via @PJL SET COPIES — the phone streams each
                    // sheet ONCE. Old code re-streamed every page N times.
                    val reps = copies.coerceIn(1, 99)
                    val up = if (nUpMode == 2) 2 else if (nUpMode == 4) 4 else 1
                    val sheetCount = (plan2.size + up - 1) / up
                    val total = sheetCount
                    // Fast-mono eligibility: greyscale + fast toggle + no
                    // placement transforms that need color compositing.
                    // N-up/booklet sheets stay on the JPEG path (composed).
                    val useFastMono = grayscale && fastMono && up == 1 &&
                            placeFit == 0 && placeMargin == 0 && placeOrient == 0
                    PageRenderer.multipageHint = plan2.size > 3 && grayscale && !useFastMono
                    JobControl.begin()
                    scanLog("printPdf: single-sided dpi=$printDpi gray=$grayscale " +
                            "fastMono=$useFastMono pages=${plan2.size} copies=$reps " +
                            "(printer) rev=$reverseOrder blank=$skipBlank up=$up")
                    var idx = 0
                    val res = withContext(Dispatchers.IO) {
                        if (up == 1) {
                            // one open renderer for the entire job
                            val session = PageSession(pdfFile, printDpi, grayscale,
                                placement(), paper)
                            try {
                                if (useFastMono) {
                                    // WINDOWS-SPEED PATH: 1-bit RLE pages.
                                    PrintTransmitter.sendPagesMono1Bit(
                                        usb, printDpi, "OTG-PDF",
                                        landscape = session.isLandscape,
                                        paper = paper,
                                        copies = reps,
                                        source = { _ ->
                                            if (idx >= total) null
                                            else {
                                                val seq = idx++
                                                val pageNo = plan2[seq]
                                                val mp = session.renderMono(
                                                    pageNo - 1, ditherMono)
                                                PrintTransmitter.MonoRenderedPage(
                                                    mp.rle, mp.width, mp.height,
                                                    seq + 1, total)
                                            }
                                        },
                                        onStatus = { busyLabel = it; scanLog("printPdf: $it") },
                                    )
                                } else {
                                    PrintTransmitter.sendPages(
                                        usb, printDpi, grayscale, "OTG-PDF",
                                        landscape = session.isLandscape,
                                        paper = paper,
                                        copies = reps,
                                        source = { _ ->
                                            if (idx >= total) null
                                            else {
                                                val seq = idx++
                                                val pageNo = plan2[seq]
                                                val rp = session.render(pageNo - 1)
                                                PrintTransmitter.RenderedPage(
                                                    rp.jpeg, rp.width, rp.height,
                                                    seq + 1, total)
                                            }
                                        },
                                        onStatus = { busyLabel = it; scanLog("printPdf: $it") },
                                    )
                                }
                            } finally {
                                session.close()
                                PageRenderer.multipageHint = false
                            }
                        } else {
                            // 2-up: landscape sheets, pages left|right.
                            // 4-up: portrait sheets, 2x2 grid TL,TR,BL,BR.
                            // Sheets are composed on the fly from full-page
                            // renders; one sheet bitmap alive at a time.
                            // one open renderer for the whole N-up job
                            val session = PageSession(pdfFile, printDpi, grayscale,
                                placement(), paper)
                            try {
                                fun sheet(seq: Int): PrintTransmitter.RenderedPage? {
                                    if (seq >= total) return null
                                    val repIdx = seq % sheetCount
                                    val base = repIdx * up
                                    val (pw, ph) = paper.pagePx(printDpi, false)
                                    val sheetJpeg: ByteArray
                                    if (up == 2) {
                                        val l = plan2.getOrNull(base)?.let {
                                            session.render(it - 1).jpeg }
                                        val r = plan2.getOrNull(base + 1)?.let {
                                            session.render(it - 1).jpeg }
                                        sheetJpeg = PageLayout.composeTwoUp(
                                            l, r, printDpi, grayscale, pw, ph)
                                    } else {
                                        val quads = (0 until 4).map { o ->
                                            plan2.getOrNull(base + o)?.let {
                                                session.render(it - 1).jpeg }
                                        }
                                        sheetJpeg = PageLayout.composeFourUp(
                                            quads, printDpi, grayscale, pw, ph)
                                    }
                                    return PrintTransmitter.RenderedPage(
                                        sheetJpeg,
                                        if (up == 2) pw * 2 else pw * 2,
                                        if (up == 2) ph else ph * 2,
                                        seq + 1, total)
                                }
                                PrintTransmitter.sendPages(
                                    usb, printDpi, grayscale, "OTG-PDF",
                                    source = { s -> sheet(s - 1) },
                                    onStatus = { busyLabel = it; scanLog("printPdf: $it") },
                                    landscape = (up == 2),
                                    paper = paper,
                                    copies = reps,
                                )
                            } finally {
                                session.close()
                            }
                        }
                    }
                    JobControl.end()
                    scanLog("printPdf: result=$res")
                    when (res) {
                        is PrintTransmitter.Result.Ok -> {
                            addHistory("PDF", describeRange(plan2.size, reps,
                                rangeExpr), "pdf",
                                pendingPreviewUriStr ?: pdfFile.toURI().toString(),
                                grayscale, printDpi)
                            val copyTxt = if (reps > 1) " x$reps (printer)" else ""
                            Notifier.jobDone(this@MainActivity,
                                "PDF printed", "$total page(s)$copyTxt at ${printDpi}dpi")
                        }
                        is PrintTransmitter.Result.Cancelled ->
                            Toast.makeText(this@MainActivity,
                                "Job cancelled", Toast.LENGTH_SHORT).show()
                        is PrintTransmitter.Result.Failed -> {
                            rescuePrinter()
                            Toast.makeText(this@MainActivity,
                                "Print failed: ${res.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else if (bookletMode) {
                    // BOOKLET: render once, impose 2-up landscape sheets,
                    // reuse the exact manual-duplex two-pass flip flow.
                    setBusy("Rendering pages...")
                    val pages = withContext(Dispatchers.IO) {
                        PageRenderer.renderPdf(pdfFile, printDpi, grayscale)
                    }
                    if (pages.size < 4) {
                        Toast.makeText(this@MainActivity,
                            "Booklet needs 4+ pages", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    JobControl.begin()
                    scanLog("printPdf: booklet ${pages.size} pages -> " +
                            "${PageLayout.bookletSheets(pages.size).size} sheets")
                    val bres = withContext(Dispatchers.IO) {
                        ManualDuplexPlanner.executeBooklet(
                            usb, pages.map { it.jpeg }, pages[0].width,
                            pages[0].height, grayscale, printDpi,
                            paper = paper,
                            onProgress = { busyLabel = it },
                            waitForEngineIdle = { n, label ->
                                waitForEngineIdleUsb(n, label)
                            },
                            onFlipPrompt = {
                                withContext(Dispatchers.Main) {
                                    clearBusy()
                                    flipGate = CompletableDeferred()
                                    showFlipDialog = true
                                }
                                flipGate!!.await()
                                withContext(Dispatchers.Main) {
                                    if (!JobControl.isCancelled)
                                        setBusy("Side 2 - sheet backs...")
                                }
                            },
                        )
                    }
                    JobControl.end()
                    when (bres) {
                        is PrintTransmitter.Result.Ok -> Notifier.jobDone(
                            this@MainActivity, "Booklet printed",
                            "${pages.size} pages as " +
                                    "${PageLayout.bookletSheets(pages.size).size} sheets")
                        is PrintTransmitter.Result.Cancelled -> Toast.makeText(
                            this@MainActivity, "Job cancelled",
                            Toast.LENGTH_SHORT).show()
                        is PrintTransmitter.Result.Failed -> {
                            rescuePrinter()
                            Toast.makeText(this@MainActivity,
                                "Booklet failed: ${bres.reason}",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    setBusy("Rendering pages...")
                    val pages = withContext(Dispatchers.IO) {
                        PageRenderer.renderPdf(pdfFile, printDpi, grayscale)
                    }
                    if (pages.size < 2) {
                        Toast.makeText(this@MainActivity,
                            "Duplex needs 2+ pages", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    JobControl.begin()
                    scanLog("printPdf: duplex ${pages.size} pages")
                    setBusy("Side 1 - even pages...")
                    val dres = withContext(Dispatchers.IO) {
                        ManualDuplexPlanner.execute(
                            usb, pages.map { it.jpeg }, pages[0].width,
                            pages[0].height, grayscale, printDpi,
                            paper = paper,
                            onProgress = { busyLabel = it },
                            waitForEngineIdle = { n, label ->
                                waitForEngineIdleUsb(n, label)
                            },
                            onFlipPrompt = {
                                withContext(Dispatchers.Main) {
                                    // hide the busy dialog FIRST - two Compose
                                    // AlertDialogs stack, and the busy one used
                                    // to cover the flip prompt (that is why no
                                    // flip text was ever visible)
                                    clearBusy()
                                    flipGate = CompletableDeferred()
                                    showFlipDialog = true
                                }
                                flipGate!!.await()
                                withContext(Dispatchers.Main) {
                                    if (!JobControl.isCancelled) {
                                        setBusy("Side 2 - odd pages...")
                                    }
                                }
                            },
                        )
                    }
                    JobControl.end()
                    when (dres) {
                        is PrintTransmitter.Result.Ok -> Notifier.jobDone(this@MainActivity,
                            "Duplex printed", "${pages.size} pages, both sides")
                        is PrintTransmitter.Result.Cancelled ->
                            Toast.makeText(this@MainActivity,
                                "Duplex job cancelled", Toast.LENGTH_SHORT).show()
                        is PrintTransmitter.Result.Failed -> {
                            rescuePrinter()
                            Toast.makeText(this@MainActivity,
                                "Duplex failed: ${dres.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                scanLog("printPdf: FAILED ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.e("M175", "printPdf failed", e)
                Toast.makeText(this@MainActivity,
                    "Print error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { clearBusy() }
        }
    }

    /**
     * Wi-Fi print: the phone uploads the document over IPP and the PC
     * bridge renders it through the Windows GDI driver (driver-quality
     * output, zero OTG cable). Duplex = two IPP jobs with the flip prompt
     * between them, using client-side page subsets.
     */
    private fun executeWifiPdfPrint(pdfFile: File, duplex: Boolean) {
        setBusy(if (duplex) "Wi-Fi duplex..." else "Sending to bridge...")
        lifecycleScope.launch {
            try {
                JobControl.begin()
                val reps = pendingPreviewCopies.coerceIn(1, 99)
                if (!duplex && !bookletMode) {
                    // Single-sided: one IPP upload; the bridge repeats copies
                    // server-side per its spooler (send once, repeat via loop
                    // of IPP jobs only when the bridge reports failure — the
                    // common case is a single upload).
                    var okAll = true
                    repeat(reps) {
                        val ok = withContext(Dispatchers.IO) {
                            bridge().printPdf(pdfFile.readBytes(),
                                "Android Wi-Fi PDF")
                        }
                        if (!ok) okAll = false
                    }
                    JobControl.end()
                    if (okAll) {
                        addHistory("PDF (Wi-Fi)", "${pendingPreviewRange.ifBlank { "all pages" }}" +
                                (if (reps > 1) " x$reps" else ""),
                            "pdf", pendingPreviewUriStr ?: pdfFile.toURI().toString(),
                            grayscale, printDpi)
                        Notifier.jobDone(this@MainActivity, "Wi-Fi print sent",
                            "via bridge $bridgeHost:$bridgePort")
                    } else {
                        Toast.makeText(this@MainActivity,
                            "Bridge rejected the job — is the PC spooler online?",
                            Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Duplex/booklet over Wi-Fi: split into two subset PDFs
                    // client-side (PdfDocument from rendered bitmaps), then
                    // two IPP jobs with the flip dialog between them.
                    val totalPages = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(pdfFile,
                            ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { r -> r.pageCount }
                        }
                    }
                    if (totalPages < 2) {
                        Toast.makeText(this@MainActivity,
                            "Duplex needs 2+ pages", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val evens = (2..totalPages step 2).toList().reversed()
                    val odds = (1..totalPages step 2).toList()
                    setBusy("Wi-Fi side 1 (evens)...")
                    val pass1 = withContext(Dispatchers.IO) {
                        subsetPdfBytes(pdfFile, evens)
                    }
                    val ok1 = withContext(Dispatchers.IO) {
                        bridge().printDuplexPass(pass1, "pass 1/2 (backs)")
                    }
                    if (!ok1) {
                        Toast.makeText(this@MainActivity,
                            "Bridge rejected pass 1", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // Upload-accepted is not printed-done either (PC spooler
                    // still rendering/marking): wait for the bridge to report
                    // idle before asking for the flip — same Windows parity
                    // as the USB path, otherwise the user flips too early.
                    if (!waitForBridgeIdle(evens.size)) return@launch
                    withContext(Dispatchers.Main) {
                        clearBusy()
                        flipGate = CompletableDeferred()
                        showFlipDialog = true
                    }
                    flipGate!!.await()
                    withContext(Dispatchers.Main) {
                        if (!JobControl.isCancelled) setBusy("Wi-Fi side 2...")
                    }
                    val pass2 = withContext(Dispatchers.IO) {
                        subsetPdfBytes(pdfFile, odds)
                    }
                    val ok2 = withContext(Dispatchers.IO) {
                        bridge().printDuplexPass(pass2, "pass 2/2 (fronts)")
                    }
                    JobControl.end()
                    if (ok2) Notifier.jobDone(this@MainActivity,
                        "Wi-Fi duplex sent", "flip completed via bridge")
                    else Toast.makeText(this@MainActivity,
                        "Bridge rejected pass 2", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Wi-Fi print failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                try { JobControl.end() } catch (_: Exception) {}
                clearBusy()
            }
        }
    }

    /**
     * Bridge-side parity wait for Wi-Fi duplex: poll /api/status until the
     * bridge reports idle (its spooler finished side 1). Bounded per-page;
     * proceeds anyway on timeout. False only on user cancel.
     */
    private suspend fun waitForBridgeIdle(passPages: Int): Boolean {
        val deadline = System.currentTimeMillis() +
                maxOf(120_000L, passPages * 45_000L)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            if (JobControl.isCancelled) return false
            val st = withContext(Dispatchers.IO) {
                runCatching { bridge().getStatus() }.getOrNull()
            }
            if (st != null && st.state.equals("idle", ignoreCase = true)) {
                scanLog("waitForBridgeIdle: side 1 done (${st.detail})")
                return true
            }
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            setBusy("Finishing Wi-Fi side 1… (${elapsed}s)")
            scanLog("waitForBridgeIdle: bridge=${st?.state ?: "<unreachable>"}")
            kotlinx.coroutines.delay(3000)
        }
        scanLog("waitForBridgeIdle: timeout — proceeding anyway")
        return true
    }

    /** Render [pages1Based] into a new PDF (for Wi-Fi duplex subsets). */
    private fun subsetPdfBytes(src: File, pages1Based: List<Int>): ByteArray {
        val out = android.graphics.pdf.PdfDocument()
        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val scale = printDpi / 72f
                for (p in pages1Based) {
                    if (p - 1 < 0 || p - 1 >= renderer.pageCount) continue
                    renderer.openPage(p - 1).use { page ->
                        val w = (page.width * scale).toInt().coerceAtLeast(8)
                        val h = (page.height * scale).toInt().coerceAtLeast(8)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(GColor.WHITE)
                        page.render(bmp, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            (w * 72 / printDpi), (h * 72 / printDpi), 1).create()
                        val pg = out.startPage(info)
                        pg.canvas.drawBitmap(bmp,
                            android.graphics.Rect(0, 0, w, h),
                            android.graphics.Rect(0, 0,
                                (w * 72 / printDpi), (h * 72 / printDpi)), null)
                        out.finishPage(pg)
                        bmp.recycle()
                    }
                }
            }
        }
        val baos = java.io.ByteArrayOutputStream()
        out.writeTo(baos)
        out.close()
        return baos.toByteArray()
    }

    /** PJL EOJ + RESET + UEL - unwedges a dead job / stuck LCD. */
    private fun rescuePrinter() {
        if (useWifi()) return // no USB channel in Wi-Fi mode
        try { usb.resetJobState() } catch (_: Exception) {}
    }

    /**
     * Windows-parity wait: after a pass's bytes are all transferred, the
     * engine is usually still marking pages — prompt (and RDYMSG) only when
     * the panel itself reports Ready. Polls the BIDI status channel every
     * 2 s, bounded by a per-page deadline (then proceeds anyway: a slow
     * engine is not a dead one). Returns false only on user cancel.
     * Runs on a background thread (callers are already on Dispatchers.IO).
     */
    private suspend fun waitForEngineIdleUsb(passPages: Int, passLabel: String): Boolean {
        val deadline = System.currentTimeMillis() +
                maxOf(120_000L, passPages * 45_000L)
        val t0 = System.currentTimeMillis()
        val bidi = BidiHttpClient(usb)
        while (System.currentTimeMillis() < deadline) {
            if (JobControl.isCancelled) return false
            val st = runCatching { bidi.readStatus() }.getOrNull()
            if (st != null && ManualDuplexPlanner.isEngineIdle(st)) {
                scanLog("waitForEngineIdle: $passLabel done (panel: $st)")
                return true
            }
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            busyLabel = "Finishing $passLabel… (${elapsed}s${st?.let { " — $it" } ?: ""})"
            scanLog("waitForEngineIdle: $passLabel panel=${st ?: "<no reply>"}")
            kotlinx.coroutines.delay(2000)
        }
        scanLog("waitForEngineIdle: $passLabel timeout — proceeding anyway")
        return true
    }

    /**
     * ONE open PdfRenderer for the whole job (fixed: the per-page helper
     * re-opened the PDF for every page — that is what made the print
     * button feel dead for seconds before the printer even started).
     * Also carries the placement engine (scale/orientation/margin/position).
     * Not thread-safe: call from one thread only.
     */
    private class PageSession(
        pdf: File,
        private val dpi: Int,
        private val gray: Boolean,
        private val place: PagePlacement.Placement,
        private val paper: Paper,
    ) : AutoCloseable {
        private val fd: ParcelFileDescriptor =
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = PdfRenderer(fd)

        val pageCount: Int get() = renderer.pageCount
        val isLandscape: Boolean
            get() = place.orientation == PagePlacement.Orientation.LANDSCAPE

        /** One page, placement-applied (scale/orientation/margins/position). */
        fun render(pageNo: Int): PageRenderer.RenderedPage {
            renderer.openPage(pageNo).use { page ->
                // fast path: untouched placement (fit/none/center/portrait)
                // = the old direct raster->JPEG pipeline, zero extra cost
                val identity = place.fitMode == PagePlacement.FitMode.FIT_PAGE &&
                        place.margins.isZero && !isLandscape &&
                        place.posX == 0.5f && place.posY == 0.5f
                if (identity) return PageRenderer.renderPageToJpeg(page, dpi / 72f, gray)

                val src = PageRenderer.renderPageBitmap(page, dpi / 72f)
                val (pw, ph) = paper.pagePx(dpi, isLandscape)
                val placed = PagePlacement.render(src, pw, ph, place, dpi)
                src.recycle()
                return PageRenderer.finishPlacedPage(placed, gray)
            }
        }

        /** Fast-mono page: PDF -> 1-bit RLE (identity placement only). */
        fun renderMono(pageNo: Int, dither: Boolean): MonoRaster.MonoPage {
            renderer.openPage(pageNo).use { page ->
                return PageRenderer.renderPageToMono1Bit(page, dpi / 72f, dither)
            }
        }

        override fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    /** Near-white page detector for "skip blank pages" (72 dpi probe). */
    private fun isBlankPage(bmp: Bitmap?): Boolean {
        bmp ?: return true
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val step = maxOf(1, w * h / 4096)
        var dark = 0; var n = 0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val l = (0.299f * (p shr 16 and 0xFF) +
                    0.587f * (p shr 8 and 0xFF) +
                    0.114f * (p and 0xFF)).toInt()
            if (l < 200) dark++
            n++; i += step
        }
        return dark * 100 / n < 1   // <1% dark pixels = blank
    }

    private fun describeRange(pageCount: Int, copies: Int,
                              rangeExpr: String): String {
        val range = when {
            rangeExpr.isNotBlank() -> "pages $rangeExpr"
            else -> "$pageCount pages"
        }
        val extras = buildList {
            if (reverseOrder) add("rev")
            if (skipBlank) add("no-blank")
            when (parityMode) { 1 -> add("odd"); 2 -> add("even") }
            when (nUpMode) { 2 -> add("2-up"); 4 -> add("4-up") }
            if (bookletMode) add("booklet")
        }
        val suffix = if (extras.isEmpty()) "" else " (${extras.joinToString(",")})"
        return if (copies > 1) "$range x$copies$suffix" else "$range$suffix"
    }

    private fun addHistory(title: String, sub: String, kind: String,
                           data: String, gray: Boolean, dpi: Int) {
        jobHistory.add(0, JobItem(title, sub, kind, data, gray, dpi))
        while (jobHistory.size > 12) jobHistory.removeAt(jobHistory.size - 1)
    }

    private fun savePlace() {
        prefs.edit().putInt("placeFit", placeFit)
            .putInt("placeOrient", placeOrient)
            .putInt("placeMargin", placeMargin)
            .putInt("placePos", placePos).apply()
    }

    /**
     * REAL cancel, Windows-spooler-style.
     *
     * Order matters: (1) tell the active sender to stop (it checks the flag
     * between USB chunks and unwinds), (2) AFTER it has unwound, push the
     * abort sequence to the printer (UEL + @PJL EOJ + @PJL RESET). The old
     * code only did (2) while the sender kept streaming, so the abort bytes
     * interleaved with live PCL XL data and the job kept printing.
     */
    private fun cancelJob() {
        if (!checkReady()) return
        scanLog("cancelJob: requested (print=${JobControl.active} scan=${ScanControl.active}) wifi=${useWifi()}")
        // a scan in flight can be cancelled too - it stops between USB reads
        // and then cancels its job on the printer
        if (ScanControl.active) ScanControl.requestCancel()
        JobControl.requestCancel()
        if (useWifi()) {
            // Wi-Fi jobs are short HTTP uploads — flag-cancel is enough; the
            // bridge/PC spooler owns the engine job from here.
            lifecycleScope.launch {
                setBusy("Cancelling job...")
                withContext(Dispatchers.IO) {
                    var waited = 0
                    while (JobControl.active && waited < 5_000) {
                        kotlinx.coroutines.delay(200)
                        waited += 200
                    }
                }
                clearBusy()
                Toast.makeText(this@MainActivity, "Job cancelled",
                    Toast.LENGTH_SHORT).show()
            }
            return
        }
        usb.cancelRequested = true
        lifecycleScope.launch {
            setBusy("Cancelling job...")
            try {
                withContext(Dispatchers.IO) {
                    // give the sender a moment to notice and unwind
                    var waited = 0
                    while (JobControl.active && waited < 12_000) {
                        kotlinx.coroutines.delay(200)
                        waited += 200
                    }
                    usb.cancelRequested = false
                    // now the channel is ours: abort the job on the printer
                    usb.resetJobState()
                    // and pull the flip prompt off the panel if duplex was up
                    runCatching { usb.sendPrint(PclxlPage.lcdMessageBytes("")) }
                }
                clearBusy()
                Toast.makeText(this@MainActivity,
                    "Job cancelled",
                    Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                clearBusy()
                Toast.makeText(this@MainActivity,
                    "Cancel failed: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------- scanning

    /**
     * The scan flows: glass (one page) or ADF (loop until the feeder is
     * empty - each page becomes its own job, so a 20-sheet tray just works).
     */
    private fun scanFlow() {
        if (!checkReady()) { scanLog("scanFlow: printer not ready"); return }
        val fromAdf = adfMode
        scanLog("scanFlow: start adf=$fromAdf wscn=$wscnMode dpi=$scanDpi gray=$scanGray")
        ScanControl.begin()
        setBusy("Preparing scanner...")
        lifecycleScope.launch {
            try {
                var count = 0
                // OUTER loop: the printer can answer with
                // "ServerErrorNotAcceptingJobs" ("temporarily blocked") -
                // that state clears itself, so wait and retry instead of
                // hammering the engine with cancels/resets (measured: those
                // change nothing and only waste a minute).
                var blockedRetries = 0
                pageLoop@ while (true) {
                    try {
                        val jpeg = withContext(Dispatchers.IO) { scanOnce(fromAdf) }
                        count++
                        setBusy("Saving scan $count...")
                        val bmp = withContext(Dispatchers.IO) {
                            val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            val small = Bitmap.createScaledBitmap(
                                b, 640, b.height * 640 / b.width, true)
                            b.recycle(); small
                        }
                        lastScanBmp = bmp
                        lastScanWarn = false
                        lastScanFailed = false
                        scannerWarning = null
                        lastScanText = "Saved | ${scanDpi} dpi | ${
                            when {
                                scanLineart -> "black & white"
                                scanGray -> "greyscale"
                                else -> "color"
                            }}" + if (fromAdf) " | page $count" else ""
                        // accumulate pages; on PDF mode everything is written
                        // as ONE multi-page document when scanning ends
                        if (pdfMode) {
                            scanPages.add(jpeg)
                            lastScanText += " | PDF page ${scanPages.size}"
                            if (!fromAdf) finishScanPdf(scanDpi)
                        } else {
                            lastScanUri = saveScan(jpeg)
                        }
                        Notifier.jobDone(this@MainActivity, "Scan $count saved",
                            "${scanDpi} dpi ${if (scanGray) "greyscale" else "color"}")
                        if (!fromAdf) break@pageLoop
                        setBusy("Checking feeder...")
                        val more = withContext(Dispatchers.IO) { feederHasPaper() }
                        if (!more) break@pageLoop
                        setBusy("Scanning next page...")
                    } catch (e: ScannerBlockedException) {
                        blockedRetries++
                        scanLog("scanFlow: printer blocked (attempt $blockedRetries)")
                        if (blockedRetries > 2) throw e
                        lastScanWarn = true
                        lastScanText = "Printer's scanner is busy/blocked (not accepting jobs). " +
                                "Waiting 25s - retry $blockedRetries of 2..."
                        setBusy("Scanner blocked - waiting 25s...")
                        kotlinx.coroutines.delay(25_000)
                        setBusy("Preparing scanner...")
                    }
                }
                if (count > 1) {
                    Toast.makeText(this@MainActivity,
                        "$count pages scanned from the feeder", Toast.LENGTH_LONG).show()
                }
                // ADF multi-page -> single PDF (non-PDF mode saves JPEGs)
                if (pdfMode && fromAdf && scanPages.isNotEmpty()) {
                    setBusy("Writing PDF...")
                    finishScanPdf(scanDpi)
                }
            } catch (e: ScannerBlockedException) {
                lastScanWarn = true
                lastScanFailed = true
                lastScanText = "Scanner is blocked. Power-cycle the printer to clear this."
                Toast.makeText(this@MainActivity,
                    "Scanner blocked - power-cycle the printer", Toast.LENGTH_LONG).show()
            } catch (e: ScanCancelledException) {
                lastScanWarn = false
                lastScanFailed = false
                lastScanText = "Scan cancelled"
                scanLog("scanFlow: cancelled by user")
            } catch (e: Exception) {
                rescuePrinter()
                lastScanWarn = true
                lastScanFailed = true
                lastScanText = "Scan failed: ${e.message}"
                Toast.makeText(this@MainActivity,
                    "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                // soft recovery only (halted endpoint / stale bytes) - the
                // heavy USB reset lives behind the "Fix scanner" button
                setBusy("Recovering scanner...")
                try {
                    withContext(Dispatchers.IO) {
                        WscnScanClient(usb).softRecover { scanLog(it) }
                    }
                    lastScanText = "Scan failed: ${e.message}"
                } catch (_: Exception) {
                }
            } finally { ScanControl.end(); clearBusy() }
        }
    }

    /** logcat trace tag - "adb logcat -s M175:D" shows the whole scan trace */
    private fun scanLog(msg: String) { android.util.Log.d("M175", msg) }

    /** ONE integrity-gated scan of one page (glass or feeder). */
    private suspend fun scanOnce(fromAdf: Boolean): ByteArray {
        // Wi-Fi bridge path: eSCL through the PC (WIA engine there).
        if (useWifi()) {
            scanLog("scanOnce: Wi-Fi bridge dpi=$scanDpi gray=$scanGray lineart=$scanLineart")
            val jpeg = withContext(Dispatchers.IO) {
                bridge().scan(scanDpi,
                    BridgeClient.colorModeFor(scanGray, scanLineart))
            }
            return withContext(Dispatchers.IO) {
                val leveled = ScanAutoLevels.fix(jpeg)
                if (scanLineart) ScanAutoLevels.toLineart(leveled) else leveled
            }
        }
        val requestedDpi = scanDpi.coerceAtMost(600) // 1200 removed
        var attempt = 0
        while (true) {
            attempt++
            scanLog("scanOnce: attempt=$attempt wscn=$wscnMode dpi=$requestedDpi " +
                    "gray=$scanGray adf=$fromAdf")
            // NATIVE-LATTICE RULE (proven on this unit): the CCD delivers
            // coherent colour only at 300 dpi — 150/200 are repaired by
            // scanning 300 and resampling (like HP's own driver). 600 is
            // the real optical glass step and goes through untouched.
            // The ADF tops out at 300 dpi (its own optical resolution).
            // 1200 REMOVED: 15+ min/page, 530 MB RAM, no visible gain.
            val engineDpi = when {
                requestedDpi > 300 && !fromAdf -> requestedDpi.coerceAtMost(600)
                else -> 300
            }
            // 600 dpi A4 ≈ 5-6 min on this engine — scale the deadline.
            val budget = when (engineDpi) {
                600 -> 6 * 60_000L
                else -> 0L
            }
            val jpeg = withContext(Dispatchers.IO) {
                drainScanChannel()
                if (wscnMode) {
                    WscnScanClient.dumpDir = getExternalFilesDir(null)
                    val wc = WscnScanClient(usb)
                    try {
                        wc.scanFlatbed(
                            engineDpi,
                            if (scanGray) "GrayScale8" else "RGB24",
                            inputSource = if (fromAdf) "ADF" else "Platen",
                            log = { scanLog(it) },
                            budgetMs = budget
                        )
                    } catch (e: Exception) {
                        scanLog("scanOnce FAILED: ${e.message}")
                        wc.cancelActive { scanLog(it) }
                        throw e
                    }
                } else {
                    LedmScanClient(usb).scanFlatbed(
                        engineDpi, if (scanGray) "Grayscale" else "RGB24",
                        inputSource = if (fromAdf) "Feeder" else "Platen",
                        log = { scanLog(it) })
                }
            }
            scanLog("scanOnce: got ${jpeg.size}B")
            val processed = withContext(Dispatchers.IO) {
                val working = if (requestedDpi < 300)
                    ScanAutoLevels.downsampleToDpi(jpeg, requestedDpi, 300)
                else jpeg
                val leveled = ScanAutoLevels.fix(working)
                if (scanLineart) ScanAutoLevels.toLineart(leveled) else leveled
            }
            scanLog("scanOnce: engine=${engineDpi}dpi requested=${requestedDpi}dpi " +
                    "${processed.size / 1024}KB | ${ScanAutoLevels.lastAction}")
            if (!ScanAutoLevels.lastCorrupt || attempt >= 2) {
                if (ScanAutoLevels.lastCorrupt) {
                    lastScanWarn = true
                    lastScanText = "! Scan incomplete - please retry"
                }
                return processed
            }
            setBusy("Retrying scan...")
        }
    }

    /** Feeder-paper check on whichever transport is active. */
    private fun feederHasPaper(): Boolean {
        // Bridge exposes platen only (AdfState Empty) — single scan per job.
        if (useWifi()) return false
        return try {
            if (wscnMode) WscnScanClient(usb).adfHasPaper { scanLog(it) } else true
        } catch (_: Exception) {
            scanCaps?.adfSupported == true && scanCaps?.scannerState != "Idle"
        }
    }

    /**
     * Drains stale bytes from EP 0x83 so old job data never splices in.
     * Uses a SHORT read timeout (800ms): with the default 5 s timeout this
     * loop added up to 15 s of "Preparing scanner..." before every scan.
     */
    private fun drainScanChannel() {
        try {
            val buf = ByteArray(64 * 1024)
            var drained = 0
            var idle = 0
            while (idle < 2 && drained < 4 * 1024 * 1024) {
                val n = usb.recvScanData(buf, 800)
                if (n > 0) { drained += n; idle = 0 } else idle++
            }
        } catch (_: Exception) {}
    }

    /** Manual scan-engine rescue ("Fix scanner" button). */
    private fun fixScannerFlow() {
        if (!checkReady()) return
        scanLog("fixScanner: manual rescue requested")
        setBusy("Recovering scanner engine...")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WscnScanClient(usb).rescueEngine(usb) { scanLog(it) }
                }
                Toast.makeText(this@MainActivity,
                    "Scanner reset",
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Recovery failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally { clearBusy() }
        }
    }

    private fun saveScan(jpeg: ByteArray): Uri? {
        val name = "scan-${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/M175Scans")
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)!!.use { it.write(jpeg) }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "M175Scans")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { it.write(jpeg) }
            return Uri.fromFile(f)
        }
    }

    /**
     * Writes the accumulated scanPages as one multi-page PDF into
     * Pictures/M175Scans and exposes it via FileProvider for sharing.
     */
    private fun finishScanPdf(dpi: Int) {
        if (scanPages.isEmpty()) return
        val name = "scan-${System.currentTimeMillis()}.pdf"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/M175Scans")
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)!!.use { out ->
                        ScanPdfWriter.writePdf(scanPages.toList(), dpi, out)
                    }
                    lastScanUri = uri
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "M175Scans")
                dir.mkdirs()
                val f = File(dir, name)
                ScanPdfWriter.toFile(scanPages.toList(), dpi, f)
                lastScanUri = Uri.fromFile(f)
            }
            scanPages.clear()
            lastScanText = lastScanText.replace(" | PDF page ${scanPages.size}", "") +
                    " | saved as PDF"
            Notifier.jobDone(this@MainActivity, "Scan PDF saved", "$dpi dpi")
        } catch (e: Exception) {
            lastScanWarn = true
            lastScanText = "PDF write failed: ${e.message}"
        }
    }

    private fun String.toUriOrNull(): Uri? = try { Uri.parse(this) } catch (_: Exception) { null }

    private fun checkReady(): Boolean {
        if (PhoneBridgeService.isHosting) {
            Toast.makeText(this,
                "This phone is hosting the bridge — Stop hosting to print/scan locally",
                Toast.LENGTH_LONG).show()
            return false
        }
        if (useWifi()) {
            if (bridgeHost.trim().isEmpty()) {
                Toast.makeText(this, "Set the bridge address in Setup",
                    Toast.LENGTH_SHORT).show()
                return false
            }
            return true
        }
        if (!usb.isOpen) {
            Toast.makeText(this, "Printer not connected",
                Toast.LENGTH_SHORT).show()
            tryConnect()
            return false
        }
        return true
    }
}
