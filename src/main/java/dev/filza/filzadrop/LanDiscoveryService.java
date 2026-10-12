
package dev.filza.filzadrop;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class LanDiscoveryService implements AutoCloseable {
    private static final int DISCOVERY_PORT = 45822;
    private final String deviceName;
    private final Consumer<String> onDevice;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running;
    private DatagramSocket socket;

    public LanDiscoveryService(String deviceName, Consumer<String> onDevice) {
        this.deviceName = deviceName.replace("|", "_");
        this.onDevice = onDevice;
    }

    public void start() {
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            running = true;

            Thread listener = new Thread(this::listen, "filzadrop-discovery");
            listener.setDaemon(true);
            listener.start();

            scheduler.scheduleAtFixedRate(this::broadcast, 0, 3,
                    TimeUnit.SECONDS);
        } catch (SocketException ex) {
            onDevice.accept("Discovery unavailable: " + ex.getMessage());
        }
    }

    private void broadcast() {
        if (!running || socket == null) return;
        try {
            String message = "FILZADROP|1|" + deviceName + "|45821";
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;

                for (InterfaceAddress address : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast == null) continue;
                    DatagramPacket packet = new DatagramPacket(
                            data, data.length, broadcast, DISCOVERY_PORT);
                    socket.send(packet);
                }
            }
        } catch (Exception ignored) {
            // Discovery is best-effort; file transfers remain independent.
        }
    }

    private void listen() {
        byte[] buffer = new byte[512];

        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet =
                        new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.UTF_8);
                String[] parts = message.split("\\|", 4);

                if (parts.length != 4 || !parts[0].equals("FILZADROP")
                        || !parts[1].equals("1")) continue;

                String senderIp = packet.getAddress().getHostAddress();
                if (senderIp.equals(getLocalIp())) continue;

                onDevice.accept(parts[2] + " — " + senderIp
                        + ":" + parts[3]);
            } catch (SocketTimeoutException ignored) {
                // Check the running flag again.
            } catch (Exception ignored) {
                if (running) {
                    // Continue listening after transient network errors.
                }
            }
        }
    }

    private String getLocalIp() {
        return TransferService.getLocalIpv4Hint();
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        if (socket != null) socket.close();
    }
}
