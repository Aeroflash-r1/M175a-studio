package com.example.ui.screens.usb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.usb.*
import com.example.core.usb.transport.*
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbScreen(
    onBack: () -> Unit,
    viewModel: UsbViewModel = koinViewModel(),
    communicationViewModel: UsbCommunicationViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val commState by communicationViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Synchronize communication session with selected device
    LaunchedEffect(uiState.selectedDevice) {
        if (uiState.selectedDevice != null) {
            communicationViewModel.startSession()
        } else {
            communicationViewModel.endSession()
        }
    }

    // Collect single-shot events
    LaunchedEffect(key1 = true) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is UsbUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is UsbUiEvent.ConnectionSuccess -> {
                    // Handled if we need custom routing, but staying on detail is perfect.
                }
            }
        }
    }

    LaunchedEffect(key1 = true) {
        communicationViewModel.uiEvent.collect { event ->
            when (event) {
                is UsbCommunicationUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("USB Discovery & Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Devices"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Top Status Bar Banner
            UsbStatusBanner(connectionState = uiState.connectionState)

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.devices.isEmpty()) {
                UsbEmptyState(onRefresh = { viewModel.refreshDevices() })
            } else {
                Text(
                    text = "Detected USB Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.devices) { device ->
                        UsbDeviceCard(
                            device = device,
                            isSelected = uiState.selectedDevice?.deviceName == device.deviceName,
                            onConnect = { viewModel.connectToDevice(device.deviceName) },
                            onDisconnect = { viewModel.disconnect() },
                            onRequestPermission = { viewModel.requestPermission(device.deviceName) }
                        )
                    }

                    // Selected device comprehensive descriptor tree
                    uiState.selectedDevice?.let { info ->
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "USB Transport Diagnostics",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            UsbTransportDiagnosticsCard(
                                info = info,
                                commState = commState,
                                onStartSession = { communicationViewModel.startSession() },
                                onEndSession = { communicationViewModel.endSession() },
                                onClaimInterface = { communicationViewModel.claimInterface(it) },
                                onReleaseInterface = { communicationViewModel.releaseInterface(it) },
                                onWriteBulk = { ep, hex -> communicationViewModel.writeRawBulk(ep, hex) },
                                onReadBulk = { ep, size -> communicationViewModel.readRawBulk(ep, size) },
                                onRecover = { communicationViewModel.recoverConnection() }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Device Detailed Descriptor",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            UsbDeviceDetailsCard(info = info)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UsbTransportDiagnosticsCard(
    info: UsbDeviceInfo,
    commState: UsbCommunicationUiState,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onClaimInterface: (Int) -> Unit,
    onReleaseInterface: (Int) -> Unit,
    onWriteBulk: (Int, String) -> Unit,
    onReadBulk: (Int, Int) -> Unit,
    onRecover: () -> Unit
) {
    var selectedEpAddressStr by remember { mutableStateOf("") }
    var payloadStr by remember { mutableStateOf("") }
    var bufferSizeStr by remember { mutableStateOf("64") }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Transport Live Metrics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val stats = commState.stats

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    DiagnosticMetricItem("Communication Ready", if (stats.isCommunicationReady) "ACTIVE" else "INACTIVE", stats.isCommunicationReady)
                    DiagnosticMetricItem("Connection Open", if (stats.isConnectionOpen) "YES" else "NO", stats.isConnectionOpen)
                    DiagnosticMetricItem("Interfaces Claimed", if (stats.claimedInterfaces.isEmpty()) "None" else stats.claimedInterfaces.joinToString(", "), stats.claimedInterfaces.isNotEmpty())
                    DiagnosticMetricItem("Endpoints Ready", if (stats.readyEndpoints.isEmpty()) "None" else stats.readyEndpoints.joinToString(", ") { "0x${Integer.toHexString(it)}" }, stats.readyEndpoints.isNotEmpty())
                }
                Column(modifier = Modifier.weight(1f)) {
                    DiagnosticMetricItem("Transfer Status", stats.transferStatus, stats.transferStatus != "Idle" && stats.transferStatus != "Disconnected")
                    DiagnosticMetricItem("Bytes Sent", "${stats.bytesSent} B", false)
                    DiagnosticMetricItem("Bytes Received", "${stats.bytesReceived} B", false)
                    DiagnosticMetricItem("Duration", "${stats.connectionDurationSec} s", false)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DiagnosticMetricItem("Transport Health", stats.transportHealth, stats.transportHealth == "Excellent")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Last Transfer Detail: ${stats.lastTransferDetails}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Control Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!stats.isCommunicationReady) {
                    Button(onClick = onStartSession, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start Session")
                    }
                } else {
                    FilledTonalButton(onClick = onEndSession, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("End Session")
                    }
                }

                Button(
                    onClick = onRecover,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Soft Recovery")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Claims and Releases List
            Text(
                text = "Interface Claim Controllers",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            info.configurations.forEach { config ->
                config.interfaces.forEach { interf ->
                    val isClaimed = stats.claimedInterfaces.contains(interf.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Interface ${interf.id} (${interf.className})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        if (isClaimed) {
                            ElevatedButton(
                                onClick = { onReleaseInterface(interf.id) },
                                colors = ButtonDefaults.elevatedButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Text("Release", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        } else {
                            ElevatedButton(
                                onClick = { onClaimInterface(interf.id) }
                            ) {
                                Text("Claim")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Raw Bulk transfer terminal sandbox
            Text(
                text = "Raw Bulk Packet Sandbox",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = selectedEpAddressStr,
                onValueChange = { selectedEpAddressStr = it },
                label = { Text("Endpoint Address (Hex or Int, e.g. 0x01, 1)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = payloadStr,
                    onValueChange = { payloadStr = it },
                    label = { Text("Payload (HEX)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                OutlinedTextField(
                    value = bufferSizeStr,
                    onValueChange = { bufferSizeStr = it },
                    label = { Text("Read Buffer Size") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val targetEp = try {
                if (selectedEpAddressStr.startsWith("0x", ignoreCase = true)) {
                    selectedEpAddressStr.substring(2).toInt(16)
                } else {
                    selectedEpAddressStr.toInt()
                }
            } catch (e: Exception) {
                null
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        targetEp?.let { ep -> onWriteBulk(ep, payloadStr) }
                    },
                    enabled = targetEp != null && stats.isCommunicationReady && payloadStr.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Bulk OUT")
                }

                Button(
                    onClick = {
                        val size = bufferSizeStr.toIntOrNull() ?: 64
                        targetEp?.let { ep -> onReadBulk(ep, size) }
                    },
                    enabled = targetEp != null && stats.isCommunicationReady,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Bulk IN")
                }
            }

            if (commState.lastResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Terminal Output:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = commState.lastResult,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticMetricItem(label: String, value: String, highlight: Boolean) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun UsbStatusBanner(connectionState: UsbConnectionState) {
    val containerColor: Color
    val contentColor: Color
    val statusText: String
    val statusIcon: androidx.compose.ui.graphics.vector.ImageVector

    when (connectionState) {
        is UsbConnectionState.Disconnected -> {
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            statusText = "USB Host: Idle (No device connected)"
            statusIcon = Icons.Default.Info
        }
        is UsbConnectionState.Connecting -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            statusText = "USB Host: Handshaking & Parsing..."
            statusIcon = Icons.Default.Refresh
        }
        is UsbConnectionState.Connected -> {
            containerColor = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
            statusText = "USB Connected: ${connectionState.deviceName.takeLast(12)}"
            statusIcon = Icons.Default.CheckCircle
        }
        is UsbConnectionState.Error -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            statusText = "USB Error: ${connectionState.message}"
            statusIcon = Icons.Default.Warning
        }
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = "Status Icon",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun UsbDeviceCard(
    device: UsbDeviceSummary,
    isSelected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermission: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.productName ?: "Generic USB Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = device.manufacturerName ?: "Unknown Manufacturer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (device.hasPermission) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (device.hasPermission) "Authorized" else "Unauthorized",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        color = if (device.hasPermission) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "VID: ${formatHex(device.vendorId)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "PID: ${formatHex(device.productId)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!device.hasPermission) {
                    Button(onClick = onRequestPermission) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Permission")
                    }
                } else {
                    if (isSelected) {
                        FilledTonalButton(onClick = onDisconnect) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Disconnect")
                        }
                    } else {
                        Button(onClick = onConnect) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect & Parse")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UsbDeviceDetailsCard(info: UsbDeviceInfo) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // General Properties
            DetailRow(label = "Product Name", value = info.productName ?: "N/A")
            DetailRow(label = "Manufacturer", value = info.manufacturerName ?: "N/A")
            DetailRow(label = "Serial Number", value = info.serialNumber ?: "N/A")
            DetailRow(label = "USB Version", value = info.usbVersion)
            DetailRow(label = "Power Specs", value = "${info.powerSource} (${info.maxPower}mA)")
            DetailRow(label = "Device Class", value = "${info.deviceClassName} (0x${Integer.toHexString(info.deviceClass)})")

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Parse configurations tree
            info.configurations.forEach { config ->
                Text(
                    text = "Configuration ${config.id}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                config.interfaces.forEach { interf ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Interface ${interf.id} (Alt: ${interf.alternateSetting})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Class: ${interf.className} (0x${Integer.toHexString(interf.classId)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (interf.endpoints.isEmpty()) {
                                Text(
                                    text = "No Endpoints defined.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "Endpoints:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )

                                interf.endpoints.forEach { ep ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "• Address: 0x${Integer.toHexString(ep.address)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "${ep.type} ${ep.direction}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (ep.direction == "IN") Color(0xFF2E7D32) else Color(0xFFC62828)
                                        )
                                        Text(
                                            text = "${ep.maxPacketSize}B (Int: ${ep.interval}ms)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun UsbEmptyState(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No USB Devices Found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect a compatible USB accessory (e.g. printer or scanner) using an OTG adapter and click Scan below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan USB Bus")
            }
        }
    }
}

private fun formatHex(value: Int): String {
    return "0x" + String.format("%04X", value)
}
