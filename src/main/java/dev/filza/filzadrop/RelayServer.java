package dev.filza.filzadrop;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RelayServer implements AutoCloseable {
    public static final int DEFAULT_RELAY_PORT = 45823;
    private static final int MAX_WAITING_SESSIONS = 128;
    private static final long SESSION_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_FAILED_LOOKUPS_PER_IP = 15;

    private record WaitingSession(Socket socket, long createdAtMs) {}

    private final int port;
    private final Map<String, WaitingSession> waitingReceivers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failedLookupsByIp = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "filzadrop-relay");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "filzadrop-relay-cleaner");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private ServerSocket serverSocket;

    public RelayServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        pool.submit(this::acceptLoop);
        cleaner.scheduleAtFixedRate(this::evictExpiredSessions, 30, 30, TimeUnit.SECONDS);
    }

    public int getBoundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    private void evictExpiredSessions() {
        long now = System.currentTimeMillis();
        waitingReceivers.forEach((code, session) -> {
            if (now - session.createdAtMs() > SESSION_TTL_MS || session.socket().isClosed()) {
                WaitingSession removed = waitingReceivers.remove(code);
                if (removed != null) {
                    try { removed.socket().close(); } catch (IOException ignored) {}
                }
            }
        });
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30_000);
                pool.submit(() -> handleClient(client));
            } catch (IOException ignored) {
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            String remoteIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "unknown";
            AtomicInteger failures = failedLookupsByIp.computeIfAbsent(remoteIp, k -> new AtomicInteger(0));
            if (failures.get() >= MAX_FAILED_LOOKUPS_PER_IP) {
                client.close();
                return;
            }

            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            String role = in.readUTF();
            String code = in.readUTF();

            if (code == null || !code.matches("\\d{6}")) {
                failures.incrementAndGet();
                out.writeUTF("INVALID_CODE");
                out.flush();
                client.close();
                return;
            }

            if ("RECEIVER".equals(role)) {
                if (waitingReceivers.size() >= MAX_WAITING_SESSIONS) {
                    evictExpiredSessions();
                    if (waitingReceivers.size() >= MAX_WAITING_SESSIONS) {
                        out.writeUTF("BUSY");
                        out.flush();
                        client.close();
                        return;
                    }
                }
                client.setSoTimeout((int) SESSION_TTL_MS);
                WaitingSession old = waitingReceivers.put(code, new WaitingSession(client, System.currentTimeMillis()));
                if (old != null) {
                    try { old.socket().close(); } catch (IOException ignored) {}
                }
                out.writeUTF("WAITING");
                out.flush();
            } else if ("SENDER".equals(role)) {
                WaitingSession session = waitingReceivers.remove(code);
                if (session == null || session.socket().isClosed()
                        || System.currentTimeMillis() - session.createdAtMs() > SESSION_TTL_MS) {
                    if (session != null) {
                        try { session.socket().close(); } catch (IOException ignored) {}
                    }
                    failures.incrementAndGet();
                    out.writeUTF("NOT_FOUND");
                    out.flush();
                    client.close();
                    return;
                }
                Socket receiver = session.socket();
                receiver.setSoTimeout(120_000);
                client.setSoTimeout(120_000);

                DataOutputStream receiverOut = new DataOutputStream(receiver.getOutputStream());
                receiverOut.writeUTF("MATCHED");
                receiverOut.flush();

                out.writeUTF("MATCHED");
                out.flush();

                pool.submit(() -> pipe(client, receiver));
                pool.submit(() -> pipe(receiver, client));
            } else {
                failures.incrementAndGet();
                client.close();
            }
        } catch (IOException e) {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void pipe(Socket from, Socket to) {
        try (InputStream in = from.getInputStream();
             OutputStream out = to.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try { from.close(); } catch (IOException ignored) {}
            try { to.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void close() {
        running = false;
        cleaner.shutdownNow();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        waitingReceivers.values().forEach(s -> {
            try { s.socket().close(); } catch (IOException ignored) {}
        });
        waitingReceivers.clear();
        pool.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RELAY_PORT;
        try (RelayServer server = new RelayServer(port)) {
            server.start();
            System.out.println("FilzaDrop Relay Server listening on port " + port);
            Thread.currentThread().join();
        }
    }
}
