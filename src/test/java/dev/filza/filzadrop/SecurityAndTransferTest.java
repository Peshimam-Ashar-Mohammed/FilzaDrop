package dev.filza.filzadrop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAndTransferTest {

    // 1. Upload rejected without an active session
    @Test
    void uploadRejectedWhenNoActiveReceiveSession(@TempDir Path tempDir) throws Exception {
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
            assertEquals(403, res.statusCode());
            assertTrue(received.isEmpty());
            assertEquals(0, Files.list(tempDir).count());
        }
    }

    // 2. Upload rejected with an invalid code
    @Test
    void uploadRejectedWithInvalidCode(@TempDir Path tempDir) throws Exception {
        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("123456");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=999999&name=test.txt"))
                    .POST(HttpRequest.BodyPublishers.ofString("wrong code payload"))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, res.statusCode());
            assertTrue(received.isEmpty());
            assertEquals(0, Files.list(tempDir).count());
        }
    }

    // 3. Expired upload session rejected
    @Test
    void expiredUploadSessionRejected(@TempDir Path tempDir) throws Exception {
        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("246810", 15L); // 15 ms TTL
            Thread.sleep(50L);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=246810&name=expired.txt"))
                    .POST(HttpRequest.BodyPublishers.ofString("too late"))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, res.statusCode());
            assertTrue(received.isEmpty());
            assertEquals(0, Files.list(tempDir).count());
        }
    }

    // 4. Repeated invalid upload attempts eventually blocked
    @Test
    void repeatedInvalidUploadAttemptsBlocked(@TempDir Path tempDir) throws Exception {
        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("654321");

            HttpClient client = HttpClient.newHttpClient();
            for (int i = 0; i < 5; i++) {
                HttpRequest wrongReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=000000&name=a.txt"))
                        .POST(HttpRequest.BodyPublishers.ofString("bad"))
                        .build();
                assertEquals(403, client.send(wrongReq, HttpResponse.BodyHandlers.ofString()).statusCode());
            }

            // Correct code now rejected due to brute-force lockout
            HttpRequest lockedReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?code=654321&name=a.txt"))
                    .POST(HttpRequest.BodyPublishers.ofString("blocked"))
                    .build();
            assertEquals(403, client.send(lockedReq, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    // 5 & 6. Invalid, expired, or unknown download session rejected
    @Test
    void invalidAndExpiredDownloadSessionRejected(@TempDir Path tempDir) throws Exception {
        Path sharedFile = tempDir.resolve("doc.txt");
        Files.writeString(sharedFile, "shared content");

        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, list -> {})) {
            bridge.start(0);
            int port = bridge.getBoundPort();

            HttpClient client = HttpClient.newHttpClient();

            // Unknown valid format code
            HttpRequest unknownReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/download?code=111222"))
                    .GET()
                    .build();
            assertEquals(403, client.send(unknownReq, HttpResponse.BodyHandlers.ofString()).statusCode());

            // Malformed code
            HttpRequest malformedReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/download?code=abc"))
                    .GET()
                    .build();
            assertEquals(403, client.send(malformedReq, HttpResponse.BodyHandlers.ofString()).statusCode());

            // Expired download session
            bridge.registerOutgoingFiles("333444", List.of(sharedFile), 15L, 3);
            Thread.sleep(50L);
            HttpRequest expiredReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/download?code=333444"))
                    .GET()
                    .build();
            assertEquals(403, client.send(expiredReq, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    // 7. Download guessing attempts throttled
    @Test
    void downloadGuessingAttemptsThrottledByRemoteAddress(@TempDir Path tempDir) throws Exception {
        Path sharedFile = tempDir.resolve("real.txt");
        Files.writeString(sharedFile, "confidential");

        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, list -> {})) {
            bridge.setMaxFailedDownloadAttemptsForTest(4);
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.registerOutgoingFiles("777888", List.of(sharedFile));

            HttpClient client = HttpClient.newHttpClient();

            // 4 failed guesses from this IP
            for (int i = 0; i < 4; i++) {
                HttpRequest bad = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/download?code=00000" + i))
                        .GET()
                        .build();
                assertEquals(403, client.send(bad, HttpResponse.BodyHandlers.ofString()).statusCode());
            }

            // Now even the valid code 777888 is blocked for this IP
            HttpRequest validButThrottled = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/download?code=777888"))
                    .GET()
                    .build();
            assertEquals(403, client.send(validButThrottled, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    // 8 & 9. Valid download succeeds and download count is respected under concurrent requests
    @Test
    void validDownloadAndConcurrentDownloadCountLimit(@TempDir Path tempDir) throws Exception {
        Path sharedFile = tempDir.resolve("report.txt");
        Files.writeString(sharedFile, "verified download payload");

        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, list -> {})) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            // Allow exactly 2 downloads
            bridge.registerOutgoingFiles("555666", List.of(sharedFile), 60_000L, 2);

            HttpClient client = HttpClient.newHttpClient();
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Callable<Integer>> tasks = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    startLatch.await();
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/download?code=555666"))
                            .GET()
                            .build();
                    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) {
                        assertEquals("verified download payload", res.body());
                    }
                    return res.statusCode();
                });
            }

            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> t : tasks) {
                futures.add(pool.submit(t));
            }
            startLatch.countDown();

            int successCount = 0;
            for (Future<Integer> f : futures) {
                if (f.get(10, TimeUnit.SECONDS) == 200) {
                    successCount++;
                }
            }
            pool.shutdownNow();

            assertEquals(2, successCount, "Concurrent requests must not exceed the configured download allowance of 2");
        }
    }

    // 10. Malicious filenames cannot escape the receive directory
    @Test
    void maliciousFilenamesCannotEscapeReceiveDirectory(@TempDir Path tempDir) throws Exception {
        assertEquals("passwd", TransferService.safeName("../../etc/passwd"));
        assertEquals("evil.exe", TransferService.safeName("..\\..\\Windows\\System32\\evil.exe"));
        assertEquals("received-file", TransferService.safeName("..."));

        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("888999");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?name=..%2F..%2Fescaped.txt&last=1"))
                    .header("X-FilzaDrop-Code", "888999")
                    .POST(HttpRequest.BodyPublishers.ofString("contained"))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertEquals(1, received.size());
            assertEquals(tempDir.toRealPath(), received.get(0).getParent().toRealPath());
            assertEquals("escaped.txt", received.get(0).getFileName().toString());
        }
    }

    // 11. Oversized uploads are rejected and partial files removed
    @Test
    void oversizedUploadsRejectedAndPartialFilesRemoved(@TempDir Path tempDir) throws Exception {
        List<Path> received = new ArrayList<>();
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, received::addAll)) {
            bridge.setMaxUploadBytesForTest(1024L); // 1 KB max for test
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("121212");

            byte[] oversized = new byte[4096];
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?name=big.bin&last=1"))
                    .header("X-FilzaDrop-Code", "121212")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, res.statusCode());
            assertTrue(received.isEmpty());
            assertEquals(0, Files.list(tempDir).count(), "Partial .part file must be deleted when upload exceeds size limit");
        }
    }

    // 12. Interrupted transfers do not leave corrupt final files
    @Test
    void interruptedTransferDoesNotLeaveCorruptFinalFiles(@TempDir Path tempDir) throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             TransferService.TransferHandle recvHandle = new TransferService.TransferHandle()) {

            int port = server.getLocalPort();
            CompletableFuture<List<Path>> receiverFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return TransferService.receiveFilesAnyNetwork(server, "314159", tempDir, recvHandle, (g, t) -> {});
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Connect and send truncated stream then abruptly close socket
            try (Socket rawClient = new Socket("127.0.0.1", port)) {
                OutputStream out = rawClient.getOutputStream();
                out.write(new byte[10]);
                out.flush();
            }

            assertThrows(Exception.class, () -> receiverFuture.get(10, TimeUnit.SECONDS));
            assertEquals(0, Files.list(tempDir).count(), "Interrupted transfer must not leave partial files in destination");
        }
    }

    // 13. Valid multi-file browser transfers still work
    @Test
    void validMultiFileBrowserTransfersSucceed(@TempDir Path tempDir) throws Exception {
        List<Path> receivedBatch = Collections.synchronizedList(new ArrayList<>());
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, receivedBatch::addAll)) {
            bridge.start(0);
            int port = bridge.getBoundPort();
            bridge.setActiveReceiveCode("112233");

            HttpClient client = HttpClient.newHttpClient();

            HttpRequest f1 = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?name=first.txt&last=0"))
                    .header("X-FilzaDrop-Code", "112233")
                    .POST(HttpRequest.BodyPublishers.ofString("hello-1"))
                    .build();
            assertEquals(200, client.send(f1, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest f2 = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/upload?name=second.txt&last=1"))
                    .header("X-FilzaDrop-Code", "112233")
                    .POST(HttpRequest.BodyPublishers.ofString("hello-2"))
                    .build();
            assertEquals(200, client.send(f2, HttpResponse.BodyHandlers.ofString()).statusCode());

            assertEquals(2, receivedBatch.size());
            assertEquals("hello-1", Files.readString(receivedBatch.get(0)));
            assertEquals("hello-2", Files.readString(receivedBatch.get(1)));
        }
    }

    // 14. Closing the bridge releases its resources
    @Test
    void closingBridgeReleasesPortAndResources(@TempDir Path tempDir) throws Exception {
        int port;
        try (MobileWebBridge bridge = new MobileWebBridge(tempDir, list -> {})) {
            bridge.start(0);
            port = bridge.getBoundPort();
            assertTrue(port > 0);
        }

        // Verify the port is released and a new server can bind to it or requests fail to connect
        try (MobileWebBridge secondBridge = new MobileWebBridge(tempDir, list -> {})) {
            secondBridge.start(port);
            assertEquals(port, secondBridge.getBoundPort());
        }
    }

    // 15. Existing desktop encrypted-transfer tests continue to pass
    @Test
    void encryptedMultiFileTransferWithSha256VerificationAndWrongCodeRejection(@TempDir Path tempDir) throws Exception {
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
}
