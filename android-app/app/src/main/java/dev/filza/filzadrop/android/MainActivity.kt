package dev.filza.filzadrop.android

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FilzaDropAndroidTheme {
                FilzaDropScreen()
            }
        }
    }
}

@Composable
fun FilzaDropAndroidTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF6D28D9),
        secondary = Color(0xFF7C3AED),
        background = Color(0xFF0D0A14),
        surface = Color(0xFF130E1E)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

data class PeerDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val platform: String,
    val activeCode: String
)

data class SelectedFileItem(
    val uri: Uri,
    val name: String,
    val size: Long
)

data class TransferDialogState(
    val success: Boolean,
    val title: String,
    val detail: String
)

@Composable
fun FilzaDropScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var activeCode by remember { mutableStateOf("— — — — — —") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var inputCode by remember { mutableStateOf("") }
    var selectedFiles by remember { mutableStateOf<List<SelectedFileItem>>(emptyList()) }

    var isReceiving by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Ready · Wi-Fi, Hotspot & Mobile Data") }
    var dialogState by remember { mutableStateOf<TransferDialogState?>(null) }

    val peers = remember { mutableStateMapOf<String, PeerDevice>() }
    val codeDirectory = remember { ConcurrentHashMap<String, InetSocketAddress>() }
    val cancelFlag = remember { AtomicBoolean(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var activeServerSocket by remember { mutableStateOf<ServerSocket?>(null) }
    var activeClosable by remember { mutableStateOf<Closeable?>(null) }

    DisposableEffect(Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("filzadrop-discovery-lock")?.apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
        val discoveryJob = scope.launch(Dispatchers.IO) {
            AndroidProtocol.runDiscoveryLoop(
                deviceName = Build.MODEL ?: "Android",
                getActiveCode = { activeCode.filter { it.isDigit() } },
                onPeer = { peer ->
                    if (peer.activeCode.isNotEmpty()) {
                        codeDirectory[peer.activeCode] = InetSocketAddress(peer.ip, peer.port)
                    }
                    peers["${peer.ip}:${peer.port}"] = peer
                }
            )
        }
        onDispose {
            discoveryJob.cancel()
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {}
        }
    }

    val multiFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.map { uri ->
                val meta = AndroidProtocol.queryFileMeta(context.contentResolver, uri)
                SelectedFileItem(uri, meta.first, meta.second)
            }
        }
    }

    fun cancelActive() {
        cancelFlag.set(true)
        try { activeServerSocket?.close() } catch (_: Exception) {}
        try { activeClosable?.close() } catch (_: Exception) {}
        activeJob?.cancel()
        isReceiving = false
        isSending = false
        activeCode = "— — — — — —"
        qrBitmap = null
        progress = 0f
        statusText = "Cancelled"
    }

    dialogState?.let { dlg ->
        Dialog(onDismissRequest = { dialogState = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (dlg.success) Color(0xFF130E1E) else Color(0xFF160D18))
                    .border(
                        1.dp,
                        if (dlg.success) Color(0xFF3B2863) else Color(0xFF6E223D),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (dlg.success) "COMPLETED" else "ERROR",
                    color = if (dlg.success) Color(0xFFC4B5FD) else Color(0xFFFDA4AF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(dlg.title, color = Color(0xFFF3EEFF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(dlg.detail, color = Color(0xFF9D8EC4), fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { dialogState = null },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (dlg.success) Color(0xFF6D28D9) else Color(0xFF2A1220)
                        )
                    ) {
                        Text(if (dlg.success) "Done" else "Dismiss", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0A14))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("FilzaDrop", color = Color(0xFFF3EEFF), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Wi-Fi & Mobile Data",
                    color = Color(0xFFA594C8),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF151022))
                        .border(1.dp, Color(0xFF261D3A), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // SEND PANE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF130E1E))
                    .border(1.dp, Color(0xFF231A36), RoundedCornerShape(10.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Send", color = Color(0xFFF2ECFF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Select one or multiple files and enter the 6-digit code", color = Color(0xFF786A96), fontSize = 11.sp)

                val selectionSummary = remember(selectedFiles) {
                    if (selectedFiles.isEmpty()) {
                        "Tap to select file(s)"
                    } else if (selectedFiles.size == 1) {
                        "${selectedFiles[0].name} (${selectedFiles[0].size / 1024} KB)"
                    } else {
                        val totalKb = selectedFiles.sumOf { it.size } / 1024
                        "${selectedFiles.size} files selected ($totalKb KB total)"
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0B18))
                        .border(1.dp, Color(0xFF2B2042), RoundedCornerShape(8.dp))
                        .clickable(enabled = !isSending && !isReceiving) { multiFilePicker.launch("*/*") }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectionSummary,
                        color = Color(0xFFD5C8F0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { v -> inputCode = v.filter { it.isDigit() }.take(6) },
                    placeholder = { Text("6-digit code", color = Color(0xFF554870)) },
                    textStyle = TextStyle(
                        color = Color(0xFFF3EEFF),
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                if (!isSending) {
                    Button(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                multiFilePicker.launch("*/*")
                                return@Button
                            }
                            if (inputCode.length != 6) {
                                statusText = "Enter a 6-digit pairing code first"
                                return@Button
                            }
                            val filesToSend = selectedFiles
                            val targetCode = inputCode
                            isSending = true
                            cancelFlag.set(false)
                            statusText = "Matching code $targetCode on Wi-Fi & Mobile Data…"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    AndroidProtocol.sendFilesAnyNetwork(
                                        contentResolver = context.contentResolver,
                                        files = filesToSend,
                                        code = targetCode,
                                        cachedLanTarget = codeDirectory[targetCode],
                                        cancelFlag = cancelFlag,
                                        registerClosable = { activeClosable = it }
                                    ) { sent, total ->
                                        progress = if (total > 0) sent.toFloat() / total else 0f
                                    }
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        progress = 1f
                                        statusText = "Sent ${filesToSend.size} file(s)"
                                        dialogState = TransferDialogState(
                                            true,
                                            "Transfer Complete",
                                            "Delivered ${filesToSend.size} encrypted file(s) with SHA-256 verification."
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        progress = 0f
                                        if (!cancelFlag.get()) {
                                            statusText = "Send failed"
                                            dialogState = TransferDialogState(
                                                false,
                                                "Transfer Failed",
                                                e.message ?: "Could not reach receiver with code $targetCode."
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D28D9))
                    ) {
                        Text("Send Files", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = { cancelActive() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1220))
                    ) {
                        Text("Cancel", color = Color(0xFFFDA4AF), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }

            // RECEIVE PANE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF130E1E))
                    .border(1.dp, Color(0xFF231A36), RoundedCornerShape(10.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Receive", color = Color(0xFFF2ECFF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Share this 6-digit code with the sender (works on Wi-Fi or Mobile Data)", color = Color(0xFF786A96), fontSize = 11.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F0B18))
                            .border(1.dp, Color(0xFF2B2042), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            activeCode,
                            color = Color(0xFFE9D5FF),
                            fontSize = 24.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF2B2042), RoundedCornerShape(8.dp))
                        )
                    }
                }

                if (!isReceiving) {
                    Button(
                        onClick = {
                            if (isSending) return@Button
                            val code = String.format("%06d", SecureRandom().nextInt(1_000_000))
                            activeCode = "${code.substring(0, 3)} ${code.substring(3)}"
                            qrBitmap = AndroidProtocol.generateQrBitmap(code, 240)
                            isReceiving = true
                            cancelFlag.set(false)
                            statusText = "Listening on Wi-Fi & Mobile Data (code $code)…"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    val server = ServerSocket(0).apply {
                                        try {
                                            close()
                                        } catch (_: Exception) {}
                                    }
                                    val boundServer = try {
                                        ServerSocket(AndroidProtocol.PORT)
                                    } catch (_: IOException) {
                                        ServerSocket(0)
                                    }
                                    activeServerSocket = boundServer
                                    val savedNames = AndroidProtocol.receiveFilesAnyNetwork(
                                        context = context,
                                        server = boundServer,
                                        code = code,
                                        cancelFlag = cancelFlag,
                                        registerClosable = { activeClosable = it }
                                    ) { got, total ->
                                        progress = if (total > 0) got.toFloat() / total else 0f
                                    }
                                    withContext(Dispatchers.Main) {
                                        isReceiving = false
                                        activeCode = "— — — — — —"
                                        qrBitmap = null
                                        progress = 1f
                                        statusText = "Saved ${savedNames.size} file(s) to Downloads/FilzaDrop"
                                        dialogState = TransferDialogState(
                                            true,
                                            "Transfer Complete",
                                            "Verified SHA-256 and saved to Downloads/FilzaDrop:\n" +
                                                savedNames.joinToString("\n") { "• $it" }
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isReceiving = false
                                        activeCode = "— — — — — —"
                                        qrBitmap = null
                                        progress = 0f
                                        if (!cancelFlag.get()) {
                                            statusText = "Receive failed"
                                            dialogState = TransferDialogState(
                                                false,
                                                "Transfer Failed",
                                                e.message ?: "Transfer rejected or interrupted."
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D28D9))
                    ) {
                        Text("Listen for Files", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = { cancelActive() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1220))
                    ) {
                        Text("Cancel", color = Color(0xFFFDA4AF), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }

            // DEVICES BAR
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF130E1E))
                    .border(1.dp, Color(0xFF231A36), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Nearby Devices (or enter any 6-digit code over Mobile Data)", color = Color(0xFF8E7FB0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (peers.isEmpty()) {
                    Text("Listening for local peers…", color = Color(0xFF786A96), fontSize = 11.sp)
                } else {
                    peers.values.forEach { peer ->
                        val label = "${peer.name} (${peer.platform})" +
                                if (peer.activeCode.isNotEmpty()) " · ${peer.activeCode}" else ""
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1B142B))
                                .border(1.dp, Color(0xFF32244F), RoundedCornerShape(6.dp))
                                .clickable {
                                    if (peer.activeCode.isNotEmpty()) {
                                        inputCode = peer.activeCode
                                        statusText = "Selected code ${peer.activeCode} (${peer.name})"
                                    }
                                }
                                .padding(8.dp)
                        ) {
                            Text(label, color = Color(0xFFD5C8F0), fontSize = 11.sp)
                        }
                    }
                }
            }

            // BOTTOM PROGRESS
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(statusText, color = Color(0xFFBFB2D9), fontSize = 11.sp)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF7C3AED),
                    trackColor = Color(0xFF1A1329)
                )
            }
        }
    }
}

