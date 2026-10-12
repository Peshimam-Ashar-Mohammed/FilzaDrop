package dev.filza.filzadrop;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class LanDiscoveryService implements AutoCloseable {
    public static final int DISCOVERY_PORT = 45822;
    private final String deviceName;
    private final String platform;
    private final Consumer<DiscoveredPeer> onDevice;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "filzadrop-discovery-bcast");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, InetSocketAddress> codeToAddress = new ConcurrentHashMap<>();
    private volatile String activePairingCode = "";
    private volatile boolean running;
    private DatagramSocket socket;

    public record DiscoveredPeer(String name, String ip, int port, String platform, String activeCode) {}

    public LanDiscoveryService(String deviceName, Consumer<DiscoveredPeer> onDevice) {
        this(deviceName, "Windows", onDevice);
    }

    public LanDiscoveryService(String deviceName, String platform, Consumer<DiscoveredPeer> onDevice) {
        this.deviceName = deviceName.replace("|", "_");
        this.platform = platform.replace("|", "_");
        this.onDevice = onDevice;
    }

    public void setActivePairingCode(String code) {
        this.activePairingCode = code == null ? "" : code.trim();
        broadcastNow();
    }

    public Optional<InetSocketAddress> resolveCode(String code) {
        InetSocketAddress addr = codeToAddress.get(code);
        if (addr != null) {
            return Optional.of(addr);
        }
        // Send an immediate WHOHAS query across all interfaces and wait briefly
        sendQuery(code);
        for (int i = 0; i < 12; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            addr = codeToAddress.get(code);
            if (addr != null) {
                return Optional.of(addr);
            }
        }
        return Optional.empty();
    }

    public void start() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            running = true;

            Thread listener = new Thread(this::listen, "filzadrop-discovery-listen");
            listener.setDaemon(true);
            listener.start();

            scheduler.scheduleAtFixedRate(this::broadcast, 0, 2, TimeUnit.SECONDS);
        } catch (SocketException ex) {
            // Fallback ephemeral port if 45822 is already bound on this machine
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(1000);
                running = true;
                Thread listener = new Thread(this::listen, "filzadrop-discovery-listen-ephemeral");
                listener.setDaemon(true);
                listener.start();
                scheduler.scheduleAtFixedRate(this::broadcast, 0, 2, TimeUnit.SECONDS);
            } catch (SocketException ignored) {
            }
        }
    }

    public void broadcastNow() {
        CompletableFuture.runAsync(this::broadcast);
    }

    private void sendQuery(String code) {
        if (!running || socket == null) return;
        String msg = "FILZADROP_WHOHAS|" + code;
        sendBroadcastPacket(msg.getBytes(StandardCharsets.UTF_8));
    }

    private void broadcast() {
        if (!running || socket == null) return;
        String codeHash = activePairingCode.isEmpty() ? "-" : activePairingCode;
        String message = "FILZADROP|2|" + deviceName + "|" + TransferService.PORT + "|" + platform + "|" + codeHash;
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        sendBroadcastPacket(data);
    }

    private void sendBroadcastPacket(byte[] data) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;

                for (InterfaceAddress address : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast == null) continue;
                    try {
                        DatagramPacket packet = new DatagramPacket(
                                data, data.length, broadcast, DISCOVERY_PORT);
                        socket.send(packet);
                    } catch (Exception ignored) {}
                }
            }
            // Also send to global broadcast & localhost for local testing
            try {
                socket.send(new DatagramPacket(
                        data, data.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
            } catch (Exception ignored) {}
        } catch (Exception ignored) {
        }
    }

    private void listen() {
        byte[] buffer = new byte[512];
        Set<String> localIps = getAllLocalIps();

        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(
                        packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8
                );
                String senderIp = packet.getAddress().getHostAddress();

                if (message.startsWith("FILZADROP_WHOHAS|")) {
                    String wantedCode = message.substring("FILZADROP_WHOHAS|".length()).trim();
                    if (!wantedCode.isEmpty() && wantedCode.equals(activePairingCode)) {
                        String reply = "FILZADROP|2|" + deviceName + "|" + TransferService.PORT + "|" + platform + "|" + activePairingCode;
                        byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort()));
                        socket.send(new DatagramPacket(data, data.length, packet.getAddress(), DISCOVERY_PORT));
                    }
                    continue;
                }

                String[] parts = message.split("\\|");
                if (parts.length < 4 || !parts[0].equals("FILZADROP")) continue;

                String name = parts[2];
                int port = Integer.parseInt(parts[3]);
                String peerPlatform = parts.length >= 5 ? parts[4] : "Device";
                String peerCode = parts.length >= 6 && !"-".equals(parts[5]) ? parts[5] : "";

                if (!peerCode.isEmpty()) {
                    codeToAddress.put(peerCode, new InetSocketAddress(senderIp, port));
                }

                if (localIps.contains(senderIp) && name.equals(deviceName)) {
                    continue;
                }

                onDevice.accept(new DiscoveredPeer(name, senderIp, port, peerPlatform, peerCode));
            } catch (SocketTimeoutException ignored) {
            } catch (Exception ignored) {
            }
        }
    }

    private Set<String> getAllLocalIps() {
        Set<String> ips = new HashSet<>();
        ips.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    ips.add(addrs.nextElement().getHostAddress());
                }
            }
        } catch (SocketException ignored) {}
        return ips;
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        if (socket != null) socket.close();
    }
}
