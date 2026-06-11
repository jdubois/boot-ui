package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ContainerGatewayDetector}.
 *
 * <p>The {@code /proc/net/route} gateway field is the little-endian native 32-bit hex form of the
 * address, so the canonical {@code 192.168.1.1} default gateway is written {@code 0101A8C0}.
 * Accordingly Docker Desktop's {@code 192.168.65.1} is {@code 0141A8C0} and the Linux Docker bridge
 * {@code 172.17.0.1} is {@code 010011AC}.</p>
 */
class ContainerGatewayDetectorTests {

    private static final String HEADER =
            "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT";

    @TempDir
    Path tempDir;

    private ContainerGatewayDetector detector(Path routeFile, List<Path> markers, Path cgroupFile) {
        return new ContainerGatewayDetector(routeFile, markers, cgroupFile);
    }

    private Path writeRoute(String... rows) throws IOException {
        Path route = tempDir.resolve("route");
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        for (String row : rows) {
            content.append(row).append('\n');
        }
        Files.writeString(route, content.toString());
        return route;
    }

    @Test
    void parsesDockerDesktopGateway() throws IOException {
        Path route = writeRoute(
                "eth0\t00000000\t0141A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0",
                "eth0\t0041A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0");

        Optional<InetAddress> gateway = detector(route, List.of(), missing()).defaultGateway();

        assertThat(gateway).isPresent();
        assertThat(gateway.get().getHostAddress()).isEqualTo("192.168.65.1");
    }

    @Test
    void parsesDockerEngineBridgeGateway() throws IOException {
        Path route = writeRoute("eth0\t00000000\t010011AC\t0003\t0\t0\t0\t00000000\t0\t0\t0");

        Optional<InetAddress> gateway = detector(route, List.of(), missing()).defaultGateway();

        assertThat(gateway).isPresent();
        assertThat(gateway.get().getHostAddress()).isEqualTo("172.17.0.1");
    }

    @Test
    void returnsEmptyWhenNoDefaultRoute() throws IOException {
        Path route = writeRoute("eth0\t0041A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0");

        assertThat(detector(route, List.of(), missing()).defaultGateway()).isEmpty();
    }

    @Test
    void returnsEmptyWhenDefaultRouteHasNoGateway() throws IOException {
        Path route = writeRoute("eth0\t00000000\t00000000\t0001\t0\t0\t0\t00000000\t0\t0\t0");

        assertThat(detector(route, List.of(), missing()).defaultGateway()).isEmpty();
    }

    @Test
    void returnsEmptyForMalformedRows() throws IOException {
        Path route = writeRoute("garbage", "eth0\t00000000\tZZZZZZZZ\t0003", "   ", "eth0\t00000000");

        assertThat(detector(route, List.of(), missing()).defaultGateway()).isEmpty();
    }

    @Test
    void returnsEmptyForMissingRouteFile() {
        assertThat(detector(missing(), List.of(), missing()).defaultGateway()).isEmpty();
    }

    @Test
    void isInContainerWhenMarkerFileExists() throws IOException {
        Path marker = tempDir.resolve(".dockerenv");
        Files.writeString(marker, "");

        assertThat(detector(missing(), List.of(marker), missing()).isInContainer())
                .isTrue();
    }

    @Test
    void isNotInContainerWhenNoMarkerAndNoCgroupHint() {
        assertThat(detector(missing(), List.of(missing()), missing()).isInContainer())
                .isFalse();
    }

    @Test
    void isInContainerWhenCgroupMentionsDocker() throws IOException {
        Path cgroup = tempDir.resolve("cgroup");
        Files.writeString(cgroup, "12:cpu:/docker/abc123\n11:memory:/docker/abc123\n");

        assertThat(detector(missing(), List.of(missing()), cgroup).isInContainer())
                .isTrue();
    }

    @Test
    void isInContainerWhenCgroupMentionsKubepods() throws IOException {
        Path cgroup = tempDir.resolve("cgroup");
        Files.writeString(cgroup, "0::/kubepods/besteffort/pod123\n");

        assertThat(detector(missing(), List.of(missing()), cgroup).isInContainer())
                .isTrue();
    }

    @Test
    void isNotInContainerWhenCgroupHasNoContainerHint() throws IOException {
        Path cgroup = tempDir.resolve("cgroup");
        Files.writeString(cgroup, "0::/init.scope\n");

        assertThat(detector(missing(), List.of(missing()), cgroup).isInContainer())
                .isFalse();
    }

    private Path missing() {
        return tempDir.resolve("does-not-exist-" + System.nanoTime());
    }
}
