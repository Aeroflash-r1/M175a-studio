package com.example.scanner.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Serializes [DimeMessage] objects into raw binary DIME streams.
 * Handles 4-byte padding alignments and big-endian formatting.
 */
object DimeSerializer {

    /**
     * Serializes a [DimeMessage] into a byte array.
     */
    fun serialize(message: DimeMessage): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        for (i in message.parts.indices) {
            val part = message.parts[i]
            val isFirst = i == 0
            val isLast = i == message.parts.size - 1

            val idBytes = part.id.toByteArray(Charsets.US_ASCII)
            val typeBytes = part.type.toByteArray(Charsets.US_ASCII)
            val dataBytes = part.data

            // 1. Header (12 bytes)
            // Byte 0: Version (5 bits, must be 1) | MB (1 bit) | ME (1 bit) | CF (1 bit)
            var byte0 = (1 shl 3) // Version 1
            if (isFirst) byte0 = byte0 or 0x04
            if (isLast) byte0 = byte0 or 0x02
            dos.writeByte(byte0)

            // Byte 1: Type Scheme (4 bits) | Reserved (4 bits)
            val byte1 = (part.typeScheme.value.toInt() shl 4) and 0xF0
            dos.writeByte(byte1)

            // Bytes 2-3: Options Length (16-bit, big-endian)
            dos.writeShort(0)

            // Bytes 4-5: ID Length (16-bit, big-endian)
            dos.writeShort(idBytes.size)

            // Bytes 6-7: Type Length (16-bit, big-endian)
            dos.writeShort(typeBytes.size)

            // Bytes 8-11: Data Length (32-bit, big-endian)
            dos.writeInt(dataBytes.size)

            // 2. Options (0 bytes, no padding needed)

            // 3. ID + Padding
            dos.write(idBytes)
            writePadding(dos, idBytes.size)

            // 4. Type + Padding
            dos.write(typeBytes)
            writePadding(dos, typeBytes.size)

            // 5. Data + Padding
            dos.write(dataBytes)
            writePadding(dos, dataBytes.size)
        }

        dos.flush()
        return bos.toByteArray()
    }

    private fun writePadding(dos: DataOutputStream, length: Int) {
        val padding = (4 - (length % 4)) % 4
        if (padding > 0) {
            dos.write(ByteArray(padding))
        }
    }
}
