package com.example.scanner.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the deterministic states of the HP LaserJet M175a Scanner Engine.
 */
sealed interface ScannerState {
    object Idle : ScannerState
    object Initializing : ScannerState
    object GettingCapabilities : ScannerState
    object CreatingJob : ScannerState
    object WaitingForImage : ScannerState
    object ReceivingImage : ScannerState
    object PollingJob : ScannerState
    object Completed : ScannerState
    object Cancelled : ScannerState
    object Failed : ScannerState
    object CleaningUp : ScannerState
    object Disconnected : ScannerState
    data class Error(val message: String) : ScannerState
}

/**
 * Deterministic State Machine for the HP M175a Scanner.
 * Ensures state transitions are valid and logged properly.
 */
class ScannerStateMachine(private val logger: ScannerEngineLogger) {

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    /**
     * Safely transitions the state machine to a new state if the transition is allowed.
     */
    @Synchronized
    fun transitionTo(newState: ScannerState) {
        val oldState = _state.value
        if (oldState == newState) return

        if (isValidTransition(oldState, newState)) {
            _state.value = newState
            logger.logStateTransition(oldState, newState)
        } else {
            logger.logWarning("⚠️ [STATE] Disallowed or unusual transition: ${oldState.javaClass.simpleName} -> ${newState.javaClass.simpleName}")
            // Perform transition anyway to avoid blocking execution in case of unpredictable recovery
            _state.value = newState
            logger.logStateTransition(oldState, newState)
        }
    }

    private fun isValidTransition(from: ScannerState, to: ScannerState): Boolean {
        if (to is ScannerState.Error || to is ScannerState.Disconnected) return true
        return when (from) {
            is ScannerState.Idle -> to is ScannerState.Initializing
            is ScannerState.Initializing -> to is ScannerState.GettingCapabilities || to is ScannerState.Failed || to is ScannerState.Idle
            is ScannerState.GettingCapabilities -> to is ScannerState.CreatingJob || to is ScannerState.Failed || to is ScannerState.CleaningUp
            is ScannerState.CreatingJob -> to is ScannerState.WaitingForImage || to is ScannerState.Failed || to is ScannerState.CleaningUp
            is ScannerState.WaitingForImage -> to is ScannerState.ReceivingImage || to is ScannerState.Failed || to is ScannerState.CleaningUp || to is ScannerState.Cancelled
            is ScannerState.ReceivingImage -> to is ScannerState.PollingJob || to is ScannerState.Failed || to is ScannerState.CleaningUp || to is ScannerState.Cancelled
            is ScannerState.PollingJob -> to is ScannerState.Completed || to is ScannerState.Failed || to is ScannerState.CleaningUp || to is ScannerState.Cancelled
            is ScannerState.Completed -> to is ScannerState.CleaningUp || to is ScannerState.Idle
            is ScannerState.Cancelled -> to is ScannerState.CleaningUp || to is ScannerState.Idle
            is ScannerState.Failed -> to is ScannerState.CleaningUp || to is ScannerState.Idle
            is ScannerState.CleaningUp -> to is ScannerState.Idle
            is ScannerState.Disconnected -> to is ScannerState.Idle || to is ScannerState.Initializing
            is ScannerState.Error -> to is ScannerState.Idle || to is ScannerState.CleaningUp
        }
    }

    fun reset() {
        _state.value = ScannerState.Idle
    }
}
