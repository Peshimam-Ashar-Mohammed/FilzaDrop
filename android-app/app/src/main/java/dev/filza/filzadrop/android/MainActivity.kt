package dev.filza.filzadrop.android

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        primary = Color(0xFFA855F7),
        secondary = Color(0xFF6D28D9),
        background = Color(0xFF0B0714),
        surface = Color(0xFF160E29)
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

@Composable
fun FilzaDropScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var activeCode by remember { mutableStateOf("• • • • • •") }
    var inputCode by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("Tap to choose file") }
    var selectedFileSize by remember { mutableStateOf(0L) }

    var isReceiving by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Ready to share with Windows & Android") }
    var relayEndpoint by remember { mutableStateOf("127.0.0.1:45823") }

    val peers = remember { mutableStateMapOf<String, PeerDevice>() }
    val codeDirectory = remember { ConcurrentHashMap<String, InetSocketAddress>() }
    val cancelFlag = remember { AtomicBoolean(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var activeServerSocket by remember { mutableStateOf<ServerSocket?>(null) }

    // Background UDP discovery for Android <-> Windows & Android <-> Android
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
        activeCode = "• • • • • •"
        progress = 0f
        statusText = "Transfer cancelled."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E081A), Color(0xFF170E2C), Color(0xFF0B0714))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Minimalist Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFA855F7), Color(0xFF6D28D9)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("F", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Column {
                    Text("FilzaDrop", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        "ANDROID • WINDOWS • ANY NETWORK",
                        color = Color(0xFF9D88C9),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // RECEIVE CARD
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF160F29))
                    .border(1.dp, Color(0xFF332255), RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("↓  RECEIVE", color = Color(0xFFC084FC), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Text("Receive Files", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0D0818))
                        .border(1.dp, Color(0xFF482E7A), RoundedCornerShape(16.dp))
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        activeCode,
                        color = Color(0xFFE9D5FF),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 5.sp
                    )
                }

                if (!isReceiving) {
                    Button(
                        onClick = {
                            if (isSending) return@Button
                            val code = String.format("%06d", SecureRandom().nextInt(1_000_000))
                            activeCode = "${code.substring(0, 3)} ${code.substring(3)}"
                            isReceiving = true
                            cancelFlag.set(false)
                            statusText = "Waiting for sender with code $code…"
                            activeJob = scope.launch(Dispatchers.IO) {
                                try {
                                    val server = ServerSocket(AndroidProtocol.PORT)
                                    activeServerSocket = server
                                    val savedFile = AndroidProtocol.receiveFile(
                                        server = server,
                                        code = code,
                                        relayEndpoint = relayEndpoint,
                                        cancelFlag = cancelFlag
                                    ) { got, total ->
                                        progress = if (total > 0) got.toFloat() / total else 0f
                                    }
                                    withContext(Dispatchers.Main) {
                                        isReceiving = false
                                        activeCode = "• • • • • •"
                                        progress = 1f
                                        statusText = "Saved to Downloads: ${savedFile.name}"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        if (!cancelFlag.get()) {
                                            statusText = "Receive failed: ${e.message}"
                                        }
                                        isReceiving = false
                                        activeCode = "• • • • • •"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                    ) {
                        Text("Generate Code & Listen", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    }
                } else {
                    Button(
                        onClick = { cancelActive() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBE123C))
                    ) {
                        Text("Cancel Receive", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            // SEND CARD
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF160F29))
                    .border(1.dp, Color(0xFF332255), RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("↑  SEND", color = Color(0xFFC084FC), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Text("Send Files", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF100A1E))
                        .border(1.dp, Color(0xFF483278), RoundedCornerShape(14.dp))
                        .clickable(enabled = !isSending && !isReceiving) { filePicker.launch("*/*") }
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedUri == null) "Tap to choose file" else "$selectedFileName (${selectedFileSize / 1024} KB)",
                        color = Color(0xFFC4B5FD),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { v -> inputCode = v.filter { it.isDigit() }.take(6) },
                    placeholder = { Text("Enter 6-digit pairing code", color = Color(0xFF64538A)) },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 4.sp
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                if (!isSending) {
                    Button(
                        onClick = {
                            val uri = selectedUri ?: run {
                                filePicker.launch("*/*")
                                return@Button
                            }
                            if (inputCode.length != 6) {
                                statusText = "Enter the 6-digit pairing code first."
                                return@Button
                            }
                            isSending = true
                            cancelFlag.set(false)
                            statusText = "Connecting to code $inputCode…"
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
                                        statusText = "Successfully sent $selectedFileName!"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        if (!cancelFlag.get()) {
                                            statusText = "Send failed: ${e.message}"
                                        }
                                        isSending = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                    ) {
                        Text("Send Now", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    }
                } else {
                    Button(
                        onClick = { cancelActive() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBE123C))
                    ) {
                        Text("Cancel Send", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            // STATUS & PROGRESS
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF130C22))
                    .border(1.dp, Color(0xFF2A1D47), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(statusText, color = Color(0xFFEDE4FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = Color(0xFFA855F7),
                    trackColor = Color(0xFF24183D)
                )
            }

            // NEARBY DEVICES
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF130C22))
                    .border(1.dp, Color(0xFF2A1D47), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "NEARBY WINDOWS & ANDROID DEVICES",
                    color = Color(0xFF9D88C9),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (peers.isEmpty()) {
                    Text("Listening for nearby devices automatically…", color = Color(0xFF8D7CB0), fontSize = 12.sp)
                } else {
                    peers.values.forEach { peer ->
                        val label = buildString {
                            append(if (peer.platform.equals("Android", true)) "📱 " else "💻 ")
                            append(peer.name)
                            if (peer.activeCode.isNotEmpty()) append("  •  Code: ${peer.activeCode}")
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1E1436))
                                .border(1.dp, Color(0xFF463075), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (peer.activeCode.isNotEmpty()) {
                                        inputCode = peer.activeCode
                                        statusText = "Auto-filled code ${peer.activeCode} from ${peer.name}"
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Text(label, color = Color(0xFFE9D5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
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
        relayEndpoint: String,
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
