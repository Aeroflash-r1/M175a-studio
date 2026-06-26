package com.example.scanner.engine

import com.example.domain.repository.ScannerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production-ready repository implementing [ScannerRepository] coordinating the active
 * [ScannerEngine] workflows, state machines, and controllers.
 */
class ScannerEngineRepository(
    private val engine: ScannerEngine,
    private val logger: ScannerEngineLogger
) : ScannerRepository {

    private val mutex = Mutex()
    private var currentController: ScannerController? = null

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    override val state: Flow<ScannerState> = _state

    private val _progress = MutableStateFlow(0f)
    override val progress: Flow<Float> = _progress

    private val _activeJob = MutableStateFlow<ScannerJob?>(null)
    override val activeJob: Flow<ScannerJob?> = _activeJob

    override val isSimulationMode: Flow<Boolean> = engine.isSimulationMode

    /**
     * Spawns a controller and executes the scanner workflow under thread-safe locking.
     */
    override suspend fun startScan(resolutionDpi: Int, colorMode: String): ScannerResult = coroutineScope {
        mutex.withLock {
            val session = engine.createSession()
            val workflow = engine.createWorkflow(session)
            val controller = engine.createController(workflow)
            currentController = controller

            val stateJob = launch {
                controller.state.collect { _state.value = it }
            }
            val progressJob = launch {
                controller.progress.collect { _progress.value = it }
            }
            val activeJobCollector = launch {
                controller.activeScannerJob.collect { _activeJob.value = it }
            }

            var scanResult: ScannerResult = ScannerResult.Failure("Scan initialized but returned no result")

            try {
                val resultDeferred = CompletableDeferred<ScannerResult>()
                controller.startScan(this, resolutionDpi, colorMode) { res ->
                    resultDeferred.complete(res)
                }
                scanResult = resultDeferred.await()
            } catch (e: Exception) {
                logger.logError("ScannerEngineRepository scan exception: ${e.message}", e)
                scanResult = ScannerResult.Failure(e.message ?: "Scanning failed", e)
            } finally {
                stateJob.cancel()
                progressJob.cancel()
                activeJobCollector.cancel()
                currentController = null
            }

            scanResult
        }
    }

    /**
     * Cancels any active, running scan job.
     */
    override fun cancelScan() {
        currentController?.cancelScan()
    }

    /**
     * Sets whether the scanner engine should run in simulation mode.
     */
    override fun setSimulationMode(enabled: Boolean) {
        engine.setSimulationMode(enabled)
    }
}
