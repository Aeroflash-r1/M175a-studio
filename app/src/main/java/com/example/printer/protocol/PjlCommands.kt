package com.example.printer.protocol

/**
 * Constants and utility functions for constructing PJL (Printer Job Language) commands
 * for the HP LaserJet 100 color MFP M175a.
 */
object PjlCommands {
    const val UEL = "\u001B%-12345X"
    const val CRLF = "\r\n"

    /**
     * Creates a standard PJL header that switches the printer into PJL mode.
     */
    fun createHeader(): ByteArray {
        return (UEL + "@PJL" + CRLF).toByteArray()
    }

    /**
     * Creates a PJL command to request the printer's identity/model string.
     */
    fun createInfoIdCommand(): ByteArray {
        return (UEL + "@PJL INFO ID" + CRLF + UEL).toByteArray()
    }

    /**
     * Creates a PJL command to request the current printer status.
     */
    fun createInfoStatusCommand(): ByteArray {
        return (UEL + "@PJL INFO STATUS" + CRLF + UEL).toByteArray()
    }

    /**
     * Universal Exit Language (UEL) command to terminate PJL sessions.
     */
    fun createExitCommand(): ByteArray {
        return UEL.toByteArray()
    }
}
