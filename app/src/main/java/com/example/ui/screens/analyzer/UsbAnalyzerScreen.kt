package com.example.ui.screens.analyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.usb.analyzer.UsbAnalyzerViewModel
import com.example.core.usb.analyzer.UsbPacket
import com.example.core.usb.analyzer.UsbEvent
import java.text.SimpleDateFormat
import java.util.*
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbAnalyzerScreen(
    viewModel: UsbAnalyzerViewModel = koinViewModel()
) {
    val packets by viewModel.packets.collectAsState()
    val events by viewModel.events.collectAsState()
    val stats by viewModel.stats.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedPacket by remember { mutableStateOf<UsbPacket?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB Traffic Analyzer", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { /* TODO: Replay */ }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Replay Session")
                    }
                    IconButton(onClick = { /* TODO: Export */ }) {
                        Icon(Icons.Default.List, contentDescription = "Export JSON/PCAP")
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Packets (${packets.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Events (${events.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Stats") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Console") }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> {
                        if (selectedPacket == null) {
                            PacketList(packets) { selectedPacket = it }
                        } else {
                            PacketDetails(
                                packet = selectedPacket!!,
                                onBack = { selectedPacket = null }
                            )
                        }
                    }
                    1 -> EventList(events)
                    2 -> StatsView(stats)
                    3 -> ConsoleView()
                }
            }
        }
    }
}

@Composable
fun PacketList(packets: List<UsbPacket>, onPacketClick: (UsbPacket) -> Unit) {
    if (packets.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No packets captured", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("ID", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Time", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Dir", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Type", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("EP", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Len", modifier = Modifier.width(50.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Status", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(packets) { packet ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .clickable { onPacketClick(packet) }
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = when (packet.status) {
                        "SUCCESS" -> MaterialTheme.colorScheme.onSurface
                        "TIMEOUT" -> Color(0xFFFF9800)
                        else -> MaterialTheme.colorScheme.error
                    }

                    Text("${packet.id}", modifier = Modifier.width(40.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                    Text(formatTime(packet.timestamp), modifier = Modifier.width(90.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                    Text(packet.direction, modifier = Modifier.width(40.dp), fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                    Text(packet.type, modifier = Modifier.width(60.dp), fontSize = 12.sp, color = color)
                    Text(if (packet.endpointAddress == 0) "0" else "0x${packet.endpointAddress.toString(16)}", modifier = Modifier.width(40.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                    Text("${packet.length}", modifier = Modifier.width(50.dp), fontSize = 12.sp, color = color)
                    Text(packet.status, modifier = Modifier.width(80.dp), fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun PacketDetails(packet: UsbPacket, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("Back")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("Packet #${packet.id} Details", fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailRow("Time", formatTimeMs(packet.timestamp))
                        DetailRow("Direction", packet.direction)
                        DetailRow("Endpoint", "0x${packet.endpointAddress.toString(16)}")
                        DetailRow("Transfer Type", packet.type)
                        DetailRow("Length", "${packet.length} bytes")
                        DetailRow("Duration", "${packet.durationMs} ms")
                        DetailRow("Status", packet.status)
                    }
                }
            }

            if (packet.data.isNotEmpty()) {
                item {
                    Text("Hex Dump", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatHexDump(packet.data),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            
            if (packet.callerStackTrace.isNotBlank()) {
                item {
                    Text("Stack Trace", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = packet.callerStackTrace,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EventList(events: List<UsbEvent>) {
    if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No events logged", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(events) { event ->
            val color = when (event.colorCode) {
                "Green" -> Color(0xFF2E7D32)
                "Red" -> MaterialTheme.colorScheme.error
                "Yellow" -> Color(0xFFF57F17)
                else -> MaterialTheme.colorScheme.primary
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = event.eventType,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                        Text(
                            text = formatTimeMs(event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = event.details, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun StatsView(stats: com.example.core.usb.analyzer.UsbAnalyzerStats) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Traffic Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Total Packets", "${stats.totalPackets}")
                    DetailRow("Bytes Sent", "${stats.totalBytesSent}")
                    DetailRow("Bytes Received", "${stats.totalBytesReceived}")
                }
            }
        }

        item {
            Text("Error Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Errors", "${stats.errors}")
                    DetailRow("Timeouts", "${stats.timeouts}")
                    DetailRow("STALLs", "${stats.stallCount}")
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
private val timeFormatMs = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun formatTime(ts: Long): String = timeFormat.format(Date(ts))
private fun formatTimeMs(ts: Long): String = timeFormatMs.format(Date(ts))

private fun formatHexDump(data: ByteArray): String {
    val sb = StringBuilder()
    for (i in data.indices step 16) {
        sb.append(String.format("%04X  ", i))
        
        // Hex
        for (j in 0 until 16) {
            if (i + j < data.size) {
                sb.append(String.format("%02X ", data[i + j]))
            } else {
                sb.append("   ")
            }
            if (j == 7) sb.append(" ")
        }
        
        sb.append(" |")
        // ASCII
        for (j in 0 until 16) {
            if (i + j < data.size) {
                val b = data[i + j].toInt() and 0xFF
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else {
                    sb.append('.')
                }
            }
        }
        sb.append("|\n")
    }
    return sb.toString()
}

@Composable
fun ConsoleView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Raw USB Console", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Manual endpoint and interface selection. Issue raw USB transfers for forensic debugging.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("Endpoint (e.g. 0x03)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("HEX Payload (e.g. 1B 45 1B...)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Text("Send Bulk OUT")
            }
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Text("Read Bulk IN")
            }
        }
        
        HorizontalDivider()
        
        Text("Response Output", fontWeight = FontWeight.Bold)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Text("No output yet...", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
