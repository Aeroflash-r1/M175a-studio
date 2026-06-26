package com.example.scanner.protocol

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Parses raw binary DIME streams into structured [DimeMessage] objects.
 * Performs strict validation on format versions, offsets, and flags.
 */
object DimeParser {

    /**
     * Parses a byte array into a [DimeMessage].
     * Throws [DimeException] on parsing error or strict check failure.
     */
    fun parse(bytes: ByteArray): DimeMessage {
        val bis = ByteArrayInputStream(bytes)
        val dis = DataInputStream(bis)
        val parts = mutableListOf<DimePart>()

        var index = 0
        val totalLength = bytes.size

        try {
            while (index < totalLength) {
                // Ensure we can read the 12-byte header
                if (totalLength - index < 12) {
                    throw DimeException("Malformed stream: expected 12-byte header, only ${totalLength - index} bytes left")
                }

                // 1. Parse Header
                val byte0 = dis.readUnsignedByte()
                val byte1 = dis.readUnsignedByte()
                val optionsLength = dis.readUnsignedShort()
                val idLength = dis.readUnsignedShort()
                val typeLength = dis.readUnsignedShort()
                
                // Read 32-bit big-endian signed or unsigned length
                val dataLengthLong = dis.readInt().toLong() and 0xFFFFFFFFL
                if (dataLengthLong > Int.MAX_VALUE) {
                    throw DimeException("Data length $dataLengthLong exceeds maximum supported integer size")
                }
                val dataLength = dataLengthLong.toInt()

                index += 12

                // Verify version (must be 1)
                val version = (byte0 ushr 3) and 0x1F
                if (version != 1) {
                    throw DimeException("Unsupported DIME version: $version (expected 1)")
                }

                val isFirst = (byte0 and 0x04) != 0
                val isLast = (byte0 and 0x02) != 0

                val typeScheme = DimeTypeScheme.fromByte(byte1.toByte())

                // Check remaining size
                val optPadding = (4 - (optionsLength % 4)) % 4
                val idPadding = (4 - (idLength % 4)) % 4
                val typePadding = (4 - (typeLength % 4)) % 4
                val dataPadding = (4 - (dataLength % 4)) % 4

                val expectedRecordBytes = optionsLength + optPadding +
                        idLength + idPadding +
                        typeLength + typePadding +
                        dataLength + dataPadding

                if (totalLength - index < expectedRecordBytes) {
                    throw DimeException("Malformed stream: expected $expectedRecordBytes bytes for fields, only ${totalLength - index} left")
                }

                // Read Options (and skip padding)
                if (optionsLength > 0) {
                    dis.skipBytes(optionsLength)
                }
                dis.skipBytes(optPadding)
                index += optionsLength + optPadding

                // Read ID
                val idBytes = ByteArray(idLength)
                if (idLength > 0) {
                    dis.readFully(idBytes)
                }
                val id = String(idBytes, Charsets.US_ASCII)
                dis.skipBytes(idPadding)
                index += idLength + idPadding

                // Read Type
                val typeBytes = ByteArray(typeLength)
                if (typeLength > 0) {
                    dis.readFully(typeBytes)
                }
                val type = String(typeBytes, Charsets.US_ASCII)
                dis.skipBytes(typePadding)
                index += typeLength + typePadding

                // Read Data
                val dataBytes = ByteArray(dataLength)
                if (dataLength > 0) {
                    dis.readFully(dataBytes)
                }
                dis.skipBytes(dataPadding)
                index += dataLength + dataPadding

                parts.add(
                    DimePart(
                        id = id,
                        type = type,
                        data = dataBytes,
                        typeScheme = typeScheme,
                        isFirst = isFirst,
                        isLast = isLast
                    )
                )

                if (isLast) {
                    break
                }
            }
        } catch (e: DimeException) {
            throw e
        } catch (e: Exception) {
            throw DimeException("DIME binary stream parse failure", e)
        }

        return DimeMessage(parts)
    }
}