object AndroidProtocol {
    const val PORT = 45821
    private const val DISCOVERY_PORT = 45822
    private const val MULTI_HEADER_MAGIC = "FD_MULTI_V2"
    private const val MAX_BYTES = 10L * 1024 * 1024 * 1024
    private const val SALT = 16
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 310_000
    private val BROKER_HOSTS = arrayOf("broker.emqx.io", "broker.hivemq.com")
    private const val BROKER_TLS_PORT = 8883
    private const val CHUNK_SIZE = 32 * 1024

    fun generateQrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val fg = android.graphics.Color.parseColor("#F3EEFF")
        val bg = android.graphics.Color.parseColor("#0F0B18")
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) fg else bg)
            }
        }
        return bmp
    }

    fun getLocalIpv4Hint(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val name = ni.name.lowercase()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        if (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) {
                            return ip
                        }
                        if (fallback == null) fallback = ip
                    }
                }
            }
            return fallback ?: "127.0.0.1"
        } catch (_: Exception) {
            return "127.0.0.1"
        }
    }

    fun queryFileMeta(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "shared-file"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return sanitizeFileName(name) to size
    }

    fun sanitizeFileName(raw: String): String {
        val slashStripped = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = slashStripped.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
        if (cleaned.isEmpty() || cleaned == "..") return "received-file"
        return if (cleaned.length > 180) cleaned.substring(0, 180) else cleaned
    }

    private fun saveVerifiedFileToDownloads(context: Context, tempFile: File, displayName: String): String {
        val safeName = sanitizeFileName(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FilzaDrop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IOException("Could not create entry in Downloads/FilzaDrop")
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(tempFile).buffered().use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                } ?: throw IOException("Could not open Downloads output stream")
                val publishValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
                return safeName
            } catch (e: Exception) {
                try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
                throw e
            }
        } else {
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val receiveDir = File(downloadsRoot, "FilzaDrop").apply { mkdirs() }
            val finalFile = File(receiveDir, safeName)
            tempFile.copyTo(finalFile, overwrite = true)
            return finalFile.name
        }
    }

    private fun sendBroadcastOverAllInterfaces(socket: DatagramSocket, data: ByteArray) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                for (ia in ni.interfaceAddresses) {
                    val bcast = ia.broadcast ?: continue
                    try {
                        socket.send(DatagramPacket(data, data.size, bcast, DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            }
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
        } catch (_: Exception) {}
    }

    fun resolveCodeOnLan(code: String): InetSocketAddress? {
        try {
            DatagramSocket().use { querySocket ->
                querySocket.broadcast = true
                querySocket.soTimeout = 250
                val msg = "FILZADROP_WHOHAS|$code".toByteArray(Charsets.UTF_8)
                val buf = ByteArray(512)
                repeat(4) {
                    sendBroadcastOverAllInterfaces(querySocket, msg)
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        querySocket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val parts = text.split("|")
                        if (parts.size >= 6 && parts[0] == "FILZADROP" && parts[5] == code) {
                            val port = parts[3].toIntOrNull() ?: PORT
                            return InetSocketAddress(packet.address.hostAddress ?: return null, port)
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun runDiscoveryLoop(
        deviceName: String,
        getActiveCode: () -> String,
        onPeer: (PeerDevice) -> Unit
    ) = coroutineScope {
        val safeDeviceName = deviceName.replace("|", "_")
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
                soTimeout = 1000
            }
        } catch (_: Exception) {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 1000
            }
        }
        launch {
            while (isActive) {
                try {
                    val code = getActiveCode().ifEmpty { "-" }
                    val msg = "FILZADROP|2|$safeDeviceName|$PORT|Android|$code"
                    sendBroadcastOverAllInterfaces(socket, msg.toByteArray(Charsets.UTF_8))
                } catch (_: Exception) {}
                delay(2000)
            }
        }
        val buf = ByteArray(512)
        while (isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)

                if (text.startsWith("FILZADROP_WHOHAS|")) {
                    val wanted = text.substringAfter("FILZADROP_WHOHAS|").trim()
                    val current = getActiveCode()
                    if (wanted.isNotEmpty() && wanted == current) {
                        val reply = "FILZADROP|2|$safeDeviceName|$PORT|Android|$current".toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        socket.send(DatagramPacket(reply, reply.size, packet.address, DISCOVERY_PORT))
                    }
                    continue
                }

                val parts = text.split("|")
                if (parts.size >= 4 && parts[0] == "FILZADROP") {
                    val peer = PeerDevice(
                        name = parts[2],
                        ip = packet.address.hostAddress ?: "",
                        port = parts[3].toIntOrNull() ?: PORT,
                        platform = if (parts.size >= 5) parts[4] else "Windows",
                        activeCode = if (parts.size >= 6 && parts[5] != "-") parts[5] else ""
                    )
                    onPeer(peer)
                }
            } catch (_: Exception) {}
        }
    }

    fun sendFilesAnyNetwork(
        contentResolver: ContentResolver,
        files: List<SelectedFileItem>,
        code: String,
        cachedLanTarget: InetSocketAddress?,
        cancelFlag: AtomicBoolean,
        registerClosable: (Closeable) -> Unit,
        onProgress: (Long, Long) -> Unit
    ) {
        // 1. Try cached or UDP-resolved LAN target first
        val lanTarget = cachedLanTarget ?: resolveCodeOnLan(code)
        if (lanTarget != null) {
            try {
                val sock = Socket()
                registerClosable(sock)
                sock.connect(lanTarget, 3000)
                sock.soTimeout = 30_000
                sock.use { s ->
                    sendFilesOverStream(contentResolver, files, s.getOutputStream(), code, cancelFlag, onProgress)
                }
                return
            } catch (_: IOException) {
                // Fall through to GlobalRelayBridge
            }
        }

        // 2. Connect via Global Cross-Network Bridge (Mobile Data, Different Wi-Fi, or AP-isolated Wi-Fi)
        val hs = connectGlobalAsSender(code, cancelFlag, registerClosable)
        if (hs.receiverLanIp.isNotBlank() && hs.receiverLanIp != "127.0.0.1") {
            try {
                val directSock = Socket()
                registerClosable(directSock)
                directSock.connect(InetSocketAddress(hs.receiverLanIp, hs.receiverLanPort), 1800)
                directSock.soTimeout = 30_000
                hs.tunnel.close()
                directSock.use { s ->
                    sendFilesOverStream(contentResolver, files, s.getOutputStream(), code, cancelFlag, onProgress)
                }
                return
            } catch (_: IOException) {
                // Different network / Mobile Data — stream over GlobalRelayBridge tunnel
            }
        }

        hs.tunnel.use { tunnel ->
            tunnel.activateSenderTunnel()
            sendFilesOverStream(contentResolver, files, tunnel.getOutputStream(), code, cancelFlag, onProgress)
        }
    }

    private fun sendFilesOverStream(
        contentResolver: ContentResolver,
        files: List<SelectedFileItem>,
        raw: OutputStream,
        code: String,
        cancelFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ) {
        val totalBytes = files.sumOf { it.size }
        if (totalBytes < 0 || totalBytes > MAX_BYTES) {
            throw IOException("Total transfer size exceeds the 10 GiB limit.")
        }

        val rng = SecureRandom()
        val salt = ByteArray(SALT).also { rng.nextBytes(it) }
        val nonce = ByteArray(NONCE).also { rng.nextBytes(it) }
        val cipher = createCipher(Cipher.ENCRYPT_MODE, code, salt, nonce)

        raw.write(salt)
        raw.write(nonce)
        raw.flush()

        DataOutputStream(CipherOutputStream(raw, cipher)).use { out ->
            out.writeUTF(MULTI_HEADER_MAGIC)
            out.writeInt(files.size)
            out.writeLong(totalBytes)

            val buf = ByteArray(64 * 1024)
            var totalSent = 0L

            for (item in files) {
                out.writeUTF(sanitizeFileName(item.name))
                out.writeLong(item.size)
                val sha256 = MessageDigest.getInstance("SHA-256")
                val input = contentResolver.openInputStream(item.uri)
                    ?: throw IOException("Cannot read selected file: ${item.name}")
                input.use { stream ->
                    var sentForFile = 0L
                    while (true) {
                        if (cancelFlag.get()) throw IOException("Cancelled")
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha256.update(buf, 0, n)
                        sentForFile += n
                        totalSent += n
                        onProgress(totalSent, totalBytes)
                    }
                    if (sentForFile != item.size) {
                        throw IOException("File size changed during transfer: ${item.name}")
                    }
                }
                out.write(sha256.digest())
            }
            out.flush()
        }
    }

    private data class IncomingChannel(val input: InputStream, val owner: Closeable)

    fun receiveFilesAnyNetwork(
        context: Context,
        server: ServerSocket,
        code: String,
        cancelFlag: AtomicBoolean,
        registerClosable: (Closeable) -> Unit,
        onProgress: (Long, Long) -> Unit
    ): List<String> {
        server.soTimeout = 300_000
        val winningChannel = CompletableFuture<IncomingChannel>()
        val localIp = getLocalIpv4Hint()
        val boundPort = server.localPort

        // 1. Direct LAN TCP listener
        val directThread = Thread({
            try {
                val s = server.accept()
                s.soTimeout = 120_000
                s.tcpNoDelay = true
                registerClosable(s)
                val ch = IncomingChannel(s.getInputStream(), s)
                if (!winningChannel.complete(ch)) {
                    try { s.close() } catch (_: Exception) {}
                }
            } catch (e: IOException) {
                winningChannel.completeExceptionally(e)
            }
        }, "filzadrop-android-direct")
        directThread.isDaemon = true
        directThread.start()

        // 2. Global Cross-Network Bridge (Mobile Data & Different Wi-Fi + LAN IP announcement)
        val globalThread = Thread({
            try {
                val tunnel = listenGlobalAsReceiver(code, localIp, boundPort, cancelFlag, registerClosable)
                val ch = IncomingChannel(tunnel.getInputStream(), tunnel)
                if (!winningChannel.complete(ch)) {
                    tunnel.close()
                } else {
                    try { server.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }, "filzadrop-android-global")
        globalThread.isDaemon = true
        globalThread.start()

        val channel = winningChannel.get()
        channel.owner.use {
            return receiveFilesFromStream(context, channel.input, code, cancelFlag, onProgress)
        }
    }

    private fun receiveFilesFromStream(
        context: Context,
        raw: InputStream,
        code: String,
        cancelFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ): List<String> {
        val savedNames = mutableListOf<String>()
        val salt = readExact(raw, SALT)
        val nonce = readExact(raw, NONCE)
        val cipher = createCipher(Cipher.DECRYPT_MODE, code, salt, nonce)

        DataInputStream(CipherInputStream(raw, cipher)).use { input ->
            val firstToken = input.readUTF()
            if (firstToken == MULTI_HEADER_MAGIC) {
                val fileCount = input.readInt()
                val totalBytes = input.readLong()
                if (fileCount < 1 || fileCount > 500 || totalBytes < 0 || totalBytes > MAX_BYTES) {
                    throw IOException("Invalid multi-file header.")
                }
                var baseOffset = 0L
                repeat(fileCount) {
                    val rawName = input.readUTF()
                    val fileSize = input.readLong()
                    val savedName = receiveSingleStreamedFile(
                        context = context,
                        input = input,
                        rawName = rawName,
                        size = fileSize,
                        expectSha256 = true,
                        cancelFlag = cancelFlag
                    ) { gotInFile ->
                        onProgress(baseOffset + gotInFile, totalBytes)
                    }
                    baseOffset += fileSize
                    savedNames.add(savedName)
                }
            } else {
                val fileSize = input.readLong()
                val savedName = receiveSingleStreamedFile(
                    context = context,
                    input = input,
                    rawName = firstToken,
                    size = fileSize,
                    expectSha256 = false,
                    cancelFlag = cancelFlag
                ) { got ->
                    onProgress(got, fileSize)
                }
                savedNames.add(savedName)
            }
            if (input.read() != -1) {
                throw IOException("Unexpected trailing data after payload.")
            }
            return savedNames
        }
    }

    private fun receiveSingleStreamedFile(
        context: Context,
        input: DataInputStream,
        rawName: String,
        size: Long,
        expectSha256: Boolean,
        cancelFlag: AtomicBoolean,
        onBytes: (Long) -> Unit
    ): String {
        if (size < 0 || size > MAX_BYTES) {
            throw IOException("Invalid file size.")
        }
        val cleanName = sanitizeFileName(rawName)
        // Use app cacheDir for temporary streaming (always permitted on Android 10-15 Scoped Storage)
        val tempFile = File.createTempFile(".filzadrop-", ".part", context.cacheDir)
        try {
            val sha256 = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tempFile).buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (got < size) {
                    if (cancelFlag.get()) {
                        throw IOException("Cancelled")
                    }
                    val toRead = minOf(buf.size.toLong(), size - got).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) throw EOFException("Connection closed before full file arrived.")
                    out.write(buf, 0, n)
                    sha256.update(buf, 0, n)
                    got += n
                    onBytes(got)
                }
            }
            if (expectSha256) {
                val expectedHash = ByteArray(32)
                input.readFully(expectedHash)
                val actualHash = sha256.digest()
                if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                    throw IOException("SHA-256 integrity mismatch for $cleanName")
                }
            }
            return saveVerifiedFileToDownloads(context, tempFile, cleanName)
        } finally {
            tempFile.delete()
        }
    }

    // --- Global Cross-Network Bridge (Mobile Data & Different Wi-Fi) ---

    private fun blindedTopicPrefix(code: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val hash = sha.digest(("filzadrop-v2-rendezvous-$code").toByteArray(Charsets.UTF_8))
        val sb = StringBuilder("fd2/")
        for (i in 0 until 12) {
            sb.append(String.format("%02x", hash[i]))
        }
        return sb.toString()
    }

    private data class HandshakeResult(val receiverLanIp: String, val receiverLanPort: Int, val tunnel: StreamTunnel)

    private fun listenGlobalAsReceiver(
        code: String,
        localLanIp: String,
        localLanPort: Int,
        cancelFlag: AtomicBoolean,
        registerClosable: (Closeable) -> Unit
    ): StreamTunnel {
        val prefix = blindedTopicPrefix(code)
        val topicCtrlToRecv = "$prefix/c2r"
        val topicCtrlToSend = "$prefix/r2c"
        val topicDataToRecv = "$prefix/d2r"

        val mqtt = connectFirstBroker(cancelFlag)
        registerClosable(mqtt)
        mqtt.subscribe(topicCtrlToRecv)
        mqtt.subscribe(topicDataToRecv)

        while (!cancelFlag.get()) {
            val pkt = mqtt.pollPacket(2000) ?: continue
            if (pkt.topic == topicCtrlToRecv) {
                val msg = String(pkt.payload, Charsets.UTF_8)
                if (msg.startsWith("WHOIS")) {
                    val reply = "IAM|$localLanIp|$localLanPort"
                    mqtt.publish(topicCtrlToSend, reply.toByteArray(Charsets.UTF_8))
                } else if (msg.startsWith("START_TUNNEL")) {
                    mqtt.publish(topicCtrlToSend, "TUNNEL_READY".toByteArray(Charsets.UTF_8))
                    return StreamTunnel(mqtt, topicDataToRecv, topicCtrlToSend, cancelFlag)
                }
            }
        }
        mqtt.close()
        throw IOException("Cancelled")
    }

    private fun connectGlobalAsSender(
        code: String,
        cancelFlag: AtomicBoolean,
        registerClosable: (Closeable) -> Unit
    ): HandshakeResult {
        val prefix = blindedTopicPrefix(code)
        val topicCtrlToRecv = "$prefix/c2r"
        val topicCtrlToSend = "$prefix/r2c"
        val topicDataToRecv = "$prefix/d2r"

        val mqtt = connectFirstBroker(cancelFlag)
        registerClosable(mqtt)
        mqtt.subscribe(topicCtrlToSend)

        val whois = "WHOIS".toByteArray(Charsets.UTF_8)
        repeat(10) {
            if (cancelFlag.get()) {
                mqtt.close()
                throw IOException("Cancelled")
            }
            mqtt.publish(topicCtrlToRecv, whois)
            val deadline = System.currentTimeMillis() + 800
            while (System.currentTimeMillis() < deadline && !cancelFlag.get()) {
                val pkt = mqtt.pollPacket(250)
                if (pkt != null && pkt.topic == topicCtrlToSend) {
                    val msg = String(pkt.payload, Charsets.UTF_8)
                    if (msg.startsWith("IAM|")) {
                        val parts = msg.split("|")
                        val lanIp = if (parts.size > 1) parts[1] else ""
                        val lanPort = if (parts.size > 2) (parts[2].toIntOrNull() ?: PORT) else PORT
                        return HandshakeResult(
                            lanIp,
                            lanPort,
                            StreamTunnel(mqtt, topicCtrlToSend, topicDataToRecv, cancelFlag)
                        )
                    }
                }
            }
        }
        mqtt.close()
        throw IOException("No receiver found for code $code on Wi-Fi or Mobile Data.")
    }

    private fun connectFirstBroker(cancelFlag: AtomicBoolean): MqttTinyClient {
        var lastEx: IOException? = null
        for (host in BROKER_HOSTS) {
            if (cancelFlag.get()) throw IOException("Cancelled")
            try {
                val client = MqttTinyClient(host, BROKER_TLS_PORT)
                client.connect()
                return client
            } catch (e: IOException) {
                lastEx = e
            }
        }
        throw lastEx ?: IOException("Could not reach cross-network relay broker.")
    }

    private class StreamTunnel(
        private val mqtt: MqttTinyClient,
        private val incomingTopic: String,
        private val outgoingTopic: String,
        private val cancelFlag: AtomicBoolean
    ) : Closeable {
        @Volatile private var closed = false

        fun activateSenderTunnel() {
            val prefix = outgoingTopic.substringBeforeLast('/')
            val topicCtrlToRecv = "$prefix/c2r"
            mqtt.publish(topicCtrlToRecv, "START_TUNNEL".toByteArray(Charsets.UTF_8))
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline && !cancelFlag.get()) {
                val pkt = mqtt.pollPacket(300)
                if (pkt != null && pkt.topic == incomingTopic) {
                    if (String(pkt.payload, Charsets.UTF_8) == "TUNNEL_READY") return
                }
            }
            throw IOException("Timed out activating cross-network tunnel.")
        }

        fun getInputStream(): InputStream = object : InputStream() {
            private var currentChunk: ByteArray? = null
            private var offset = 0
            private var eof = false

            override fun read(): Int {
                val single = ByteArray(1)
                val n = read(single, 0, 1)
                return if (n == -1) -1 else (single[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (eof) return -1
                while (currentChunk == null || offset >= currentChunk!!.size) {
                    if (cancelFlag.get() || closed) throw IOException("Cancelled")
                    val pkt = mqtt.pollPacket(60_000) ?: throw EOFException("Cross-network stream timed out.")
                    if (pkt.topic != incomingTopic || pkt.payload.isEmpty()) continue
                    val type = pkt.payload[0].toInt()
                    if (type == 0x01) {
                        currentChunk = pkt.payload.copyOfRange(1, pkt.payload.size)
                        offset = 0
                        mqtt.publish(outgoingTopic, "ACK".toByteArray(Charsets.UTF_8))
                    } else if (type == 0x02) {
                        mqtt.publish(outgoingTopic, "ACK".toByteArray(Charsets.UTF_8))
                        eof = true
                        return -1
                    }
                }
                val chunk = currentChunk!!
                val toCopy = minOf(len, chunk.size - offset)
                System.arraycopy(chunk, offset, b, off, toCopy)
                offset += toCopy
                return toCopy
            }
        }

        fun getOutputStream(): OutputStream = object : OutputStream() {
            private val buffer = ByteArrayOutputStream(CHUNK_SIZE)

            override fun write(b: Int) {
                buffer.write(b)
                if (buffer.size() >= CHUNK_SIZE) flushBuffer()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var remaining = len
                var pos = off
                while (remaining > 0) {
                    val space = CHUNK_SIZE - buffer.size()
                    val toWrite = minOf(remaining, space)
                    buffer.write(b, pos, toWrite)
                    pos += toWrite
                    remaining -= toWrite
                    if (buffer.size() >= CHUNK_SIZE) flushBuffer()
                }
            }

            private fun flushBuffer() {
                if (buffer.size() == 0) return
                val raw = buffer.toByteArray()
                buffer.reset()
                val framed = ByteArray(raw.size + 1)
                framed[0] = 0x01
                System.arraycopy(raw, 0, framed, 1, raw.size)
                mqtt.publish(outgoingTopic, framed)
                waitForAck()
            }

            override fun flush() {
                flushBuffer()
            }

            override fun close() {
                if (!closed) {
                    flushBuffer()
                    mqtt.publish(outgoingTopic, byteArrayOf(0x02))
                    try { waitForAck() } catch (_: IOException) {}
                    closed = true
                }
            }

            private fun waitForAck() {
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline && !cancelFlag.get()) {
                    val pkt = mqtt.pollPacket(500)
                    if (pkt != null && pkt.topic == incomingTopic) {
                        if (String(pkt.payload, Charsets.UTF_8) == "ACK") return
                    }
                }
                if (cancelFlag.get()) throw IOException("Cancelled")
                throw IOException("Cross-network peer did not acknowledge data chunk.")
            }
        }

        override fun close() {
            closed = true
            mqtt.close()
        }
    }

    private data class MqttPacket(val topic: String, val payload: ByteArray)

    private class MqttTinyClient(private val host: String, private val port: Int) : Closeable {
        private var socket: Socket? = null
        private lateinit var input: DataInputStream
        private lateinit var output: DataOutputStream
        private val incomingQueue = LinkedBlockingQueue<MqttPacket>(512)
        @Volatile private var running = false
        private var packetId = 1

        fun connect() {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket() as SSLSocket
            ssl.connect(InetSocketAddress(host, port), 5_000)
            ssl.soTimeout = 45_000
            ssl.startHandshake()
            socket = ssl
            input = DataInputStream(BufferedInputStream(ssl.inputStream))
            output = DataOutputStream(BufferedOutputStream(ssl.outputStream))

            val clientId = "fda-" + UUID.randomUUID().toString().take(12)
            val body = ByteArrayOutputStream()
            DataOutputStream(body).apply {
                writeUTF("MQTT")
                writeByte(4)
                writeByte(0x02)
                writeShort(30)
                writeUTF(clientId)
                flush()
            }
            sendFrame(0x10, body.toByteArray())

            val header = input.readUnsignedByte()
            val remLen = readRemainingLength(input)
            val connAck = ByteArray(remLen)
            input.readFully(connAck)
            if ((header and 0xF0) != 0x20 || connAck.size < 2 || connAck[1].toInt() != 0) {
                close()
                throw IOException("Broker rejected connection.")
            }

            running = true
            Thread({ readLoop() }, "fda-mqtt-reader").apply { isDaemon = true; start() }
            Thread({ pingLoop() }, "fda-mqtt-ping").apply { isDaemon = true; start() }
        }

        @Synchronized
        fun subscribe(topic: String) {
            val body = ByteArrayOutputStream()
            DataOutputStream(body).apply {
                writeShort(packetId++)
                writeUTF(topic)
                writeByte(0)
                flush()
            }
            sendFrame(0x82, body.toByteArray())
        }

        @Synchronized
        fun publish(topic: String, payload: ByteArray) {
            val body = ByteArrayOutputStream()
            DataOutputStream(body).apply {
                writeUTF(topic)
                write(payload)
                flush()
            }
            sendFrame(0x30, body.toByteArray())
        }

        @Synchronized
        private fun sendFrame(fixedHeader: Int, body: ByteArray) {
            output.writeByte(fixedHeader)
            writeRemainingLength(output, body.size)
            output.write(body)
            output.flush()
        }

        fun pollPacket(timeoutMs: Long): MqttPacket? =
            incomingQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)

        private fun readLoop() {
            try {
                while (running && socket?.isClosed == false) {
                    val header = input.readUnsignedByte()
                    val remLen = readRemainingLength(input)
                    val data = ByteArray(remLen)
                    input.readFully(data)
                    if ((header and 0xF0) == 0x30) {
                        val pktIn = DataInputStream(ByteArrayInputStream(data))
                        val topic = pktIn.readUTF()
                        var headerBytes = topic.toByteArray(Charsets.UTF_8).size + 2
                        val qos = (header and 0x06) shr 1
                        if (qos > 0) {
                            pktIn.readUnsignedShort()
                            headerBytes += 2
                        }
                        val payload = ByteArray(remLen - headerBytes)
                        pktIn.readFully(payload)
                        incomingQueue.offer(MqttPacket(topic, payload))
                    }
                }
            } catch (_: IOException) {
            } finally {
                running = false
            }
        }

        private fun pingLoop() {
            while (running && socket?.isClosed == false) {
                try {
                    Thread.sleep(15_000)
                    if (running) sendFrame(0xC0, ByteArray(0))
                } catch (_: Exception) {
                    break
                }
            }
        }

        private fun readRemainingLength(inp: DataInputStream): Int {
            var multiplier = 1
            var value = 0
            var encodedByte: Int
            do {
                encodedByte = inp.readUnsignedByte()
                value += (encodedByte and 127) * multiplier
                multiplier *= 128
            } while ((encodedByte and 128) != 0)
            return value
        }

        private fun writeRemainingLength(out: DataOutputStream, length: Int) {
            var x = length
            do {
                var encodedByte = x % 128
                x /= 128
                if (x > 0) encodedByte = encodedByte or 128
                out.writeByte(encodedByte)
            } while (x > 0)
        }

        override fun close() {
            running = false
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    private fun createCipher(mode: Int, code: String, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            }
        } finally {
            key.fill(0)
            spec.clearPassword()
        }
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n < 0) throw EOFException("Handshake truncated.")
            off += n
        }
        return b
    }
}
