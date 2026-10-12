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

/**
 * Lightweight cross-network TCP Relay Server.
 * Both Receiver and Sender can connect to a RelayServer using only the pairing code.
 * Can be run standalone on any VPS/cloud host (`java -cp ... dev.filza.filzadrop.RelayServer`)
 * or automatically started in the background on the local host.
 */
public final class RelayServer implements AutoCloseable {
    public static final int DEFAULT_RELAY_PORT = 45823;

    private final int port;
    private final Map<String, Socket> waitingReceivers = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "filzadrop-relay");
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
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(120_000);
                pool.submit(() -> handleClient(client));
            } catch (IOException ignored) {
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            String role = in.readUTF(); // "RECEIVER" or "SENDER"
            String code = in.readUTF();

            if ("RECEIVER".equals(role)) {
                Socket old = waitingReceivers.put(code, client);
                if (old != null) {
                    try { old.close(); } catch (IOException ignored) {}
                }
                out.writeUTF("WAITING");
                out.flush();
            } else if ("SENDER".equals(role)) {
                Socket receiver = waitingReceivers.remove(code);
                if (receiver == null || receiver.isClosed()) {
                    out.writeUTF("NOT_FOUND");
                    out.flush();
                    client.close();
                    return;
                }
                DataOutputStream receiverOut = new DataOutputStream(receiver.getOutputStream());
                receiverOut.writeUTF("MATCHED");
                receiverOut.flush();

                out.writeUTF("MATCHED");
                out.flush();

                // Pipe bytes bidirectionally between sender and receiver
                pool.submit(() -> pipe(client, receiver));
                pool.submit(() -> pipe(receiver, client));
            } else {
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
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        waitingReceivers.values().forEach(s -> {
            try { s.close(); } catch (IOException ignored) {}
        });
        waitingReceivers.clear();
        pool.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_RELAY_PORT;
        try (RelayServer server = new RelayServer(port)) {
            server.start();
            System.out.println("FilzaDrop Cross-Network Relay Server listening on port " + port);
            Thread.currentThread().join();
        }
    }
}
