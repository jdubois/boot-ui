package io.github.jdubois.bootui.autoconfigure.safety;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Detects whether the JVM is running inside a container and, if so, which non-loopback address(es)
 * published-port traffic arrives from, so they can be trusted as loopback-equivalent.
 *
 * <p>When a BootUI application runs in a container with a published port (for example
 * {@code docker run -p 8080:8080 …}), host&#8594;container traffic is SNAT'd to a single non-loopback
 * address. There are two distinct cases:</p>
 * <ul>
 *   <li><strong>Linux Docker Engine</strong> — the SNAT source is the container's default gateway on
 *       the bridge network (for example {@code 172.17.0.1}), which is recorded in
 *       {@code /proc/net/route}. See {@link #defaultGateway()}.</li>
 *   <li><strong>Docker Desktop (macOS/Windows)</strong> — the SNAT source is the Docker Desktop host
 *       gateway ({@code 192.168.65.1}), which is <em>not</em> the container's route-table gateway
 *       (that is still the bridge, for example {@code 172.17.0.1}). It is instead published to the
 *       container as the DNS name {@code gateway.docker.internal}. See
 *       {@link #dockerDesktopGateways()}.</li>
 * </ul>
 *
 * <p>{@link LocalhostOnlyFilter} uses this detector to optionally trust those addresses without
 * requiring a broad {@code bootui.trusted-proxies} CIDR.</p>
 *
 * <p>All detection is guarded: every method fails closed (reports "not a container" / "no gateway")
 * rather than throwing when the relevant files are missing, unreadable, or malformed, or when the
 * Docker Desktop DNS name does not resolve — which is also exactly what happens on non-Linux hosts
 * that have no {@code /proc} filesystem and on plain Linux Docker Engine where
 * {@code gateway.docker.internal} is not defined.</p>
 *
 * <p>This class has no Spring dependency. Package-private constructors accepting explicit paths (and
 * a {@link HostResolver}) are provided so tests can point it at temporary fixtures and a fake
 * resolver.</p>
 */
class ContainerGatewayDetector {

    private static final Path DEFAULT_ROUTE_FILE = Paths.get("/proc/net/route");

    private static final List<Path> DEFAULT_CONTAINER_MARKERS =
            List.of(Paths.get("/.dockerenv"), Paths.get("/run/.containerenv"));

    private static final Path DEFAULT_CGROUP_FILE = Paths.get("/proc/1/cgroup");

    private static final String DEFAULT_ROUTE_DESTINATION = "00000000";

    /**
     * DNS name that Docker Desktop injects into every container; its IPv4 address is the SNAT source
     * of published-port traffic ({@code 192.168.65.1}).
     */
    private static final String DOCKER_DESKTOP_GATEWAY_HOST = "gateway.docker.internal";

    /** Seam for resolving a host name to its addresses, so tests can avoid real DNS. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Path routeFile;
    private final List<Path> containerMarkerFiles;
    private final Path cgroupFile;
    private final HostResolver hostResolver;

    /** Production constructor wired against the real Linux {@code /proc} paths and system resolver. */
    ContainerGatewayDetector() {
        this(DEFAULT_ROUTE_FILE, DEFAULT_CONTAINER_MARKERS, DEFAULT_CGROUP_FILE, InetAddress::getAllByName);
    }

    /**
     * Test constructor accepting explicit paths and the real system resolver.
     *
     * @param routeFile the {@code /proc/net/route} equivalent to parse for the default gateway
     * @param containerMarkerFiles files whose mere existence indicates a container (for example
     *     {@code /.dockerenv} or Podman's {@code /run/.containerenv})
     * @param cgroupFile the {@code /proc/1/cgroup} equivalent inspected for container runtime markers
     */
    ContainerGatewayDetector(Path routeFile, List<Path> containerMarkerFiles, Path cgroupFile) {
        this(routeFile, containerMarkerFiles, cgroupFile, InetAddress::getAllByName);
    }

    /**
     * Test constructor accepting explicit paths and an injectable {@link HostResolver}.
     *
     * @param routeFile the {@code /proc/net/route} equivalent to parse for the default gateway
     * @param containerMarkerFiles files whose mere existence indicates a container
     * @param cgroupFile the {@code /proc/1/cgroup} equivalent inspected for container runtime markers
     * @param hostResolver resolver used for {@link #dockerDesktopGateways()} (defaults to DNS)
     */
    ContainerGatewayDetector(
            Path routeFile, List<Path> containerMarkerFiles, Path cgroupFile, HostResolver hostResolver) {
        this.routeFile = routeFile;
        this.containerMarkerFiles = containerMarkerFiles;
        this.cgroupFile = cgroupFile;
        this.hostResolver = hostResolver;
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

    /**
     * Resolves the Docker Desktop host gateway, published in every Docker Desktop container as the
     * DNS name {@code gateway.docker.internal}. On Docker Desktop (macOS/Windows) this resolves to
     * {@code 192.168.65.1} (and, on dual-stack setups, an additional IPv6 address), which is the
     * SNAT source of published-port traffic — and which is <em>not</em> the container's route-table
     * default gateway ({@code 172.17.0.1} on the docker0 bridge) returned by {@link #defaultGateway()}.
     *
     * @return every non-loopback address {@code gateway.docker.internal} resolves to, or an empty set
     *     on hosts where the name does not resolve (native Linux Docker Engine, or no container at
     *     all). Never throws.
     */
    Set<InetAddress> dockerDesktopGateways() {
        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(DOCKER_DESKTOP_GATEWAY_HOST);
        } catch (UnknownHostException | RuntimeException e) {
            return Set.of();
        }
        if (resolved == null) {
            return Set.of();
        }
        Set<InetAddress> gateways = new LinkedHashSet<>();
        for (InetAddress address : resolved) {
            if (address != null && !address.isLoopbackAddress() && !address.isAnyLocalAddress()) {
                gateways.add(address);
            }
        }
        return Set.copyOf(gateways);
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
