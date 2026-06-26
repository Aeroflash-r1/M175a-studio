package com.example.ui.screens.scanner

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scanner.engine.ScannerResult
import com.example.scanner.engine.ScannerState
import org.koin.androidx.compose.koinViewModel
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    viewModel: ScannerViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HP M175a Scanner Console") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main content area supporting scroll for smaller screens
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Simulation Mode Banner
                item {
                    SimulationModeCard(
                        isSimulation = uiState.isSimulationMode,
                        onToggle = { viewModel.setSimulationMode(it) }
                    )
                }

                // Scan Configuration Panel
                item {
                    ScanConfigCard(
                        selectedResolution = uiState.selectedResolutionDpi,
                        selectedColorMode = uiState.selectedColorMode,
                        onResolutionSelected = { viewModel.selectResolution(it) },
                        onColorModeSelected = { viewModel.selectColorMode(it) },
                        enabled = uiState.state == ScannerState.Idle
                    )
                }

                // Workflow State & Progress Panel
                item {
                    WorkflowStatusCard(
                        state = uiState.state,
                        progress = uiState.progress,
                        activeJobId = uiState.activeJob?.jobId,
                        onScanClick = { viewModel.startScan() },
                        onCancelClick = { viewModel.cancelScan() }
                    )
                }

                // Captured Image Preview
                item {
                    CapturedImageCard(
                        result = uiState.lastResult,
                        onClearClick = { viewModel.clearLastResult() }
                    )
                }

                // Diagnostics Terminal Panel
                item {
                    DiagnosticsConsoleCard(
                        logs = uiState.diagnosticLogs,
                        onClearLogs = { viewModel.clearLogs() }
                    )
                }
            }
        }
    }
}

@Composable
fun SimulationModeCard(
    isSimulation: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSimulation) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (isSimulation) {
            borderStrokeForColor(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isSimulation) Icons.Default.Info else Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (isSimulation) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isSimulation) "Safety Simulation Mode" else "Physical USB Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSimulation) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isSimulation) {
                            "Running virtual transaction validation loops with high-fidelity canvas drawings."
                        } else {
                            "Attempting direct claiming on Endpoint 0x83 / 0x03."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isSimulation,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("simulation_toggle")
            )
        }
    }
}

@Composable
fun ScanConfigCard(
    selectedResolution: Int,
    selectedColorMode: String,
    onResolutionSelected: (Int) -> Unit,
    onColorModeSelected: (String) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Scanner Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Resolution segment
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resolution (DPI)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(300, 600).forEach { dpi ->
                        val isSelected = selectedResolution == dpi
                        Button(
                            onClick = { onResolutionSelected(dpi) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("res_${dpi}_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text("$dpi DPI")
                        }
                    }
                }
            }

            // Color Mode segment
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Color Space", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf("Color", "Grayscale").forEach { mode ->
                        val isSelected = selectedColorMode == mode
                        Button(
                            onClick = { onColorModeSelected(mode) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("color_mode_${mode.lowercase()}_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(mode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkflowStatusCard(
    state: ScannerState,
    progress: Float,
    activeJobId: String?,
    onScanClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val isScanning = state != ScannerState.Idle && state !is ScannerState.Error && state != ScannerState.Disconnected

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Workflow Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // State Badge
                StateBadge(state = state)
            }

            if (isScanning) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (activeJobId != null) "Job ID: $activeJobId" else "Acquiring scheduler connection...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            // Controls Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isScanning) {
                    Button(
                        onClick = onCancelClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cancel_scan_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel Scan")
                    }
                } else {
                    Button(
                        onClick = onScanClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_scan_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Document")
                    }
                }
            }
        }
    }
}

@Composable
fun CapturedImageCard(
    result: ScannerResult?,
    onClearClick: () -> Unit
) {
    if (result == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Retrieved Document Preview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onClearClick, modifier = Modifier.testTag("clear_preview_button")) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear preview")
                }
            }

            when (result) {
                is ScannerResult.Success -> {
                    val bitmap = remember(result.imageData) {
                        android.graphics.BitmapFactory.decodeByteArray(result.imageData, 0, result.imageData.size)
                    }

                    if (bitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(Color.White)
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Scanned result preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // Metadata table
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetadataRow(label = "Resolution", value = "${result.info.resolutionDpi} DPI")
                        MetadataRow(label = "Dimensions", value = "${result.info.widthPx} x ${result.info.heightPx} px")
                        MetadataRow(
                            label = "File Size",
                            value = String.format("%.2f KB", result.info.fileSize.toDouble() / 1024)
                        )
                        MetadataRow(
                            label = "Captured",
                            value = result.info.captureTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        )
                    }
                }
                is ScannerResult.Failure -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Scan Failed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                ScannerResult.Cancelled -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("The scan was cancelled by the user.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsConsoleCard(
    logs: List<String>,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Protocol & Transport Logs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onClearLogs, modifier = Modifier.testTag("clear_logs_button")) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Clear console logs")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Console is empty. Run a scan to capture telemetry.",
                        color = Color(0xFF888888),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        reverseLayout = false
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                color = if (log.contains("❌") || log.contains("Fatal") || log.contains("Error")) {
                                    Color(0xFFF44336)
                                } else if (log.contains("✅") || log.contains("Success")) {
                                    Color(0xFF4CAF50)
                                } else if (log.contains("⚙️") || log.contains("Step")) {
                                    Color(0xFF2196F3)
                                } else {
                                    Color(0xFFDCDCDC)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StateBadge(state: ScannerState) {
    val (label, color) = when (state) {
        is ScannerState.Idle -> "Idle" to MaterialTheme.colorScheme.secondary
        is ScannerState.Initializing -> "Initializing" to Color(0xFF2196F3)
        is ScannerState.GettingCapabilities -> "Capabilities" to Color(0xFF00bcd4)
        is ScannerState.CreatingJob -> "Scheduling" to Color(0xFFff9800)
        is ScannerState.WaitingForImage -> "Waiting" to Color(0xFF9c27b0)
        is ScannerState.ReceivingImage -> "Streaming" to Color(0xFF009688)
        is ScannerState.PollingJob -> "Polling" to Color(0xFF3f51b5)
        is ScannerState.Completed -> "Success" to Color(0xFF4CAF50)
        is ScannerState.Cancelled -> "Cancelled" to Color(0xFF795548)
        is ScannerState.Failed -> "Failed" to MaterialTheme.colorScheme.error
        is ScannerState.CleaningUp -> "Cleaning" to Color(0xFF607d8b)
        is ScannerState.Disconnected -> "Offline" to Color(0xFFe51c23)
        is ScannerState.Error -> "Error" to MaterialTheme.colorScheme.error
    }

    SuggestionChip(
        onClick = {},
        label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.15f),
            labelColor = color
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(
            enabled = true,
            borderColor = color.copy(alpha = 0.5f)
        )
    )
}

@Composable
fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun borderStrokeForColor(color: Color) = BorderStroke(1.dp, color)
