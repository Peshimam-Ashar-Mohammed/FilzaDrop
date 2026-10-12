package dev.filza.filzadrop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MobileWebBridge implements AutoCloseable {
    public static final int WEB_PORT = 45824;
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private HttpServer server;
    private final Consumer<List<Path>> onFilesReceivedFromAndroid;
    private final Path receiveDirectory;

    private volatile String activeReceiveCode = "";
    private volatile long receiveExpiryMs = 0L;
    private final AtomicInteger failedUploadAttempts = new AtomicInteger(0);
    private final List<Path> currentBatchReceived = Collections.synchronizedList(new ArrayList<>());

    private record DownloadSession(List<Path> files, long expiryMs, AtomicInteger downloadsRemaining) {}
    private final Map<String, DownloadSession> downloadSessions = new ConcurrentHashMap<>();

    public MobileWebBridge(Consumer<List<Path>> onFilesReceivedFromAndroid) {
        this(TransferService.receiveDir(), onFilesReceivedFromAndroid);
    }

    public MobileWebBridge(Path receiveDirectory, Consumer<List<Path>> onFilesReceivedFromAndroid) {
        this.receiveDirectory = receiveDirectory;
        this.onFilesReceivedFromAndroid = onFilesReceivedFromAndroid;
    }

    public synchronized void setActiveReceiveCode(String code) {
        String clean = code == null ? "" : code.trim();
        this.activeReceiveCode = clean;
        this.failedUploadAttempts.set(0);
        this.currentBatchReceived.clear();
        this.receiveExpiryMs = clean.isEmpty() ? 0L : System.currentTimeMillis() + SESSION_TTL_MS;
    }

    public void registerOutgoingFiles(String code, List<Path> files) {
        if (code == null || code.isBlank() || files == null || files.isEmpty()) {
            return;
        }
        List<Path> validFiles = files.stream()
                .filter(f -> f != null && Files.isRegularFile(f))
                .toList();
        if (!validFiles.isEmpty()) {
            downloadSessions.put(
                    code.trim(),
                    new DownloadSession(validFiles, System.currentTimeMillis() + SESSION_TTL_MS, new AtomicInteger(5))
            );
        }
    }

    public String getMobileUrl(String code) {
        String base = "http://" + TransferService.getLocalIpv4Hint() + ":" + WEB_PORT;
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        return digits.isEmpty() ? base : base + "/?code=" + digits;
    }

    public void start() throws IOException {
        start(WEB_PORT);
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/upload", this::handleUpload);
        server.createContext("/complete-batch", this::handleCompleteBatch);
        server.createContext("/download", this::handleDownload);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "filzadrop-mobile-bridge");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public int getBoundPort() {
        return server != null ? server.getAddress().getPort() : WEB_PORT;
    }

    private boolean isAuthorizedUploadCode(String suppliedCode) {
        String expected = this.activeReceiveCode;
        if (expected == null || expected.isEmpty()) {
            return false; // Explicitly reject when no receive session is active
        }
        if (System.currentTimeMillis() > this.receiveExpiryMs) {
            setActiveReceiveCode("");
            return false;
        }
        if (failedUploadAttempts.get() >= MAX_FAILED_ATTEMPTS) {
            setActiveReceiveCode("");
            return false;
        }
        if (suppliedCode == null || !suppliedCode.matches("\\d{6}")) {
            failedUploadAttempts.incrementAndGet();
            return false;
        }
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                suppliedCode.getBytes(StandardCharsets.UTF_8)
        );
        if (!matches) {
            if (failedUploadAttempts.incrementAndGet() >= MAX_FAILED_ATTEMPTS) {
                setActiveReceiveCode("");
            }
            return false;
        }
        return true;
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
                  .pane-sub { font-size: 11px; color: #786A96; margin: 0 0 12px 0; }
                  input[type="text"] { width: 100%; font-family: monospace; background: #0F0B18; border: 1px solid #2B2042; border-radius: 8px; padding: 10px; color: #F3EEFF; font-size: 16px; font-weight: 700; text-align: center; margin-bottom: 10px; outline: none; }
                  input[type="text"]:focus { border-color: #7C3AED; }
                  input[type="file"] { width: 100%; color: #A594C8; font-size: 12px; margin-bottom: 12px; }
                  button { width: 100%; background: #6D28D9; border: none; border-radius: 8px; padding: 11px; color: #fff; font-weight: 600; font-size: 12px; cursor: pointer; }
                  button:hover { background: #7C3AED; }
                  .modal-backdrop { display: none; position: fixed; inset: 0; background: rgba(6, 4, 10, 0.78); align-items: center; justify-content: center; padding: 20px; }
                  .modal { width: 100%; max-width: 320px; background: #130E1E; border: 1px solid #32244F; border-radius: 12px; padding: 18px; }
                  .modal-title { font-size: 15px; font-weight: 700; margin-bottom: 6px; }
                  .modal-body { font-size: 12px; color: #A594C8; margin-bottom: 14px; word-break: break-word; }
                </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header">
                      <h1>FilzaDrop</h1>
                      <span class="badge">Direct P2P · No Cloud</span>
                    </div>
                    <div class="pane">
                      <div class="pane-title">Send to PC</div>
                      <div class="pane-sub">Select one or more files and confirm the 6-digit code</div>
                      <input type="file" id="fileInput" multiple />
                      <input type="text" id="sendCode" maxlength="6" placeholder="6-digit code" />
                      <button id="sendBtn" onclick="sendFiles()">Send Files</button>
                    </div>
                    <div class="pane">
                      <div class="pane-title">Receive from PC</div>
                      <div class="pane-sub">Enter the PC's 6-digit code to download</div>
                      <input type="text" id="recvCode" maxlength="6" placeholder="6-digit code" />
                      <button onclick="downloadFile()">Receive Files</button>
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
                    const params = new URLSearchParams(window.location.search);
                    const urlCode = params.get('code') || '';
                    if (urlCode) {
                      document.getElementById('sendCode').value = urlCode;
                      document.getElementById('recvCode').value = urlCode;
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
                      const code = document.getElementById('sendCode').value.trim();
                      const files = document.getElementById('fileInput').files;
                      const btn = document.getElementById('sendBtn');
                      if (!code || files.length === 0) {
                        showModal('Missing Details', 'Select at least one file and enter the 6-digit pairing code.');
                        return;
                      }
                      btn.disabled = true;
                      try {
                        for (let i = 0; i < files.length; i++) {
                          const f = files[i];
                          btn.innerText = 'Sending (' + (i + 1) + '/' + files.length + ')...';
                          const isLast = (i === files.length - 1) ? '1' : '0';
                          const res = await fetch('/upload?code=' + encodeURIComponent(code) + '&name=' + encodeURIComponent(f.name) + '&last=' + isLast, {
                            method: 'POST', body: f
                          });
                          const text = await res.text();
                          if (!res.ok) {
                            showModal('Transfer Failed', text);
                            btn.disabled = false;
                            btn.innerText = 'Send Files';
                            return;
                          }
                        }
                        showModal('Transfer Complete', 'Delivered ' + files.length + ' file(s) directly to your PC.');
                      } catch (e) {
                        showModal('Transfer Failed', 'Could not reach PC.');
                      } finally {
                        btn.disabled = false;
                        btn.innerText = 'Send Files';
                      }
                    }
                    function downloadFile() {
                      const code = document.getElementById('recvCode').value.trim();
                      if (!code) {
                        showModal('Missing Code', 'Enter the 6-digit pairing code.');
                        return;
                      }
                      window.location.href = '/download?code=' + encodeURIComponent(code);
                    }
                  </script>
                </body>
                </html>
                """;
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleUpload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }

        String query = ex.getRequestURI().getRawQuery();
        String code = queryParam(query, "code");
        String rawName = queryParam(query, "name");
        boolean isLast = !"0".equals(queryParam(query, "last"));

        if (!isAuthorizedUploadCode(code)) {
            try { ex.getRequestBody().close(); } catch (IOException ignored) {}
            respondText(ex, 403, "Unauthorized or expired pairing code. Click 'Listen for File' on PC first.");
            return;
        }

        String safeFileName;
        try {
            safeFileName = TransferService.safeName(rawName.isEmpty() ? "android-file" : rawName);
        } catch (IOException e) {
            respondText(ex, 400, "Invalid filename.");
            return;
        }

        Files.createDirectories(receiveDirectory);
        Path temp = Files.createTempFile(receiveDirectory, ".filzadrop-mobile-", ".part");
        boolean committed = false;

        try {
            long totalRead = 0L;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = ex.getRequestBody();
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalRead += n;
                    if (totalRead > TransferService.MAX_BYTES) {
                        throw new IOException("Upload exceeds maximum allowed size.");
                    }
                    out.write(buf, 0, n);
                }
            }

            Path target = TransferService.uniquePath(receiveDirectory, safeFileName);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            committed = true;
            currentBatchReceived.add(target);

            if (isLast) {
                List<Path> completedBatch = new ArrayList<>(currentBatchReceived);
                setActiveReceiveCode(""); // Single-use session invalidated on completion
                if (onFilesReceivedFromAndroid != null && !completedBatch.isEmpty()) {
                    onFilesReceivedFromAndroid.accept(completedBatch);
                }
            }

            respondText(ex, 200, "Saved to PC: " + target.getFileName());
        } catch (IOException e) {
            respondText(ex, 413, e.getMessage());
        } finally {
            if (!committed) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private void handleCompleteBatch(HttpExchange ex) throws IOException {
        respondText(ex, 200, "OK");
    }

    private void handleDownload(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 405, "Method Not Allowed");
            return;
        }
        String code = queryParam(ex.getRequestURI().getRawQuery(), "code");
        if (code == null || !code.matches("\\d{6}")) {
            respondText(ex, 403, "Invalid 6-digit pairing code.");
            return;
        }

        DownloadSession session = downloadSessions.get(code);
        if (session == null || System.currentTimeMillis() > session.expiryMs()) {
            downloadSessions.remove(code);
            respondText(ex, 404, "Expired or inactive download code.");
            return;
        }
        if (session.downloadsRemaining().decrementAndGet() < 0) {
            downloadSessions.remove(code);
            respondText(ex, 403, "Download limit reached for this pairing code.");
            return;
        }

        List<Path> validFiles = session.files().stream()
                .filter(Files::isRegularFile)
                .toList();
        if (validFiles.isEmpty()) {
            downloadSessions.remove(code);
            respondText(ex, 404, "Shared file is no longer available.");
            return;
        }

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

    private static void respondText(HttpExchange ex, int status, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
