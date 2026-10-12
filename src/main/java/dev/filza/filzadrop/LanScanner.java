package dev.filza.filzadrop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LanScanner {

    private LanScanner() {}

    public static void scan(
            Consumer<String> onDevice,
            Runnable onFinished
    ) {
        Thread worker = new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(32);
            Set<String> reportedHosts = new HashSet<>();

            try {
                reportArpNeighbors(onDevice, reportedHosts);

                Enumeration<NetworkInterface> interfaces =
                        NetworkInterface.getNetworkInterfaces();

                List<Callable<Void>> tasks = new ArrayList<>();

                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();

                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                        continue;
                    }

                    for (var address : ni.getInterfaceAddresses()) {
                        if (!(address.getAddress() instanceof Inet4Address)
                                || address.getBroadcast() == null) {
                            continue;
                        }

                        int prefix = address.getNetworkPrefixLength();
                        if (prefix < 16 || prefix > 30) {
                            continue;
                        }

                        byte[] interfaceBytes =
                                address.getAddress().getAddress();
                        int host = ((interfaceBytes[0] & 255) << 24)
                                | ((interfaceBytes[1] & 255) << 16)
                                | ((interfaceBytes[2] & 255) << 8)
                                | (interfaceBytes[3] & 255);

                        int mask = (int) (0xFFFFFFFFL << (32 - prefix));
                        int network = host & mask;
                        int broadcast = network | ~mask;
                        int count = broadcast - network - 1;

                        if (count <= 0 || count > 1022) {
                            continue;
                        }

                        for (int i = 1; i <= count; i++) {
                            final int target = network + i;

                            if (target == host) {
                                continue;
                            }

                            tasks.add(() -> {
                                byte[] bytes = {
                                        (byte) (target >>> 24),
                                        (byte) (target >>> 16),
                                        (byte) (target >>> 8),
                                        (byte) target
                                };

                                try {
                                    InetAddress candidate =
                                            InetAddress.getByAddress(bytes);

                                    if (candidate.isReachable(400)) {
                                        String candidateIp =
                                                candidate.getHostAddress();
                                        if (reportedHosts.add(candidateIp)) {
                                            onDevice.accept(candidateIp);
                                        }
                                    }
                                } catch (Exception ignored) {
                                }

                                return null;
                            });
                        }
                    }
                }

                pool.invokeAll(tasks);
            } catch (Exception ex) {
                onDevice.accept("Scan error: " + ex.getMessage());
            } finally {
                pool.shutdownNow();
                onFinished.run();
            }
        }, "filzadrop-lan-scan");

        worker.setDaemon(true);
        worker.start();
    }

    private static void reportArpNeighbors(
            Consumer<String> onDevice,
            Set<String> reportedHosts
    ) {
        try {
            Process process = new ProcessBuilder("arp", "-a")
                    .redirectErrorStream(true)
                    .start();

            Pattern ipv4 = Pattern.compile(
                    "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"
            );
            Pattern macAddress = Pattern.compile(
                    "(?i)(?:[0-9a-f]{2}[-:]){5}[0-9a-f]{2}"
            );
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = ipv4.matcher(line);
                    if (!matcher.find()) {
                        continue;
                    }

                    if (!macAddress.matcher(line).find()) {
                        continue;
                    }

                    String ip = matcher.group();
                    if (!ip.equals("255.255.255.255")
                            && reportedHosts.add(ip)) {
                        onDevice.accept(ip);
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
            // ARP is an optional supplement to the subnet reachability scan.
        }
    }
}
