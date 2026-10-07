package com.example.nfcfileproto

import android.content.ContentValues
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * NfcReceiverApduService:
 * Implements Android's Host-based Card Emulation (HCE).
 *
 * When this device (Phone B) is set to Receive mode and held near an NFC reader (Phone A),
 * Android routes incoming APDUs targeting our AID ("F0010203040506")
 * to this service via [processCommandApdu].
 *
 * This service handles:
 * 1. AID SELECT command (returns 0x90 0x00 OK)
 * 2. INS_START_TRANSFER (extracts metadata: fileName, totalChunks, fileSize)
 * 3. INS_SEND_CHUNK (accumulates binary chunks and returns 0x90 0x00 ACK)
 * 4. INS_COMPLETE_TRANSFER (writes accumulated file to storage, copies to Downloads, and notifies UI)
 */
class NfcReceiverApduService : HostApduService() {

    companion object {
        private const val TAG = "NfcReceiverHce"

        // State observable by the UI
        data class ReceiverState(
            val status: String = "Ready to receive via NFC",
            val currentFileName: String? = null,
            val currentChunk: Int = 0,
            val totalChunks: Int = 0,
            val fileSize: Int = 0,
            val isTransferActive: Boolean = false,
            val savedFilePath: String? = null,
            val savedFile: File? = null,
            val mimeType: String? = null,
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
                status = "NFC Connected! Starting file transfer...",
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
                        fileSize = metadata.fileSize,
                        mimeType = getMimeType(metadata.fileName),
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
                        status = "Receiving chunk ${chunk.chunkIndex + 1}/$totalExpectedChunks (${NfcProtocol.formatFileSize(fileBuffer.size().toLong())})",
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
                    val mime = getMimeType(savedFile.name)
                    _stateFlow.value = ReceiverState(
                        status = "✅ File Received: ${savedFile.name} (${NfcProtocol.formatFileSize(savedFile.length())})",
                        currentFileName = savedFile.name,
                        currentChunk = totalExpectedChunks,
                        totalChunks = totalExpectedChunks,
                        fileSize = savedFile.length().toInt(),
                        isTransferActive = false,
                        savedFilePath = savedFile.absolutePath,
                        savedFile = savedFile,
                        mimeType = mime
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
            val bytes = fileBuffer.toByteArray()
            FileOutputStream(targetFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            Log.d(TAG, "File written successfully to ${targetFile.absolutePath}")

            // Also copy to public Downloads so user can find it outside the app
            saveToPublicDownloads(fileName, bytes)

            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file to disk", e)
            null
        }
    }

    private fun saveToPublicDownloads(fileName: String, bytes: ByteArray) {
        try {
            val mime = getMimeType(fileName)
            val resolver = applicationContext.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } else {
                resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            }

            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(targetUri, contentValues, null, null)
                }
                Log.d(TAG, "File copied to public Downloads: $targetUri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice: could not copy to public Downloads (${e.message}), file remains safely in app storage")
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    /**
     * Called when NFC connection is de-selected or link lost.
     */
    override fun onDeactivated(reason: Int) {
        val reasonStr = if (reason == DEACTIVATION_LINK_LOSS) "Link Loss (Phone moved away)" else "Deselected"
        Log.d(TAG, "HCE Deactivated: $reasonStr")
        if (_stateFlow.value.isTransferActive && _stateFlow.value.currentChunk < _stateFlow.value.totalChunks) {
            _stateFlow.value = _stateFlow.value.copy(
                status = "⚠️ Connection interrupted. Hold phones steady and re-tap.",
                isTransferActive = false,
                hasError = true
            )
        }
    }
}
