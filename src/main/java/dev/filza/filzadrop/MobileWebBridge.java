package dev.filza.filzadrop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Local-network HTTP bridge for browser-to-PC, PC-to-browser, and Android-to-Android transfers.
 * Note: Uses local HTTP for zero-install phone browser compatibility on trusted local Wi-Fi/Hotspot networks.
 * For end-to-end AES-256-GCM encrypted transport, use the desktop or native Android app over TCP port 45821.
 */
public final class MobileWebBridge implements AutoCloseable {
    public static final int WEB_PORT = 45824;
    private static final long DEFAULT_SESSION_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_FAILED_UPLOAD_ATTEMPTS = 5;
    private static final int DEFAULT_MAX_FAILED_DOWNLOAD_ATTEMPTS = 10;
    private static final int DEFAULT_MAX_DOWNLOADS_PER_SESSION = 5;
    private static final int MAX_TRACKED_IPS = 256;

    private HttpServer server;
    private ExecutorService executor;
    private final Consumer<List<Path>> onFilesReceivedFromAndroid;
    private final Path receiveDirectory;

    private final Object uploadLock = new Object();
    private volatile String activeReceiveCode = "";
    private volatile long receiveExpiryMs = 0L;
    private volatile long maxUploadBytes = TransferService.MAX_BYTES;
    private volatile int maxFailedDownloadAttempts = DEFAULT_MAX_FAILED_DOWNLOAD_ATTEMPTS;
    private final AtomicInteger failedUploadAttempts = new AtomicInteger(0);
    private final Map<String, AtomicInteger> failedUploadAttemptsByIp = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failedDownloadAttemptsByIp = new ConcurrentHashMap<>();
    private final List<Path> currentBatchReceived = Collections.synchronizedList(new ArrayList<>());

    private static final class DownloadSession {
        final List<Path> files;
        final long expiryMs;
        int downloadsRemaining;

        DownloadSession(List<Path> files, long expiryMs, int downloadsRemaining) {
            this.files = files;
            this.expiryMs = expiryMs;
            this.downloadsRemaining = downloadsRemaining;
        }
    }

    private static final class BrowserReceiveSession implements Closeable {
        final String code;
        final Path stagingDir;
        final List<Path> stagedFiles = Collections.synchronizedList(new ArrayList<>());
        final long expiryMs;
        final TransferService.TransferHandle handle = new TransferService.TransferHandle();
        volatile ServerSocket tcpServer;
        volatile boolean ready = false;

        BrowserReceiveSession(String code, Path stagingDir, long expiryMs) {
            this.code = code;
            this.stagingDir = stagingDir;
            this.expiryMs = expiryMs;
        }

        @Override
        public void close() {
            handle.cancel();
            if (tcpServer != null) {
                try { tcpServer.close(); } catch (IOException ignored) {}
            }
        }
    }

    private final Map<String, DownloadSession> downloadSessions = new ConcurrentHashMap<>();
    private final Map<String, BrowserReceiveSession> browserReceiveSessions = new ConcurrentHashMap<>();
    private final Map<String, List<Path>> forwardBatches = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> downloadPickupFutures = new ConcurrentHashMap<>();

    public MobileWebBridge(Consumer<List<Path>> onFilesReceivedFromAndroid) {
        this(TransferService.receiveDir(), onFilesReceivedFromAndroid);
    }

    public MobileWebBridge(Path receiveDirectory, Consumer<List<Path>> onFilesReceivedFromAndroid) {
        this.receiveDirectory = receiveDirectory;
        this.onFilesReceivedFromAndroid = onFilesReceivedFromAndroid;
    }

