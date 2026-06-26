package com.example.scanner.engine

import com.example.domain.repository.UsbCommunicationRepository
import com.example.scanner.protocol.ScannerProtocolRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central orchestrator and manager of the HP LaserJet M175a Scanner Subsystem.
 * Acts as the primary interface to configure simulation states and spawn active sessions.
 */
class ScannerEngine(
    private val usbCommRepository: UsbCommunicationRepository,
    private val protocolRepository: ScannerProtocolRepository,
    private val logger: ScannerEngineLogger
) {
    private val _isSimulationMode = MutableStateFlow(true) // Default to true for cloud-native streaming emulator
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    val stateMachine = ScannerStateMachine(logger)

    /**
     * Toggles the simulation mode of the scanner engine.
     */
    fun setSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        logger.logRecovery("ℹ️ [ENGINE] Simulation mode set to: $enabled")
    }

    /**
     * Spawns a fresh configured [ScannerSession].
     */
    fun createSession(): ScannerSession {
        return ScannerSession(
            usbCommRepository = usbCommRepository,
            protocolRepository = protocolRepository,
            logger = logger,
            isSimulation = _isSimulationMode.value
        )
    }

    /**
     * Spawns a fresh configured [ScannerWorkflow] utilizing the active session.
     */
    fun createWorkflow(session: ScannerSession): ScannerWorkflow {
        return ScannerWorkflow(
            session = session,
            protocolRepository = protocolRepository,
            stateMachine = stateMachine,
            logger = logger
        )
    }

    /**
     * Spawns a fresh configured [ScannerController] utilizing the active workflow.
     */
    fun createController(workflow: ScannerWorkflow): ScannerController {
        return ScannerController(
            workflow = workflow,
            stateMachine = stateMachine,
            logger = logger
        )
    }
}
