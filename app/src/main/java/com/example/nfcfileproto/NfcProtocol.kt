package com.example.nfcfileproto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * NfcProtocol: Defines the custom ISO-DEP APDU protocol constants and helper serializers
 * for peer-to-peer file transfer over pure NFC.
 *
 * APDU (Application Protocol Data Unit) Structure (ISO/IEC 7816-4):
 * [CLA (1 byte)] [INS (1 byte)] [P1 (1 byte)] [P2 (1 byte)] [Lc (1 byte)] [Data (Lc bytes)] [Le (optional)]
 *
 * Status Words (SW1 SW2):
 * - 0x90 0x00: Operation Successful (SW_OK)
 * - 0x6A 0x82: File / Resource Not Found
 * - 0x6F 0x00: General Error / Failure
 */
object NfcProtocol {

    // Custom 7-byte Application Identifier (AID) matching apdu_service.xml
    const val AID_HEX = "F0010203040506"
    val AID_BYTES: ByteArray = hexStringToByteArray(AID_HEX)

    // Standard ISO 7816-4 Instruction for Selecting an Application
    const val INS_SELECT: Byte = 0xA4.toByte()

    // Proprietary Instruction bytes for our file transfer protocol
    const val INS_START_TRANSFER: Byte = 0x01
    const val INS_SEND_CHUNK: Byte = 0x02
    const val INS_COMPLETE_TRANSFER: Byte = 0x03

    // Standard APDU Success and Error Response Status Words
    val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
    val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())

    /**
     * Chunk payload size (240 bytes).
     * Maximizes throughput while remaining safely within the universal 255-byte short APDU limit.
     * APDU structure: 4-byte header + 1-byte Lc + 4-byte index + 2-byte len + 240-byte data = 251 bytes <= 255.
     */
    const val CHUNK_DATA_SIZE = 240

    /**
     * Helper to verify if an APDU response ends with 0x90 0x00 (SUCCESS).
     */
    fun isSuccess(response: ByteArray?): Boolean {
        if (response == null || response.size < 2) return false
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        return (sw1 == 0x90.toByte() && sw2 == 0x00.toByte())
    }

    /**
     * Formats status words or raw bytes to readable hex string.
     */
    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return "null"
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    /**
     * Builds standard SELECT AID command APDU:
     * Header: CLA=0x00, INS=0xA4, P1=0x04, P2=0x00, Lc=AID length, Data=AID
     * Standard ISO 7816-4 Case 3 APDU (without Le by default, or with Le if specified).
     */
    fun buildSelectAidApdu(withLe: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00) // CLA: Standard class
        out.write(0xA4) // INS: SELECT FILE / APPLICATION
        out.write(0x04) // P1: Select by DF name (AID)
        out.write(0x00) // P2: First or only occurrence
        out.write(AID_BYTES.size) // Lc: Length of AID (7)
        out.write(AID_BYTES)      // Data: AID bytes
        if (withLe) {
            out.write(0x00)       // Le: Expected response length
        }
        return out.toByteArray()
    }

    /**
     * Builds START_TRANSFER command APDU:
     * Header: CLA=0x80 (proprietary), INS=0x01, P1=0x00, P2=0x00, Lc, Data
     * Data Payload layout:
     * - Total Chunks: 4 bytes (Int)
     * - File Size (bytes): 4 bytes (Int)
     * - File Name Length: 2 bytes (Short)
     * - File Name: UTF-8 encoded bytes (capped at 80 bytes)
     */
    fun buildStartTransferApdu(fileName: String, fileSize: Int, totalChunks: Int): ByteArray {
        val safeName = if (fileName.length > 80) fileName.take(80) else fileName
        val nameBytes = safeName.toByteArray(Charsets.UTF_8)
        val dataBuf = ByteBuffer.allocate(4 + 4 + 2 + nameBytes.size)
        dataBuf.putInt(totalChunks)
        dataBuf.putInt(fileSize)
        dataBuf.putShort(nameBytes.size.toShort())
        dataBuf.put(nameBytes)
        val data = dataBuf.array()

        val out = ByteArrayOutputStream()
        out.write(0x80) // Proprietary class
        out.write(INS_START_TRANSFER.toInt())
        out.write(0x00) // P1
        out.write(0x00) // P2
        out.write(data.size) // Lc
        out.write(data)
        return out.toByteArray()
    }

    /**
     * Parses the START_TRANSFER payload received by the HCE receiver.
     */
    data class TransferMetadata(
        val totalChunks: Int,
        val fileSize: Int,
        val fileName: String
    )

    fun parseStartTransferData(data: ByteArray): TransferMetadata? {
        return try {
            val buf = ByteBuffer.wrap(data)
            val totalChunks = buf.int
            val fileSize = buf.int
            val nameLen = buf.short.toInt()
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)
            TransferMetadata(totalChunks, fileSize, fileName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Builds SEND_CHUNK command APDU:
     * Header: CLA=0x80, INS=0x02, P1=0x00, P2=0x00, Lc, Data
     * Data Payload layout:
     * - Chunk Index: 4 bytes (Int)
     * - Chunk Data Length: 2 bytes (Short)
     * - Chunk Data: chunk bytes (up to CHUNK_DATA_SIZE)
     */
    fun buildSendChunkApdu(chunkIndex: Int, chunkData: ByteArray): ByteArray {
        val dataBuf = ByteBuffer.allocate(4 + 2 + chunkData.size)
        dataBuf.putInt(chunkIndex)
        dataBuf.putShort(chunkData.size.toShort())
        dataBuf.put(chunkData)
        val data = dataBuf.array()

        val out = ByteArrayOutputStream()
        out.write(0x80)
        out.write(INS_SEND_CHUNK.toInt())
        out.write(0x00)
        out.write(0x00)
        out.write(data.size)
        out.write(data)
        return out.toByteArray()
    }

    /**
     * Parses incoming SEND_CHUNK data on the receiver.
     */
    data class ChunkPayload(
        val chunkIndex: Int,
        val chunkData: ByteArray
    )

    fun parseSendChunkData(data: ByteArray): ChunkPayload? {
        return try {
            val buf = ByteBuffer.wrap(data)
            val chunkIndex = buf.int
            val chunkLen = buf.short.toInt()
            val chunkBytes = ByteArray(chunkLen)
            buf.get(chunkBytes)
            ChunkPayload(chunkIndex, chunkBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Builds COMPLETE_TRANSFER command APDU:
     * Header: CLA=0x80, INS=0x03, P1=0x00, P2=0x00, Lc=0
     */
    fun buildCompleteTransferApdu(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80)
        out.write(INS_COMPLETE_TRANSFER.toInt())
        out.write(0x00)
        out.write(0x00)
        out.write(0x00) // Lc: 0
        return out.toByteArray()
    }

    /**
     * Format byte count into human readable format (KB, MB).
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Helper to convert hex string into byte array.
     */
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
