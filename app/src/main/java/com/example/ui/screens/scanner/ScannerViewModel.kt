package com.example.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.repository.ScannerRepository
import com.example.scanner.engine.ScannerJob
import com.example.scanner.engine.ScannerResult
import com.example.scanner.engine.ScannerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * UI State holding options and active scan statistics.
 */
data class ScannerUiState(
    val state: ScannerState = ScannerState.Idle,
    val progress: Float = 0.0f,
    val activeJob: ScannerJob? = null,
    val isSimulationMode: Boolean = true,
    val selectedResolutionDpi: Int = 300,
    val selectedColorMode: String = "Color",
    val lastResult: ScannerResult? = null,
    val diagnosticLogs: List<String> = emptyList()
)

/**
 * Dedicated ViewModel for managing scanning controls, state, and visual displays.
 */
class ScannerViewModel(
    private val scannerRepository: ScannerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        // Collect reactive state flows from the repository
        viewModelScope.launch {
            scannerRepository.state.collect { state ->
                addDiagnosticLog(getLogMessageForState(state))
                _uiState.value = _uiState.value.copy(state = state)
            }
        }

        viewModelScope.launch {
            scannerRepository.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(progress = progress)
            }
        }

        viewModelScope.launch {
            scannerRepository.activeJob.collect { activeJob ->
                _uiState.value = _uiState.value.copy(activeJob = activeJob)
            }
        }

        viewModelScope.launch {
            scannerRepository.isSimulationMode.collect { sim ->
                _uiState.value = _uiState.value.copy(isSimulationMode = sim)
            }
        }

        addDiagnosticLog("System initialized. Welcome to M175a Studio Scan console.")
    }

    /**
     * Triggers the 5-step scan workflow.
     */
    fun startScan() {
        val current = _uiState.value
        addDiagnosticLog("🚀 Triggering scan: ${current.selectedResolutionDpi} DPI, Mode=${current.selectedColorMode}")
        
        viewModelScope.launch {
            val result = scannerRepository.startScan(
                resolutionDpi = current.selectedResolutionDpi,
                colorMode = current.selectedColorMode
            )
            
            _uiState.value = _uiState.value.copy(lastResult = result)

            when (result) {
                is ScannerResult.Success -> {
                    addDiagnosticLog("✅ Scan finished! Captured ${result.imageData.size} bytes. Dimensions: ${result.info.widthPx}x${result.info.heightPx}px")
                }
                is ScannerResult.Failure -> {
                    addDiagnosticLog("❌ Scan failed: ${result.message}")
                }
                ScannerResult.Cancelled -> {
                    addDiagnosticLog("🛑 Scan cancelled by user.")
                }
            }
        }
    }

    /**
     * Cancels an ongoing scan execution.
     */
    fun cancelScan() {
        addDiagnosticLog("🛑 Requesting active job cancellation...")
        scannerRepository.cancelScan()
    }

    /**
     * Sets whether the engine runs in mock/simulation mode.
     */
    fun setSimulationMode(enabled: Boolean) {
        scannerRepository.setSimulationMode(enabled)
        addDiagnosticLog("⚙️ Simulation Mode changed to: $enabled")
    }

    fun selectResolution(dpi: Int) {
        _uiState.value = _uiState.value.copy(selectedResolutionDpi = dpi)
        addDiagnosticLog("⚙️ Resolution set to $dpi DPI")
    }

    fun selectColorMode(mode: String) {
        _uiState.value = _uiState.value.copy(selectedColorMode = mode)
        addDiagnosticLog("⚙️ Color mode set to $mode")
    }

    fun clearLastResult() {
        _uiState.value = _uiState.value.copy(lastResult = null)
        addDiagnosticLog("🧹 Cleared last scan results from memory.")
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(diagnosticLogs = emptyList())
    }

    private fun addDiagnosticLog(message: String) {
        val timestamp = java.time.LocalTime.now().format(dateFormatter)
        val line = "[$timestamp] $message"
        val logs = _uiState.value.diagnosticLogs.toMutableList()
        logs.add(0, line) // Prepend to show latest on top
        if (logs.size > 100) {
            logs.removeAt(logs.size - 1)
        }
        _uiState.value = _uiState.value.copy(diagnosticLogs = logs)
    }

    private fun getLogMessageForState(state: ScannerState): String {
        return when (state) {
            is ScannerState.Idle -> "Idle. Ready for scanning."
            is ScannerState.Initializing -> "Step 1: Opening session & claiming USB interfaces..."
            is ScannerState.GettingCapabilities -> "Step 1: Gaining Device Capabilities (GetScannerElements SOAP Request)..."
            is ScannerState.CreatingJob -> "Step 2: Submitting CreateScanJob request to scheduler..."
            is ScannerState.WaitingForImage -> "Step 3: Initializing Bulk read on EP3 (0x83)..."
            is ScannerState.ReceivingImage -> "Step 3: Streaming multi-megabyte binary DIME payload..."
            is ScannerState.PollingJob -> "Step 4: Querying JobSummary state parameters..."
            is ScannerState.Completed -> "Job State Completed. Parsing SOI/EOI markers..."
            is ScannerState.Cancelled -> "Workflow aborted: job cancelled."
            is ScannerState.Failed -> "Workflow error: job failed on device."
            is ScannerState.CleaningUp -> "Step 5: Sending DestroyScanJob XML to release resources..."
            is ScannerState.Disconnected -> "USB Communication Interface detached."
            is ScannerState.Error -> "Fatal Engine Error: ${(state as ScannerState.Error).message}"
        }
    }
}
