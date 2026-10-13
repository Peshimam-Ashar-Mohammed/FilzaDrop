package dev.filza.filzadrop;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Zero-cost cross-network bridge for transfers over Mobile Data (4G/5G) and different Wi-Fi networks.
 *
 * How it works:
 * 1. Uses outbound TLS (port 8883) to public ephemeral message brokers (no port forwarding required,
 *    works behind CGNAT / Mobile Data / symmetric NAT).
 * 2. Topic names are blinded using SHA-256("filzadrop-v2-topic-" + code).
 * 3. During handshake, the Receiver advertises its local LAN IP(s) so if both devices happen to be on
 *    the same Wi-Fi/Hotspot (even when router AP isolation blocks UDP broadcast), the Sender can
 *    immediately connect via direct LAN TCP.
 * 4. If direct LAN TCP is unreachable (Mobile Data or different Wi-Fi), the bridge provides a
 *    Piped Socket pair that tunnels the existing AES-256-GCM + SHA-256 encrypted stream in 32 KB chunks
 *    with flow-control ACKs and zero persistent storage (retain = false, QoS 0).
 */
public final class GlobalRelayBridge {
    private static final String[] BROKER_HOSTS = {
            "broker.emqx.io",
            "broker.hivemq.com"
    };
    private static final int BROKER_TLS_PORT = 8883;
    private static final int CHUNK_SIZE = 32 * 1024;

    private GlobalRelayBridge() {}

    public record HandshakeResult(String receiverLanIp, int receiverLanPort, StreamTunnel tunnel) {}

    public static String blindedTopicPrefix(String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(("filzadrop-v2-rendezvous-" + code).getBytes(StandardCharsets.UTF_8));
            return "fd2/" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (Exception e) {
            return "fd2/" + code;
        }
    }

    /**
     * Connects to the global TLS rendezvous broker as a Receiver.
     * Announces local LAN IP (so same-Wi-Fi senders can upgrade to direct TCP if reachable)
     * and waits for a Sender to start a cross-network tunnel if direct TCP isn't used.
     */
    public static StreamTunnel listenAsReceiver(
            String code,
            String localLanIp,
            int localLanPort,
            TransferService.TransferHandle handle
    ) throws IOException {
        String prefix = blindedTopicPrefix(code);
        String topicCtrlToRecv = prefix + "/c2r";
        String topicCtrlToSend = prefix + "/r2c";
        String topicDataToRecv = prefix + "/d2r";

        MqttTinyClient mqtt = connectFirstAvailableBroker(handle);
        handle.register(mqtt);

        mqtt.subscribe(topicCtrlToRecv);
        mqtt.subscribe(topicDataToRecv);

        // Wait for sender HELLO or local LAN IP query
        while (!handle.isCancelled()) {
            MqttPacket pkt = mqtt.pollPacket(2000);
            if (pkt == null) {
                continue;
            }
            if (topicCtrlToRecv.equals(pkt.topic)) {
                String msg = new String(pkt.payload, StandardCharsets.UTF_8);
                if (msg.startsWith("WHOIS")) {
                    // Reply with our LAN IP:port so sender can try direct LAN TCP first, or request TUNNEL
                    String reply = "IAM|" + localLanIp + "|" + localLanPort;
                    mqtt.publish(topicCtrlToSend, reply.getBytes(StandardCharsets.UTF_8));
                } else if (msg.startsWith("START_TUNNEL")) {
                    mqtt.publish(topicCtrlToSend, "TUNNEL_READY".getBytes(StandardCharsets.UTF_8));
                    return new StreamTunnel(mqtt, topicDataToRecv, topicCtrlToSend, true, handle);
                }
            }
        }
        mqtt.close();
        throw new TransferService.CancellationException("Cancelled");
    }

