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
    private static final SecureRandom RNG = new SecureRandom();
    private static final int SALT = 16, NONCE = 12, TAG_BITS = 128, BUFFER = 64 * 1024, ITERATIONS = 310_000;
    private static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;

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

    /**
     * Generates a clean 6-digit pairing code.
     */
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

    /**
     * Active session handle that allows instant cancellation of either send or receive.
     */
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

    /**
     * Sends a file using ONLY the pairing code:
     * 1. Resolves via LAN Discovery if on the same network
     * 2. Falls back to Cross-Network Relay Server if across different networks
     */
    public static void sendByCode(
            Path file,
            String code,
            LanDiscoveryService discovery,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        Optional<InetSocketAddress> lanTarget = discovery != null ? discovery.resolveCode(code) : Optional.empty();
        if (lanTarget.isPresent()) {
            InetSocketAddress addr = lanTarget.get();
            sendDirect(file, addr.getHostString(), addr.getPort(), code, handle, progress);
            return;
        }

        // Try Cross-Network Relay Server
        try {
            Socket relaySocket = connectRelayAsSender(code, handle);
            sendOverSocket(file, relaySocket, code, handle, progress);
            return;
        } catch (IOException relayEx) {
            // Final fallback: try localhost direct if testing on same machine
            try {
                sendDirect(file, "127.0.0.1", PORT, code, handle, progress);
                return;
            } catch (Exception ignored) {
            }
            throw new IOException("No active receiver found for code " + code + " on LAN or Relay.", relayEx);
        }
    }

    public static void sendDirect(
            Path file,
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
        sendOverSocket(file, socket, code, handle, progress);
    }

    private static void sendOverSocket(
            Path file,
            Socket socket,
            String code,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Maximum file size is 10 GiB.");
        byte[] salt = random(SALT), nonce = random(NONCE);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, code, salt, nonce);

        try (socket) {
            OutputStream raw = socket.getOutputStream();
            raw.write(salt);
            raw.write(nonce);
            raw.flush();

            try (DataOutputStream out = new DataOutputStream(new CipherOutputStream(raw, cipher));
                 InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                out.writeUTF(file.getFileName().toString());
                out.writeLong(size);
                byte[] buf = new byte[BUFFER];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (handle.isCancelled()) {
                        throw new CancellationException("Transfer cancelled.");
                    }
                    out.write(buf, 0, n);
                    sent += n;
                    progress.accept(sent, size);
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

    /**
     * Listens simultaneously on Direct TCP (for LAN/Android speed) AND Cross-Network Relay Server.
     * Whichever accepts a sender first is used to receive the encrypted stream.
     */
    public static Path receiveAnyNetwork(
            ServerSocket server,
            String code,
            Path directory,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        Files.createDirectories(directory);
        handle.register(server);
        server.setSoTimeout(300_000);

        CompletableFuture<Socket> winningSocket = new CompletableFuture<>();

        // 1. Direct LAN / Local listener
        Thread directThread = new Thread(() -> {
            try {
                Socket s = server.accept();
                handle.register(s);
                if (!winningSocket.complete(s)) {
                    try { s.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                winningSocket.completeExceptionally(e);
            }
        }, "filzadrop-direct-accept");
        directThread.setDaemon(true);
        directThread.start();

        // 2. Cross-network Relay listener
        Thread relayThread = new Thread(() -> {
            try {
                Socket relaySock = connectRelayAsReceiver(code, handle);
                if (!winningSocket.complete(relaySock)) {
                    try { relaySock.close(); } catch (IOException ignored) {}
                } else {
                    try { server.close(); } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {
                // Relay optional if LAN succeeds
            }
        }, "filzadrop-relay-accept");
        relayThread.setDaemon(true);
        relayThread.start();

        Socket socket;
        try {
            socket = winningSocket.get();
        } catch (Exception e) {
            if (handle.isCancelled()) {
                throw new CancellationException("Receive cancelled.");
            }
            throw e;
        }

        return receiveFromSocket(socket, code, directory, handle, progress);
    }

    private static Path receiveFromSocket(
            Socket socket,
            String code,
            Path directory,
            TransferHandle handle,
            BiConsumer<Long, Long> progress
    ) throws Exception {
        try (socket) {
            socket.setSoTimeout(120_000);
            socket.setTcpNoDelay(true);
            InputStream raw = socket.getInputStream();
            byte[] salt = readExactly(raw, SALT), nonce = readExactly(raw, NONCE);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, code, salt, nonce);
            Path temp = null;
            boolean committed = false;
            try (DataInputStream in = new DataInputStream(new CipherInputStream(raw, cipher))) {
                String name = safeName(in.readUTF());
                long size = in.readLong();
                if (size < 0 || size > MAX_BYTES) throw new IOException("Invalid file size; maximum is 10 GiB.");
                temp = Files.createTempFile(directory, ".filzadrop-", ".part");
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                    byte[] buf = new byte[BUFFER];
                    long got = 0;
                    while (got < size) {
                        if (handle.isCancelled()) {
                            throw new CancellationException("Receive cancelled.");
                        }
                        int n = in.read(buf, 0, (int) Math.min(buf.length, size - got));
                        if (n < 0) throw new EOFException("Connection ended before the full file arrived.");
                        out.write(buf, 0, n);
                        got += n;
                        progress.accept(got, size);
                    }
                }
                if (in.read() != -1) throw new IOException("Unexpected extra data after file payload.");
                Path target = uniquePath(directory, name);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target);
                }
                committed = true;
                return target;
            } catch (Exception e) {
                if (handle.isCancelled()) {
                    throw new CancellationException("Receive cancelled.");
                }
                throw new IOException("Transfer rejected or interrupted. Check pairing code.", e);
            } finally {
                if (!committed && temp != null) {
                    try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                }
            }
        }
    }

    private static Socket connectRelayAsReceiver(String code, TransferHandle handle) throws IOException {
        Socket s = new Socket();
        handle.register(s);
        s.connect(new InetSocketAddress(relayHost, relayPort), 5_000);
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
        s.connect(new InetSocketAddress(relayHost, relayPort), 5_000);
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

    static String safeName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Empty filename.");
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isBlank() || n.equals("..")) n = "received-file";
        return n.length() > 180 ? n.substring(0, 180) : n;
    }

    static Path uniquePath(Path dir, String file) throws IOException {
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
