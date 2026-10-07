package com.example.nfcfileproto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.nfcfileproto.ui.theme.NfcFileProtoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class TransferTab {
    RECEIVE,
    SEND
}

/**
 * MainActivity:
 * Direct, peer-to-peer file transfer over pure NFC (ISO-DEP / HCE).
 *
 * Implements clean dual-role management:
 * - RECEIVE Mode: Disables reader mode to prevent RF collisions, registers HCE preferred service so Samsung/Android routes AIDs cleanly.
 * - SEND Mode: Enables ISO-DEP Reader Mode, chunks and streams file bytes with per-chunk retries.
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null

    // Current operational mode
    private val activeTab = mutableStateOf(TransferTab.RECEIVE)

    // Staged file for Sender
    private var stagedFileName = mutableStateOf<String?>(null)
    private var stagedFileBytes = mutableStateOf<ByteArray?>(null)
    private var stagedFileMime = mutableStateOf<String?>(null)
    private var stagedFileBitmap = mutableStateOf<Bitmap?>(null)
    private var optimizeImage = mutableStateOf(true)

    // Sender progress
    private val senderProgress = mutableStateOf<NfcSenderTransceiver.SendProgress?>(null)

    // Transfer job
    private var activeTransferJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Handle incoming Android Share Intent (e.g. sharing image/PDF from Gallery or Files)
        handleIncomingIntent(intent)

        setContent {
            NfcFileProtoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val receiverState by NfcReceiverApduService.stateFlow.collectAsState()

                    // Vibrate when file receive completes
                    LaunchedEffect(receiverState.savedFilePath) {
                        if (receiverState.savedFilePath != null) {
                            vibrateDevice(500)
                        }
                    }

                    NfcMainScreen(
                        nfcAdapter = nfcAdapter,
                        currentTab = activeTab.value,
                        onTabChange = { tab ->
                            activeTab.value = tab
                            applyNfcMode(tab)
                        },
                        stagedFileName = stagedFileName.value,
                        stagedFileBytes = stagedFileBytes.value,
                        stagedFileMime = stagedFileMime.value,
                        stagedFileBitmap = stagedFileBitmap.value,
                        optimizeImage = optimizeImage.value,
                        onToggleOptimize = { optimizeImage.value = it },
                        senderProgress = senderProgress.value,
                        receiverState = receiverState,
                        onFileSelected = { name, bytes, mime ->
                            stagedFileName.value = name
                            stagedFileMime.value = mime
                            val isImg = mime?.startsWith("image/") == true
                            if (isImg) {
                                stagedFileBitmap.value = try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                stagedFileBitmap.value = null
                            }
                            stagedFileBytes.value = bytes
                            activeTab.value = TransferTab.SEND
                            applyNfcMode(TransferTab.SEND)

                            val effectiveBytes = if (isImg && optimizeImage.value) {
                                compressImage(bytes)
                            } else {
                                bytes
                            }
                            senderProgress.value = NfcSenderTransceiver.SendProgress(
                                status = "Ready to send! Hold phones back-to-back with Receiver phone.",
                                currentChunk = 0,
                                totalChunks = (effectiveBytes.size + NfcProtocol.CHUNK_DATA_SIZE - 1) / NfcProtocol.CHUNK_DATA_SIZE,
                                totalBytes = effectiveBytes.size
                            )
                        },
                        onClearStagedFile = {
                            stagedFileName.value = null
                            stagedFileBytes.value = null
                            stagedFileMime.value = null
                            stagedFileBitmap.value = null
                            senderProgress.value = null
                        },
                        onResetReceiver = {
                            NfcReceiverApduService.resetState()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val name = getDisplayName(this@MainActivity, uri)
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        val mime = contentResolver.getType(uri) ?: getMimeTypeFromFileName(name)
                        if (bytes != null) {
                            withContext(Dispatchers.Main) {
                                stagedFileName.value = name
                                stagedFileMime.value = mime
                                stagedFileBytes.value = bytes
                                if (mime.startsWith("image/")) {
                                    stagedFileBitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                                activeTab.value = TransferTab.SEND
                                applyNfcMode(TransferTab.SEND)
                                senderProgress.value = NfcSenderTransceiver.SendProgress(
                                    status = "Ready to send! Hold phones back-to-back with Receiver.",
                                    currentChunk = 0,
                                    totalChunks = (bytes.size + NfcProtocol.CHUNK_DATA_SIZE - 1) / NfcProtocol.CHUNK_DATA_SIZE,
                                    totalBytes = bytes.size
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyNfcMode(activeTab.value)
    }

    override fun onPause() {
        super.onPause()
        disableAllNfcModes()
    }

    /**
     * Applies NFC configuration according to current tab:
     * - RECEIVE: Disable reader mode, enable HCE preferred service
     * - SEND: Unset HCE preferred service, enable Reader mode
     */
    private fun applyNfcMode(tab: TransferTab) {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        try {
            val cardEmulation = CardEmulation.getInstance(adapter)
            val serviceComponent = ComponentName(this, NfcReceiverApduService::class.java)

            if (tab == TransferTab.RECEIVE) {
                // Crucial: Disable reader polling so device is purely an HCE listen target
                adapter.disableReaderMode(this)
                // Crucial: Route our AID directly to our service, overriding Samsung/Google Wallet
                cardEmulation.setPreferredService(this, serviceComponent)
            } else {
                // Crucial: Unset preferred service so sender acts purely as an NFC Reader
                cardEmulation.unsetPreferredService(this)
                // Enable Reader Mode with ISO-DEP polling
                val flags = NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                val extras = Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
                }
                adapter.enableReaderMode(this, this, flags, extras)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disableAllNfcModes() {
        try {
            nfcAdapter?.disableReaderMode(this)
            nfcAdapter?.let {
                CardEmulation.getInstance(it).unsetPreferredService(this)
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Reader callback invoked when an HCE target tag is discovered.
     */
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null || activeTab.value != TransferTab.SEND) return

        val fileName = stagedFileName.value
        val rawBytes = stagedFileBytes.value ?: return
        val mime = stagedFileMime.value

        // Vibrate to signal physical NFC handshake detected
        vibrateDevice(60)

        val bytesToSend = if (mime?.startsWith("image/") == true && optimizeImage.value) {
            compressImage(rawBytes)
        } else {
            rawBytes
        }

        // Cancel previous transfer if still active
        activeTransferJob?.cancel()

        activeTransferJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            NfcSenderTransceiver.transferFileOverNfc(
                tag = tag,
                fileName = fileName ?: "nfc_file",
                fileBytes = bytesToSend
            ) { progress ->
                senderProgress.value = progress
                if (progress.isComplete) {
                    vibrateDevice(400)
                }
            }
        }
    }

    private fun vibrateDevice(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (ignored: Exception) {}
    }
}

@Composable
fun NfcMainScreen(
    nfcAdapter: NfcAdapter?,
    currentTab: TransferTab,
    onTabChange: (TransferTab) -> Unit,
    stagedFileName: String?,
    stagedFileBytes: ByteArray?,
    stagedFileMime: String?,
    stagedFileBitmap: Bitmap?,
    optimizeImage: Boolean,
    onToggleOptimize: (Boolean) -> Unit,
    senderProgress: NfcSenderTransceiver.SendProgress?,
    receiverState: NfcReceiverApduService.Companion.ReceiverState,
    onFileSelected: (name: String, bytes: ByteArray, mime: String?) -> Unit,
    onClearStagedFile: () -> Unit,
    onResetReceiver: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val name = getDisplayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val mime = context.contentResolver.getType(uri) ?: getMimeTypeFromFileName(name)
                    if (bytes != null) {
                        withContext(Dispatchers.Main) {
                            onFileSelected(name, bytes, mime)
                        }
                    }
                } catch (e: Exception) {
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
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title
        Text(
            text = "⚡ NFC File Transfer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Direct ISO-DEP • No Wi-Fi • No Bluetooth",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Hardware NFC Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    nfcAdapter == null -> MaterialTheme.colorScheme.errorContainer
                    !nfcAdapter.isEnabled -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                }
            )
        ) {
            Text(
                text = when {
                    nfcAdapter == null -> "⚠️ NFC is not supported on this device"
                    !nfcAdapter.isEnabled -> "⚠️ NFC is turned OFF. Turn ON NFC in Settings."
                    else -> "✅ NFC is Active"
                },
                modifier = Modifier.padding(10.dp),
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mode Navigation Tabs (RECEIVE vs SEND)
        TabRow(
            selectedTabIndex = if (currentTab == TransferTab.RECEIVE) 0 else 1,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = currentTab == TransferTab.RECEIVE,
                onClick = { onTabChange(TransferTab.RECEIVE) },
                text = {
                    Text(
                        text = "📥 Receive Mode",
                        fontWeight = if (currentTab == TransferTab.RECEIVE) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
            Tab(
                selected = currentTab == TransferTab.SEND,
                onClick = { onTabChange(TransferTab.SEND) },
                text = {
                    Text(
                        text = "📤 Send Mode",
                        fontWeight = if (currentTab == TransferTab.SEND) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentTab == TransferTab.RECEIVE) {
            // ==========================================
            // RECEIVER VIEW
            // ==========================================
            ReceiverCard(
                receiverState = receiverState,
                onReset = onResetReceiver,
                context = context
            )
        } else {
            // ==========================================
            // SENDER VIEW
            // ==========================================
            SenderCard(
                fileName = stagedFileName,
                fileBytes = stagedFileBytes,
                fileMime = stagedFileMime,
                fileBitmap = stagedFileBitmap,
                optimizeImage = optimizeImage,
                onToggleOptimize = onToggleOptimize,
                senderProgress = senderProgress,
                onSelectFile = { filePickerLauncher.launch("*/*") },
                onClearFile = onClearStagedFile
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Usage Guide
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "💡 How to exchange files:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1. Set Phone B to 📥 Receive Mode.\n" +
                            "2. On Phone A, click 📤 Send Mode and choose a file (Image, PDF, etc.).\n" +
                            "3. Bring both phones firmly back-to-back until the progress reaches 100%!",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            }
        }
    }
}

@Composable
fun ReceiverCard(
    receiverState: NfcReceiverApduService.Companion.ReceiverState,
    onReset: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (receiverState.savedFile != null)
                Color(0xFFE8F5E9)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Receiver Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status message
            Text(
                text = receiverState.status,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    receiverState.savedFile != null -> Color(0xFF1B5E20)
                    receiverState.hasError -> MaterialTheme.colorScheme.error
                    receiverState.isTransferActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            // Progress bar
            if (receiverState.totalChunks > 0 && receiverState.isTransferActive) {
                Spacer(modifier = Modifier.height(10.dp))
                val progress = receiverState.currentChunk.toFloat() / receiverState.totalChunks
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(progress * 100).toInt()}% received",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Chunk ${receiverState.currentChunk}/${receiverState.totalChunks}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Completed File Card
            if (receiverState.savedFile != null) {
                val file = receiverState.savedFile
                val isImage = receiverState.mimeType?.startsWith("image/") == true

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                if (isImage) {
                    val bitmap = remember(file) {
                        try {
                            BitmapFactory.decodeFile(file.absolutePath)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Received Image Preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Text(
                    text = "📄 ${file.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Size: ${NfcProtocol.formatFileSize(file.length())} • ${receiverState.mimeType}",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { openFileWithIntent(context, file) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open File")
                    }

                    OutlinedButton(
                        onClick = { shareFileWithIntent(context, file) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share")
                    }
                }
            }
        }
    }
}

@Composable
fun SenderCard(
    fileName: String?,
    fileBytes: ByteArray?,
    fileMime: String?,
    fileBitmap: Bitmap?,
    optimizeImage: Boolean,
    onToggleOptimize: (Boolean) -> Unit,
    senderProgress: NfcSenderTransceiver.SendProgress?,
    onSelectFile: () -> Unit,
    onClearFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Select & Send File",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSelectFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (fileName == null) "Select File (Image / PDF)" else "Change File")
                }

                if (fileName != null) {
                    OutlinedButton(onClick = onClearFile) {
                        Text("Clear")
                    }
                }
            }

            if (fileName != null && fileBytes != null) {
                Spacer(modifier = Modifier.height(12.dp))

                // Image preview if an image was selected
                if (fileBitmap != null) {
                    Image(
                        bitmap = fileBitmap.asImageBitmap(),
                        contentDescription = "Selected Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "📄 $fileName",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Original Size: ${NfcProtocol.formatFileSize(fileBytes.size.toLong())} ($fileMime)",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )

                // Image optimization toggle for fast transfer
                if (fileMime?.startsWith("image/") == true && fileBytes.size > 200 * 1024) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = optimizeImage,
                            onCheckedChange = onToggleOptimize
                        )
                        Text(
                            text = "Optimize image for fast NFC transfer (~80 KB, ~3 sec)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (senderProgress != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = senderProgress.status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            senderProgress.isComplete -> Color(0xFF1B5E20)
                            senderProgress.isError -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )

                    if (senderProgress.totalChunks > 0 && !senderProgress.isComplete && !senderProgress.isError) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = senderProgress.currentChunk.toFloat() / senderProgress.totalChunks
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(progress * 100).toInt()}% sent",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Chunk ${senderProgress.currentChunk}/${senderProgress.totalChunks}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap 'Select File' to choose an image, PDF, or document to send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

/**
 * Open received file using system viewer via FileProvider.
 */
private fun openFileWithIntent(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val mime = getMimeTypeFromFileName(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open file with"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share received file via Android share sheet.
 */
private fun shareFileWithIntent(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val mime = getMimeTypeFromFileName(file.name)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share file"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
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

private fun getMimeTypeFromFileName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

/**
 * Compresses an image bitmap to ~80-120 KB so NFC transfer completes within a few seconds.
 */
private fun compressImage(bytes: ByteArray, maxDimension: Int = 1280, quality: Int = 80): ByteArray {
    return try {
        val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val width = originalBitmap.width
        val height = originalBitmap.height
        val max = Math.max(width, height)

        val scale = if (max > maxDimension) maxDimension.toFloat() / max else 1.0f
        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(originalBitmap, (width * scale).toInt(), (height * scale).toInt(), true)
        } else {
            originalBitmap
        }

        val out = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val result = out.toByteArray()
        if (result.size < bytes.size) result else bytes
    } catch (e: Exception) {
        bytes
    }
}
