package com.example.nfcfileproto

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NfcSenderTransceiver:
 * Handles pure NFC ISO-DEP transceive operations on the Sender (Phone A).
 *
 * Implements the sender side of our APDU protocol:
 * 1. Connects to the detected [Tag] via [IsoDep].
 * 2. Transceives SELECT AID APDU to bind to Phone B's [NfcReceiverApduService].
 * 3. Transceives INS_START_TRANSFER with file metadata.
 * 4. Iteratively chunks the file into ~200-byte blocks and sends each via INS_SEND_CHUNK,
 *    waiting for 0x90 0x00 ACK after each chunk.
 * 5. Transceives INS_COMPLETE_TRANSFER to signal EOF and confirm file write.
 */
object NfcSenderTransceiver {

    private const val TAG = "NfcSenderTransceiver"

    data class SendProgress(
        val status: String,
        val currentChunk: Int,
        val totalChunks: Int,
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
                onProgress(SendProgress("❌ Tag does not support ISO-DEP (not an Android HCE device)", 0, 0, isError = true))
            }
            return@withContext
        }

        try {
            isoDep.connect()
            // Set timeout generous enough for HCE processing (5 seconds)
            isoDep.timeout = 5000

            Log.d(TAG, "IsoDep connected. Max transceive length: ${isoDep.maxTransceiveLength}")

            // Step 1: SELECT AID APDU
            withContext(Dispatchers.Main) {
                onProgress(SendProgress("Connecting to receiver...", 0, 0))
            }
            val selectCommand = NfcProtocol.buildSelectAidApdu()
            val selectResponse = isoDep.transceive(selectCommand)
            if (!NfcProtocol.isSuccess(selectResponse)) {
                withContext(Dispatchers.Main) {
                    onProgress(SendProgress("❌ Failed to connect to receiver service (SELECT failed)", 0, 0, isError = true))
                }
                return@withContext
            }

            // Calculate chunk details
            val chunkSize = NfcProtocol.CHUNK_DATA_SIZE
            val totalChunks = if (fileBytes.isEmpty()) 1 else (fileBytes.size + chunkSize - 1) / chunkSize

            // Step 2: START_TRANSFER APDU (Metadata)
            withContext(Dispatchers.Main) {
                onProgress(SendProgress("Starting transfer of $fileName...", 0, totalChunks))
            }
            val startCommand = NfcProtocol.buildStartTransferApdu(fileName, fileBytes.size, totalChunks)
            val startResponse = isoDep.transceive(startCommand)
            if (!NfcProtocol.isSuccess(startResponse)) {
                withContext(Dispatchers.Main) {
                    onProgress(SendProgress("❌ Receiver rejected transfer start", 0, totalChunks, isError = true))
                }
                return@withContext
            }

            // Step 3: Iterate and send each chunk
            for (chunkIndex in 0 until totalChunks) {
                val offset = chunkIndex * chunkSize
                val length = Math.min(chunkSize, fileBytes.size - offset)
                val chunkData = if (fileBytes.isEmpty()) ByteArray(0) else fileBytes.copyOfRange(offset, offset + length)

                val chunkCommand = NfcProtocol.buildSendChunkApdu(chunkIndex, chunkData)
                val chunkResponse = isoDep.transceive(chunkCommand)

                if (!NfcProtocol.isSuccess(chunkResponse)) {
                    withContext(Dispatchers.Main) {
                        onProgress(SendProgress("❌ Transfer failed at chunk ${chunkIndex + 1}/$totalChunks", chunkIndex, totalChunks, isError = true))
                    }
                    return@withContext
                }

                // Notify UI of progress
                withContext(Dispatchers.Main) {
                    onProgress(SendProgress("Sending chunk ${chunkIndex + 1}/$totalChunks", chunkIndex + 1, totalChunks))
                }
            }

            // Step 4: COMPLETE_TRANSFER APDU
            val completeCommand = NfcProtocol.buildCompleteTransferApdu()
            val completeResponse = isoDep.transceive(completeCommand)
            if (NfcProtocol.isSuccess(completeResponse)) {
                withContext(Dispatchers.Main) {
                    onProgress(SendProgress("✅ Transfer Complete! (${fileBytes.size} bytes sent)", totalChunks, totalChunks, isComplete = true))
                }
            } else {
                withContext(Dispatchers.Main) {
                    onProgress(SendProgress("❌ Receiver failed to finalize file", totalChunks, totalChunks, isError = true))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "NFC Transceive exception", e)
            withContext(Dispatchers.Main) {
                onProgress(SendProgress("❌ NFC Error: ${e.message ?: "Connection lost. Keep phones steady."}", 0, 0, isError = true))
            }
        } finally {
            try {
                if (isoDep.isConnected) isoDep.close()
            } catch (ignored: Exception) {}
        }
    }
}
