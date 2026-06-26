package com.example.core.usb.transport

import android.hardware.usb.UsbInterface

/**
 * Thread-safe manager responsible for claiming and releasing USB interfaces.
 */
class UsbInterfaceManager(
    private val usbConnection: UsbConnection,
    private val logger: UsbPacketLogger
) {
    private val claimedInterfaces = mutableSetOf<Int>()

    /**
     * Claims the specified USB interface.
     */
    @Synchronized
    fun claimInterface(interfaceId: Int, force: Boolean = true): Boolean {
        if (claimedInterfaces.contains(interfaceId)) {
            return true
        }
        val usbInterface = getInterfaceById(interfaceId) ?: return false
        val success = usbConnection.rawConnection.claimInterface(usbInterface, force)
        if (success) {
            claimedInterfaces.add(interfaceId)
            logger.logInterfaceClaimed(interfaceId)
        } else {
            logger.logTransferFailure("CLAIM_INTERFACE", interfaceId, "Android claimInterface returned false", 0)
        }
        return success
    }

    /**
     * Releases the specified USB interface.
     */
    @Synchronized
    fun releaseInterface(interfaceId: Int): Boolean {
        if (!claimedInterfaces.contains(interfaceId)) {
            return true
        }
        val usbInterface = getInterfaceById(interfaceId) ?: return false
        val success = usbConnection.rawConnection.releaseInterface(usbInterface)
        if (success) {
            claimedInterfaces.remove(interfaceId)
            logger.logInterfaceReleased(interfaceId)
        } else {
            logger.logTransferFailure("RELEASE_INTERFACE", interfaceId, "Android releaseInterface returned false", 0)
        }
        return success
    }

    /**
     * Checks if the interface is currently claimed.
     */
    @Synchronized
    fun isInterfaceClaimed(interfaceId: Int): Boolean {
        return claimedInterfaces.contains(interfaceId)
    }

    /**
     * Returns a list of all currently claimed interface IDs.
     */
    @Synchronized
    fun getClaimedInterfaces(): List<Int> = claimedInterfaces.toList()

    /**
     * Releases all claimed interfaces safely.
     */
    @Synchronized
    fun releaseAll() {
        val targets = claimedInterfaces.toList()
        for (id in targets) {
            releaseInterface(id)
        }
    }

    private fun getInterfaceById(interfaceId: Int): UsbInterface? {
        val device = usbConnection.device
        for (i in 0 until device.interfaceCount) {
            val interf = device.getInterface(i)
            if (interf.id == interfaceId) {
                return interf
            }
        }
        return null
    }
}
