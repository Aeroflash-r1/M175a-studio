package com.example.ui.screens.analyzer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.usb.analyzer.UsbAnalyzerViewModel
import com.example.core.usb.analyzer.UsbSessionReport
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbSessionReportsScreen(
    onBack: () -> Unit,
    viewModel: UsbAnalyzerViewModel = koinViewModel()
) {
    val reports by viewModel.sessionReports.collectAsState()
    var selectedReport by remember { mutableStateOf<UsbSessionReport?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedReport == null) "USB Session Reports" else "Report Details") },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (selectedReport != null) selectedReport = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedReport != null) {
                        IconButton(onClick = { /* TODO: Export */ }) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedReport == null) {
                ReportList(reports) { selectedReport = it }
            } else {
                ReportDetails(selectedReport!!)
            }
        }
    }
}

@Composable
fun ReportList(reports: List<UsbSessionReport>, onSelect: (UsbSessionReport) -> Unit) {
    if (reports.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No session reports available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(reports.reversed()) { report ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(report) },
                colors = CardDefaults.cardColors(
                    containerColor = if (report.summary.reason.contains("ended normally")) 
                                     MaterialTheme.colorScheme.primaryContainer 
                                     else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    Text("Session ID: ${report.id.take(8)}...", fontWeight = FontWeight.Bold)
                    Text("Time: ${sdf.format(Date(report.timestamp))}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Result: ${report.summary.reason}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun ReportDetails(report: UsbSessionReport) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USB Session Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HorizontalDivider()
                DetailRow("Device Open", if (report.summary.deviceOpenPass) "PASS" else "FAILED")
                DetailRow("Descriptor Parsing", if (report.summary.descriptorParsingPass) "PASS" else "FAILED")
                DetailRow("Interface Enumeration", if (report.summary.interfaceEnumerationPass) "PASS" else "FAILED")
                DetailRow("Interface Claim", if (report.summary.interfaceClaimPass) "PASS" else "FAILED")
                DetailRow("Endpoint Ready", if (report.summary.endpointReadyPass) "PASS" else "FAILED")
                DetailRow("Bulk Transfers", "${report.summary.bulkTransfers}")
                DetailRow("Control Transfers", "${report.summary.controlTransfers}")
                Spacer(modifier = Modifier.height(4.dp))
                Text("Reason: ${report.summary.reason}", color = MaterialTheme.colorScheme.error)
            }
        }

        // Header
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Session Header", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HorizontalDivider()
                DetailRow("App Version", report.header.appVersion)
                DetailRow("Android Version", report.header.androidVersion)
                DetailRow("Manufacturer", report.header.manufacturer)
                DetailRow("Model", report.header.model)
                DetailRow("Kernel", report.header.kernelVersion)
                DetailRow("USB API", report.header.usbHostApiVersion)
            }
        }

        // Device Info
        if (report.deviceInfo != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    DetailRow("VID", "0x${report.deviceInfo.vid.toString(16)}")
                    DetailRow("PID", "0x${report.deviceInfo.pid.toString(16)}")
                    DetailRow("Manufacturer", report.deviceInfo.manufacturerName ?: "Unknown")
                    DetailRow("Product", report.deviceInfo.productName ?: "Unknown")
                    DetailRow("Serial Number", report.deviceInfo.serialNumber ?: "Unknown")
                    DetailRow("USB Version", report.deviceInfo.usbVersion ?: "Unknown")
                    DetailRow("Configurations", "${report.deviceInfo.configurationCount}")
                }
            }
        }

        // Interface Claims
        if (report.claims.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Interface Claim Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    for (claim in report.claims) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("Interface ${claim.interfaceNumber} (Force: ${claim.force}) -> ${if (claim.result) "SUCCESS" else "FAILED"}")
                            Text("Duration: ${claim.durationMs}ms", style = MaterialTheme.typography.bodySmall)
                            Text("Caller: ${claim.caller}", style = MaterialTheme.typography.bodySmall)
                            if (claim.failureDiagnostic != null) {
                                Text("Diagnostic: ${claim.failureDiagnostic}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (claim.exception != null) {
                                Text("Exception: ${claim.exception}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        
        // Interfaces
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Interface Enumeration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HorizontalDivider()
                for (intf in report.interfaces) {
                    Text("Interface ${intf.interfaceNumber} (Alt ${intf.alternateSetting})", fontWeight = FontWeight.SemiBold)
                    Text("Class: ${intf.interfaceClass}, Subclass: ${intf.interfaceSubclass}, Protocol: ${intf.interfaceProtocol}", style = MaterialTheme.typography.bodySmall)
                    for (ep in intf.endpoints) {
                        Text("  ↳ EP 0x${ep.address.toString(16)} [${ep.direction}] ${ep.transferType} MaxPkt:${ep.maxPacketSize}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}
