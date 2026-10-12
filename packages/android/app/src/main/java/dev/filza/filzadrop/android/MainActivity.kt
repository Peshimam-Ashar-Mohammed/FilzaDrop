package dev.filza.filzadrop.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
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
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("Tap to select a file") }
    var selectedFileSize by remember { mutableStateOf(0L) }

    var isReceiving by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Ready") }
    var relayEndpoint by remember { mutableStateOf("127.0.0.1:45823") }
    var dialogState by remember { mutableStateOf<TransferDialogState?>(null) }

    val peers = remember { mutableStateMapOf<String, PeerDevice>() }
    val codeDirectory = remember { ConcurrentHashMap<String, InetSocketAddress>() }
    val cancelFlag = remember { AtomicBoolean(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var activeServerSocket by remember { mutableStateOf<ServerSocket?>(null) }

    DisposableEffect(Unit) {
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
        onDispose { discoveryJob.cancel() }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            val meta = AndroidProtocol.queryFileMeta(context.contentResolver, uri)
            selectedFileName = meta.first
            selectedFileSize = meta.second
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

    // Custom Transfer Complete / Failed Dialog
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
                Text("Select a file and enter the receiver's 6-digit code", color = Color(0xFF786A96), fontSize = 11.sp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0B18))
                        .border(1.dp, Color(0xFF2B2042), RoundedCornerShape(8.dp))
                        .clickable(enabled = !isSending && !isReceiving) { filePicker.launch("*/*") }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedUri == null) "Tap to select a file" else "$selectedFileName (${selectedFileSize / 1024} KB)",
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
                            val uri = selectedUri ?: run {
                                filePicker.launch("*/*")
                                return@Button
                            }
                            if (inputCode.length != 6) {
                                statusText = "Enter a 6-digit pairing code first"
                                return@Button
                            }
                            isSending = true
                            cancelFlag.set(false)
                            statusText = "Sending $selectedFileName…"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    AndroidProtocol.sendFile(
                                        contentResolver = context.contentResolver,
                                        uri = uri,
                                        fileName = selectedFileName,
                                        fileSize = selectedFileSize,
                                        code = inputCode,
                                        knownTarget = codeDirectory[inputCode],
                                        relayEndpoint = relayEndpoint,
                                        cancelFlag = cancelFlag
                                    ) { sent, total ->
                                        progress = if (total > 0) sent.toFloat() / total else 0f
                                    }
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        progress = 1f
                                        statusText = "Sent $selectedFileName"
                                        dialogState = TransferDialogState(
                                            true,
                                            "Transfer Complete",
                                            "Delivered encrypted file: $selectedFileName"
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        if (!cancelFlag.get()) {
                                            statusText = "Send failed"
                                            dialogState = TransferDialogState(
                                                false,
                                                "Transfer Failed",
                                                e.message ?: "Could not reach receiver with code $inputCode."
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
                        Text("Send", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
                Text("Share this 6-digit code or QR with the sender", color = Color(0xFF786A96), fontSize = 11.sp)

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
                            qrBitmap = AndroidProtocol.generateQrBitmap("filzadrop://pair?code=$code", 240)
                            isReceiving = true
                            cancelFlag.set(false)
                            statusText = "Listening with code $code"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    val server = ServerSocket(AndroidProtocol.PORT)
                                    activeServerSocket = server
                                    val savedFile = AndroidProtocol.receiveFile(
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
                                        statusText = "Saved ${savedFile.name}"
                                        dialogState = TransferDialogState(
                                            true,
                                            "Transfer Complete",
                                            "Saved to Downloads:\n${savedFile.absolutePath}"
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isReceiving = false
                                        activeCode = "— — — — — —"
                                        qrBitmap = null
                                        if (!cancelFlag.get()) {
                                            statusText = "Receive failed"
                                            dialogState = TransferDialogState(
                                                false,
                                                "Transfer Failed",
                                                e.message ?: "Transfer interrupted."
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
                        Text("Listen for File", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
                Text("Devices", color = Color(0xFF8E7FB0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                                        statusText = "Selected code ${peer.activeCode}"
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
        return name to size
    }

    suspend fun runDiscoveryLoop(
        deviceName: String,
        getActiveCode: () -> String,
        onPeer: (PeerDevice) -> Unit
    ) = coroutineScope {
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
                    val msg = "FILZADROP|2|$deviceName|$PORT|Android|$code"
                    val bytes = msg.toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
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

    fun sendFile(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        code: String,
        knownTarget: InetSocketAddress?,
        relayEndpoint: String,
        cancelFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ) {
        val socket = Socket()
        if (knownTarget != null) {
            socket.connect(knownTarget, 6000)
        } else {
            val parts = relayEndpoint.split(":")
            socket.connect(InetSocketAddress(parts[0], parts[1].toInt()), 6000)
            val dOut = DataOutputStream(socket.getOutputStream())
            val dIn = DataInputStream(socket.getInputStream())
            dOut.writeUTF("SENDER")
            dOut.writeUTF(code)
            dOut.flush()
            if (dIn.readUTF() != "MATCHED") {
                socket.close()
                throw IOException("Code $code not found on LAN or Relay")
            }
        }

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
                contentResolver.openInputStream(uri)!!.use { input ->
                    out.writeUTF(fileName)
                    out.writeLong(fileSize)
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        if (cancelFlag.get()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent, fileSize)
                    }
                    out.flush()
                }
            }
        }
    }

    fun receiveFile(
        server: ServerSocket,
        code: String,
        cancelFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit
    ): File {
        server.soTimeout = 300_000
        val socket = server.accept()
        server.close()

        socket.use { s ->
            val raw = s.getInputStream()
            val salt = readExact(raw, SALT)
            val nonce = readExact(raw, NONCE)
            val cipher = createCipher(Cipher.DECRYPT_MODE, code, salt, nonce)

            DataInputStream(CipherInputStream(raw, cipher)).use { input ->
                val name = input.readUTF().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val size = input.readLong()
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloads, name)
                FileOutputStream(target).buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (got < size) {
                        if (cancelFlag.get()) {
                            target.delete()
                            throw IOException("Cancelled")
                        }
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), size - got).toInt())
                        if (n < 0) throw EOFException("Incomplete file")
                        out.write(buf, 0, n)
                        got += n
                        onProgress(got, size)
                    }
                }
                return target
            }
        }
    }

    private fun createCipher(mode: Int, code: String, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return b
    }
}
