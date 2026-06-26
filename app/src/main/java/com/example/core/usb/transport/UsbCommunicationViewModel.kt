package com.example.core.usb.transport

import androidx.lifecycle.viewModelScope
import com.example.core.base.BaseViewModel
import com.example.core.base.UiEvent
import com.example.core.base.UiState
import com.example.domain.repository.UsbCommunicationRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * UI State for generic USB transport diagnostics.
 */
data class UsbCommunicationUiState(
    val stats: UsbTransportStats = UsbTransportStats(),
    val isProcessing: Boolean = false,
    val lastResult: String = "",
    val errorMessage: String? = null
) : UiState

/**
 * UI Events emitted by the communication system.
 */
sealed interface UsbCommunicationUiEvent : UiEvent {
    data class ShowSnackbar(val message: String) : UsbCommunicationUiEvent
}

/**
 * ViewModel managing the active low-level USB transfer diagnostics.
 */
class UsbCommunicationViewModel(
    private val communicationRepository: UsbCommunicationRepository
) : BaseViewModel<UsbCommunicationUiState, UsbCommunicationUiEvent>() {

    init {
        communicationRepository.transportStats
            .onEach { update ->
                setState { copy(stats = update) }
            }
            .launchIn(viewModelScope)
    }

    override fun createInitialState(): UsbCommunicationUiState = UsbCommunicationUiState()

    /**
     * Initializes a generic communication session on the active connection.
     */
    fun startSession() {
        val success = communicationRepository.startSession()
        if (success) {
            sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Transport session established successfully"))
        } else {
            sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Failed to establish session. Check connection."))
        }
    }

    /**
     * Tears down the active generic transport session.
     */
    fun endSession() {
        communicationRepository.endSession()
        sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Transport session closed."))
    }

    /**
     * Claims the specified interface.
     */
    fun claimInterface(interfaceId: Int) {
        setState { copy(isProcessing = true) }
        viewModelScope.launch {
            val success = communicationRepository.claimInterface(interfaceId)
            setState { copy(isProcessing = false) }
            if (success) {
                sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Claimed Interface $interfaceId successfully"))
            } else {
                sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Failed to claim Interface $interfaceId"))
            }
        }
    }

    /**
     * Releases the specified interface.
     */
    fun releaseInterface(interfaceId: Int) {
        setState { copy(isProcessing = true) }
        viewModelScope.launch {
            val success = communicationRepository.releaseInterface(interfaceId)
            setState { copy(isProcessing = false) }
            if (success) {
                sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Released Interface $interfaceId successfully"))
            } else {
                sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Failed to release Interface $interfaceId"))
            }
        }
    }

    /**
     * Executes a raw Bulk OUT packet write operation.
     */
    fun writeRawBulk(endpointAddress: Int, hexData: String) {
        val bytes = try {
            hexData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Malformed Hex payload"))
            return
        }

        setState { copy(isProcessing = true) }
        viewModelScope.launch {
            when (val result = communicationRepository.writeBulk(endpointAddress, bytes)) {
                is UsbTransferResult.Success -> {
                    setState {
                        copy(
                            isProcessing = false,
                            lastResult = "Bulk OUT: Sent ${result.bytesTransferred} bytes [HEX: $hexData]"
                        )
                    }
                }
                is UsbTransferResult.Failure -> {
                    setState { copy(isProcessing = false, lastResult = "Bulk OUT Failure: ${result.errorMessage}") }
                    sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Write failed: ${result.errorMessage}"))
                }
                is UsbTransferResult.Timeout -> {
                    setState { copy(isProcessing = false, lastResult = "Bulk OUT Timeout") }
                    sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Write timed out"))
                }
            }
        }
    }

    /**
     * Executes a raw Bulk IN packet read operation.
     */
    fun readRawBulk(endpointAddress: Int, bufferSize: Int) {
        setState { copy(isProcessing = true) }
        viewModelScope.launch {
            when (val result = communicationRepository.readBulk(endpointAddress, bufferSize)) {
                is UsbTransferResult.Success -> {
                    val hex = result.data.joinToString("") { String.format("%02X", it) }
                    setState {
                        copy(
                            isProcessing = false,
                            lastResult = "Bulk IN: Received ${result.bytesTransferred} bytes [HEX: $hex]"
                        )
                    }
                }
                is UsbTransferResult.Failure -> {
                    setState { copy(isProcessing = false, lastResult = "Bulk IN Failure: ${result.errorMessage}") }
                    sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Read failed: ${result.errorMessage}"))
                }
                is UsbTransferResult.Timeout -> {
                    setState { copy(isProcessing = false, lastResult = "Bulk IN Timeout") }
                    sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Read timed out"))
                }
            }
        }
    }

    /**
     * Dispatches transport-level recovery routine.
     */
    fun recoverConnection() {
        setState { copy(isProcessing = true) }
        viewModelScope.launch {
            val success = communicationRepository.recoverConnection()
            setState { copy(isProcessing = false) }
            if (success) {
                sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Transport interface reset successful"))
            } else {
                sendEvent(UsbCommunicationUiEvent.ShowSnackbar("Transport interface reset failed"))
            }
        }
    }
}
