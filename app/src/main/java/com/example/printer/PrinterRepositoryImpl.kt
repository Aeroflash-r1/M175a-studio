package com.example.printer

import com.example.domain.repository.PrinterRepository
import com.example.printer.protocol.PrinterProtocolRepository

/**
 * Concrete implementation of [PrinterRepository] for the HP LaserJet M175a.
 */
class PrinterRepositoryImpl(
    private val protocolRepository: PrinterProtocolRepository
) : PrinterRepository {
    
    override suspend fun getPrinterIdentity(): String {
        return protocolRepository.getPrinterId()
    }
}
