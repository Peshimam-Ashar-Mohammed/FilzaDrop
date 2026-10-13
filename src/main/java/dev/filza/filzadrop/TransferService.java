package dev.filza.filzadrop;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class TransferService {
    public static final int PORT = 45821;
    public static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;
    private static final SecureRandom RNG = new SecureRandom();
    private static final int SALT = 16, NONCE = 12, TAG_BITS = 128, BUFFER = 64 * 1024, ITERATIONS = 310_000;
    private static final String MULTI_HEADER_MAGIC = "FD_MULTI_V2";

    private static volatile String relayHost = System.getProperty("filzadrop.relay", "127.0.0.1");
    private static volatile int relayPort = Integer.getInteger("filzadrop.relay.port", RelayServer.DEFAULT_RELAY_PORT);

    private TransferService() {}

    public static void setRelayEndpoint(String host, int port) {
        if (host != null && !host.isBlank()) {
            relayHost = host.trim();
        }
        if (port > 0 && port <= 65535) {
            relayPort = port;
        }
    }

    public static String getRelayEndpoint() {
        return relayHost + ":" + relayPort;
    }

    public static String newCode() {
        return String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
    }

    public static Path receiveDir() {
        return Path.of(System.getProperty("user.home"), "Downloads", "FilzaDrop");
    }

    public static String getLocalIpv4Hint() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces.hasMoreElements()) {
                var ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getName() + " " + ni.getDisplayName()).toLowerCase(Locale.ROOT);
                if (name.contains("wsl") || name.contains("virtual") || name.contains("vmware")
                        || name.contains("virtualbox") || name.contains("vethernet")) {
                    continue;
                }
                var addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (name.contains("wi-fi") || name.contains("wlan") || name.contains("wireless")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback != null ? fallback : "127.0.0.1";
        } catch (SocketException ex) {
            return "127.0.0.1";
        }
    }

    public static final class TransferHandle implements AutoCloseable {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<Closeable> closeables = new ArrayList<>();

        public synchronized void register(Closeable c) {
            if (cancelled.get()) {
                try { c.close(); } catch (IOException ignored) {}
            } else {
                closeables.add(c);
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public synchronized void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                for (Closeable c : closeables) {
                    try { c.close(); } catch (IOException ignored) {}
                }
                closeables.clear();
            }
        }

        @Override
        public void close() {
            for (Closeable c : closeables) {
                try { c.close(); } catch (IOException ignored) {}
            }
            closeables.clear();
        }
    }

    public static void sendByCode(
            Path file,
            String code,
            LanDiscoveryService discovery,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        sendFilesByCode(List.of(file), code, discovery, handle, progress);
    }

    public static void sendFilesByCode(
            List<Path> files,
            String code,
            LanDiscoveryService discovery,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files selected.");
        }

        // 1. Fast LAN UDP discovery
        Optional<InetSocketAddress> lanTarget = discovery != null ? discovery.resolveCode(code) : Optional.empty();
        if (lanTarget.isPresent()) {
            InetSocketAddress addr = lanTarget.get();
            try {
                sendFilesDirect(files, addr.getHostString(), addr.getPort(), code, handle, progress);
                return;
            } catch (IOException ignored) {
                // Fall through to relay / global bridge if direct connect failed
            }
        }

        // 2. Local/custom TCP RelayServer (if running on LAN/VPS)
        try {
            Socket relaySocket = connectRelayAsSender(code, handle);
            sendFilesOverSocket(files, relaySocket, code, handle, progress);
            return;
        } catch (IOException ignored) {
        }

        // 3. Global Cross-Network Bridge (works across Mobile Data, CGNAT, and different Wi-Fi networks,
        //    AND also exchanges local LAN IP in case UDP broadcast was blocked on the same Wi-Fi!)
        try {
            GlobalRelayBridge.HandshakeResult hs = GlobalRelayBridge.connectAsSender(code, handle);
            // Try upgrading to direct LAN TCP first if receiver is on the same network
            if (hs.receiverLanIp() != null && !hs.receiverLanIp().isBlank() && !"127.0.0.1".equals(hs.receiverLanIp())) {
                try {
                    Socket directSock = new Socket();
                    handle.register(directSock);
                    directSock.connect(new InetSocketAddress(hs.receiverLanIp(), hs.receiverLanPort()), 1800);
                    directSock.setSoTimeout(30_000);
                    hs.tunnel().close();
                    sendFilesOverSocket(files, directSock, code, handle, progress);
                    return;
                } catch (IOException ignored) {
                    // Different network / Mobile Data — proceed with global encrypted tunnel
                }
            }
            try (GlobalRelayBridge.StreamTunnel tunnel = hs.tunnel()) {
                tunnel.activateSenderTunnel();
                sendFilesOverStream(files, tunnel.getOutputStream(), code, handle, progress);
                return;
            }
        } catch (Exception globalEx) {
            if (handle.isCancelled()) {
                throw new CancellationException("Transfer cancelled.");
            }
            // Final fallback: localhost direct if testing on same machine
            try {
                sendFilesDirect(files, "127.0.0.1", PORT, code, handle, progress);
                return;
            } catch (Exception ignored) {
            }
            throw new IOException("No active receiver found for code " + code + " on Wi-Fi or Mobile Data.", globalEx);
        }
    }

    public static void sendFilesDirect(
            List<Path> files,
            String host,
            int port,
            String code,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        Socket socket = new Socket();
        handle.register(socket);
        socket.connect(new InetSocketAddress(host, port), 6_000);
        socket.setSoTimeout(30_000);
        sendFilesOverSocket(files, socket, code, handle, progress);
    }

    private static void sendFilesOverSocket(
            List<Path> files,
            Socket socket,
            String code,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        try (socket) {
            sendFilesOverStream(files, socket.getOutputStream(), code, handle, progress);
        }
    }

    static void sendFilesOverStream(
            List<Path> files,
            OutputStream raw,
            String code,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        long totalBytes = 0;
        for (Path f : files) {
            long sz = Files.size(f);
            if (sz < 0 || totalBytes + sz > MAX_BYTES) {
                throw new IOException("Total transfer size exceeds the 10 GiB limit.");
            }
            totalBytes += sz;
        }

        byte[] salt = random(SALT), nonce = random(NONCE);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, code, salt, nonce);

        try {
            raw.write(salt);
            raw.write(nonce);
            raw.flush();

            try (DataOutputStream out = new DataOutputStream(new CipherOutputStream(raw, cipher))) {
                out.writeUTF(MULTI_HEADER_MAGIC);
                out.writeInt(files.size());
                out.writeLong(totalBytes);

                byte[] buf = new byte[BUFFER];
                long totalSent = 0;

                for (Path file : files) {
                    long fileSize = Files.size(file);
                    out.writeUTF(file.getFileName().toString());
                    out.writeLong(fileSize);

                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (handle.isCancelled()) {
                                throw new CancellationException("Transfer cancelled.");
                            }
                            out.write(buf, 0, n);
                            sha256.update(buf, 0, n);
                            totalSent += n;
                            progress.accept(totalSent, totalBytes);
                        }
                    }
                    byte[] digest = sha256.digest();
                    out.write(digest);
                }
                out.flush();
            }
        } catch (Exception e) {
            if (handle.isCancelled()) {
                throw new CancellationException("Transfer cancelled.");
            }
            throw e;
        }
    }

    private record IncomingChannel(InputStream in, Closeable owner) {}

    public static List<Path> receiveFilesAnyNetwork(
            ServerSocket server,
            String code,
            Path directory,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        Files.createDirectories(directory);
        handle.register(server);
        server.setSoTimeout(300_000);

        CompletableFuture<IncomingChannel> winningChannel = new CompletableFuture<>();
        int boundPort = server.getLocalPort();
        String localIp = getLocalIpv4Hint();

        // 1. Direct LAN / Local TCP listener
        Thread directThread = new Thread(() -> {
            try {
                Socket s = server.accept();
                s.setSoTimeout(120_000);
                s.setTcpNoDelay(true);
                handle.register(s);
                IncomingChannel ch = new IncomingChannel(s.getInputStream(), s);
                if (!winningChannel.complete(ch)) {
                    try { s.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                winningChannel.completeExceptionally(e);
            }
        }, "filzadrop-direct-accept");
        directThread.setDaemon(true);
        directThread.start();

        // 2. Local/custom TCP Relay listener
        Thread relayThread = new Thread(() -> {
            try {
                Socket relaySock = connectRelayAsReceiver(code, handle);
                relaySock.setSoTimeout(120_000);
                relaySock.setTcpNoDelay(true);
                IncomingChannel ch = new IncomingChannel(relaySock.getInputStream(), relaySock);
                if (!winningChannel.complete(ch)) {
                    try { relaySock.close(); } catch (IOException ignored) {}
                } else {
                    try { server.close(); } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {
            }
        }, "filzadrop-relay-accept");
        relayThread.setDaemon(true);
        relayThread.start();

        // 3. Global Cross-Network Bridge (Mobile Data & Different Wi-Fi + LAN IP exchange)
        Thread globalThread = new Thread(() -> {
            try {
                GlobalRelayBridge.StreamTunnel tunnel =
                        GlobalRelayBridge.listenAsReceiver(code, localIp, boundPort, handle);
                IncomingChannel ch = new IncomingChannel(tunnel.getInputStream(), tunnel);
                if (!winningChannel.complete(ch)) {
                    tunnel.close();
                } else {
                    try { server.close(); } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {
            }
        }, "filzadrop-global-accept");
        globalThread.setDaemon(true);
        globalThread.start();

        IncomingChannel channel;
        try {
            channel = winningChannel.get();
        } catch (Exception e) {
            if (handle.isCancelled()) {
                throw new CancellationException("Receive cancelled.");
            }
            throw e;
        }

        try (Closeable owner = channel.owner()) {
            return receiveFilesFromStream(channel.in(), code, directory, handle, progress);
        }
    }

    static List<Path> receiveFilesFromStream(
            InputStream raw,
            String code,
            Path directory,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        Files.createDirectories(directory);
        List<Path> savedFiles = new ArrayList<>();
        byte[] salt = readExactly(raw, SALT), nonce = readExactly(raw, NONCE);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, code, salt, nonce);

        try (DataInputStream in = new DataInputStream(new CipherInputStream(raw, cipher))) {
            String firstToken = in.readUTF();
            if (MULTI_HEADER_MAGIC.equals(firstToken)) {
                int fileCount = in.readInt();
                long totalBytes = in.readLong();
                if (fileCount < 1 || fileCount > 500 || totalBytes < 0 || totalBytes > MAX_BYTES) {
                    throw new IOException("Invalid multi-file header.");
                }
                long totalGot = 0;
                for (int i = 0; i < fileCount; i++) {
                    String rawName = in.readUTF();
                    long fileSize = in.readLong();
                    Path saved = receiveSingleStreamedFile(in, directory, rawName, fileSize, true, handle, gotInFile -> {
                        progress.accept(gotInFile, totalBytes);
                    }, totalGot);
                    totalGot += fileSize;
                    savedFiles.add(saved);
                }
            } else {
                long fileSize = in.readLong();
                Path saved = receiveSingleStreamedFile(in, directory, firstToken, fileSize, false, handle, got -> {
                    progress.accept(got, fileSize);
                }, 0L);
                savedFiles.add(saved);
            }
            if (in.read() != -1) {
                throw new IOException("Unexpected trailing data after payload.");
            }
            return savedFiles;
        } catch (Exception e) {
            for (Path p : savedFiles) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            }
            if (handle.isCancelled()) {
                throw new CancellationException("Receive cancelled.");
            }
            throw new IOException("Transfer rejected or interrupted: " + e.getMessage(), e);
        }
    }

    private static Path receiveSingleStreamedFile(
            DataInputStream in,
            Path directory,
            String rawName,
            long size,
            boolean expectSha256,
            TransferHandle handle,
            java.util.function.LongConsumer totalProgress,
            long baseOffset
    ) throws Exception {
        String name = safeName(rawName);
        if (size < 0 || size > MAX_BYTES) {
            throw new IOException("Invalid file size; maximum is 10 GiB.");
        }
        Path temp = Files.createTempFile(directory, ".filzadrop-", ".part");
        boolean committed = false;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                byte[] buf = new byte[BUFFER];
                long got = 0;
                while (got < size) {
                    if (handle.isCancelled()) {
                        throw new CancellationException("Receive cancelled.");
                    }
                    int n = in.read(buf, 0, (int) Math.min(buf.length, size - got));
                    if (n < 0) {
                        throw new EOFException("Connection ended before full file arrived.");
                    }
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    got += n;
                    totalProgress.accept(baseOffset + got);
                }
            }
            if (expectSha256) {
                byte[] expectedHash = new byte[32];
                in.readFully(expectedHash);
                byte[] actualHash = sha256.digest();
                if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                    throw new IOException("SHA-256 integrity check failed for " + name);
                }
            }
            Path target = uniquePath(directory, name);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            committed = true;
            return target;
        } finally {
            if (!committed) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private static Socket connectRelayAsReceiver(String code, TransferHandle handle) throws IOException {
        Socket s = new Socket();
        handle.register(s);
        s.connect(new InetSocketAddress(relayHost, relayPort), 3_000);
        s.setSoTimeout(300_000);
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        DataInputStream in = new DataInputStream(s.getInputStream());
        out.writeUTF("RECEIVER");
        out.writeUTF(code);
        out.flush();
        String status = in.readUTF();
        if (!"WAITING".equals(status)) {
            s.close();
            throw new IOException("Relay rejected registration: " + status);
        }
        String matched = in.readUTF();
        if (!"MATCHED".equals(matched)) {
            s.close();
            throw new IOException("Relay closed before sender matched.");
        }
        return s;
    }

    private static Socket connectRelayAsSender(String code, TransferHandle handle) throws IOException {
        Socket s = new Socket();
        handle.register(s);
        s.connect(new InetSocketAddress(relayHost, relayPort), 2_000);
        s.setSoTimeout(60_000);
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        DataInputStream in = new DataInputStream(s.getInputStream());
        out.writeUTF("SENDER");
        out.writeUTF(code);
        out.flush();
        String status = in.readUTF();
        if (!"MATCHED".equals(status)) {
            s.close();
            throw new IOException("Code not found on relay.");
        }
        return s;
    }

    public static final class CancellationException extends IOException {
        public CancellationException(String message) {
            super(message);
        }
    }

    private static Cipher cipher(int mode, String code, byte[] salt, byte[] nonce) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
                return c;
            } finally {
                java.util.Arrays.fill(key, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] b = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(b, off, length - off);
            if (n < 0) throw new EOFException("Connection closed during handshake.");
            off += n;
        }
        return b;
    }

    public static String safeName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Empty filename.");
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isBlank() || n.equals("..")) n = "received-file";
        return n.length() > 180 ? n.substring(0, 180) : n;
    }

    public static Path uniquePath(Path dir, String file) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return p;
        String base = file, ext = "";
        int dot = file.lastIndexOf('.');
        if (dot > 0) {
            base = file.substring(0, dot);
            ext = file.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            p = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
        throw new IOException("Could not choose a unique destination name.");
    }

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        double x = n;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            x /= 1024;
            i++;
        } while (x >= 1024 && i < u.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", x, u[i]);
    }
}
