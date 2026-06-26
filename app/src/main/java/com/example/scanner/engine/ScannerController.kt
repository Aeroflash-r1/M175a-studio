package com.example.scanner.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Controller class coordinating the active scan jobs and triggering clean cancellations.
 */
class ScannerController(
    private val workflow: ScannerWorkflow,
    private val stateMachine: ScannerStateMachine,
    private val logger: ScannerEngineLogger
) {
    private var activeJob: Job? = null

    val state: StateFlow<ScannerState> = stateMachine.state
    val progress: StateFlow<Float> = workflow.progress
    val activeScannerJob: StateFlow<ScannerJob?> = workflow.activeJob

    /**
     * Starts a background scanner job within the provided coroutine scope.
     */
    fun startScan(
        scope: CoroutineScope,
        resolutionDpi: Int,
        colorMode: String,
        onResult: (ScannerResult) -> Unit
    ) {
        val currentState = stateMachine.state.value
        if (currentState != ScannerState.Idle && currentState !is ScannerState.Error && currentState != ScannerState.Disconnected) {
            logger.logWarning("⚠️ Scan execution already running. Rejecting scan request.")
            return
        }

        activeJob = scope.launch(Dispatchers.IO) {
            val result = workflow.execute(resolutionDpi, colorMode)
            onResult(result)
        }
    }

    /**
     * Gracefully cancels any ongoing active scanner job.
     */
    fun cancelScan() {
        if (activeJob != null && activeJob?.isActive == true) {
            logger.logRecovery("🛑 User requested scan cancellation. Cancelling active coroutine...")
            activeJob?.cancel()
            stateMachine.transitionTo(ScannerState.Cancelled)
            logger.logRecovery("🛑 Scan job cancelled successfully.")
        } else {
            logger.logWarning("⚠️ Cancel requested, but no active job is currently running.")
        }
    }
}
