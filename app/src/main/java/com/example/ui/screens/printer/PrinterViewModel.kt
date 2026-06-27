package com.example.ui.screens.printer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.repository.PrinterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Printer screen.
 */
class PrinterViewModel(
    private val printerRepository: PrinterRepository
) : ViewModel() {

    private val _printerIdentity = MutableStateFlow<String?>(null)
    val printerIdentity: StateFlow<String?> = _printerIdentity.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    /**
     * Runs a communication test by fetching the printer's identity string.
     */
    fun runCommTest() {
        viewModelScope.launch {
            _isTesting.value = true
            _printerIdentity.value = "Testing communication..."
            val identity = printerRepository.getPrinterIdentity()
            _printerIdentity.value = identity
            _isTesting.value = false
        }
    }
}
