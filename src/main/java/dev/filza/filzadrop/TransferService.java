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
import java.util.Enumeration;
import java.util.function.LongConsumer;

final class TransferService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int PORT = 45821, SALT = 16, NONCE = 12, TAG_BITS = 128, BUFFER = 64 * 1024, ITERATIONS = 310_000;
    private static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;
    private TransferService() {}

    static String newCode() { return String.format(java.util.Locale.ROOT, "%08d", RNG.nextInt(100_000_000)); }
    static Path receiveDir() { return Path.of(System.getProperty("user.home"), "Downloads", "FilzaDrop"); }
    static String localIp() {
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) { NetworkInterface n = ns.nextElement(); if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                Enumeration<InetAddress> as = n.getInetAddresses(); while (as.hasMoreElements()) { InetAddress a = as.nextElement(); if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress(); }
            }
        } catch (SocketException ignored) {}
        return "Find this PC's IPv4 address";
    }

    static void send(Path file, String host, int port, String code, LongConsumer progress) throws Exception {
        long size = Files.size(file); if (size > MAX_BYTES) throw new IOException("Maximum file size in this version is 10 GiB.");
        byte[] salt = random(SALT), nonce = random(NONCE); Cipher cipher = cipher(Cipher.ENCRYPT_MODE, code, salt, nonce);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 8_000); socket.setSoTimeout(30_000);
            OutputStream raw = socket.getOutputStream(); raw.write(salt); raw.write(nonce); raw.flush();
            // Only one wrapper owns/closes the cipher stream; closing it writes the GCM tag.
            try (DataOutputStream out = new DataOutputStream(new CipherOutputStream(raw, cipher)); InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                out.writeUTF(file.getFileName().toString()); out.writeLong(size); byte[] buf = new byte[BUFFER]; long sent = 0; int n;
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); sent += n; progress.accept(sent); }
                out.flush();
            }
        }
    }

    static Path receive(ServerSocket server, String code, Path directory, LongConsumer progress) throws Exception {
        Files.createDirectories(directory); server.setSoTimeout(120_000);
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(120_000); socket.setTcpNoDelay(true); InputStream raw = socket.getInputStream();
            byte[] salt = readExactly(raw, SALT), nonce = readExactly(raw, NONCE); Cipher cipher = cipher(Cipher.DECRYPT_MODE, code, salt, nonce);
            Path temp = null; boolean committed = false;
            try (DataInputStream in = new DataInputStream(new CipherInputStream(raw, cipher))) {
                String name = safeName(in.readUTF()); long size = in.readLong();
                if (size < 0 || size > MAX_BYTES) throw new IOException("Invalid file size; maximum is 10 GiB.");
                temp = Files.createTempFile(directory, ".filzadrop-", ".part");
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                    byte[] buf = new byte[BUFFER]; long got = 0; while (got < size) { int n = in.read(buf, 0, (int)Math.min(buf.length, size - got)); if (n < 0) throw new EOFException("Connection ended before the full file arrived."); out.write(buf, 0, n); got += n; progress.accept(got); }
                }
                // Drain to EOF: AES-GCM authenticates the payload only when its final tag is checked.
                if (in.read() != -1) throw new IOException("Unexpected extra data after file payload.");
                Path target = uniquePath(directory, name);
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target); }
                committed = true; return target;
            } catch (IOException e) {
                throw new IOException("Transfer rejected or interrupted. Check the pairing code and connection; incomplete files are discarded.", e);
            } finally { if (!committed && temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
        }
    }

    private static Cipher cipher(int mode, String code, byte[] salt, byte[] nonce) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce)); return c; }
            finally { java.util.Arrays.fill(key, (byte)0); }
        } finally { spec.clearPassword(); }
    }
    private static byte[] random(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }
    private static byte[] readExactly(InputStream in, int length) throws IOException { byte[] b = new byte[length]; int off=0; while(off<length){int n=in.read(b,off,length-off);if(n<0)throw new EOFException("Connection closed during handshake.");off+=n;}return b; }
    private static String safeName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Empty filename."); String n = name.replace('\\','/'); n=n.substring(n.lastIndexOf('/')+1); n=n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim(); while(n.startsWith(".")) n=n.substring(1); if(n.isBlank()||n.equals(".."))n="received-file"; return n.length()>180?n.substring(0,180):n;
    }
    private static Path uniquePath(Path dir, String file) throws IOException {
        Path p=dir.resolve(file); if(!Files.exists(p))return p; String base=file, ext=""; int dot=file.lastIndexOf('.'); if(dot>0){base=file.substring(0,dot);ext=file.substring(dot);} for(int i=1;i<10000;i++){p=dir.resolve(base+" ("+i+")"+ext);if(!Files.exists(p))return p;} throw new IOException("Could not choose a unique destination name.");
    }
    static String bytes(long n) { if(n<1024)return n+" B"; double x=n; String[] u={"KB","MB","GB","TB"}; int i=-1; do{x/=1024;i++;}while(x>=1024&&i<u.length-1);return String.format(java.util.Locale.ROOT,"%.1f %s",x,u[i]); }
}
