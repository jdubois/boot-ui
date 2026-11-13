package io.github.jdubois.bootui.engine.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstanceLeaseTests {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"../evil", "app/name", "app\\name", "..", ".", "", "id with spaces", "a/b"})
    void rejectsPathTraversalOrUnsafeIdentifiers(String unsafeId) {
        assertThatThrownBy(() -> new InstanceLease(
                        unsafeId,
                        "12345678",
                        1234L,
                        1700000000000L,
                        "order-service",
                        "Spring Boot",
                        "http://localhost:8080",
                        "/bootui/api",
                        1700000000000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");

        assertThatThrownBy(() -> new InstanceLease(
                        "12345678",
                        unsafeId,
                        1234L,
                        1700000000000L,
                        "order-service",
                        "Spring Boot",
                        "http://localhost:8080",
                        "/bootui/api",
                        1700000000000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.com:8080",
                "http://192.168.1.10:8080",
                "http://localhost",
                "ftp://localhost:8080",
                "http://user:pass@localhost:8080",
                "http://localhost:8080/extra",
                "http://localhost:8080?x=1"
            })
    void rejectsNonLoopbackOrMalformedBaseUrls(String invalidUrl) {
        assertThatThrownBy(() -> new InstanceLease(
                        "a1b2c3d4",
                        "e5f60718",
                        1234L,
                        1700000000000L,
                        "order-service",
                        "Spring Boot",
                        invalidUrl,
                        "/bootui/api",
                        1700000000000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void composesFullEffectiveApiPathAcrossContextServletWebFluxAndQuarkusPrefixes() {
        assertThat(InstanceLease.composeApiPath("/bootui/api")).isEqualTo("/bootui/api");
        assertThat(InstanceLease.composeApiPath("/bootui/api", "", "/")).isEqualTo("/bootui/api");
        assertThat(InstanceLease.composeApiPath("/bootui/api", "/shop/", "/mvc/"))
                .isEqualTo("/shop/mvc/bootui/api");
        assertThat(InstanceLease.composeApiPath("/ops/api", "reactive")).isEqualTo("/reactive/ops/api");
        assertThat(InstanceLease.composeApiPath("/bootui/api", "/q")).isEqualTo("/q/bootui/api");
    }

    @Test
    void writesAtomicLeaseWithOwnerOnlyPermissionsAndDeletesOnClose() throws IOException {
        Path bootUiHome = tempDir.resolve(".bootui");
        Path instancesDir = bootUiHome.resolve("instances");
        RunIdentity run = RunIdentity.start();
        InstanceLease lease = InstanceLease.forCurrentProcess(
                run, "payment-service", "Spring Boot", "http://127.0.0.1:8085/", "/pay/bootui/api");

        Path written;
        try (InstanceLeaseManager manager = new InstanceLeaseManager(instancesDir)) {
            written = manager.write(lease);
            assertThat(written).isEqualTo(instancesDir.resolve(RunIdentity.instanceId() + ".json"));
            assertThat(written).exists();
            String json = Files.readString(written);
            assertThat(json)
                    .contains("\"instanceId\": \"" + RunIdentity.instanceId() + "\"")
                    .contains("\"runId\": \"" + run.id() + "\"")
                    .contains("\"appName\": \"payment-service\"")
                    .contains("\"framework\": \"Spring Boot\"")
                    .contains("\"baseUrl\": \"http://127.0.0.1:8085\"")
                    .contains("\"apiPath\": \"/pay/bootui/api\"")
                    .doesNotContain("token")
                    .doesNotContain("mcpEnabled");

            if (Files.getFileStore(instancesDir).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(instancesDir);
                Set<PosixFilePermission> parentPerms = Files.getPosixFilePermissions(bootUiHome);
                Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(written);
                assertThat(dirPerms).isEqualTo(PosixFilePermissions.fromString("rwx------"));
                assertThat(parentPerms).isEqualTo(PosixFilePermissions.fromString("rwx------"));
                assertThat(filePerms).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
        }

        assertThat(written).doesNotExist();
    }

    @Test
    void resolvesInstancesDirectoryFromExplicitConfigBootUiHomeOrUserHome() {
        Path explicit = tempDir.resolve("custom-instances");
        assertThat(InstanceLeaseManager.resolveInstancesDir(
                        explicit.toString(), Map.of("BOOTUI_HOME", "/ignored"), "/ignored-home"))
                .isEqualTo(explicit.toAbsolutePath().normalize());

        Path bootUiHome = tempDir.resolve("env-home");
        assertThat(InstanceLeaseManager.resolveInstancesDir(
                        null, Map.of("BOOTUI_HOME", bootUiHome.toString()), "/ignored-home"))
                .isEqualTo(bootUiHome.resolve("instances").toAbsolutePath().normalize());

        Path userHome = tempDir.resolve("user-home");
        assertThat(InstanceLeaseManager.resolveInstancesDir(null, Map.of(), userHome.toString()))
                .isEqualTo(userHome.resolve(".bootui")
                        .resolve("instances")
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void refusesToWriteIntoSymbolicLinkInstancesDirectory() throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real"));
        Path symlinkDir = tempDir.resolve("symlink-instances");
        Files.createSymbolicLink(symlinkDir, realDir);

        InstanceLease lease = InstanceLease.forCurrentProcess(
                RunIdentity.start(), "app", "Spring Boot", "http://localhost:8080", "/bootui/api");

        try (InstanceLeaseManager manager = new InstanceLeaseManager(symlinkDir)) {
            assertThatThrownBy(() -> manager.write(lease)).isInstanceOf(IOException.class);
        }
    }
}
