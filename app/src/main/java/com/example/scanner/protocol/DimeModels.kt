package com.example.scanner.protocol

/**
 * Type scheme for DIME payloads (usually MIME for attachments and SOAP XML).
 */
enum class DimeTypeScheme(val value: Byte) {
    NONE(0x00),
    MIME(0x01),
    URI(0x02),
    UNKNOWN(0x03);

    companion object {
        fun fromByte(byte: Byte): DimeTypeScheme {
            val schemeValue = (byte.toInt() shr 4) and 0x0F
            return values().firstOrNull { it.value == schemeValue.toByte() } ?: UNKNOWN
        }
    }
}

/**
 * Represents an individual DIME record (part) containing binary data, ID, MIME type, and state flags.
 */
data class DimePart(
    val id: String,
    val type: String,
    val data: ByteArray,
    val typeScheme: DimeTypeScheme = DimeTypeScheme.MIME,
    val isFirst: Boolean = false,
    val isLast: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DimePart
        if (id != other.id) return false
        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        if (typeScheme != other.typeScheme) return false
        if (isFirst != other.isFirst) return false
        if (isLast != other.isLast) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + typeScheme.hashCode()
        result = 31 * result + isFirst.hashCode()
        result = 31 * result + isLast.hashCode()
        return result
    }
}

/**
 * Represents a complete DIME message containing multiple parts.
 */
data class DimeMessage(
    val parts: List<DimePart>
) {
    /**
     * Helper to find a part by its unique Content-ID or name.
     */
    fun findPartById(id: String): DimePart? {
        return parts.firstOrNull { it.id == id }
    }

    /**
     * Helper to retrieve all image parts (usually image/jpeg).
     */
    fun getImageParts(): List<DimePart> {
        return parts.filter { it.type.startsWith("image/") }
    }

    /**
     * Helper to get the primary SOAP XML part.
     */
    fun getSoapPart(): DimePart? {
        return parts.firstOrNull { it.type.contains("soap") || it.type.contains("xml") }
    }
}

/**
 * Exception thrown when DIME message parsing or serialization violates the protocol standards.
 */
class DimeException(message: String, cause: Throwable? = null) : Exception(message, cause)
