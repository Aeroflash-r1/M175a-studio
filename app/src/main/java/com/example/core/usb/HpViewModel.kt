package com.example.core.usb

import androidx.lifecycle.viewModelScope
import com.example.core.base.BaseViewModel
import com.example.core.base.UiEvent
import com.example.core.base.UiState
import com.example.core.logging.Logger
import com.example.domain.repository.UsbRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * UI State for the HP LaserJet 100 color MFP M175a Discovery & Diagnostic Screen.
 */
data class HpUiState(
    val detectedDevices: List<UsbDeviceSummary> = emptyList(),
    val isHpDeviceAttached: Boolean = false,
    val hpDeviceSummary: UsbDeviceSummary? = null,
    val hpDeviceInfo: UsbDeviceInfo? = null,
    val capabilityReport: HpCapabilityReport? = null,
    val diagnostics: HpDiagnostics? = null,
    val connectionState: UsbConnectionState = UsbConnectionState.Disconnected,
    val isRefreshing: Boolean = false,
    val hasPermission: Boolean = false
) : UiState

/**
 * UI Events for HP specific discovery.
 */
sealed interface HpUiEvent : UiEvent {
    data class ShowSnackbar(val message: String) : HpUiEvent
    object ConnectionSuccess : HpUiEvent
}

/**
 * Dedicated ViewModel handling discovery, identification, classification and validation of the HP LaserJet 100 color MFP M175a.
 */
class HpViewModel(
    private val usbRepository: UsbRepository,
    private val logger: Logger
) : BaseViewModel<HpUiState, HpUiEvent>() {

    private val tag = "HpDeviceDiscovery"

    init {
        // Observe generic USB devices and filter for the HP device
        usbRepository.discoveredDevices
            .onEach { devices ->
                val hpDevice = devices.find { HpDeviceClassifier.isHpTargetDevice(it.vendorId, it.productId) }
                val attached = hpDevice != null

                if (attached && !currentState.isHpDeviceAttached) {
                    logger.i(tag, "🔍 HP Device Detected: HP LaserJet 100 color MFP M175a found on the USB bus!")
                } else if (!attached && currentState.isHpDeviceAttached) {
                    logger.i(tag, "🔌 HP Device Removed from the USB bus.")
                }

                setState {
                    copy(
                        detectedDevices = devices,
                        isHpDeviceAttached = attached,
                        hpDeviceSummary = hpDevice,
                        hasPermission = hpDevice?.hasPermission ?: false,
                        isRefreshing = false
                    )
                }

                // If permission status changed, refresh connected info if active
                if (attached && hpDevice?.hasPermission == true && currentState.hpDeviceInfo == null) {
                    // Try auto connecting or wait for user to click parse
                }
            }
            .launchIn(viewModelScope)

        // Observe connection state changes
        usbRepository.connectionState
            .onEach { state ->
                setState { copy(connectionState = state) }
                updateDiagnostics()
            }
            .launchIn(viewModelScope)

        // Observe detailed descriptors
        usbRepository.selectedDeviceInfo
            .onEach { info ->
                if (info != null && HpDeviceClassifier.isHpTargetDevice(info.vendorId, info.productId)) {
                    logger.i(tag, "✅ HP Device Classified. Generating capability report...")
                    val report = HpDeviceClassifier.generateCapabilityReport(info)
                    logger.i(tag, "📊 Capability Report Generated: Scanner Present=${report.isScannerInterfacePresent}, Printer Present=${report.isPrinterInterfacePresent}, Communication Ready=${report.isCommunicationReady}")
                    
                    setState {
                        copy(
                            hpDeviceInfo = info,
                            capabilityReport = report
                        )
                    }
                } else {
                    setState {
                        copy(
                            hpDeviceInfo = null,
                            capabilityReport = null
                        )
                    }
                }
                updateDiagnostics()
            }
            .launchIn(viewModelScope)

        // Observe USB events
        usbRepository.usbEvents
            .onEach { event ->
                when (event) {
                    is UsbEvent.Attached -> {
                        if (HpDeviceClassifier.isHpTargetDevice(event.vendorId, event.productId)) {
                            logger.i(tag, "🎯 VID/PID Match: Supported HP M175a attached.")
                            sendEvent(HpUiEvent.ShowSnackbar("Supported HP LaserJet M175a Connected!"))
                        }
                    }
                    is UsbEvent.Detached -> {
                        // Handled by discovered list flow
                    }
                    is UsbEvent.PermissionStatus -> {
                        // Handled by list flow
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun createInitialState(): HpUiState = HpUiState()

    /**
     * Rescans the USB Host Bus.
     */
    fun refreshDevices() {
        setState { copy(isRefreshing = true) }
        viewModelScope.launch {
            usbRepository.refreshDevices()
        }
    }

    /**
     * Request OS permission to open the HP LaserJet M175a.
     */
    fun requestHpPermission() {
        val summary = currentState.hpDeviceSummary ?: return
        logger.i(tag, "🔑 Requesting OS permission for HP device: ${summary.deviceName}")
        usbRepository.requestPermission(summary.deviceName)
    }

    /**
     * Connects to the HP LaserJet M175a and parses its descriptor tables.
     */
    fun connectAndParse() {
        val summary = currentState.hpDeviceSummary ?: return
        viewModelScope.launch {
            logger.i(tag, "🔌 Connecting and starting descriptor analysis for HP device...")
            when (val result = usbRepository.connectToDevice(summary.deviceName)) {
                is UsbConnectionResult.Success -> {
                    logger.i(tag, "✅ Validation Passed. Descriptor tables mapped perfectly.")
                    sendEvent(HpUiEvent.ConnectionSuccess)
                }
                is UsbConnectionResult.Failure -> {
                    logger.e(tag, "❌ Validation Failed: ${result.error.message}")
                    sendEvent(HpUiEvent.ShowSnackbar("Analysis Failed: ${result.error.message}"))
                }
            }
        }
    }

    /**
     * Tears down the active session.
     */
    fun disconnect() {
        logger.i(tag, "🔌 Releasing HP device connection.")
        usbRepository.disconnect()
    }

    private fun updateDiagnostics() {
        val info = currentState.hpDeviceInfo
        val hasPerm = currentState.hasPermission
        val connState = when (currentState.connectionState) {
            is UsbConnectionState.Connected -> "Connected"
            is UsbConnectionState.Connecting -> "Connecting"
            is UsbConnectionState.Disconnected -> "Disconnected"
            is UsbConnectionState.Error -> "Error: ${(currentState.connectionState as UsbConnectionState.Error).message}"
        }

        if (info != null) {
            val diags = HpDeviceClassifier.generateDiagnostics(info, hasPerm, connState)
            setState { copy(diagnostics = diags) }
        } else {
            setState { copy(diagnostics = null) }
        }
    }
}
