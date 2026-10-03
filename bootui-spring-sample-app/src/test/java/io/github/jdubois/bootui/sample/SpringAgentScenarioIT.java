package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The correlation scenario's raw-executor route with the BootUI agent attached ({@code docs/PLAN-v2.md} M5-2, step 5):
 * the sample runs in a JVM of its own started with {@code -javaagent}, and every statement the route hands to its raw
 * thread pool is owned by its request at the {@code PROPAGATED} tier. The surefire scenario, without the agent, keeps
 * asserting the same statements unowned. It also follows the {@code work-after-response} seed and its counterexample.
 */
class SpringAgentScenarioIT {

    private static final String RAW_EXECUTOR = "/scenario/raw-executor-query";
    private static final String SEED = "/api/insights/orders/after-response";

    private static Process process;
    private static Path log;
    private static BootUiHttpProbe probe;

    @BeforeAll
    static void startTheSampleWithTheAgent() throws Exception {
        String agent = System.getProperty("bootui.agent.jar");
        assertThat(agent).as("the agent jar, from the dependency plugin").isNotBlank();
        assertThat(Path.of(agent)).exists();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path directory = Path.of("target", "agent-scenario");
        Files.createDirectories(directory);
        log = directory.resolve("sample.log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agent,
                "-Dspring.devtools.restart.enabled=false",
                "-cp",
                System.getProperty("java.class.path"),
                AgentScenarioApplication.class.getName(),
                "--server.port=" + port,
                "--spring.profiles.active=dev",
                "--spring.datasource.url=jdbc:h2:mem:bootui_agent_scenario;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "--bootui.show-banner=false",
                "--bootui.overrides-file=" + directory.resolve("overrides.properties"),
                "--bootui.activity.feed-source=journal",
                "--management.tracing.export.enabled=false"));
        process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        probe = new BootUiHttpProbe("http://localhost:" + port);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (true) {
            assertThat(process.isAlive())
                    .as("the sample is running: %s", tail())
                    .isTrue();
            try {
                // BootUI answers once the web server is up, but the seed tables come from an ApplicationRunner, which
                // runs after it: wait until the counterexample, which reads them, answers too.
                if (probe.get("/bootui/api/java-agent").status() == 200
                        && probe.get(SEED + "/waits").status() == 200) {
                    break;
                }
            } catch (RuntimeException notYet) {
                // still starting
            }
            assertThat(System.nanoTime()).as("the sample started: %s", tail()).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    @AfterAll
    static void stop() throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void everyStatementTheRawExecutorRunsIsOwnedByItsRequestAtThePropagatedTier() throws Exception {
        JsonNode agent = probe.get("/bootui/api/java-agent").json();
        assertThat(agent.path("state").asText()).as(agent.toString()).isEqualTo("ARMED");
        JsonNode sensor = agent.path("sensors").path(0);
        assertThat(sensor.path("id").asText()).isEqualTo("executors");
        assertThat(sensor.path("selfTestPassed").asBoolean())
                .as(sensor.toString())
                .isTrue();

        long since = System.currentTimeMillis() - 1;
        for (int i = 0; i < 4; i++) {
            assertThat(probe.get(RAW_EXECUTOR).status()).isEqualTo(200);
        }
        List<CompletableFuture<Integer>> simultaneous = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            simultaneous.add(
                    CompletableFuture.supplyAsync(() -> probe.get(RAW_EXECUTOR).status()));
        }
        for (CompletableFuture<Integer> status : simultaneous) {
            assertThat(status.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        Thread.sleep(500);

        JsonNode feed = probe.get("/bootui/api/activity?source=journal&limit=5000&since=" + since)
                .json();
        Set<String> requests = new HashSet<>();
        for (JsonNode entry : feed.path("entries")) {
            if ("REQUEST".equals(entry.path("type").asText())
                    && RAW_EXECUTOR.equals(entry.path("path").asText())) {
                requests.add(entry.path("id").asText());
            }
        }
        assertThat(requests).hasSize(8);
        Pattern rawThreads = Pattern.compile(CorrelationScenarioRoutes.RAW_EXECUTOR_THREADS);
        int statements = 0;
        for (JsonNode entry : feed.path("entries")) {
            if ("SQL".equals(entry.path("type").asText())
                    && rawThreads.matcher(entry.path("thread").asText()).matches()) {
                statements++;
                assertThat(entry.path("parentId").asText())
                        .as("a statement on the raw executor is nested under its request: %s", entry)
                        .isIn(requests);
            }
        }
        assertThat(statements)
                .as("every request ran its statement on the raw executor")
                .isEqualTo(8);
        for (String request : requests) {
            JsonNode profile =
                    probe.get("/bootui/api/activity/request/" + request).json();
            JsonNode sql = section(profile, "SQL");
            assertThat(sql.path("total").asInt()).as(profile.toString()).isEqualTo(1);
            assertThat(sql.path("childTiers").path(0).asText()).isEqualTo("PROPAGATED");
            assertThat(sql.path("ambiguous").asInt()).isZero();
            for (JsonNode tier : profile.path("correlationTiers")) {
                if ("PROPAGATED".equals(tier.path("tier").asText())) {
                    assertThat(tier.path("available").asBoolean())
                            .as("the agent propagates for this application: %s", tier)
                            .isTrue();
                }
            }
        }
    }

    @Test
    void workStillRunningAfterTheResponseIsObservedAndTheCounterexampleIsNot() throws Exception {
        assertThat(probe.get(SEED).status()).isEqualTo(200);
        assertThat(probe.get(SEED + "/waits").status()).isEqualTo(200);
        Thread.sleep(1_000);

        JsonNode report = probe.get("/bootui/api/runtime-insights").json();
        List<String> subjects = new ArrayList<>();
        List<String> lazy = new ArrayList<>();
        for (JsonNode observation : report.path("observations")) {
            if ("work-after-response".equals(observation.path("kind").asText())) {
                subjects.add(observation.path("subject").asText());
            } else if ("lazy-sql-after-handler".equals(observation.path("kind").asText())) {
                lazy.add(observation.path("subject").asText());
            }
        }
        assertThat(subjects).as(report.path("checks").toString()).contains("GET " + SEED);
        assertThat(subjects).noneMatch(subject -> subject.endsWith("/waits"));
        assertThat(lazy)
                .as("the propagated task's statement is not lazy loading in the response")
                .noneMatch(subject -> subject.contains(SEED));

        JsonNode feed = probe.get("/bootui/api/activity?source=journal&type=REQUEST&limit=200")
                .json();
        String seedRequest = null;
        for (JsonNode entry : feed.path("entries")) {
            if (SEED.equals(entry.path("path").asText())) {
                seedRequest = entry.path("id").asText();
            }
        }
        assertThat(seedRequest).isNotNull();
        JsonNode journal = probe.get("/bootui/api/activity/request/" + seedRequest + "/journal")
                .json();
        JsonNode handoff = journal.path("handoffs").path(0);
        assertThat(handoff.path("afterResponse").asBoolean())
                .as(journal.toString())
                .isTrue();
        assertThat(handoff.path("sqlCount").asInt()).isEqualTo(1);
        assertThat(handoff.path("executionId").asText()).startsWith("async-");
    }

    private static JsonNode section(JsonNode profile, String type) {
        for (JsonNode section : profile.path("sections")) {
            if (type.equals(section.path("type").asText())) {
                return section;
            }
        }
        throw new AssertionError("no " + type + " section in " + profile);
    }

    private static String tail() {
        try {
            String text = Files.readString(log);
            return text.length() <= 4_000 ? text : text.substring(text.length() - 4_000);
        } catch (IOException ex) {
            return ex.toString();
        }
    }
}
