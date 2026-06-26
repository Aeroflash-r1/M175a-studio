package com.example.core.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base view model containing common logic for state management and event emission.
 */
abstract class BaseViewModel<S : UiState, E : UiEvent> : ViewModel() {

    private val initialState: S by lazy { createInitialState() }

    private val _uiState: MutableStateFlow<S> = MutableStateFlow(initialState)
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    private val _uiEvent: MutableSharedFlow<E> = MutableSharedFlow()
    val uiEvent: SharedFlow<E> = _uiEvent.asSharedFlow()

    /**
     * Create the initial state for this view model.
     */
    protected abstract fun createInitialState(): S

    /**
     * Update the current UI state.
     */
    protected fun setState(reduce: S.() -> S) {
        val newState = currentState.reduce()
        _uiState.value = newState
    }

    /**
     * Get the current UI state.
     */
    protected val currentState: S
        get() = _uiState.value

    /**
     * Emit a single-shot event to the UI.
     */
    protected fun sendEvent(event: E) {
        viewModelScope.launch {
            _uiEvent.emit(event)
        }
    }
}
