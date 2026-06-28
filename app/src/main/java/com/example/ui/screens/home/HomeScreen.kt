package com.example.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.navigation.Screen

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Home", style = MaterialTheme.typography.headlineMedium)
            Text("M175a Studio Main Dashboard", modifier = Modifier.padding(bottom = 16.dp))
            
            Button(onClick = { onNavigate(Screen.Printer.route) }, modifier = Modifier.padding(4.dp)) {
                Text("Printer")
            }
            Button(onClick = { onNavigate(Screen.Scanner.route) }, modifier = Modifier.padding(4.dp)) {
                Text("Scanner")
            }
            Button(onClick = { onNavigate(Screen.History.route) }, modifier = Modifier.padding(4.dp)) {
                Text("History")
            }
            Button(onClick = { onNavigate(Screen.Settings.route) }, modifier = Modifier.padding(4.dp)) {
                Text("Settings")
            }
            Button(onClick = { onNavigate(Screen.Usb.route) }, modifier = Modifier.padding(4.dp)) {
                Text("USB Diagnostics")
            }
            Button(onClick = { onNavigate(Screen.HpDetails.route) }, modifier = Modifier.padding(4.dp)) {
                Text("HP LaserJet Discovery")
            }
            Button(onClick = { onNavigate(Screen.Analyzer.route) }, modifier = Modifier.padding(4.dp)) {
                Text("USB Traffic Analyzer")
            }
            Button(onClick = { onNavigate(Screen.SessionReports.route) }, modifier = Modifier.padding(4.dp)) {
                Text("USB Session Reports")
            }
        }
    }
}
