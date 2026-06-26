package com.example.core.usb.transport

/**
 * Represents the outcome of a USB transfer operation.
 */
sealed interface UsbTransferResult {
    /**
     * Successful USB transfer.
     */
    data class Success(
        val bytesTransferred: Int,
        val data: ByteArray,
        val durationMs: Long
    ) : UsbTransferResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            if (bytesTransferred != other.bytesTransferred) return false
            if (!data.contentEquals(other.data)) return false
            if (durationMs != other.durationMs) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytesTransferred
            result = 31 * result + data.contentHashCode()
            result = 31 * result + durationMs.hashCode()
            return result
        }
    }

    /**
     * USB transfer failed due to an error.
     */
    data class Failure(
        val errorMessage: String,
        val exception: Throwable? = null,
        val durationMs: Long
    ) : UsbTransferResult

    /**
     * USB transfer timed out.
     */
    data class Timeout(
        val durationMs: Long
    ) : UsbTransferResult
}

/**
 * Represents a generic USB transfer request.
 */
sealed interface UsbTransferRequest {
    val timeoutMs: Int

    /**
     * Bulk transfer request (IN or OUT).
     */
    data class Bulk(
        val endpointAddress: Int,
        val data: ByteArray? = null,
        val length: Int,
        override val timeoutMs: Int = 5000
    ) : UsbTransferRequest {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Bulk
            if (endpointAddress != other.endpointAddress) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            if (length != other.length) return false
            if (timeoutMs != other.timeoutMs) return false
            return true
        }

        override fun hashCode(): Int {
            var result = endpointAddress
            result = 31 * result + (data?.contentHashCode() ?: 0)
            result = 31 * result + length
            result = 31 * result + timeoutMs
            return result
        }
    }

    /**
     * Control transfer request (IN or OUT).
     */
    data class Control(
        val requestType: Int,
        val request: Int,
        val value: Int,
        val index: Int,
        val data: ByteArray? = null,
        val length: Int,
        override val timeoutMs: Int = 5000
    ) : UsbTransferRequest {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Control
            if (requestType != other.requestType) return false
            if (request != other.request) return false
            if (value != other.value) return false
            if (index != other.index) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            if (length != other.length) return false
            if (timeoutMs != other.timeoutMs) return false
            return true
        }

        override fun hashCode(): Int {
            var result = requestType
            result = 31 * result + request
            result = 31 * result + value
            result = 31 * result + index
            result = 31 * result + (data?.contentHashCode() ?: 0)
            result = 31 * result + length
            result = 31 * result + timeoutMs
            return result
        }
    }
}

/**
 * Represents a high-level response of a generic USB transfer operation.
 */
sealed interface UsbTransferResponse {
    /**
     * Transfer succeeded.
     */
    data class Success(val data: ByteArray) : UsbTransferResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    /**
     * Transfer failed.
     */
    data class Failure(val error: String) : UsbTransferResponse
}

/**
 * Represents the transport-level operational statistics.
 */
data class UsbTransportStats(
    val isCommunicationReady: Boolean = false,
    val isConnectionOpen: Boolean = false,
    val claimedInterfaces: List<Int> = emptyList(),
    val readyEndpoints: List<Int> = emptyList(),
    val transferStatus: String = "Idle",
    val lastTransferDetails: String = "None",
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val connectionDurationSec: Long = 0,
    val transportHealth: String = "Normal"
)
