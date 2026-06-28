package com.example.core.usb.analyzer

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class UsbAnalyzerViewModel(
    private val engine: UsbAnalyzerEngine
) : ViewModel() {

    val packets: StateFlow<List<UsbPacket>> = engine.packets
    val events: StateFlow<List<UsbEvent>> = engine.events
    val stats: StateFlow<UsbAnalyzerStats> = engine.stats

    fun clear() {
        engine.clear()
    }
}
