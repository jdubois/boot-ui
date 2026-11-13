package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.quarkus.cli.QuarkusInstanceLeaseLifecycle;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarkusInstanceLeaseLifecycleTest {

    @TempDir
    Path tempDir;

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void enabledByDefaultInDevelopmentAndDisabledByDefaultInTestLaunchMode() {
        Config empty = config(Map.of("bootui.cli.instances-dir", tempDir.toString()));
        QuarkusServerPortSupplier portSupplier = new QuarkusServerPortSupplier(empty) {
            @Override
            public int localServerPort() {
                return 18090;
            }
        };
        QuarkusApplicationInfo appInfo = new QuarkusApplicationInfo(empty);
        QuarkusInstanceLeaseLifecycle lifecycle = new QuarkusInstanceLeaseLifecycle(empty, portSupplier, appInfo);

        assertThat(lifecycle.registerIfEligible(LaunchMode.TEST)).isNull();
        assertThat(lifecycle.registerIfEligible(LaunchMode.NORMAL)).isNull();
        Path devLease = lifecycle.registerIfEligible(LaunchMode.DEVELOPMENT);
        assertThat(devLease).isNotNull().exists();
        lifecycle.close();
        assertThat(devLease).doesNotExist();
    }

    @Test
    void writesLeaseWithResolvedPortAndRootPathAndDeletesOnClose() throws Exception {
        Config cfg = config(Map.of(
                "quarkus.application.name",
                "quarkus-billing",
                "quarkus.http.port",
                "18090",
                "quarkus.http.root-path",
                "/api-root",
                "bootui.path",
                "/console",
                "bootui.cli.instance-discovery.enabled",
                "true",
                "bootui.cli.instances-dir",
                tempDir.toString()));
        QuarkusServerPortSupplier portSupplier = new QuarkusServerPortSupplier(cfg) {
            @Override
            public int localServerPort() {
                return 18090;
            }
        };
        QuarkusApplicationInfo appInfo = new QuarkusApplicationInfo(cfg);
        QuarkusInstanceLeaseLifecycle lifecycle = new QuarkusInstanceLeaseLifecycle(cfg, portSupplier, appInfo);

        Path written = lifecycle.registerIfEligible(LaunchMode.TEST);
        assertThat(written).isNotNull().exists();
        String json = Files.readString(written);
        assertThat(json)
                .contains("\"instanceId\": \"" + RunIdentity.instanceId() + "\"")
                .contains("\"runId\": \"" + appInfo.run().id() + "\"")
                .contains("\"appName\": \"quarkus-billing\"")
                .contains("\"framework\": \"Quarkus\"")
                .contains("\"baseUrl\": \"http://localhost:18090\"")
                .contains("\"apiPath\": \"/api-root/console/api\"");

        lifecycle.close();
        assertThat(written).doesNotExist();
    }
}