    void setMaxUploadBytesForTest(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    void setMaxFailedDownloadAttemptsForTest(int maxAttempts) {
        this.maxFailedDownloadAttempts = maxAttempts;
    }

    public void setActiveReceiveCode(String code) {
        setActiveReceiveCode(code, DEFAULT_SESSION_TTL_MS);
    }

    public void setActiveReceiveCode(String code, long ttlMs) {
        synchronized (uploadLock) {
            String clean = code == null ? "" : code.trim();
            this.activeReceiveCode = clean;
            this.failedUploadAttempts.set(0);
            this.failedUploadAttemptsByIp.clear();
            this.currentBatchReceived.clear();
            this.receiveExpiryMs = clean.isEmpty() ? 0L : System.currentTimeMillis() + Math.max(1L, ttlMs);
        }
    }

    public void registerOutgoingFiles(String code, List<Path> files) {
        registerOutgoingFiles(code, files, DEFAULT_SESSION_TTL_MS, DEFAULT_MAX_DOWNLOADS_PER_SESSION);
    }

    public void registerOutgoingFiles(String code, List<Path> files, long ttlMs, int maxDownloads) {
        if (code == null || !code.trim().matches("\\d{6}") || files == null || files.isEmpty() || maxDownloads < 1) {
            return;
        }
        String clean = code.trim();
        List<Path> validFiles = files.stream()
                .filter(f -> f != null && Files.isRegularFile(f))
                .toList();
        if (!validFiles.isEmpty()) {
            downloadSessions.put(
                    clean,
                    new DownloadSession(validFiles, System.currentTimeMillis() + Math.max(1L, ttlMs), maxDownloads)
            );
            downloadPickupFutures.computeIfAbsent(clean, k -> new CompletableFuture<>());
            BrowserReceiveSession recvSession = browserReceiveSessions.get(clean);
            if (recvSession != null) {
                recvSession.ready = true;
            }
        }
    }

    public CompletableFuture<Void> getDownloadPickupFuture(String code) {
        if (code == null || !code.trim().matches("\\d{6}")) {
            return new CompletableFuture<>();
        }
        return downloadPickupFutures.computeIfAbsent(code.trim(), k -> new CompletableFuture<>());
    }

    public String getMobileUrl(String code) {
        String base = "http://" + TransferService.getLocalIpv4Hint() + ":" + getBoundPort();
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        return digits.isEmpty() ? base : base + "/?code=" + digits;
    }

    public void start() throws IOException {
        start(WEB_PORT);
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 16);
        server.createContext("/", this::handleIndex);
        server.createContext("/upload", this::handleUpload);
        server.createContext("/download", this::handleDownload);
        server.createContext("/receive-session", this::handleReceiveSession);
        server.createContext("/receive-status", this::handleReceiveStatus);
        executor = new ThreadPoolExecutor(
                2,
                16,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "filzadrop-mobile-bridge");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        server.setExecutor(executor);
        server.start();
    }

    public int getBoundPort() {
        return server != null ? server.getAddress().getPort() : WEB_PORT;
    }

    private static String remoteAddressKey(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    private static AtomicInteger counterForIp(Map<String, AtomicInteger> map, String ip) {
        AtomicInteger existing = map.get(ip);
        if (existing != null) {
            return existing;
        }
        if (map.size() >= MAX_TRACKED_IPS) {
            return new AtomicInteger(MAX_FAILED_UPLOAD_ATTEMPTS + DEFAULT_MAX_FAILED_DOWNLOAD_ATTEMPTS);
        }
        return map.computeIfAbsent(ip, k -> new AtomicInteger(0));
    }

    private boolean authorizeUpload(String suppliedCode, String remoteIp) {
        synchronized (uploadLock) {
            AtomicInteger ipFailures = counterForIp(failedUploadAttemptsByIp, remoteIp);
            if (ipFailures.get() >= MAX_FAILED_UPLOAD_ATTEMPTS) {
                return false;
            }
            String expected = this.activeReceiveCode;
            if (expected == null || expected.isEmpty()) {
                ipFailures.incrementAndGet();
                return false;
            }
            if (System.currentTimeMillis() > this.receiveExpiryMs) {
                this.activeReceiveCode = "";
                this.currentBatchReceived.clear();
                return false;
            }
            if (failedUploadAttempts.get() >= MAX_FAILED_UPLOAD_ATTEMPTS) {
                this.activeReceiveCode = "";
                this.currentBatchReceived.clear();
                return false;
            }
            if (suppliedCode == null || !suppliedCode.matches("\\d{6}")) {
                ipFailures.incrementAndGet();
                if (failedUploadAttempts.incrementAndGet() >= MAX_FAILED_UPLOAD_ATTEMPTS) {
                    this.activeReceiveCode = "";
                    this.currentBatchReceived.clear();
                }
                return false;
            }
            boolean matches = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    suppliedCode.getBytes(StandardCharsets.UTF_8)
            );
            if (!matches) {
                ipFailures.incrementAndGet();
                if (failedUploadAttempts.incrementAndGet() >= MAX_FAILED_UPLOAD_ATTEMPTS) {
                    this.activeReceiveCode = "";
                    this.currentBatchReceived.clear();
                }
                return false;
            }
            return true;
        }
    }

