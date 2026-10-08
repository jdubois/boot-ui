package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The BootUI agent on a real Spring Boot executable jar ({@code docs/PLAN-v2.md} M5-12): the sample's repackaged jar
 * runs with {@code java -javaagent:bootui-agent.jar -jar}, so its classes and libraries load from {@code jar:nested:}
 * URLs through Spring Boot's launcher class loader rather than from a class path of directories and plain jars, as in
 * {@link SpringAgentScenarioIT}. The claim arms with the three default sensors, Code Inventory scans the classes nested
 * in {@code BOOT-INF/classes} and maps the jars in {@code BOOT-INF/lib} to their artifacts, and Code Paths times the
 * seeded slow route through the sample's beans.
 */
class SpringAgentExecutableJarIT {

    private static final String PREFIX = "io.github.jdubois.bootui.sample.inventory.GreetingService#";
    private static final String GREET = PREFIX + "greet(Ljava/lang/String;)Ljava/lang/String;";
    private static final String FAREWELL = PREFIX + "farewell(Ljava/lang/String;)Ljava/lang/String;";

    private static SampleExecutableJar sample;
    private static BootUiHttpProbe probe;

    @BeforeAll
    static void startTheExecutableJarWithTheAgent() throws Exception {
        String agent = System.getProperty("bootui.agent.jar");
        assertThat(agent).as("the agent jar, from the dependency plugin").isNotBlank();
        assertThat(Path.of(agent)).exists();
        sample = SampleExecutableJar.start("agent", List.of("-javaagent:" + agent), List.of());
        probe = sample.probe();
    }

    @AfterAll
    static void stop() throws InterruptedException {
        if (sample != null) {
            sample.close();
        }
    }

    @Test
    void theClaimArmsWithTheDefaultSensorsInstalledAndSelfTested() {
        JsonNode report = probe.get("/bootui/api/java-agent").json();
        assertThat(report.path("state").asText()).as(sample.tail()).isEqualTo("ARMED");
        assertThat(report.path("loadMode").asText()).isEqualTo("javaagent");
        List<String> sensors = new ArrayList<>();
        for (JsonNode sensor : report.path("sensors")) {
            sensors.add(sensor.path("id").asText());
            assertThat(sensor.path("state").asText()).as(sensor.toString()).isEqualTo("installed");
            assertThat(sensor.path("selfTestPassed").asBoolean())
                    .as(sensor.toString())
                    .isTrue();
            assertThat(sensor.path("active").asBoolean()).as(sensor.toString()).isTrue();
            assertThat(sensor.path("instrumentedTypes").asInt())
                    .as(sensor.toString())
                    .isPositive();
        }
        assertThat(sensors)
                .containsExactlyInAnyOrder(
                        "executors",
                        "inventory",
                        "code-paths",
                        "processes",
                        "network",
                        "files",
                        "blocking",
                        "resources");
    }

    @Test
    void codeInventoryReadsTheNestedClassesAndLibraries() throws Exception {
        assertThat(probe.get("/api/hello").status()).isEqualTo(200);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        JsonNode summary;
        do {
            summary = probe.get("/bootui/api/code-inventory").json();
            if ("COMPLETE".equals(summary.path("scan").path("status").asText())) {
                break;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        assertThat(summary.path("available").asBoolean()).as(summary.toString()).isTrue();
        JsonNode scan = summary.path("scan");
        assertThat(scan.path("status").asText()).as(summary.toString()).isEqualTo("COMPLETE");
        // BOOT-INF/classes, read inside the executable jar.
        assertThat(scan.path("roots").asInt()).as(scan.toString()).isPositive();
        assertThat(scan.path("classes").asInt()).as(scan.toString()).isPositive();
        assertThat(scan.path("skipped").asInt()).as(scan.toString()).isZero();
        assertThat(summary.path("methods").path("executed").asInt()).isPositive();
        assertThat(summary.path("dependencies").path("declared").asInt()).isPositive();

        JsonNode methods = probe.get("/bootui/api/code-inventory/methods?class=GreetingService")
                .json();
        JsonNode greet = method(methods, GREET);
        assertThat(greet.path("status").asText()).as(greet.toString()).isEqualTo("EXECUTED");
        assertThat(greet.path("firstRoute").asText()).as(greet.toString()).isEqualTo("GET /api/hello");
        // Never called. Nothing here waits for the inventory sensor before the context starts, as the scenario
        // application does, so a class loaded before it installed is honestly not tracked rather than never executed.
        assertThat(method(methods, FAREWELL).path("status").asText()).isIn("NEVER_EXECUTED", "NOT_TRACKED");

        // The jars in BOOT-INF/lib, mapped from their jar:nested: code sources to the declared artifacts.
        assertThat(artifacts("not-loaded")).contains("commons-exec");
        // HikariCP and Flyway load once the context starts, after the claim; Spring's own jars load before it.
        assertThat(artifacts("loaded")).contains("HikariCP", "flyway-core").doesNotContain("commons-exec");
    }

    @Test
    void codePathsNameTheSlowMethodOfTheSeededRoute() throws Exception {
        String route = "GET /api/quotes/{sku}";
        for (int i = 0; i < 8; i++) {
            assertThat(probe.get("/api/quotes/sku-" + i).status()).isEqualTo(200);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode row = null;
        JsonNode report;
        do {
            Thread.sleep(250);
            report = probe.get("/bootui/api/code-paths").json();
            for (JsonNode candidate : report.path("routes")) {
                if (route.equals(candidate.path("route").asText())) {
                    row = candidate;
                }
            }
        } while ((row == null || row.path("warmRequests").asInt() < 7) && System.nanoTime() < deadline);
        assertThat(report.path("available").asBoolean()).as(report.toString()).isTrue();
        assertThat(row).as(report.toString()).isNotNull();
        assertThat(row.path("warmRequests").asInt()).as(row.toString()).isEqualTo(7);
        assertThat(row.path("assemblyOnly").asBoolean()).isFalse();
        assertThat(row.path("topMethods").path(0).path("method").asText())
                .as(row.toString())
                .isEqualTo("io.github.jdubois.bootui.sample.codepaths.SlowPricingService#quote(Ljava/lang/String;)I");
    }

    private static JsonNode method(JsonNode methods, String key) {
        for (JsonNode method : methods.path("methods")) {
            if (key.equals(method.path("key").asText())) {
                return method;
            }
        }
        throw new AssertionError("No method " + key + " in " + methods);
    }

    private static List<String> artifacts(String status) {
        JsonNode report = probe.get("/bootui/api/code-inventory/dependencies?status=" + status + "&limit=1000")
                .json();
        List<String> artifacts = new ArrayList<>();
        for (JsonNode dependency : report.path("dependencies")) {
            artifacts.add(dependency.path("artifactId").asText());
        }
        return artifacts;
    }
}
