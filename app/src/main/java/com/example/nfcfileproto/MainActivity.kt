package com.example.nfcfileproto

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nfcfileproto.ui.theme.NfcFileProtoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity:
 * The single-screen UI for the NFC File Transfer Prototype.
 *
 * Capabilities:
 * 1. Inspects NFC adapter state (Available, Enabled, or Disabled).
 * 2. Provides file picker to select a small file (< 500 KB recommended).
 * 3. Operates as Sender: enables [NfcAdapter.enableReaderMode] with [IsoDep] flags
 *    when a file is selected and user is ready to tap.
 * 4. Operates as Receiver: automatically listens via background [NfcReceiverApduService]
 *    and observes incoming transfers via StateFlow.
 * 5. Shows real-time transfer progress, status, and completion/error notices.
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null

    // Holds currently selected file bytes and name for ReaderCallback
    private var pendingFileName: String? = null
    private var pendingFileBytes: ByteArray? = null

    // State flow for Sender progress updates
    private val senderProgress = mutableStateOf<NfcSenderTransceiver.SendProgress?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            NfcFileProtoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NfcFileTransferScreen(
                        nfcAdapter = nfcAdapter,
                        senderProgress = senderProgress.value,
                        onFileSelected = { name, bytes ->
                            pendingFileName = name
                            pendingFileBytes = bytes
                            senderProgress.value = NfcSenderTransceiver.SendProgress(
                                status = "File ready. Bring phones back-to-back to transfer via NFC.",
                                currentChunk = 0,
                                totalChunks = (bytes.size + NfcProtocol.CHUNK_DATA_SIZE - 1) / NfcProtocol.CHUNK_DATA_SIZE
                            )
                        },
                        onClearFile = {
                            pendingFileName = null
                            pendingFileBytes = null
                            senderProgress.value = null
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    /**
     * Enables Reader Mode to detect another NFC device running Host Card Emulation.
     * Uses FLAG_READER_NFC_A and FLAG_READER_SKIP_NDEF_CHECK to quickly capture ISO-DEP tags.
     */
    private fun enableNfcReaderMode() {
        nfcAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                val extras = Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
                }
                adapter.enableReaderMode(this, this, flags, extras)
            }
        }
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Called by Android NFC subsystem on a background thread when an NFC Tag / HCE device is discovered.
     */
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        val fileName = pendingFileName
        val fileBytes = pendingFileBytes

        if (fileName != null && fileBytes != null) {
            // Sender transfer execution
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                NfcSenderTransceiver.transferFileOverNfc(
                    tag = tag,
                    fileName = fileName,
                    fileBytes = fileBytes
                ) { progress ->
                    senderProgress.value = progress
                }
            }
        }
    }
}

@Composable
fun NfcFileTransferScreen(
    nfcAdapter: NfcAdapter?,
    senderProgress: NfcSenderTransceiver.SendProgress?,
    onFileSelected: (name: String, bytes: ByteArray) -> Unit,
    onClearFile: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Observe receiver state from the HCE Service
    val receiverState by NfcReceiverApduService.stateFlow.collectAsState()

    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedFileSize by remember { mutableStateOf(0) }

    // File Picker Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val name = getDisplayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

                    if (bytes != null) {
                        if (bytes.size > 500 * 1024) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "File is ${bytes.size / 1024} KB. Warning: Files over 500 KB may be slow or fragile over pure NFC.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        withContext(Dispatchers.Main) {
                            selectedFileName = name
                            selectedFileSize = bytes.size
                            onFileSelected(name, bytes)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to load file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Text(
            text = "NFC File Transfer Prototype",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Pure NFC (ISO-DEP / HCE) • No Wi-Fi • No Bluetooth",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // NFC Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    nfcAdapter == null -> MaterialTheme.colorScheme.errorContainer
                    !nfcAdapter.isEnabled -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when {
                        nfcAdapter == null -> "⚠️ NFC is not supported on this device"
                        !nfcAdapter.isEnabled -> "⚠️ NFC is turned OFF. Please enable NFC in Settings."
                        else -> "✅ NFC is Active & Ready"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // SENDER SECTION (Phone A)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Phone A: Sender",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Select File")
                    }

                    if (selectedFileName != null) {
                        OutlinedButton(
                            onClick = {
                                selectedFileName = null
                                selectedFileSize = 0
                                onClearFile()
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedFileName != null) {
                    Text(
                        text = "Selected: $selectedFileName ($selectedFileSize bytes)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "No file selected. Click 'Select File' to begin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                if (senderProgress != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = senderProgress.status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            senderProgress.isComplete -> Color(0xFF2E7D32)
                            senderProgress.isError -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )

                    if (senderProgress.totalChunks > 0 && !senderProgress.isComplete && !senderProgress.isError) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { senderProgress.currentChunk.toFloat() / senderProgress.totalChunks },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(senderProgress.currentChunk.toFloat() / senderProgress.totalChunks * 100).toInt()}% completed",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // RECEIVER SECTION (Phone B)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phone B: Receiver",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { NfcReceiverApduService.resetState() }) {
                        Text("Reset")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Status: ${receiverState.status}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        receiverState.savedFilePath != null -> Color(0xFF2E7D32)
                        receiverState.hasError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (receiverState.totalChunks > 0 && receiverState.isTransferActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { receiverState.currentChunk.toFloat() / receiverState.totalChunks },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Chunk ${receiverState.currentChunk}/${receiverState.totalChunks}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }

                if (receiverState.savedFilePath != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Saved in Downloads folder:\n${receiverState.savedFilePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Research Instruction Footer
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.LightGray.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Text(
                text = "📌 Testing Tip: To transfer, select a file on Phone A, unlock both phones with NFC turned ON, and hold their NFC antennas firmly back-to-back until complete.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
                color = Color.DarkGray
            )
        }
    }
}

private fun getDisplayName(context: Context, uri: Uri): String {
    var name = "nfc_file_${System.currentTimeMillis()}"
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                val str = cursor.getString(index)
                if (!str.isNullOrBlank()) name = str
            }
        }
    } else {
        uri.path?.let { p ->
            val cut = p.lastIndexOf('/')
            if (cut != -1) name = p.substring(cut + 1)
        }
    }
    return name
}