    /**
     * Queries the global TLS rendezvous broker by 6-digit code.
     * Returns the Receiver's LAN IP (if it responds) plus an ready-to-use cross-network StreamTunnel.
     */
    public static HandshakeResult connectAsSender(
            String code,
            TransferService.TransferHandle handle
    ) throws IOException {
        String prefix = blindedTopicPrefix(code);
        String topicCtrlToRecv = prefix + "/c2r";
        String topicCtrlToSend = prefix + "/r2c";
        String topicDataToRecv = prefix + "/d2r";

        MqttTinyClient mqtt = connectFirstAvailableBroker(handle);
        handle.register(mqtt);

        mqtt.subscribe(topicCtrlToSend);

        byte[] whois = "WHOIS".getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 10 && !handle.isCancelled(); attempt++) {
            mqtt.publish(topicCtrlToRecv, whois);
            long deadline = System.currentTimeMillis() + 800;
            while (System.currentTimeMillis() < deadline && !handle.isCancelled()) {
                MqttPacket pkt = mqtt.pollPacket(250);
                if (pkt != null && topicCtrlToSend.equals(pkt.topic)) {
                    String msg = new String(pkt.payload, StandardCharsets.UTF_8);
                    if (msg.startsWith("IAM|")) {
                        String[] parts = msg.split("\\|");
                        String lanIp = parts.length > 1 ? parts[1] : "";
                        int lanPort = parts.length > 2 ? Integer.parseInt(parts[2]) : TransferService.PORT;
                        return new HandshakeResult(
                                lanIp,
                                lanPort,
                                new StreamTunnel(mqtt, topicCtrlToSend, topicDataToRecv, false, handle)
                        );
                    }
                }
            }
        }
        mqtt.close();
        throw new IOException("No receiver found for code " + code + " on Wi-Fi or Mobile Data.");
    }

    private static MqttTinyClient connectFirstAvailableBroker(TransferService.TransferHandle handle) throws IOException {
        IOException lastEx = null;
        for (String host : BROKER_HOSTS) {
            if (handle.isCancelled()) {
                throw new TransferService.CancellationException("Cancelled");
            }
            try {
                MqttTinyClient client = new MqttTinyClient(host, BROKER_TLS_PORT);
                client.connect();
                return client;
            } catch (IOException e) {
                lastEx = e;
            }
        }
        throw lastEx != null ? lastEx : new IOException("Could not reach cross-network relay broker.");
    }

    /**
     * Wraps the chunked MQTT stream into a standard InputStream / OutputStream pair
     * with per-chunk ACK flow control so large files stream reliably without dropping packets.
     */
    public static final class StreamTunnel implements Closeable {
        private final MqttTinyClient mqtt;
        private final String incomingTopic;
        private final String outgoingTopic;
        private final boolean isReceiver;
        private final TransferService.TransferHandle handle;
        private volatile boolean closed = false;

        StreamTunnel(
                MqttTinyClient mqtt,
                String incomingTopic,
                String outgoingTopic,
                boolean isReceiver,
                TransferService.TransferHandle handle
        ) {
            this.mqtt = mqtt;
            this.incomingTopic = incomingTopic;
            this.outgoingTopic = outgoingTopic;
            this.isReceiver = isReceiver;
            this.handle = handle;
        }

        public void activateSenderTunnel() throws IOException {
            String prefix = outgoingTopic.substring(0, outgoingTopic.lastIndexOf('/'));
            String topicCtrlToRecv = prefix + "/c2r";
            mqtt.publish(topicCtrlToRecv, "START_TUNNEL".getBytes(StandardCharsets.UTF_8));
            long deadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < deadline && !handle.isCancelled()) {
                MqttPacket pkt = mqtt.pollPacket(300);
                if (pkt != null && incomingTopic.equals(pkt.topic)) {
                    String msg = new String(pkt.payload, StandardCharsets.UTF_8);
                    if ("TUNNEL_READY".equals(msg)) {
                        return;
                    }
                }
            }
            throw new IOException("Timed out activating cross-network tunnel.");
        }

        public InputStream getInputStream() {
            return new InputStream() {
                private byte[] currentChunk = null;
                private int offset = 0;
                private boolean eof = false;

                @Override
                public int read() throws IOException {
                    byte[] single = new byte[1];
                    int n = read(single, 0, 1);
                    return n == -1 ? -1 : (single[0] & 0xFF);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (eof) return -1;
                    while (currentChunk == null || offset >= currentChunk.length) {
                        if (handle.isCancelled() || closed) {
                            throw new TransferService.CancellationException("Transfer cancelled.");
                        }
                        MqttPacket pkt = mqtt.pollPacket(60_000);
                        if (pkt == null) {
                            throw new EOFException("Cross-network stream timed out.");
                        }
                        if (!incomingTopic.equals(pkt.topic) || pkt.payload.length == 0) {
                            continue;
                        }
                        byte type = pkt.payload[0];
                        if (type == 0x01) { // DATA chunk
                            currentChunk = new byte[pkt.payload.length - 1];
                            System.arraycopy(pkt.payload, 1, currentChunk, 0, currentChunk.length);
                            offset = 0;
                            // Send ACK so sender can send next chunk
                            mqtt.publish(outgoingTopic, "ACK".getBytes(StandardCharsets.UTF_8));
                        } else if (type == 0x02) { // EOF marker
                            mqtt.publish(outgoingTopic, "ACK".getBytes(StandardCharsets.UTF_8));
                            eof = true;
                            return -1;
                        }
                    }
                    int available = currentChunk.length - offset;
                    int toCopy = Math.min(len, available);
                    System.arraycopy(currentChunk, offset, b, off, toCopy);
                    offset += toCopy;
                    return toCopy;
                }
            };
        }

        public OutputStream getOutputStream() {
            return new OutputStream() {
                private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(CHUNK_SIZE);

                @Override
                public void write(int b) throws IOException {
                    buffer.write(b);
                    if (buffer.size() >= CHUNK_SIZE) {
                        flushBuffer();
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    int remaining = len;
                    int pos = off;
                    while (remaining > 0) {
                        int space = CHUNK_SIZE - buffer.size();
                        int toWrite = Math.min(remaining, space);
                        buffer.write(b, pos, toWrite);
                        pos += toWrite;
                        remaining -= toWrite;
                        if (buffer.size() >= CHUNK_SIZE) {
                            flushBuffer();
                        }
                    }
                }

                private void flushBuffer() throws IOException {
                    if (buffer.size() == 0) return;
                    byte[] raw = buffer.toByteArray();
                    buffer.reset();
                    byte[] framed = new byte[raw.length + 1];
                    framed[0] = 0x01; // DATA marker
                    System.arraycopy(raw, 0, framed, 1, raw.length);
                    mqtt.publish(outgoingTopic, framed);
                    waitForAck();
                }

                @Override
                public void flush() throws IOException {
                    flushBuffer();
                }

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        flushBuffer();
                        mqtt.publish(outgoingTopic, new byte[]{0x02}); // EOF marker
                        try {
                            waitForAck();
                        } catch (IOException ignored) {}
                        closed = true;
                    }
                }

                private void waitForAck() throws IOException {
                    long deadline = System.currentTimeMillis() + 30_000;
                    while (System.currentTimeMillis() < deadline && !handle.isCancelled()) {
                        MqttPacket pkt = mqtt.pollPacket(500);
                        if (pkt != null && incomingTopic.equals(pkt.topic)) {
                            String msg = new String(pkt.payload, StandardCharsets.UTF_8);
                            if ("ACK".equals(msg)) {
                                return;
                            }
                        }
                    }
                    if (handle.isCancelled()) {
                        throw new TransferService.CancellationException("Transfer cancelled.");
                    }
                    throw new IOException("Cross-network peer did not acknowledge data chunk.");
                }
            };
        }

        @Override
        public void close() {
            closed = true;
            mqtt.close();
        }
    }

    private record MqttPacket(String topic, byte[] payload) {}

    /**
     * Minimal, zero-dependency MQTT 3.1.1 client over TLS (`SSLSocket`).
     */
    private static final class MqttTinyClient implements Closeable {
        private final String host;
        private final int port;
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private final BlockingQueue<MqttPacket> incomingQueue = new LinkedBlockingQueue<>(512);
        private volatile boolean running = false;
        private int packetId = 1;

        MqttTinyClient(String host, int port) {
            this.host = host;
            this.port = port;
        }

        void connect() throws IOException {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket();
            ssl.connect(new InetSocketAddress(host, port), 5_000);
            ssl.setSoTimeout(45_000);
            ssl.startHandshake();
            this.socket = ssl;
            this.in = new DataInputStream(new BufferedInputStream(ssl.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(ssl.getOutputStream()));

            String clientId = "fd-" + UUID.randomUUID().toString().substring(0, 12);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream bodyOut = new DataOutputStream(body);
            bodyOut.writeUTF("MQTT");
            bodyOut.writeByte(4); // Protocol level 4 (MQTT 3.1.1)
            bodyOut.writeByte(0x02); // Clean session
            bodyOut.writeShort(30); // Keepalive 30s
            bodyOut.writeUTF(clientId);
            bodyOut.flush();

            sendFrame(0x10, body.toByteArray());

            int header = in.readUnsignedByte();
            int remLen = readRemainingLength(in);
            byte[] connAck = new byte[remLen];
            in.readFully(connAck);
            if ((header & 0xF0) != 0x20 || connAck.length < 2 || connAck[1] != 0) {
                close();
                throw new IOException("Broker rejected connection.");
            }

            running = true;
            Thread reader = new Thread(this::readLoop, "filzadrop-mqtt-reader");
            reader.setDaemon(true);
            reader.start();

            Thread pinger = new Thread(this::pingLoop, "filzadrop-mqtt-ping");
            pinger.setDaemon(true);
            pinger.start();
        }

        synchronized void subscribe(String topic) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream bodyOut = new DataOutputStream(body);
            bodyOut.writeShort(packetId++);
            bodyOut.writeUTF(topic);
            bodyOut.writeByte(0); // QoS 0
            bodyOut.flush();
            sendFrame(0x82, body.toByteArray());
        }

        synchronized void publish(String topic, byte[] payload) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream bodyOut = new DataOutputStream(body);
            bodyOut.writeUTF(topic);
            bodyOut.write(payload);
            bodyOut.flush();
            sendFrame(0x30, body.toByteArray());
        }

        private synchronized void sendFrame(int fixedHeaderByte, byte[] body) throws IOException {
            out.writeByte(fixedHeaderByte);
            writeRemainingLength(out, body.length);
            out.write(body);
            out.flush();
        }

        MqttPacket pollPacket(long timeoutMs) {
            try {
                return incomingQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private void readLoop() {
            try {
                while (running && socket != null && !socket.isClosed()) {
                    int header = in.readUnsignedByte();
                    int remLen = readRemainingLength(in);
                    byte[] data = new byte[remLen];
                    in.readFully(data);
                    int packetType = header & 0xF0;
                    if (packetType == 0x30) { // PUBLISH
                        DataInputStream pktIn = new DataInputStream(new ByteArrayInputStream(data));
                        String topic = pktIn.readUTF();
                        int topicBytesLen = topic.getBytes(StandardCharsets.UTF_8).length + 2;
                        int qos = (header & 0x06) >> 1;
                        if (qos > 0) {
                            pktIn.readUnsignedShort();
                            topicBytesLen += 2;
                        }
                        byte[] payload = new byte[remLen - topicBytesLen];
                        pktIn.readFully(payload);
                        incomingQueue.offer(new MqttPacket(topic, payload));
                    }
                }
            } catch (IOException ignored) {
            } finally {
                running = false;
            }
        }

        private void pingLoop() {
            while (running && socket != null && !socket.isClosed()) {
                try {
                    Thread.sleep(15_000);
                    if (running) {
                        sendFrame(0xC0, new byte[0]);
                    }
                } catch (Exception ignored) {
                    break;
                }
            }
        }

        private static int readRemainingLength(DataInputStream in) throws IOException {
            int multiplier = 1;
            int value = 0;
            int encodedByte;
            do {
                encodedByte = in.readUnsignedByte();
                value += (encodedByte & 127) * multiplier;
                multiplier *= 128;
                if (multiplier > 128 * 128 * 128) {
                    throw new IOException("Malformed MQTT remaining length");
                }
            } while ((encodedByte & 128) != 0);
            return value;
        }

        private static void writeRemainingLength(DataOutputStream out, int length) throws IOException {
            int x = length;
            do {
                int encodedByte = x % 128;
                x /= 128;
                if (x > 0) {
                    encodedByte |= 128;
                }
                out.writeByte(encodedByte);
            } while (x > 0);
        }

        @Override
        public void close() {
            running = false;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
