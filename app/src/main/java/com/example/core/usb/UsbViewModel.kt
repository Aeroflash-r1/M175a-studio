package com.example.core.usb

import androidx.lifecycle.viewModelScope
import com.example.core.base.BaseViewModel
import com.example.core.base.UiEvent
import com.example.core.base.UiState
import com.example.domain.repository.UsbRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * UI State for the USB Generic Host Screen.
 */
data class UsbUiState(
    val devices: List<UsbDeviceSummary> = emptyList(),
    val selectedDevice: UsbDeviceInfo? = null,
    val connectionState: UsbConnectionState = UsbConnectionState.Disconnected,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
) : UiState

/**
 * Single-shot events emitted by the USB screen.
 */
sealed interface UsbUiEvent : UiEvent {
    data class ShowSnackbar(val message: String) : UsbUiEvent
    object ConnectionSuccess : UsbUiEvent
}

/**
 * View model managing state for generic USB device discovery and configuration parsing.
 */
class UsbViewModel(
    private val usbRepository: UsbRepository
) : BaseViewModel<UsbUiState, UsbUiEvent>() {

    init {
        // Observe live device lists
        usbRepository.discoveredDevices
            .onEach { list ->
                setState { copy(devices = list, isRefreshing = false) }
            }
            .launchIn(viewModelScope)

        // Observe connection state changes
        usbRepository.connectionState
            .onEach { state ->
                setState { copy(connectionState = state) }
                when (state) {
                    is UsbConnectionState.Connected -> {
                        sendEvent(UsbUiEvent.ConnectionSuccess)
                        sendEvent(UsbUiEvent.ShowSnackbar("Connected successfully to USB device."))
                    }
                    is UsbConnectionState.Error -> {
                        sendEvent(UsbUiEvent.ShowSnackbar(state.message))
                    }
                    else -> {}
                }
            }
            .launchIn(viewModelScope)

        // Observe selected device detailed information
        usbRepository.selectedDeviceInfo
            .onEach { info ->
                setState { copy(selectedDevice = info) }
            }
            .launchIn(viewModelScope)

        // Observe system-wide USB events
        usbRepository.usbEvents
            .onEach { event ->
                when (event) {
                    is UsbEvent.Attached -> {
                        sendEvent(UsbUiEvent.ShowSnackbar("New USB Device Attached: ${event.deviceName}"))
                        refreshDevices()
                    }
                    is UsbEvent.Detached -> {
                        sendEvent(UsbUiEvent.ShowSnackbar("USB Device Detached: ${event.deviceName}"))
                        refreshDevices()
                    }
                    is UsbEvent.PermissionStatus -> {
                        val msg = if (event.granted) "Permission granted." else "Permission denied."
                        sendEvent(UsbUiEvent.ShowSnackbar("Device: ${event.deviceName} - $msg"))
                        refreshDevices()
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun createInitialState(): UsbUiState = UsbUiState()

    /**
     * Triggers a manual scan of the USB host controller.
     */
    fun refreshDevices() {
        setState { copy(isRefreshing = true) }
        viewModelScope.launch {
            usbRepository.refreshDevices()
            // Turn off refresh indicator if empty or complete
            setState { copy(isRefreshing = false) }
        }
    }

    /**
     * Request access permission from the system for the given device.
     */
    fun requestPermission(deviceName: String) {
        usbRepository.requestPermission(deviceName)
    }

    /**
     * Establishes a connection to the specified device.
     */
    fun connectToDevice(deviceName: String) {
        viewModelScope.launch {
            when (val result = usbRepository.connectToDevice(deviceName)) {
                is UsbConnectionResult.Success -> {
                    setState { copy(errorMessage = null) }
                }
                is UsbConnectionResult.Failure -> {
                    setState { copy(errorMessage = result.error.message) }
                }
            }
        }
    }

    /**
     * Tears down any active connection.
     */
    fun disconnect() {
        usbRepository.disconnect()
        setState { copy(selectedDevice = null, errorMessage = null) }
    }
}
