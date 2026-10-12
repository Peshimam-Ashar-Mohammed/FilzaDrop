package dev.filza.filzadrop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Built-in Mobile/Android Web Bridge on port 45824.
 * Allows any Android phone (or tablet) to send/receive files with Windows or another Android phone
 * using only the 6-digit pairing code — either via the Native Android app or any mobile browser.
 */
public final class MobileWebBridge implements AutoCloseable {
    public static final int WEB_PORT = 45824;

    private HttpServer server;
    private final Consumer<Path> onFileReceivedFromAndroid;
    private volatile String activeReceiveCode = "";
    private final Map<String, Path> sharedFilesByCode = new ConcurrentHashMap<>();

    public MobileWebBridge(Consumer<Path> onFileReceivedFromAndroid) {
        this.onFileReceivedFromAndroid = onFileReceivedFromAndroid;
    }

    public void setActiveReceiveCode(String code) {
        this.activeReceiveCode = code == null ? "" : code.trim();
    }

    public void registerOutgoingFile(String code, Path file) {
        if (code != null && file != null) {
            sharedFilesByCode.put(code.trim(), file);
        }
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(WEB_PORT), 0);
            server.createContext("/", this::handleIndex);
            server.createContext("/upload", this::handleUpload);
            server.createContext("/download", this::handleDownload);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "filzadrop-mobile-bridge");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        } catch (IOException ignored) {
        }
    }

    private void handleIndex(HttpExchange ex) throws IOException {
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
                  .container { width: 100%; max-width: 380px; }
                  h1 { font-size: 17px; font-weight: 700; margin: 0 0 16px 0; color: #F3EEFF; }
                  .pane { background: #130E1E; border: 1px solid #231A36; border-radius: 10px; padding: 16px; margin-bottom: 12px; }
                  .pane-title { font-size: 14px; font-weight: 700; margin: 0 0 4px 0; }
                  .pane-sub { font-size: 11px; color: #786A96; margin: 0 0 12px 0; }
                  input[type="text"] { width: 100%; font-family: monospace; background: #0F0B18; border: 1px solid #2B2042; border-radius: 8px; padding: 10px; color: #F3EEFF; font-size: 16px; font-weight: 700; text-align: center; margin-bottom: 10px; outline: none; }
                  input[type="text"]:focus { border-color: #7C3AED; }
                  input[type="file"] { width: 100%; color: #A594C8; font-size: 12px; margin-bottom: 12px; }
                  button { width: 100%; background: #6D28D9; border: none; border-radius: 8px; padding: 11px; color: #fff; font-weight: 600; font-size: 12px; cursor: pointer; }
                  button:hover { background: #7C3AED; }
                  .status { font-size: 11px; color: #8E7FB0; margin-top: 8px; }
                </style>
                </head>
                <body>
                  <div class="container">
                    <h1>FilzaDrop</h1>
                    <div class="pane">
                      <div class="pane-title">Send</div>
                      <div class="pane-sub">Select a file and enter the 6-digit code</div>
                      <input type="file" id="fileInput" />
                      <input type="text" id="sendCode" maxlength="6" placeholder="6-digit code" />
                      <button onclick="sendFile()">Send</button>
                    </div>
                    <div class="pane">
                      <div class="pane-title">Receive</div>
                      <div class="pane-sub">Enter sender's 6-digit code to download</div>
                      <input type="text" id="recvCode" maxlength="6" placeholder="6-digit code" />
                      <button onclick="downloadFile()">Receive</button>
                    </div>
                    <div class="status" id="status">Ready</div>
                  </div>
                  <script>
                    async function sendFile() {
                      const code = document.getElementById('sendCode').value.trim();
                      const file = document.getElementById('fileInput').files[0];
                      const status = document.getElementById('status');
                      if (!code || !file) { status.innerText = 'Select a file and enter the 6-digit code'; return; }
                      status.innerText = 'Sending ' + file.name + '...';
                      const res = await fetch('/upload?code=' + encodeURIComponent(code) + '&name=' + encodeURIComponent(file.name), {
                        method: 'POST', body: file
                      });
                      status.innerText = await res.text();
                    }
                    function downloadFile() {
                      const code = document.getElementById('recvCode').value.trim();
                      if (!code) return;
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
        String query = ex.getRequestURI().getRawQuery();
        String code = queryParam(query, "code");
        String fileName = queryParam(query, "name");

        if (code.isEmpty() || (!code.equals(activeReceiveCode) && !activeReceiveCode.isEmpty())) {
            respondText(ex, 403, "Invalid or inactive pairing code.");
            return;
        }

        Path dir = TransferService.receiveDir();
        Files.createDirectories(dir);
        Path target = TransferService.uniquePath(dir, TransferService.safeName(fileName.isEmpty() ? "android-file" : fileName));
        try (InputStream in = ex.getRequestBody();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
        if (onFileReceivedFromAndroid != null) {
            onFileReceivedFromAndroid.accept(target);
        }
        respondText(ex, 200, "Delivered to PC: " + target.getFileName());
    }

    private void handleDownload(HttpExchange ex) throws IOException {
        String code = queryParam(ex.getRequestURI().getRawQuery(), "code");
        Path file = sharedFilesByCode.get(code);
        if (file == null || !Files.isRegularFile(file)) {
            respondText(ex, 404, "No file queued for code " + code);
            return;
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"");
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = ex.getResponseBody();
             InputStream in = Files.newInputStream(file)) {
            in.transferTo(out);
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
