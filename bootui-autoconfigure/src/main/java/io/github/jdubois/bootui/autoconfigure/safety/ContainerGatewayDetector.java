package io.github.jdubois.bootui.autoconfigure.safety;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects whether the JVM is running inside a container and, if so, what the container's default
 * gateway address is.
 *
 * <p>When a BootUI application runs in a container with a published port (for example
 * {@code docker run -p 8080:8080 …}), host&#8594;container traffic is SNAT'd to the container's
 * default gateway (172.17.0.1 on a Linux Docker Engine bridge, 192.168.65.1 on Docker Desktop), so
 * it arrives at the application from that single non-loopback address. {@link LocalhostOnlyFilter}
 * uses this detector to optionally trust that one address as loopback-equivalent without requiring a
 * broad {@code bootui.trusted-proxies} CIDR.</p>
 *
 * <p>All filesystem access is guarded: every method fails closed (reports "not a container" / "no
 * gateway") rather than throwing when the relevant files are missing, unreadable, or malformed —
 * which is also exactly what happens on non-Linux hosts that have no {@code /proc} filesystem.</p>
 *
 * <p>This class has no Spring dependency. A package-private constructor accepting explicit paths is
 * provided so tests can point it at temporary fixtures.</p>
 */
class ContainerGatewayDetector {

    private static final Path DEFAULT_ROUTE_FILE = Paths.get("/proc/net/route");

    private static final List<Path> DEFAULT_CONTAINER_MARKERS =
            List.of(Paths.get("/.dockerenv"), Paths.get("/run/.containerenv"));

    private static final Path DEFAULT_CGROUP_FILE = Paths.get("/proc/1/cgroup");

    private static final String DEFAULT_ROUTE_DESTINATION = "00000000";

    private final Path routeFile;
    private final List<Path> containerMarkerFiles;
    private final Path cgroupFile;

    /** Production constructor wired against the real Linux {@code /proc} paths. */
    ContainerGatewayDetector() {
        this(DEFAULT_ROUTE_FILE, DEFAULT_CONTAINER_MARKERS, DEFAULT_CGROUP_FILE);
    }

    /**
     * Test constructor accepting explicit paths.
     *
     * @param routeFile the {@code /proc/net/route} equivalent to parse for the default gateway
     * @param containerMarkerFiles files whose mere existence indicates a container (for example
     *     {@code /.dockerenv} or Podman's {@code /run/.containerenv})
     * @param cgroupFile the {@code /proc/1/cgroup} equivalent inspected for container runtime markers
     */
    ContainerGatewayDetector(Path routeFile, List<Path> containerMarkerFiles, Path cgroupFile) {
        this.routeFile = routeFile;
        this.containerMarkerFiles = containerMarkerFiles;
        this.cgroupFile = cgroupFile;
    }

    /**
     * Returns {@code true} when the JVM appears to be running inside a container. True when any
     * configured marker file exists (Docker's {@code /.dockerenv}, Podman's {@code /run/.containerenv})
     * or when {@code /proc/1/cgroup} mentions {@code docker}, {@code containerd}, or {@code kubepods}.
     */
    boolean isInContainer() {
        for (Path marker : containerMarkerFiles) {
            if (existsQuietly(marker)) {
                return true;
            }
        }
        return cgroupIndicatesContainer();
    }

    /**
     * Parses the default route from {@code /proc/net/route} and returns its gateway address.
     *
     * @return the container's default gateway, or empty on a non-Linux host, a missing/unreadable
     *     route file, a malformed file, or when there is no default route with a non-zero gateway
     */
    Optional<InetAddress> defaultGateway() {
        List<String> lines = readAllLinesQuietly(routeFile);
        if (lines == null) {
            return Optional.empty();
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] columns = trimmed.split("\\s+");
            if (columns.length < 3) {
                continue;
            }
            String destination = columns[1];
            String gateway = columns[2];
            if (!DEFAULT_ROUTE_DESTINATION.equalsIgnoreCase(destination)) {
                continue;
            }
            if (DEFAULT_ROUTE_DESTINATION.equalsIgnoreCase(gateway)) {
                continue;
            }
            InetAddress address = parseLittleEndianHexIpv4(gateway);
            if (address != null) {
                return Optional.of(address);
            }
        }
        return Optional.empty();
    }

    private boolean cgroupIndicatesContainer() {
        List<String> lines = readAllLinesQuietly(cgroupFile);
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("docker") || lower.contains("containerd") || lower.contains("kubepods")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a {@code /proc/net/route} gateway field (the little-endian hexadecimal native 32-bit
     * representation of an IPv4 address) into an {@link InetAddress}. For example {@code 0101A8C0}
     * decodes to {@code 192.168.1.1} and {@code 010011AC} to {@code 172.17.0.1}.
     *
     * @return the address, or {@code null} when the field is not exactly eight hex digits
     */
    private static InetAddress parseLittleEndianHexIpv4(String hex) {
        if (hex == null || hex.length() != 8) {
            return null;
        }
        int value;
        try {
            value = (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] address = new byte[] {
            (byte) (value & 0xFF),
            (byte) ((value >>> 8) & 0xFF),
            (byte) ((value >>> 16) & 0xFF),
            (byte) ((value >>> 24) & 0xFF)
        };
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean existsQuietly(Path path) {
        try {
            return path != null && Files.exists(path);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> readAllLinesQuietly(Path path) {
        try {
            if (path == null || !Files.isReadable(path)) {
                return null;
            }
            return Files.readAllLines(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
