package com.example.nfcfileproto

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * NfcReceiverApduService:
 * Implements Android's Host-based Card Emulation (HCE).
 *
 * When this device (Phone B) is held near an NFC reader (Phone A),
 * Android routes incoming APDUs targeting our AID ("F0010203040506")
 * to this service via [processCommandApdu].
 *
 * This service handles:
 * 1. AID SELECT command (returns 0x90 0x00 OK)
 * 2. INS_START_TRANSFER (extracts metadata: fileName, totalChunks, fileSize)
 * 3. INS_SEND_CHUNK (accumulates binary chunks and returns 0x90 0x00 ACK)
 * 4. INS_COMPLETE_TRANSFER (writes accumulated file to Downloads and notifies UI)
 */
class NfcReceiverApduService : HostApduService() {

    companion object {
        private const val TAG = "NfcReceiverHce"

        // State observable by the UI
        data class ReceiverState(
            val status: String = "Idle (Ready to receive via NFC)",
            val currentFileName: String? = null,
            val currentChunk: Int = 0,
            val totalChunks: Int = 0,
            val isTransferActive: Boolean = false,
            val savedFilePath: String? = null,
            val hasError: Boolean = false
        )

        private val _stateFlow = MutableStateFlow(ReceiverState())
        val stateFlow = _stateFlow.asStateFlow()

        fun resetState() {
            _stateFlow.value = ReceiverState()
        }
    }

    // In-memory buffer to assemble file chunks during transfer
    private var activeFileName: String? = null
    private var totalExpectedChunks: Int = 0
    private var totalFileSize: Int = 0
    private var receivedChunkCount: Int = 0
    private var fileBuffer = ByteArrayOutputStream()

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            Log.e(TAG, "Empty or invalid APDU received")
            return NfcProtocol.STATUS_FAILED
        }

        val cla = commandApdu[0]
        val ins = commandApdu[1]

        // 1. SELECT AID Command (Standard ISO/IEC 7816-4)
        if (ins == NfcProtocol.INS_SELECT) {
            Log.d(TAG, "SELECT AID command received. Acknowledging connection.")
            _stateFlow.value = ReceiverState(
                status = "NFC Connected! Waiting for transfer...",
                isTransferActive = true
            )
            return NfcProtocol.STATUS_SUCCESS
        }

        // Extract Data payload from command APDU if present
        // Format: [CLA][INS][P1][P2][Lc][...Data...]
        val lc = if (commandApdu.size > 4) (commandApdu[4].toInt() and 0xFF) else 0
        val data = if (lc > 0 && commandApdu.size >= 5 + lc) {
            commandApdu.copyOfRange(5, 5 + lc)
        } else {
            ByteArray(0)
        }

        return when (ins) {
            // 2. START_TRANSFER: Initialize metadata and clear buffer
            NfcProtocol.INS_START_TRANSFER -> {
                val metadata = NfcProtocol.parseStartTransferData(data)
                if (metadata != null) {
                    activeFileName = metadata.fileName
                    totalExpectedChunks = metadata.totalChunks
                    totalFileSize = metadata.fileSize
                    receivedChunkCount = 0
                    fileBuffer.reset()

                    Log.d(TAG, "Starting transfer: ${metadata.fileName}, ${metadata.totalChunks} chunks, ${metadata.fileSize} bytes")
                    _stateFlow.value = ReceiverState(
                        status = "Receiving: ${metadata.fileName}",
                        currentFileName = metadata.fileName,
                        currentChunk = 0,
                        totalChunks = metadata.totalChunks,
                        isTransferActive = true
                    )
                    NfcProtocol.STATUS_SUCCESS
                } else {
                    Log.e(TAG, "Failed to parse START_TRANSFER metadata")
                    NfcProtocol.STATUS_FAILED
                }
            }

            // 3. SEND_CHUNK: Append chunk to buffer and acknowledge
            NfcProtocol.INS_SEND_CHUNK -> {
                val chunk = NfcProtocol.parseSendChunkData(data)
                if (chunk != null) {
                    fileBuffer.write(chunk.chunkData)
                    receivedChunkCount++

                    // Update UI state with current progress
                    _stateFlow.value = _stateFlow.value.copy(
                        status = "Receiving chunk ${chunk.chunkIndex + 1}/$totalExpectedChunks",
                        currentChunk = chunk.chunkIndex + 1
                    )
                    // Return ACK (0x90 0x00) so sender can proceed to next chunk
                    NfcProtocol.STATUS_SUCCESS
                } else {
                    Log.e(TAG, "Failed to parse SEND_CHUNK data")
                    NfcProtocol.STATUS_FAILED
                }
            }

            // 4. COMPLETE_TRANSFER: Save assembled file to disk
            NfcProtocol.INS_COMPLETE_TRANSFER -> {
                Log.d(TAG, "COMPLETE_TRANSFER received. Saving file...")
                val savedFile = saveFileToDisk()
                if (savedFile != null) {
                    _stateFlow.value = ReceiverState(
                        status = "✅ File Saved: ${savedFile.name} (${savedFile.length()} bytes)",
                        currentFileName = savedFile.name,
                        currentChunk = totalExpectedChunks,
                        totalChunks = totalExpectedChunks,
                        isTransferActive = false,
                        savedFilePath = savedFile.absolutePath
                    )
                    NfcProtocol.STATUS_SUCCESS
                } else {
                    _stateFlow.value = ReceiverState(
                        status = "❌ Failed to save file to storage",
                        hasError = true
                    )
                    NfcProtocol.STATUS_FAILED
                }
            }

            else -> {
                Log.w(TAG, "Unknown APDU instruction: $ins")
                NfcProtocol.STATUS_FAILED
            }
        }
    }

    /**
     * Saves accumulated bytes to the device's public Downloads directory.
     */
    private fun saveFileToDisk(): File? {
        return try {
            val fileName = activeFileName ?: "nfc_file_${System.currentTimeMillis()}"
            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(filesDir, "received")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val targetFile = File(downloadsDir, fileName)
            FileOutputStream(targetFile).use { fos ->
                fos.write(fileBuffer.toByteArray())
                fos.flush()
            }
            Log.d(TAG, "File written successfully to ${targetFile.absolutePath}")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file to disk", e)
            null
        }
    }

    /**
     * Called when NFC connection is de-selected or link lost.
     */
    override fun onDeactivated(reason: Int) {
        val reasonStr = if (reason == DEACTIVATION_LINK_LOSS) "Link Loss (Phone moved away)" else "Deselected"
        Log.d(TAG, "HCE Deactivated: $reasonStr")
        if (_stateFlow.value.isTransferActive && _stateFlow.value.currentChunk < _stateFlow.value.totalChunks) {
            _stateFlow.value = _stateFlow.value.copy(
                status = "⚠️ Connection lost ($reasonStr). Please re-tap.",
                isTransferActive = false,
                hasError = true
            )
        }
    }
}
