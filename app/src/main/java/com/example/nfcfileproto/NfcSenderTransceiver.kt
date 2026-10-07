package com.example.nfcfileproto

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NfcSenderTransceiver:
 * Handles pure NFC ISO-DEP transceive operations on the Sender (Phone A).
 *
 * Implements the sender side of our APDU protocol:
 * 1. Connects to the detected [Tag] via [IsoDep].
 * 2. Transceives SELECT AID APDU with retries to bind to Phone B's [NfcReceiverApduService].
 * 3. Transceives INS_START_TRANSFER with file metadata.
 * 4. Iteratively chunks the file into ~240-byte blocks and sends each via INS_SEND_CHUNK,
 *    with per-chunk retry to handle hand micro-movements.
 * 5. Transceives INS_COMPLETE_TRANSFER to signal EOF and confirm file save.
 */
object NfcSenderTransceiver {

    private const val TAG = "NfcSenderTransceiver"

    data class SendProgress(
        val status: String,
        val currentChunk: Int,
        val totalChunks: Int,
        val bytesSent: Int = 0,
        val totalBytes: Int = 0,
        val isComplete: Boolean = false,
        val isError: Boolean = false
    )

    /**
     * Executes the file transfer over IsoDep.
     *
     * @param tag The NFC Tag detected by NfcAdapter.ReaderCallback
     * @param fileName The display name of the file
     * @param fileBytes The raw byte content of the file
     * @param onProgress Callback invoked on each progress update
     */
    suspend fun transferFileOverNfc(
        tag: Tag,
        fileName: String,
        fileBytes: ByteArray,
        onProgress: (SendProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            withContext(Dispatchers.Main) {
                onProgress(SendProgress("❌ Detected device is not an ISO-DEP target (Ensure Receiver is in Receive mode)", 0, 0, isError = true))
            }
            return@withContext
        }

        try {
            isoDep.connect()
            // Set timeout generous enough for HCE background processing
            isoDep.timeout = 5000

            Log.d(TAG, "IsoDep connected. Max transceive length: ${isoDep.maxTransceiveLength}")

            // Stabilization pause for NFC RF field & remote HCE service binding
            delay(60)

            // Step 1: SELECT AID APDU with retry
            withContext(Dispatchers.Main) {
                onProgress(SendProgress("NFC detected! Connecting to receiver...", 0, 0, 0, fileBytes.size))
            }

            var selectSuccess = false
            var lastSelectStatusHex = "timeout"

            // Try standard Case 3 SELECT (without Le), and fallback to Case 4 (with Le)
            val selectAttempts = listOf(false, false, false, true, true)
            for ((index, withLe) in selectAttempts.withIndex()) {
                try {
                    val selectCommand = NfcProtocol.buildSelectAidApdu(withLe = withLe)
                    val selectResponse = isoDep.transceive(selectCommand)
                    if (NfcProtocol.isSuccess(selectResponse)) {
                        selectSuccess = true
                        Log.d(TAG, "SELECT AID succeeded on attempt ${index + 1} (withLe=$withLe)")
                        break
                    } else {
                        lastSelectStatusHex = NfcProtocol.bytesToHex(selectResponse)
                        Log.w(TAG, "SELECT attempt ${index + 1} returned: $lastSelectStatusHex")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SELECT attempt ${index + 1} threw: ${e.message}")
                    lastSelectStatusHex = e.message ?: "error"
                }
                delay(80)
            }

            if (!selectSuccess) {
                withContext(Dispatchers.Main) {
                    onProgress(
                        SendProgress(
                            "❌ Connection rejected ($lastSelectStatusHex). Make sure Receiver phone is unlocked and showing 'Receive' screen.",
                            0, 0, isError = true
                        )
                    )
                }
                return@withContext
            }

            // Calculate chunk details
            val chunkSize = NfcProtocol.CHUNK_DATA_SIZE
            val totalChunks = if (fileBytes.isEmpty()) 1 else (fileBytes.size + chunkSize - 1) / chunkSize

            // Step 2: START_TRANSFER APDU (Metadata)
            withContext(Dispatchers.Main) {
                onProgress(SendProgress("Starting transfer of $fileName...", 0, totalChunks, 0, fileBytes.size))
            }

            val startCommand = NfcProtocol.buildStartTransferApdu(fileName, fileBytes.size, totalChunks)
            var startSuccess = false
            for (attempt in 1..3) {
                try {
                    val startResponse = isoDep.transceive(startCommand)
                    if (NfcProtocol.isSuccess(startResponse)) {
                        startSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "START_TRANSFER attempt $attempt failed: ${e.message}")
                }
                delay(40)
            }

            if (!startSuccess) {
                withContext(Dispatchers.Main) {
                    onProgress(SendProgress("❌ Receiver rejected transfer start", 0, totalChunks, isError = true))
                }
                return@withContext
            }

            // Step 3: Iterate and send each chunk with per-chunk retry
            var totalBytesSent = 0
            for (chunkIndex in 0 until totalChunks) {
                val offset = chunkIndex * chunkSize
                val length = Math.min(chunkSize, fileBytes.size - offset)
                val chunkData = if (fileBytes.isEmpty()) ByteArray(0) else fileBytes.copyOfRange(offset, offset + length)

                val chunkCommand = NfcProtocol.buildSendChunkApdu(chunkIndex, chunkData)

                var chunkSuccess = false
                for (attempt in 1..4) {
                    try {
                        val chunkResponse = isoDep.transceive(chunkCommand)
                        if (NfcProtocol.isSuccess(chunkResponse)) {
                            chunkSuccess = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Chunk $chunkIndex attempt $attempt failed: ${e.message}")
                    }
                    delay(30)
                }

                if (!chunkSuccess) {
                    withContext(Dispatchers.Main) {
                        onProgress(
                            SendProgress(
                                "❌ Transfer lost at chunk ${chunkIndex + 1}/$totalChunks. Please re-tap and hold steady.",
                                chunkIndex, totalChunks, totalBytesSent, fileBytes.size, isError = true
                            )
                        )
                    }
                    return@withContext
                }

                totalBytesSent += length

                // Notify UI of progress
                withContext(Dispatchers.Main) {
                    onProgress(
                        SendProgress(
                            "Sending chunk ${chunkIndex + 1}/$totalChunks (${NfcProtocol.formatFileSize(totalBytesSent.toLong())} of ${NfcProtocol.formatFileSize(fileBytes.size.toLong())})",
                            chunkIndex + 1,
                            totalChunks,
                            totalBytesSent,
                            fileBytes.size
                        )
                    )
                }
            }

            // Step 4: COMPLETE_TRANSFER APDU with retry
            val completeCommand = NfcProtocol.buildCompleteTransferApdu()
            var completeSuccess = false
            for (attempt in 1..3) {
                try {
                    val completeResponse = isoDep.transceive(completeCommand)
                    if (NfcProtocol.isSuccess(completeResponse)) {
                        completeSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    delay(30)
                }
            }

            if (completeSuccess) {
                withContext(Dispatchers.Main) {
                    onProgress(
                        SendProgress(
                            "✅ Transfer Complete! (${NfcProtocol.formatFileSize(fileBytes.size.toLong())} sent)",
                            totalChunks,
                            totalChunks,
                            fileBytes.size,
                            fileBytes.size,
                            isComplete = true
                        )
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    onProgress(
                        SendProgress("❌ Receiver failed to finalize file save", totalChunks, totalChunks, isError = true)
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "NFC Transceive exception", e)
            withContext(Dispatchers.Main) {
                onProgress(
                    SendProgress(
                        "❌ NFC Error: ${e.message ?: "Connection lost. Keep phones aligned."}",
                        0, 0, isError = true
                    )
                )
            }
        } finally {
            try {
                if (isoDep.isConnected) isoDep.close()
            } catch (ignored: Exception) {}
        }
    }
}
