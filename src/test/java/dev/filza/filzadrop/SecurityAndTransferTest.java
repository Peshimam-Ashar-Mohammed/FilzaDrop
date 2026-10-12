package dev.filza.filzadrop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAndTransferTest {

    @Test
    void mobileUploadRejectedWhenNoActiveReceiveSession(@TempDir Path tempDir) throws Exception {
        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=123456&name=test.txt"))
                    .POST(HttpRequest.BodyPublishers.ofString("unauthorized payload"))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, res.statusCode(), "Upload must be rejected when no receive session is active");
            assertTrue(received.isEmpty());
            assertEquals(0, Files.list(tempDir).count());
        }
    }

    @Test
    void mobileUploadBruteForceLockoutAndSingleUseSession(@TempDir Path tempDir) throws Exception {
        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("654321");

            HttpClient client = HttpClient.newHttpClient();

            // 5 wrong attempts trigger lockout
            for (int i = 0; i < 5; i++) {
                HttpRequest wrongReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=000000&name=a.txt"))
                        .POST(HttpRequest.BodyPublishers.ofString("bad"))
                        .build();
                HttpResponse<String> res = client.send(wrongReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(403, res.statusCode());
            }

            // Even the correct code is now locked out until a new session is started
            HttpRequest lockedReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=654321&name=a.txt"))
                    .POST(HttpRequest.BodyPublishers.ofString("late"))
                    .build();
            assertEquals(403, client.send(lockedReq, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void mobileMultiFileUploadAndDownloadIsolation(@TempDir Path tempDir) throws Exception {
        List<Path> receivedBatch = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, receivedBatch::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("112233");

            HttpClient client = HttpClient.newHttpClient();

            // Receive code cannot be used to download
            HttpRequest dlAttempt = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/download?code=112233"))
                    .GET()
                    .build();
            assertEquals(404, client.send(dlAttempt, HttpResponse.BodyHandlers.ofString()).statusCode());

            // Upload 2 files in one batch
            HttpRequest f1 = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=112233&name=../../first.txt&last=0"))
                    .POST(HttpRequest.BodyPublishers.ofString("hello-1"))
                    .build();
            assertEquals(200, client.send(f1, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest f2 = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=112233&name=second.txt&last=1"))
                    .POST(HttpRequest.BodyPublishers.ofString("hello-2"))
                    .build();
            assertEquals(200, client.send(f2, HttpResponse.BodyHandlers.ofString()).statusCode());

            assertEquals(2, receivedBatch.size());
            assertEquals("first.txt", receivedBatch.get(0).getFileName().toString());
            assertEquals("hello-1", Files.readString(receivedBatch.get(0)));
            assertEquals("hello-2", Files.readString(receivedBatch.get(1)));

            // Replay with same code after batch completion must be rejected
            HttpRequest replay = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=112233&name=third.txt&last=1"))
                    .POST(HttpRequest.BodyPublishers.ofString("replay"))
                    .build();
            assertEquals(403, client.send(replay, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void pathTraversalSanitization() throws IOException {
        assertEquals("passwd", TransferService.safeName("../../etc/passwd"));
        assertEquals("evil.exe", TransferService.safeName("..\\..\\Windows\\System32\\evil.exe"));
        assertEquals("received-file", TransferService.safeName("..."));
    }

    @Test
    void encryptedMultiFileTransferWithSha256Verification(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Path dstDir = tempDir.resolve("dst");
        Files.createDirectories(srcDir);
        Files.createDirectories(dstDir);

        Path fileA = srcDir.resolve("alpha.txt");
        Path fileB = srcDir.resolve("beta.bin");
        Files.writeString(fileA, "Encrypted content alpha", StandardCharsets.UTF_8);
        byte[] binaryPayload = new byte[128 * 1024];
        for (int i = 0; i < binaryPayload.length; i++) binaryPayload[i] = (byte) (i & 0xFF);
        Files.write(fileB, binaryPayload);

        String code = "482910";
        try (ServerSocket server = new ServerSocket(0);
             TransferService.TransferHandle recvHandle = new TransferService.TransferHandle();
             TransferService.TransferHandle sendHandle = new TransferService.TransferHandle()) {

            int port = server.getLocalPort();
            CompletableFuture<List<Path>> receiverFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return TransferService.receiveFilesAnyNetwork(server, code, dstDir, recvHandle, (got, total) -> {});
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            TransferService.sendFilesDirect(List.of(fileA, fileB), "127.0.0.1", port, code, sendHandle, (sent, total) -> {});

            List<Path> saved = receiverFuture.get(15, TimeUnit.SECONDS);
            assertEquals(2, saved.size());
            assertEquals("Encrypted content alpha", Files.readString(saved.get(0), StandardCharsets.UTF_8));
            assertArrayEquals(binaryPayload, Files.readAllBytes(saved.get(1)));
        }
    }

    @Test
    void wrongPairingCodeRejectedAndTempFilesCleaned(@TempDir Path tempDir) throws Exception {
        Path srcFile = tempDir.resolve("secret.txt");
        Path dstDir = tempDir.resolve("out");
        Files.writeString(srcFile, "Top secret payload");
        Files.createDirectories(dstDir);

        try (ServerSocket server = new ServerSocket(0);
             TransferService.TransferHandle recvHandle = new TransferService.TransferHandle();
             TransferService.TransferHandle sendHandle = new TransferService.TransferHandle()) {

            int port = server.getLocalPort();
            CompletableFuture<List<Path>> receiverFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return TransferService.receiveFilesAnyNetwork(server, "111111", dstDir, recvHandle, (got, total) -> {});
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            try {
                TransferService.sendFilesDirect(List.of(srcFile), "127.0.0.1", port, "999999", sendHandle, (sent, total) -> {});
            } catch (Exception ignored) {
            }

            assertThrows(Exception.class, () -> receiverFuture.get(15, TimeUnit.SECONDS));
            assertEquals(0, Files.list(dstDir).count(), "Temporary .part files must be cleaned up on auth failure");
        }
    }
}
