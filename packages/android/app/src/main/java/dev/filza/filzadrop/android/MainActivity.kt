package dev.filza.filzadrop.android

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
    var statusText by remember { mutableStateOf("Ready · Direct Wi-Fi P2P") }
    var dialogState by remember { mutableStateOf<TransferDialogState?>(null) }

    val peers = remember { mutableStateMapOf<String, PeerDevice>() }
    val codeDirectory = remember { ConcurrentHashMap<String, InetSocketAddress>() }
    val cancelFlag = remember { AtomicBoolean(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var activeServerSocket by remember { mutableStateOf<ServerSocket?>(null) }

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
            Text("FilzaDrop", color = Color(0xFFF3EEFF), fontWeight = FontWeight.Bold, fontSize = 17.sp)

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
                            statusText = "Connecting to code $targetCode…"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    val resolvedTarget = codeDirectory[targetCode]
                                        ?: AndroidProtocol.resolveCodeOnLan(targetCode)
                                        ?: throw IOException("No receiver found on Wi-Fi for code $targetCode.")

                                    AndroidProtocol.sendFiles(
                                        contentResolver = context.contentResolver,
                                        files = filesToSend,
                                        code = targetCode,
                                        target = resolvedTarget,
                                        cancelFlag = cancelFlag
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
                Text("Share this 6-digit code with the sender (Android or Windows)", color = Color(0xFF786A96), fontSize = 11.sp)

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
                            statusText = "Listening with code $code"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    val server = ServerSocket(AndroidProtocol.PORT)
                                    activeServerSocket = server
                                    val savedFiles = AndroidProtocol.receiveFiles(
                                        server = server,
                                        code = code,
                                        cancelFlag = cancelFlag
                                    ) { got, total ->
                                        progress = if (total > 0) got.toFloat() / total else 0f
                                    }
                                    withContext(Dispatchers.Main) {
                                        isReceiving = false
                                        activeCode = "— — — — — —"
                                        qrBitmap = null
                                        progress = 1f
                                        statusText = "Saved ${savedFiles.size} file(s) to Downloads/FilzaDrop"
                                        dialogState = TransferDialogState(
                                            true,
                                            "Transfer Complete",
                                            "Verified SHA-256 and saved ${savedFiles.size} file(s) to Downloads/FilzaDrop:\n" +
                                                savedFiles.joinToString("\n") { "• ${it.name}" }
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
                Text("Devices on Wi-Fi", color = Color(0xFF8E7FB0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (peers.isEmpty()) {
                    Text("Searching local network…", color = Color(0xFF786A96), fontSize = 11.sp)
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

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Could not create unique filename in destination.")
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
                querySocket.soTimeout = 300
                val msg = "FILZADROP_WHOHAS|$code".toByteArray(Charsets.UTF_8)
                val buf = ByteArray(512)
                repeat(5) {
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
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(DISCOVERY_PORT))
            broadcast = true
            soTimeout = 1000
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

    fun sendFiles(
        contentResolver: ContentResolver,
        files: List<SelectedFileItem>,
        code: String,
        target: InetSocketAddress,
        cancelFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ) {
        val totalBytes = files.sumOf { it.size }
        if (totalBytes < 0 || totalBytes > MAX_BYTES) {
            throw IOException("Total transfer size exceeds the 10 GiB limit.")
        }

        val socket = Socket()
        socket.connect(target, 6000)
        socket.soTimeout = 30_000

        socket.use { s ->
            val rng = SecureRandom()
            val salt = ByteArray(SALT).also { rng.nextBytes(it) }
            val nonce = ByteArray(NONCE).also { rng.nextBytes(it) }
            val cipher = createCipher(Cipher.ENCRYPT_MODE, code, salt, nonce)

            val raw = s.getOutputStream()
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
    }

    fun receiveFiles(
        server: ServerSocket,
        code: String,
        cancelFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ): List<File> {
        server.soTimeout = 300_000
        val socket = server.accept()
        server.close()

        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val receiveDir = File(downloadsRoot, "FilzaDrop").apply { mkdirs() }
        val savedFiles = mutableListOf<File>()

        socket.use { s ->
            s.soTimeout = 120_000
            s.tcpNoDelay = true
            val raw = s.getInputStream()
            val salt = readExact(raw, SALT)
            val nonce = readExact(raw, NONCE)
            val cipher = createCipher(Cipher.DECRYPT_MODE, code, salt, nonce)

            try {
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
                            val saved = receiveSingleStreamedFile(
                                input = input,
                                dir = receiveDir,
                                rawName = rawName,
                                size = fileSize,
                                expectSha256 = true,
                                cancelFlag = cancelFlag
                            ) { gotInFile ->
                                onProgress(baseOffset + gotInFile, totalBytes)
                            }
                            baseOffset += fileSize
                            savedFiles.add(saved)
                        }
                    } else {
                        val fileSize = input.readLong()
                        val saved = receiveSingleStreamedFile(
                            input = input,
                            dir = receiveDir,
                            rawName = firstToken,
                            size = fileSize,
                            expectSha256 = false,
                            cancelFlag = cancelFlag
                        ) { got ->
                            onProgress(got, fileSize)
                        }
                        savedFiles.add(saved)
                    }
                    if (input.read() != -1) {
                        throw IOException("Unexpected trailing data after payload.")
                    }
                    return savedFiles
                }
            } catch (e: Exception) {
                savedFiles.forEach { it.delete() }
                throw e
            }
        }
    }

    private fun receiveSingleStreamedFile(
        input: DataInputStream,
        dir: File,
        rawName: String,
        size: Long,
        expectSha256: Boolean,
        cancelFlag: AtomicBoolean,
        onBytes: (Long) -> Unit
    ): File {
        if (size < 0 || size > MAX_BYTES) {
            throw IOException("Invalid file size.")
        }
        val cleanName = sanitizeFileName(rawName)
        val tempFile = File.createTempFile(".filzadrop-", ".part", dir)
        var committed = false
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
            val finalFile = uniqueFile(dir, cleanName)
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            committed = true
            return finalFile
        } finally {
            if (!committed) {
                tempFile.delete()
            }
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