    private List<Path> reserveDownloadFiles(String suppliedCode, String remoteIp) {
        AtomicInteger ipFailures = counterForIp(failedDownloadAttemptsByIp, remoteIp);
        if (ipFailures.get() >= maxFailedDownloadAttempts) {
            return null;
        }
        if (suppliedCode == null || !suppliedCode.matches("\\d{6}")) {
            ipFailures.incrementAndGet();
            return null;
        }

        long now = System.currentTimeMillis();
        // Constant-time scan over active download sessions so timing does not leak code prefixes
        DownloadSession matchedSession = null;
        String matchedKey = null;
        byte[] suppliedBytes = suppliedCode.getBytes(StandardCharsets.UTF_8);

        for (Map.Entry<String, DownloadSession> entry : downloadSessions.entrySet()) {
            byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(keyBytes, suppliedBytes)) {
                matchedKey = entry.getKey();
                matchedSession = entry.getValue();
            }
        }

        if (matchedSession == null || matchedKey == null) {
            ipFailures.incrementAndGet();
            return null;
        }

        synchronized (matchedSession) {
            if (now > matchedSession.expiryMs || matchedSession.downloadsRemaining <= 0) {
                downloadSessions.remove(matchedKey, matchedSession);
                ipFailures.incrementAndGet();
                return null;
            }
            List<Path> validFiles = matchedSession.files.stream()
                    .filter(Files::isRegularFile)
                    .toList();
            if (validFiles.isEmpty()) {
                downloadSessions.remove(matchedKey, matchedSession);
                ipFailures.incrementAndGet();
                return null;
            }
            matchedSession.downloadsRemaining--;
            if (matchedSession.downloadsRemaining <= 0) {
                downloadSessions.remove(matchedKey, matchedSession);
            }
            return validFiles;
        }
    }

    private boolean hasAvailableDownload(String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        DownloadSession ds = downloadSessions.get(code);
        if (ds == null) {
            return false;
        }
        synchronized (ds) {
            if (System.currentTimeMillis() > ds.expiryMs || ds.downloadsRemaining <= 0) {
                return false;
            }
            return ds.files.stream().anyMatch(Files::isRegularFile);
        }
    }

    private void handleReceiveSession(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        String requestedCode = queryParam(query, "code");
        String code = (requestedCode != null && requestedCode.trim().matches("\\d{6}"))
                ? requestedCode.trim()
                : TransferService.newCode();

        if (hasAvailableDownload(code)) {
            respondJson(ex, 200, "{\"code\":\"" + code + "\",\"status\":\"ready\"}");
            return;
        }

        BrowserReceiveSession existing = browserReceiveSessions.get(code);
        if (existing != null && System.currentTimeMillis() <= existing.expiryMs) {
            String st = (existing.ready || hasAvailableDownload(code)) ? "ready" : "waiting";
            respondJson(ex, 200, "{\"code\":\"" + code + "\",\"status\":\"" + st + "\"}");
            return;
        }

        Path stagingDir = Files.createTempDirectory("filzadrop-brecv-");
        BrowserReceiveSession session = new BrowserReceiveSession(
                code,
                stagingDir,
                System.currentTimeMillis() + DEFAULT_SESSION_TTL_MS
        );
        browserReceiveSessions.put(code, session);

        // Also listen via TransferService (LAN TCP + GlobalRelayBridge Mobile Data / Wi-Fi)
        // so Native Android App or remote PC senders can send directly to this browser receive code.
        Thread listenerThread = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(0)) {
                session.tcpServer = ss;
                List<Path> received = TransferService.receiveFilesAnyNetwork(
                        ss,
                        code,
                        stagingDir,
                        session.handle,
                        (got, total) -> {}
                );
                if (received != null && !received.isEmpty()) {
                    registerOutgoingFiles(code, received);
                    session.ready = true;
                }
            } catch (Exception ignored) {
            }
        }, "filzadrop-browser-recv-" + code);
        listenerThread.setDaemon(true);
        listenerThread.start();

        respondJson(ex, 200, "{\"code\":\"" + code + "\",\"status\":\"waiting\"}");
    }

    private void handleReceiveStatus(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }
        String code = queryParam(ex.getRequestURI().getRawQuery(), "code").trim();
        if (!code.matches("\\d{6}")) {
            respondJson(ex, 400, "{\"status\":\"invalid\"}");
            return;
        }
        if (hasAvailableDownload(code)) {
            respondJson(ex, 200, "{\"status\":\"ready\"}");
            return;
        }
        BrowserReceiveSession session = browserReceiveSessions.get(code);
        if (session != null) {
            if (System.currentTimeMillis() > session.expiryMs) {
                browserReceiveSessions.remove(code, session);
                session.close();
                respondJson(ex, 200, "{\"status\":\"expired\"}");
                return;
            }
            respondJson(ex, 200, "{\"status\":\"" + (session.ready ? "ready" : "waiting") + "\"}");
            return;
        }
        respondJson(ex, 200, "{\"status\":\"waiting\"}");
    }

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>FilzaDrop</title>
                <style>
                  * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
                  body { margin: 0; min-height: 100vh; background: #0D0A14; color: #F2ECFF; display: flex; align-items: center; justify-content: center; padding: 18px; }
                  .container { width: 100%; max-width: 390px; }
                  .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
                  h1 { font-size: 17px; font-weight: 700; margin: 0; color: #F3EEFF; }
                  .badge { font-size: 10px; color: #A594C8; background: #151022; border: 1px solid #261D3A; padding: 4px 8px; border-radius: 6px; }
                  .pane { background: #130E1E; border: 1px solid #231A36; border-radius: 10px; padding: 16px; margin-bottom: 12px; }
                  .pane-title { font-size: 14px; font-weight: 700; margin: 0 0 4px 0; }
                  .pane-sub { font-size: 11px; color: #786A96; margin: 0 0 12px 0; line-height: 1.4; }
                  .code-display { width: 100%; background: #0F0B18; border: 1px solid #2B2042; border-radius: 8px; padding: 14px 10px; color: #E9D5FF; font-family: monospace; font-size: 22px; font-weight: 700; text-align: center; letter-spacing: 2px; margin-bottom: 10px; }
                  input[type="text"] { width: 100%; font-family: monospace; background: #0F0B18; border: 1px solid #2B2042; border-radius: 8px; padding: 10px; color: #F3EEFF; font-size: 15px; font-weight: 700; text-align: center; margin-bottom: 10px; outline: none; }
                  input[type="text"]:focus { border-color: #7C3AED; }
                  input[type="file"] { width: 100%; color: #A594C8; font-size: 12px; margin-bottom: 12px; }
                  button { width: 100%; background: #6D28D9; border: none; border-radius: 8px; padding: 11px; color: #fff; font-weight: 600; font-size: 12px; cursor: pointer; }
                  button:hover { background: #7C3AED; }
                  .btn-row { display: flex; gap: 8px; }
                  .btn-secondary { background: #1E1533; border: 1px solid #3B2863; color: #E9D5FF; }
                  .btn-secondary:hover { background: #2A1D47; }
                  .btn-cancel { background: #2A1220; border: 1px solid #6E223D; color: #FDA4AF; margin-top: 8px; display: none; }
                  .status-note { font-size: 11px; color: #C4B5FD; margin-top: 8px; text-align: center; display: none; line-height: 1.4; }
                  .modal-backdrop { display: none; position: fixed; inset: 0; background: rgba(6, 4, 10, 0.78); align-items: center; justify-content: center; padding: 20px; }
                  .modal { width: 100%; max-width: 320px; background: #130E1E; border: 1px solid #32244F; border-radius: 12px; padding: 18px; }
                  .modal-title { font-size: 15px; font-weight: 700; margin-bottom: 6px; }
                  .modal-body { font-size: 12px; color: #A594C8; margin-bottom: 14px; word-break: break-word; line-height: 1.4; }
                </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header">
                      <h1>FilzaDrop</h1>
                      <span class="badge">Android &amp; PC</span>
                    </div>
                    <div class="pane">
                      <div class="pane-title">Send to Android or PC</div>
                      <div class="pane-sub">Select one or more files and enter the receiver's 6-digit code</div>
                      <input type="file" id="fileInput" multiple />
                      <input type="text" id="sendCode" maxlength="6" placeholder="6-digit code" />
                      <button id="sendBtn" onclick="sendFiles()">Send Files</button>
                    </div>
                    <div class="pane">
                      <div class="pane-title">Receive from Android or PC</div>
                      <div class="pane-sub">Tap Receive from Android or Receive from PC to get a 6-digit code, or enter a sender's code first</div>
                      <div class="code-display" id="activeRecvCodeBox">— — — — — —</div>
                      <input type="text" id="recvCode" maxlength="6" placeholder="Optional: sender's 6-digit code" />
                      <div class="btn-row">
                        <button id="recvAndroidBtn" onclick="startReceive('Android')">Receive from Android</button>
                        <button id="recvPcBtn" class="btn-secondary" onclick="startReceive('PC')">Receive from PC</button>
                      </div>
                      <button id="cancelRecvBtn" class="btn-cancel" onclick="cancelReceive()">Cancel Receiving</button>
                      <div id="recvStatusNote" class="status-note"></div>
                    </div>
                  </div>
                  <div class="modal-backdrop" id="modalBackdrop">
                    <div class="modal">
                      <div class="modal-title" id="modalTitle"></div>
                      <div class="modal-body" id="modalBody"></div>
                      <button onclick="closeModal()">Done</button>
                    </div>
                  </div>
                  <script>
                    let pollTimer = null;
                    let activeListeningCode = '';
                    const params = new URLSearchParams(window.location.search);
                    const urlCode = (params.get('code') || '').replace(/\\D/g, '').slice(0, 6);
                    if (urlCode) {
                      document.getElementById('sendCode').value = urlCode;
                      document.getElementById('recvCode').value = urlCode;
                      document.getElementById('activeRecvCodeBox').innerText = urlCode.slice(0, 3) + ' ' + urlCode.slice(3);
                      if (window.history && window.history.replaceState) {
                        window.history.replaceState({}, document.title, '/');
                      }
                    }
                    function showModal(title, body) {
                      document.getElementById('modalTitle').innerText = title;
                      document.getElementById('modalBody').innerText = body;
                      document.getElementById('modalBackdrop').style.display = 'flex';
                    }
                    function closeModal() {
                      document.getElementById('modalBackdrop').style.display = 'none';
                    }
                    async function sendFiles() {
                      const code = document.getElementById('sendCode').value.replace(/\\D/g, '').trim();
                      const files = document.getElementById('fileInput').files;
                      const btn = document.getElementById('sendBtn');
                      if (code.length !== 6 || files.length === 0) {
                        showModal('Missing Details', 'Select at least one file and enter a 6-digit pairing code.');
                        return;
                      }
                      btn.disabled = true;
                      try {
                        for (let i = 0; i < files.length; i++) {
                          const f = files[i];
                          btn.innerText = 'Sending (' + (i + 1) + '/' + files.length + ')...';
                          const isLast = (i === files.length - 1) ? '1' : '0';
                          const res = await fetch('/upload?name=' + encodeURIComponent(f.name) + '&last=' + isLast + '&forward=1', {
                            method: 'POST',
                            headers: { 'X-FilzaDrop-Code': code },
                            body: f
                          });
                          const text = await res.text();
                          if (!res.ok) {
                            showModal('Transfer Failed', text);
                            btn.disabled = false;
                            btn.innerText = 'Send Files';
                            return;
                          }
                        }
                        showModal('Transfer Complete', 'Delivered ' + files.length + ' file(s) with code ' + code + '.');
                      } catch (e) {
                        showModal('Transfer Failed', 'Could not reach receiver.');
                      } finally {
                        btn.disabled = false;
                        btn.innerText = 'Send Files';
                      }
                    }
                    async function startReceive(sourceLabel) {
                      const typedCode = document.getElementById('recvCode').value.replace(/\\D/g, '').trim();
                      const statusNote = document.getElementById('recvStatusNote');
                      const cancelBtn = document.getElementById('cancelRecvBtn');
                      const codeBox = document.getElementById('activeRecvCodeBox');
                      if (pollTimer) {
                        clearInterval(pollTimer);
                        pollTimer = null;
                      }
                      try {
                        const query = (typedCode.length === 6) ? ('?code=' + encodeURIComponent(typedCode)) : '';
                        const res = await fetch('/receive-session' + query, { method: 'POST' });
                        if (!res.ok) {
                          showModal('Receive Error', 'Could not start receive session.');
                          return;
                        }
                        const data = await res.json();
                        const code = data.code;
                        activeListeningCode = code;
                        codeBox.innerText = code.slice(0, 3) + ' ' + code.slice(3);
                        document.getElementById('recvCode').value = code;
                        if (data.status === 'ready') {
                          triggerDownload(code);
                          return;
                        }
                        statusNote.style.display = 'block';
                        statusNote.innerText = 'Listening for ' + sourceLabel + ' · Enter code ' + code + ' on the sending ' + sourceLabel + ' and tap Send Files…';
                        cancelBtn.style.display = 'block';
                        pollTimer = setInterval(async () => {
                          try {
                            const pollRes = await fetch('/receive-status?code=' + encodeURIComponent(code));
                            if (!pollRes.ok) return;
                            const pollData = await pollRes.json();
                            if (pollData.status === 'ready') {
                              cancelReceive();
                              triggerDownload(code);
                            } else if (pollData.status === 'expired') {
                              cancelReceive();
                              showModal('Session Expired', 'Receive session timed out. Tap Receive again to generate a new code.');
                            }
                          } catch (err) {}
                        }, 1000);
                      } catch (e) {
                        showModal('Receive Error', 'Could not reach FilzaDrop bridge.');
                      }
                    }
                    function cancelReceive() {
                      if (pollTimer) {
                        clearInterval(pollTimer);
                        pollTimer = null;
                      }
                      activeListeningCode = '';
                      document.getElementById('recvStatusNote').style.display = 'none';
                      document.getElementById('cancelRecvBtn').style.display = 'none';
                      document.getElementById('activeRecvCodeBox').innerText = '— — — — — —';
                    }
                    function triggerDownload(code) {
                      showModal('Transfer Complete', 'Downloading file(s) for code ' + code + ' to your device…');
                      window.location.href = '/download?code=' + encodeURIComponent(code);
                    }
                  </script>
                </body>
                </html>
                """;
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private boolean isPcActiveReceiveMatch(String suppliedCode) {
        synchronized (uploadLock) {
            String expected = this.activeReceiveCode;
            if (expected == null || expected.isEmpty() || suppliedCode == null || !suppliedCode.matches("\\d{6}")) {
                return false;
            }
            if (System.currentTimeMillis() > this.receiveExpiryMs) {
                return false;
            }
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    suppliedCode.getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private void handleUpload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }

        String remoteIp = remoteAddressKey(ex);
        String query = ex.getRequestURI().getRawQuery();
        String headerCode = ex.getRequestHeaders().getFirst("X-FilzaDrop-Code");
        String code = (headerCode != null && !headerCode.isBlank())
                ? headerCode.trim()
                : queryParam(query, "code");
        String rawName = queryParam(query, "name");
        boolean isLast = !"0".equals(queryParam(query, "last"));
        boolean allowForward = "1".equals(queryParam(query, "forward"));

        BrowserReceiveSession browserSession = (code != null && code.matches("\\d{6}"))
                ? browserReceiveSessions.get(code)
                : null;
        if (browserSession != null && System.currentTimeMillis() > browserSession.expiryMs) {
            browserReceiveSessions.remove(code, browserSession);
            browserSession.close();
            browserSession = null;
        }

        boolean isPcTarget = isPcActiveReceiveMatch(code);

        // If this upload targets an Android browser receive session or Android/PC peer forwarding
        if (!isPcTarget && (browserSession != null || (allowForward && code != null && code.matches("\\d{6}")))) {
            AtomicInteger ipFailures = counterForIp(failedUploadAttemptsByIp, remoteIp);
            if (ipFailures.get() >= MAX_FAILED_UPLOAD_ATTEMPTS) {
                try { ex.getRequestBody().close(); } catch (IOException ignored) {}
                respondText(ex, 403, "Unauthorized or rate-limited pairing code.");
                return;
            }
            handlePeerOrBrowserSessionUpload(ex, code, rawName, isLast, browserSession);
            return;
        }

        if (!authorizeUpload(code, remoteIp)) {
            try { ex.getRequestBody().close(); } catch (IOException ignored) {}
            respondText(ex, 403, "Unauthorized or expired pairing code.");
            return;
        }

        String safeFileName;
        try {
            safeFileName = TransferService.safeName(rawName.isEmpty() ? "android-file" : rawName);
        } catch (IOException e) {
            try { ex.getRequestBody().close(); } catch (IOException ignored) {}
            respondText(ex, 400, "Invalid filename.");
            return;
        }

        Files.createDirectories(receiveDirectory);
        Path temp = Files.createTempFile(receiveDirectory, ".filzadrop-mobile-", ".part");
        boolean committed = false;

        try {
            long totalRead = 0L;
            long limit = this.maxUploadBytes;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = ex.getRequestBody();
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalRead += n;
                    if (totalRead > limit) {
                        throw new SizeLimitExceededException("Upload exceeds maximum allowed size.");
                    }
                    out.write(buf, 0, n);
                }
            }

            List<Path> completedBatch = null;
            Path target;
            synchronized (uploadLock) {
                // Re-verify session is still active and matches before committing the file
                String currentExpected = this.activeReceiveCode;
                if (currentExpected == null || currentExpected.isEmpty()
                        || System.currentTimeMillis() > this.receiveExpiryMs
                        || !MessageDigest.isEqual(
                                currentExpected.getBytes(StandardCharsets.UTF_8),
                                code.getBytes(StandardCharsets.UTF_8))) {
                    throw new SecurityException("Receive session expired or invalidated during transfer.");
                }

                target = TransferService.uniquePath(receiveDirectory, safeFileName);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target);
                }
                committed = true;
                currentBatchReceived.add(target);

                if (isLast) {
                    completedBatch = new ArrayList<>(currentBatchReceived);
                    this.activeReceiveCode = "";
                    this.currentBatchReceived.clear();
                }
            }

            if (completedBatch != null && onFilesReceivedFromAndroid != null && !completedBatch.isEmpty()) {
                onFilesReceivedFromAndroid.accept(completedBatch);
            }

            respondText(ex, 200, "Saved to PC: " + target.getFileName());
        } catch (SizeLimitExceededException e) {
            respondText(ex, 413, e.getMessage());
        } catch (SecurityException e) {
            respondText(ex, 403, e.getMessage());
        } catch (IOException e) {
            respondText(ex, 400, "Upload interrupted or failed.");
        } finally {
            if (!committed) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private void handlePeerOrBrowserSessionUpload(
            HttpExchange ex,
            String code,
            String rawName,
            boolean isLast,
            BrowserReceiveSession browserSession
    ) throws IOException {
        String safeFileName;
        try {
            safeFileName = TransferService.safeName(rawName.isEmpty() ? "android-file" : rawName);
        } catch (IOException e) {
            try { ex.getRequestBody().close(); } catch (IOException ignored) {}
            respondText(ex, 400, "Invalid filename.");
            return;
        }

        Path stageDir = (browserSession != null)
                ? browserSession.stagingDir
                : Files.createTempDirectory("filzadrop-fwd-");
        Files.createDirectories(stageDir);
        Path temp = Files.createTempFile(stageDir, ".filzadrop-peer-", ".part");
        boolean committed = false;

        try {
            long totalRead = 0L;
            long limit = this.maxUploadBytes;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = ex.getRequestBody();
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalRead += n;
                    if (totalRead > limit) {
                        throw new SizeLimitExceededException("Upload exceeds maximum allowed size.");
                    }
                    out.write(buf, 0, n);
                }
            }

            Path target = TransferService.uniquePath(stageDir, safeFileName);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            committed = true;

            if (browserSession != null) {
                browserSession.stagedFiles.add(target);
                if (isLast) {
                    List<Path> batch = new ArrayList<>(browserSession.stagedFiles);
                    registerOutgoingFiles(code, batch);
                    browserSession.ready = true;
                }
                respondText(ex, 200, "Delivered to Android: " + target.getFileName());
            } else {
                List<Path> batch = forwardBatches.computeIfAbsent(
                        code,
                        k -> Collections.synchronizedList(new ArrayList<>())
                );
                batch.add(target);
                if (isLast) {
                    List<Path> completed = new ArrayList<>(batch);
                    forwardBatches.remove(code);
                    registerOutgoingFiles(code, completed);
                    // Also forward asynchronously to Native Android App / remote PC if listening on LAN or Mobile Data
                    Thread fwd = new Thread(() -> {
                        try {
                            TransferService.sendFilesByCode(
                                    completed,
                                    code,
                                    null,
                                    new TransferService.TransferHandle(),
                                    (s, t) -> {}
                            );
                        } catch (Exception ignored) {
                        }
                    }, "filzadrop-web-forward-" + code);
                    fwd.setDaemon(true);
                    fwd.start();
                }
                respondText(ex, 200, "Delivered: " + target.getFileName());
            }
        } catch (SizeLimitExceededException e) {
            respondText(ex, 413, e.getMessage());
        } catch (IOException e) {
            respondText(ex, 400, "Upload interrupted or failed.");
        } finally {
            if (!committed) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private void handleDownload(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }
        String remoteIp = remoteAddressKey(ex);
        String headerCode = ex.getRequestHeaders().getFirst("X-FilzaDrop-Code");
        String code = (headerCode != null && !headerCode.isBlank())
                ? headerCode.trim()
                : queryParam(ex.getRequestURI().getRawQuery(), "code");

        List<Path> validFiles = reserveDownloadFiles(code, remoteIp);
        if (validFiles == null) {
            respondText(ex, 403, "Invalid, expired, or rate-limited pairing code.");
            return;
        }

        CompletableFuture<Void> pickupFuture = (code != null) ? downloadPickupFutures.get(code.trim()) : null;

        ex.getResponseHeaders().set("Cache-Control", "no-store");
        try {
            if (validFiles.size() == 1) {
                Path file = validFiles.get(0);
                String safe = TransferService.safeName(file.getFileName().toString());
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + safe + "\"");
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, Files.size(file));
                try (OutputStream out = ex.getResponseBody();
                     InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    in.transferTo(out);
                }
            } else {
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"FilzaDrop-Files.zip\"");
                ex.getResponseHeaders().set("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, 0);
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(ex.getResponseBody()))) {
                    for (Path file : validFiles) {
                        String entryName = TransferService.safeName(file.getFileName().toString());
                        zos.putNextEntry(new ZipEntry(entryName));
                        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                            in.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                }
            }
            if (pickupFuture != null) {
                pickupFuture.complete(null);
            }
        } catch (IOException e) {
            if (pickupFuture != null) {
                pickupFuture.complete(null);
            }
            throw e;
        }
    }

    private static final class SizeLimitExceededException extends IOException {
        SizeLimitExceededException(String message) {
            super(message);
        }
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return "";
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respondJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void respondText(HttpExchange ex, int status, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        for (BrowserReceiveSession s : browserReceiveSessions.values()) {
            s.close();
        }
        browserReceiveSessions.clear();
        forwardBatches.clear();
        downloadPickupFutures.clear();
        downloadSessions.clear();
        failedUploadAttemptsByIp.clear();
        failedDownloadAttemptsByIp.clear();
    }
}
