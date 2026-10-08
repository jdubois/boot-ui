package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.scenario.CorrelationScenarioRoutes;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                // The seeded report's title: a value Side Effects must never show (M5-5d).
                "-Dsample.report.title=bootui-title-never-shown",
                "-cp",
                System.getProperty("java.class.path"),
                AgentScenarioApplication.class.getName(),
                "--server.port=" + port,
                "--spring.profiles.active=dev",
                "--spring.datasource.url=jdbc:h2:mem:bootui_agent_scenario;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "--bootui.show-banner=false",
                "--bootui.overrides-file=" + directory.resolve("overrides.properties"),
                "--bootui.activity.feed-source=journal",
                // The default sensors, files, environment, and blocking among them, for the Side Effects seeds, and
                // the opt-in caught-exceptions sensor, for the Exceptions panel's caught-in-code seeds.
                "--bootui.agent.sensors=executors,inventory,code-paths,processes,network,files,environment,blocking,"
                        + "caught-exceptions",
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

    /**
     * The Exceptions panel's caught-in-code seeds (M5-6a2): the {@code IOException} {@code StockLookup.stockOrDefault}
     * drops is a finding, not seen rethrown or logged at {@code WARN} or above, once its request settled; the same
     * exception logged, logged by its message, wrapped and rethrown, or handed on as a failed future is not.
     */
    @Test
    void theSwallowedSeedIsAFindingAndItsCounterexamplesAreNot() throws Exception {
        Map<String, String> seeds = Map.of(
                "swallowed", "stockOrDefault",
                "logged", "stockLogged",
                "logged-message", "stockLoggedMessage",
                "rethrown", "stockOrFail",
                "handed-on", "stockLater");
        for (String route : seeds.keySet()) {
            assertThat(probe.get("/api/caught/" + route).status())
                    .as(route)
                    .isEqualTo(route.equals("rethrown") ? 404 : 200);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode report;
        while (true) {
            report = probe.get("/bootui/api/exceptions/caught").json();
            assertThat(report.path("available").asBoolean())
                    .as(report.toString())
                    .isTrue();
            boolean settled = true;
            for (String method : seeds.values()) {
                JsonNode row = stockRow(report, method);
                settled &= row != null && row.path("pending").asLong() == 0;
            }
            if (settled) {
                break;
            }
            assertThat(System.nanoTime()).as("the seeds settled: %s", report).isLessThan(deadline);
            Thread.sleep(500);
        }
        JsonNode swallowed = stockRow(report, "stockOrDefault");
        assertThat(swallowed.path("finding").asBoolean()).as(report.toString()).isTrue();
        assertThat(swallowed.path("notRethrownOrLogged").asLong()).isPositive();
        assertThat(swallowed.path("family").asText()).isEqualTo("io");
        assertThat(swallowed.path("route").asText()).isEqualTo("/api/caught/swallowed");
        assertThat(stockRow(report, "stockLogged").path("logged").asLong())
                .as(report.toString())
                .isPositive();
        assertThat(stockRow(report, "stockLoggedMessage").path("logged").asLong())
                .as(report.toString())
                .isPositive();
        assertThat(stockRow(report, "stockOrFail").path("rethrown").asLong())
                .as(report.toString())
                .isPositive();
        assertThat(stockRow(report, "stockLater").path("handedOn").asLong())
                .as(report.toString())
                .isPositive();
        for (String method : List.of("stockLogged", "stockLoggedMessage", "stockOrFail", "stockLater")) {
            JsonNode row = stockRow(report, method);
            assertThat(row.path("finding").asBoolean()).as(row.toString()).isFalse();
            assertThat(row.path("notRethrownOrLogged").asLong())
                    .as(row.toString())
                    .isZero();
        }
        JsonNode summary = probe.get("/bootui/api/exceptions").json().path("caughtInCode");
        assertThat(summary.path("findings").asInt()).as(summary.toString()).isPositive();
        // Never a message, a stack trace, or an identity.
        assertThat(report.toString()).doesNotContain("unavailable for console").doesNotContain("identity");
    }

    private static JsonNode stockRow(JsonNode report, String method) {
        for (JsonNode row : report.path("rows")) {
            if (row.path("siteClass").asText().endsWith(".StockLookup")
                    && method.equals(row.path("method").asText())) {
                return row;
            }
        }
        return null;
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
        assertThat(sensor.path("active").asBoolean()).as(sensor.toString()).isTrue();
        for (JsonNode hook : sensor.path("hooks")) {
            assertThat(hook.path("selfTest").asText()).as(hook.toString()).isNotEqualTo("failed");
        }

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
        Set<String> earlier = requests(SEED + "/waits");
        assertThat(probe.get(SEED).status()).isEqualTo(200);
        assertThat(probe.get(SEED + "/waits").status()).isEqualTo(200);

        // The seeded task queries 200 ms after its response, and the counterexample's worker may close its handoff a
        // little after its response: wait, bounded, until the report sees the first and the journal holds the second.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode report;
        boolean waitsRecorded;
        List<String> subjects = new ArrayList<>();
        List<String> lazy = new ArrayList<>();
        do {
            Thread.sleep(100);
            // Read before the report, so the report asserted on was taken once the counterexample's handoff was in.
            waitsRecorded = handoffRecorded(SEED + "/waits", earlier);
            report = probe.get("/bootui/api/runtime-insights").json();
            subjects.clear();
            lazy.clear();
            for (JsonNode observation : report.path("observations")) {
                if ("work-after-response".equals(observation.path("kind").asText())) {
                    subjects.add(observation.path("subject").asText());
                } else if ("lazy-sql-after-handler"
                        .equals(observation.path("kind").asText())) {
                    lazy.add(observation.path("subject").asText());
                }
            }
        } while (!(waitsRecorded && subjects.contains("GET " + SEED)) && System.nanoTime() < deadline);
        assertThat(waitsRecorded)
                .as("the counterexample's handoff was recorded")
                .isTrue();
        assertThat(subjects).as(report.path("checks").toString()).contains("GET " + SEED);
        assertThat(subjects).noneMatch(subject -> subject.endsWith("/waits"));
        assertThat(lazy)
                .as("the propagated task's statement is not lazy loading in the response")
                .noneMatch(subject -> subject.contains(SEED));

        JsonNode feed = probe.get("/bootui/api/activity?source=journal&type=REQUEST&limit=200")
                .json();
        // The feed is newest first: this test's own seed request, not one an earlier test may have made.
        String seedRequest = null;
        for (JsonNode entry : feed.path("entries")) {
            if (SEED.equals(entry.path("path").asText())) {
                seedRequest = entry.path("id").asText();
                break;
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

    /**
     * Code Inventory with the agent ({@code docs/PLAN-v2.md} §5.15): the seeded never-called method is never executed, the
     * one {@code GET /api/hello} calls is executed with its first request, and the declared jar nothing loads is not
     * loaded in this run. Every read answers the available shape.
     */
    @Test
    void codeInventoryListsTheSeedsAndTheDeclaredJarNothingLoads() throws Exception {
        assertThat(probe.get("/api/hello").status()).isEqualTo(200);
        String greet =
                "io.github.jdubois.bootui.sample.inventory.GreetingService#greet(Ljava/lang/String;)Ljava/lang/String;";
        String farewell =
                "io.github.jdubois.bootui.sample.inventory.GreetingService#farewell(Ljava/lang/String;)Ljava/lang/String;";

        // The scan of the sample's class files runs off the request path once the run started: wait for it, bounded.
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
        assertThat(summary.path("unavailableReason").isNull()).isTrue();
        assertThat(summary.path("scan").path("status").asText())
                .as(summary.toString())
                .isEqualTo("COMPLETE");
        assertThat(summary.path("run").path("generation").asLong()).isPositive();
        assertThat(summary.path("methods").path("tracked").asInt()).isPositive();
        assertThat(summary.path("methods").path("executed").asInt()).isPositive();
        assertThat(summary.path("changes").path("previousRun").asBoolean()).isFalse();
        assertThat(summary.path("dependencies").path("declared").asInt()).isPositive();
        assertThat(summary.path("limitations").isArray()).isTrue();

        JsonNode methods = probe.get("/bootui/api/code-inventory/methods?class=GreetingService")
                .json();
        JsonNode greetRow = null;
        JsonNode farewellRow = null;
        for (JsonNode method : methods.path("methods")) {
            if (greet.equals(method.path("key").asText())) {
                greetRow = method;
            } else if (farewell.equals(method.path("key").asText())) {
                farewellRow = method;
            }
        }
        assertThat(farewellRow).as(methods.toString()).isNotNull();
        assertThat(farewellRow.path("status").asText())
                .as(farewellRow.toString())
                .isEqualTo("NEVER_EXECUTED");
        assertThat(greetRow).as(methods.toString()).isNotNull();
        assertThat(greetRow.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(greetRow.path("firstRequestId").asText())
                .as("its first call belongs to the request that made it: %s", greetRow)
                .matches("[0-9a-f]{16}");
        assertThat(methods.path("page").path("matched").asInt()).isGreaterThanOrEqualTo(3);

        JsonNode notLoaded = probe.get("/bootui/api/code-inventory/dependencies?status=not-loaded&limit=1000")
                .json();
        List<String> artifacts = new ArrayList<>();
        for (JsonNode dependency : notLoaded.path("dependencies")) {
            artifacts.add(dependency.path("groupId").asText() + ":"
                    + dependency.path("artifactId").asText());
        }
        assertThat(artifacts).as(notLoaded.toString()).contains("org.apache.commons:commons-exec");
        JsonNode loaded = probe.get("/bootui/api/code-inventory/dependencies?status=loaded&limit=1000")
                .json();
        List<String> loadedArtifacts = new ArrayList<>();
        for (JsonNode dependency : loaded.path("dependencies")) {
            loadedArtifacts.add(dependency.path("artifactId").asText());
        }
        assertThat(loadedArtifacts)
                .as(loaded.toString())
                .contains("spring-core")
                .doesNotContain("commons-exec");

        JsonNode changes = probe.get("/bootui/api/code-inventory/changes").json();
        assertThat(changes.path("available").asBoolean()).isTrue();
        assertThat(changes.path("counts").path("previousRun").asBoolean()).isFalse();
        assertThat(changes.path("changes").isArray()).isTrue();
        assertThat(changes.path("page").isObject()).isTrue();
    }

    /**
     * The code-paths sensor with the agent ({@code docs/PLAN-v2.md} M5-4a): installed beside the inventory sensor on
     * the same transformer, self-tested, active for this claim, and recording a fragment for each request through the
     * sample's beans, with nothing dropped and no internal error.
     */
    @Test
    void theCodePathsSensorRecordsTheRequestsThroughTheSamplesBeans() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/hello").status()).isEqualTo(200);
        }
        JsonNode codePaths = null;
        JsonNode inventory = null;
        for (JsonNode sensor : probe.get("/bootui/api/java-agent").json().path("sensors")) {
            if ("code-paths".equals(sensor.path("id").asText())) {
                codePaths = sensor;
            } else if ("inventory".equals(sensor.path("id").asText())) {
                inventory = sensor;
            }
        }
        assertThat(codePaths).as("the code-paths sensor's row").isNotNull();
        assertThat(inventory).as("the inventory sensor's row").isNotNull();
        assertThat(codePaths.path("state").asText()).as(codePaths.toString()).isEqualTo("installed");
        assertThat(codePaths.path("selfTestPassed").asBoolean())
                .as(codePaths.toString())
                .isTrue();
        assertThat(codePaths.path("active").asBoolean())
                .as(codePaths.toString())
                .isTrue();
        assertThat(codePaths.path("instrumentedTypes").asInt())
                .as(codePaths.toString())
                .isPositive();
        JsonNode counters = codePaths.path("codePaths");
        assertThat(counters.path("fragmentsFlushed").asLong())
                .as(counters.toString())
                .isGreaterThanOrEqualTo(3);
        assertThat(counters.path("fragmentsDropped").asLong()).isZero();
        assertThat(counters.path("queueDropped").asLong()).isZero();
        assertThat(counters.path("errors").asLong()).isZero();
        assertThat(counters.path("disabledReason").isNull()).isTrue();
        assertThat(inventory.path("selfTestPassed").asBoolean())
                .as(inventory.toString())
                .isTrue();
    }

    /**
     * Code Paths with the agent ({@code docs/PLAN-v2.md} §5.14, M5-4b): the seeded slow blocking route's tree names
     * {@code SlowPricingService.quote} under the two bean layers that call it, {@code route-time-breakdown} names it in
     * the handler split, and the tree's handler-phase time reconciles with the breakdown's handler phase within 5 %.
     * Every read answers the available shape.
     */
    @Test
    void codePathsNameTheSlowMethodOfTheSeededRouteAndReconcileWithItsHandlerPhase() throws Exception {
        String route = "GET /api/quotes/{sku}";
        String quote = "io.github.jdubois.bootui.sample.codepaths.SlowPricingService#quote(Ljava/lang/String;)I";
        String controller = "io.github.jdubois.bootui.sample.codepaths.QuoteController#quote(Ljava/lang/String;)"
                + "Lio/github/jdubois/bootui/sample/codepaths/QuoteService$Quote;";
        // One cold request, then warm ones: route-time-breakdown needs five warm requests.
        for (int i = 0; i < 8; i++) {
            assertThat(probe.get("/api/quotes/sku-" + i).status()).isEqualTo(200);
        }

        // Each request's tree settles about two seconds after its last fragment: wait, bounded.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode report;
        JsonNode row = null;
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
        assertThat(report.path("unavailableReason").isNull()).isTrue();
        assertThat(report.path("status").path("generation").asLong()).isPositive();
        assertThat(report.path("excludedMethods").isArray()).isTrue();
        assertThat(report.path("limitations").isArray()).isTrue();
        assertThat(row).as(report.toString()).isNotNull();
        assertThat(row.path("warmRequests").asInt()).as(row.toString()).isEqualTo(7);
        assertThat(row.path("assemblyOnly").asBoolean()).isFalse();
        assertThat(row.path("firstRequestMillis").isNumber()).isTrue();
        // The available shapes, which only a run with the agent reaches, against the conformance catalog's contracts.
        List<String> failures = new ArrayList<>();
        AbstractBootUiApiConformanceTest.assertJsonContract("/code-paths", contract("/code-paths"), report, failures);
        assertThat(row.path("topMethods").path(0).path("method").asText())
                .as(row.toString())
                .isEqualTo(quote);
        JsonNode panel = panelFromManifest("code-paths");
        assertThat(panel.path("available").asBoolean()).as(panel.toString()).isTrue();

        JsonNode tree = probe.get("/bootui/api/code-paths/route?route="
                        + java.net.URLEncoder.encode(route, java.nio.charset.StandardCharsets.UTF_8))
                .json();
        assertThat(tree.path("found").asBoolean()).as(tree.toString()).isTrue();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/code-paths/route", contract("/code-paths/route"), tree, failures);
        assertThat(tree.path("firstRequestId").asText()).matches("[0-9a-f]{16}");
        assertThat(tree.path("shareOf").asText()).isEqualTo("handler");
        JsonNode slow = null;
        JsonNode entry = null;
        for (JsonNode node : tree.path("nodes")) {
            if (quote.equals(node.path("method").asText())) {
                slow = node;
            } else if (controller.equals(node.path("method").asText())) {
                entry = node;
            }
        }
        assertThat(entry).as("the controller under the request: %s", tree).isNotNull();
        assertThat(slow).as("the slow method two bean layers down: %s", tree).isNotNull();
        assertThat(slow.path("depth").asInt()).isEqualTo(entry.path("depth").asInt() + 2);
        assertThat(slow.path("phase").asText()).isEqualTo("HANDLER");
        assertThat(slow.path("callsPerRequest").asDouble()).isEqualTo(1.0);
        assertThat(slow.path("selfMillis").asDouble()).isGreaterThanOrEqualTo(49.0);
        assertThat(slow.path("p50Millis").isNumber()).isTrue();
        assertThat(tree.path("methods").toString()).contains(route);
        double handlerMillis = tree.path("handlerMillis").asDouble();
        assertThat(handlerMillis).as(tree.toString()).isGreaterThanOrEqualTo(49.0);
        String exemplar = tree.path("exemplarRequestIds").path(0).asText();
        JsonNode request =
                probe.get("/bootui/api/code-paths/requests/" + exemplar).json();
        assertThat(request.path("found").asBoolean()).as(request.toString()).isTrue();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/code-paths/requests/{id}", contract("/code-paths/requests/"), request, failures);
        assertThat(failures).as("code paths contracts, available").isEmpty();
        assertThat(request.path("route").asText()).isEqualTo(route);
        assertThat(request.path("topMethods").path(0).path("method").asText()).isEqualTo(quote);

        // route-time-breakdown names the slow method in its handler split, and the handler phase reconciles.
        JsonNode observation = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            for (JsonNode candidate :
                    probe.get("/bootui/api/runtime-insights").json().path("observations")) {
                if ("route-time-breakdown".equals(candidate.path("kind").asText())
                        && route.equals(candidate.path("subject").asText())) {
                    observation = candidate;
                }
            }
            if (observation != null && observation.path("sentence").asText().contains("SlowPricingService.quote")) {
                break;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        assertThat(observation).isNotNull();
        assertThat(observation.path("sentence").asText())
                .as(observation.toString())
                .contains("Handler: SlowPricingService.quote");
        JsonNode detail = probe.get("/bootui/api/runtime-insights/insights/"
                        + observation.path("id").asText())
                .json();
        double handlerPhase = 0;
        double slowPart = 0;
        for (JsonNode evidence : detail.path("rows")) {
            String label = evidence.path("cells").path(0).asText();
            if (label.startsWith("Handler: ") || label.equals("Other handler time") || label.startsWith("Handler,")) {
                handlerPhase += evidence.path("cells").path(1).asDouble();
            }
            if (label.equals("Handler: SlowPricingService.quote")) {
                slowPart = evidence.path("cells").path(1).asDouble();
            }
        }
        // The route makes no recorded call, so its handler is split, and the slow method takes most of it.
        assertThat(slowPart / handlerPhase)
                .as("SlowPricingService.quote's part of the handler phase: %s", detail)
                .isGreaterThanOrEqualTo(0.85);
        double perRequest = handlerPhase / observation.path("eligible").asInt();
        assertThat(handlerMillis)
                .as(
                        "the tree's handler time %s ms against the handler phase %s ms: %s",
                        handlerMillis, perRequest, detail)
                .isCloseTo(perRequest, org.assertj.core.data.Percentage.withPercentage(5));
    }

    /**
     * M5-4c with the agent: the seeded N+1 route's statements carry the stamp of the service method that issued them,
     * so {@code repeated-selects} names {@code InsightOrderService.ordersLineByLine}, the route tree shows its SQL under
     * that method, and Beans at runtime lists the controller's observed calls into the service beside its declared
     * dependency, in the available shape.
     */
    @Test
    void theSeededNPlusOneNamesItsIssuingMethodAndBeansAtRuntimeShowTheCalls() throws Exception {
        String route = "GET /api/insights/orders";
        String issuer =
                "io.github.jdubois.bootui.sample.insights.InsightOrderService#ordersLineByLine()Ljava/util/List;";
        for (int i = 0; i < 5; i++) {
            assertThat(probe.get("/api/insights/orders").status()).isEqualTo(200);
        }

        JsonNode finding = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            for (JsonNode candidate :
                    probe.get("/bootui/api/runtime-insights").json().path("observations")) {
                if ("repeated-selects".equals(candidate.path("kind").asText())
                        && route.equals(candidate.path("subject").asText())) {
                    finding = candidate;
                }
            }
            if (finding != null && finding.path("sentence").asText().contains("Issued by")) {
                break;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        assertThat(finding).as("the seeded N+1 is found").isNotNull();
        assertThat(finding.path("sentence").asText())
                .as(finding.toString())
                .endsWith("Issued by `InsightOrderService.ordersLineByLine`.");

        JsonNode tree;
        JsonNode node = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Thread.sleep(250);
            tree = probe.get("/bootui/api/code-paths/route?route="
                            + java.net.URLEncoder.encode(route, java.nio.charset.StandardCharsets.UTF_8))
                    .json();
            for (JsonNode candidate : tree.path("nodes")) {
                if (issuer.equals(candidate.path("method").asText())
                        && candidate.path("calls").size() > 0) {
                    node = candidate;
                }
            }
        } while (node == null && System.nanoTime() < deadline);
        assertThat(node)
                .as("the issuing method's node with its calls: %s", tree)
                .isNotNull();
        JsonNode sql = node.path("calls").path(0);
        assertThat(sql.path("kind").asText()).as(node.toString()).isEqualTo("SQL");
        assertThat(sql.path("callsPerRequest").asDouble()).as(node.toString()).isGreaterThanOrEqualTo(2.0);
        assertThat(sql.path("totalMillis").isNumber()).isTrue();

        JsonNode beans = probe.get("/bootui/api/code-paths/beans").json();
        List<String> failures = new ArrayList<>();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/code-paths/beans", contract("/code-paths/beans"), beans, failures);
        assertThat(failures).as("Beans at runtime contract, available").isEmpty();
        assertThat(beans.path("available").asBoolean()).as(beans.toString()).isTrue();
        assertThat(beans.path("beansAvailable").asBoolean())
                .as(beans.toString())
                .isTrue();
        JsonNode edge = null;
        for (JsonNode candidate : beans.path("edges")) {
            if ("insightSeedController".equals(candidate.path("from").asText())
                    && "insightOrderService".equals(candidate.path("to").asText())) {
                edge = candidate;
            }
        }
        assertThat(edge)
                .as("the controller's calls into the service: %s", beans)
                .isNotNull();
        assertThat(edge.path("declared").asBoolean()).isTrue();
        assertThat(edge.path("observed").asBoolean()).isTrue();
        assertThat(edge.path("calls").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(edge.path("observable").asBoolean()).isTrue();
        assertThat(edge.path("unobservableReason").isNull()).isTrue();
    }

    /**
     * I5: Hibernate flushes the seeded writes when the transaction commits, which Spring's transaction interceptor does
     * after the {@code @Transactional} service method returned: the inserts show under the controller method that called
     * it, while the service's own reads stay under the service.
     */
    @Test
    void statementsFlushedAtCommitShowUnderTheMethodThatCalledTheTransactionalOne() throws Exception {
        String route = "POST /api/insights/tags/read-then-write";
        String controller = "io.github.jdubois.bootui.sample.insights.InsightSeedController#tagsReadThenWrite()"
                + "Ljava/util/Map;";
        String transactional = "io.github.jdubois.bootui.sample.insights.InsightTagService#countThenSave()J";
        for (int i = 0; i < 4; i++) {
            assertThat(probe.post("/api/insights/tags/read-then-write", Map.of("Content-Type", "application/json"))
                            .status())
                    .isEqualTo(200);
        }

        JsonNode tree;
        JsonNode caller = null;
        JsonNode service = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Thread.sleep(250);
            tree = probe.get("/bootui/api/code-paths/route?route="
                            + java.net.URLEncoder.encode(route, java.nio.charset.StandardCharsets.UTF_8))
                    .json();
            caller = null;
            service = null;
            for (JsonNode candidate : tree.path("nodes")) {
                if (controller.equals(candidate.path("method").asText())) {
                    caller = candidate;
                } else if (transactional.equals(candidate.path("method").asText())) {
                    service = candidate;
                }
            }
        } while ((caller == null || caller.path("calls").isEmpty() || service == null) && System.nanoTime() < deadline);
        assertThat(caller).as("the controller's node: %s", tree).isNotNull();
        assertThat(service).as("the transactional service's node: %s", tree).isNotNull();
        JsonNode flushed = caller.path("calls").path(0);
        assertThat(flushed.path("kind").asText())
                .as("the inserts flushed at commit, under the caller: %s", caller)
                .isEqualTo("SQL");
        assertThat(flushed.path("callsPerRequest").asDouble())
                .as(caller.toString())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(service.path("calls").path(0).path("kind").asText())
                .as("the service's own reads stay under it: %s", service)
                .isEqualTo("SQL");
    }

    /**
     * M5-7a with the agent: a method's change impact lists the seeded route whose requests' own call trees ran it, read
     * from the route trees, not from the bean graph or calls composed across requests; the run comparison carries its
     * code changes, unavailable with the reason in a first run, in the shape every stack answers.
     */
    @Test
    void aMethodsChangeImpactListsTheSeededRouteWhoseCallTreesRanIt() throws Exception {
        String route = "GET /api/insights/orders";
        for (int i = 0; i < 4; i++) {
            assertThat(probe.get("/api/insights/orders").status()).isEqualTo(200);
        }

        String symbol = java.net.URLEncoder.encode(
                "InsightOrderService#ordersLineByLine", java.nio.charset.StandardCharsets.UTF_8);
        JsonNode impact;
        JsonNode observed = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Thread.sleep(250);
            impact = probe.get("/bootui/api/runtime-insights/impact?symbol=" + symbol)
                    .json();
            observed = null;
            for (JsonNode candidate : impact.path("observed")) {
                if (route.equals(candidate.path("route").asText())) {
                    observed = candidate;
                }
            }
            // Code Inventory's first request alone gives one request: more come only from the route trees.
        } while ((observed == null || observed.path("executedRequests").asLong() < 2) && System.nanoTime() < deadline);
        List<String> failures = new ArrayList<>();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/runtime-insights/impact", BootUiApiContractCatalog.changeImpact(), impact, failures);
        assertThat(failures).as("change impact contract, a method").isEmpty();
        assertThat(observed).as("the seeded route ran the method: %s", impact).isNotNull();
        assertThat(impact.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(impact.path("observedFrom").asText()).isEqualTo("ROUTE_TREES");
        assertThat(impact.path("node").asText())
                .isEqualTo("METHOD io.github.jdubois.bootui.sample.insights.InsightOrderService#ordersLineByLine");
        assertThat(impact.path("methods").toString())
                .contains("InsightOrderService#ordersLineByLine()Ljava/util/List;");
        assertThat(impact.path("methodStatus").asText()).isEqualTo("EXECUTED");
        assertThat(observed.path("executedRequests").asLong())
                .as("the route trees counted its requests: %s", observed)
                .isGreaterThanOrEqualTo(2);

        JsonNode comparison =
                probe.get("/bootui/api/runtime-insights/comparison").json();
        failures.clear();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/runtime-insights/comparison", BootUiApiContractCatalog.runComparison(), comparison, failures);
        assertThat(failures).as("run comparison contract, with the agent").isEmpty();
        JsonNode codeChanges = comparison.path("codeChanges");
        assertThat(codeChanges.isObject())
                .as("with the agent, the comparison carries code changes")
                .isTrue();
        assertThat(codeChanges.path("available").isBoolean())
                .as(codeChanges.toString())
                .isTrue();
        if (!codeChanges.path("available").asBoolean()) {
            assertThat(codeChanges.path("unavailableReason").asText())
                    .as("a first run has nothing to compare its code with")
                    .isNotBlank();
        }
    }

    /**
     * The processes sensor with the agent ({@code docs/PLAN-v2.md} §5.16, M5-5a): the seeded route's {@code java} process
     * is a row of its route, started from the seed's method, with its exit, and never an argument; the counterexample
     * route starts none.
     */
    @Test
    void sideEffectsShowTheSeededProcessByItsFileNameOnlyAndNotTheCounterexample() throws Exception {
        assertThat(probe.get("/api/side-effects/java-version").status()).isEqualTo(200);
        assertThat(probe.get("/api/side-effects/runtime-version").status()).isEqualTo(200);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode report;
        JsonNode row = null;
        do {
            Thread.sleep(250);
            report = probe.get("/bootui/api/side-effects/sensor?sensor=processes")
                    .json();
            for (JsonNode candidate : report.path("rows")) {
                if ("GET /api/side-effects/java-version"
                        .equals(candidate.path("attribution").asText())) {
                    row = candidate;
                }
            }
        } while ((row == null || row.path("completed").asLong() < 1) && System.nanoTime() < deadline);
        assertThat(report.path("available").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.path("sensor").path("state").asText())
                .as(report.toString())
                .isEqualTo("recording");
        assertThat(row).as(report.toString()).isNotNull();
        assertThat(row.path("scope").asText()).isEqualTo("route");
        assertThat(row.path("target").asText()).isEqualTo("java");
        assertThat(row.path("callSite").asText())
                .isEqualTo("io.github.jdubois.bootui.sample.sideeffects.JavaVersionReporter#version");
        assertThat(row.path("count").asLong()).isEqualTo(1L);
        assertThat(row.path("failed").asLong()).isZero();
        assertThat(row.path("completed").asLong()).isEqualTo(1L);
        assertThat(row.path("lastExitStatus").asInt()).isZero();
        assertThat(row.path("exemplarRequestIds").size()).isEqualTo(1);
        assertThat(report.toString())
                .as("no argument of the process ever appears")
                .doesNotContain("never-shown-by-bootui")
                .doesNotContain("\"-version\"")
                .as("the counterexample route starts no process")
                .doesNotContain("runtime-version");
        List<String> failures = new ArrayList<>();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/side-effects/sensor", BootUiApiContractCatalog.sideEffectsSensor(), report, failures);
        JsonNode summary = probe.get("/bootui/api/side-effects").json();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/side-effects",
                BootUiApiContractCatalog.reads().stream()
                        .filter(contract -> contract.relativePath().equals("/side-effects"))
                        .findFirst()
                        .orElseThrow(),
                summary,
                failures);
        assertThat(failures).as("side effects contracts with the agent").isEmpty();
        assertThat(panelFromManifest("side-effects").path("available").asBoolean())
                .isTrue();
    }

    /**
     * The files and environment sensors with the agent ({@code docs/PLAN-v2.md} §5.16, M5-5d): the seeded route's report,
     * written in the working directory, outside the temporary directory, is an application write of its route by path
     * pattern, and its property read a read of its route by name; the counterexamples' temporary file is under
     * {@code $TMPDIR}, their logging handler's file grouped apart as logging, and class loading only counted; no file
     * contents or property value ever appears.
     */
    @Test
    void sideEffectsShowTheSeededReportAndPropertyReadAndGroupTheCounterexamplesApart() throws Exception {
        assertThat(probe.get("/api/side-effects/report").status()).isEqualTo(200);
        assertThat(probe.get("/api/side-effects/scratch").status()).isEqualTo(200);
        assertThat(probe.get("/api/side-effects/log").status()).isEqualTo(200);
        String route = "GET /api/side-effects/report";

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode files;
        JsonNode environment;
        JsonNode report = null;
        JsonNode property = null;
        do {
            Thread.sleep(250);
            files = probe.get("/bootui/api/side-effects/sensor?sensor=files&limit=500")
                    .json();
            environment = probe.get("/bootui/api/side-effects/sensor?sensor=environment&limit=500")
                    .json();
            for (JsonNode candidate : files.path("rows")) {
                if (route.equals(candidate.path("attribution").asText())
                        && candidate.path("target").asText().endsWith("/bootui-side-effects/report-{n}-{n}-{n}.csv")) {
                    report = candidate;
                }
            }
            for (JsonNode candidate : environment.path("rows")) {
                if (route.equals(candidate.path("attribution").asText())
                        && "sample.report.title".equals(candidate.path("target").asText())) {
                    property = candidate;
                }
            }
        } while ((report == null || property == null) && System.nanoTime() < deadline);
        assertThat(files.path("sensor").path("state").asText())
                .as(files.toString())
                .isEqualTo("recording");
        assertThat(report).as(files.toString()).isNotNull();
        assertThat(report.path("kind").asText()).isEqualTo("write");
        assertThat(report.path("origin").asText()).isEqualTo("application");
        assertThat(report.path("location").asText()).isNotEqualTo("temporary-directory");
        assertThat(report.path("callSite").asText())
                .isEqualTo("io.github.jdubois.bootui.sample.sideeffects.ReportWriter#writeReport");
        assertThat(property).as(environment.toString()).isNotNull();
        assertThat(property.path("kind").asText()).isEqualTo("system property");
        assertThat(property.path("origin").asText()).isEqualTo("application");

        List<JsonNode> rows = new ArrayList<>();
        files.path("rows").forEach(rows::add);
        assertThat(rows)
                .as("the scratch file is under the temporary directory, never an application write elsewhere")
                .filteredOn(row -> row.path("target").asText().contains("bootui-scratch-"))
                .isNotEmpty()
                .allSatisfy(row -> {
                    assertThat(row.path("target").asText()).startsWith("$TMPDIR/");
                    assertThat(row.path("location").asText()).isEqualTo("temporary-directory");
                });
        assertThat(rows)
                .as("the logging handler's file is logging, grouped apart")
                .filteredOn(row -> row.path("target").asText().contains("bootui-sample-"))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.path("origin").asText()).isEqualTo("logging"));
        assertThat(rows)
                .as("class loading is never an application row")
                .filteredOn(row -> row.path("target").asText().endsWith(".class")
                        || row.path("target").asText().endsWith(".jar"))
                .isEmpty();
        assertThat(files.toString() + environment)
                .as("no file contents or property value ever appears")
                .doesNotContain("sample-side-effects-contents-never-shown")
                .doesNotContain("bootui-title-never-shown");
        List<String> failures = new ArrayList<>();
        AbstractBootUiApiConformanceTest.assertJsonContract(
                "/side-effects/sensor", BootUiApiContractCatalog.sideEffectsSensor(), files, failures);
        assertThat(failures).as("side effects contracts with the agent").isEmpty();
    }

    /**
     * The blocking sensor on Spring MVC ({@code docs/PLAN-v2.md} §5.16, M5-5c): installed and self-tested, but not
     * applicable, since a thread-per-request server runs no event loop; its rows stay empty.
     */
    @Test
    void theBlockingSensorIsNotApplicableOnSpringMvc() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode blocking;
        do {
            Thread.sleep(250);
            blocking = null;
            for (JsonNode sensor : probe.get("/bootui/api/side-effects").json().path("sensors")) {
                if ("blocking".equals(sensor.path("id").asText())) {
                    blocking = sensor;
                }
            }
        } while ((blocking == null
                        || !"not-applicable".equals(blocking.path("state").asText()))
                && System.nanoTime() < deadline);
        assertThat(blocking).isNotNull();
        assertThat(blocking.path("state").asText()).as(blocking.toString()).isEqualTo("not-applicable");
        assertThat(blocking.path("reason").asText()).contains("Spring MVC");
        List<String> hooks = new ArrayList<>();
        blocking.path("hooks").forEach(hook -> hooks.add(hook.path("id").asText()));
        assertThat(hooks).contains("LockSupport.park", "Thread.sleep call sites", "Object.wait call sites");
        JsonNode rows =
                probe.get("/bootui/api/side-effects/sensor?sensor=blocking").json();
        assertThat(rows.path("rows").size()).as(rows.toString()).isZero();
    }

    /** The conformance catalog's contract of the Code Paths read whose path starts with {@code path}. */
    private static BootUiApiContractCatalog.ReadContract contract(String path) {
        List<BootUiApiContractCatalog.ReadContract> contracts = new ArrayList<>(BootUiApiContractCatalog.reads());
        contracts.addAll(BootUiApiContractCatalog.codePathsTrees());
        return contracts.stream()
                .filter(contract -> contract.panelId().equals("code-paths"))
                .filter(contract -> path.equals("/code-paths")
                        ? contract.relativePath().equals(path)
                        : contract.relativePath().startsWith(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no contract for " + path));
    }

    private static JsonNode panelFromManifest(String id) {
        for (JsonNode panel : probe.get("/bootui/api/panels").json().path("panels")) {
            if (id.equals(panel.path("id").asText())) {
                return panel;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    /** The ids of the requests to {@code path} in the journal. */
    private static Set<String> requests(String path) {
        Set<String> ids = new HashSet<>();
        JsonNode feed = probe.get("/bootui/api/activity?source=journal&type=REQUEST&limit=200")
                .json();
        for (JsonNode entry : feed.path("entries")) {
            if (path.equals(entry.path("path").asText())) {
                ids.add(entry.path("id").asText());
            }
        }
        return ids;
    }

    /** Whether the journal holds a request to {@code path} other than {@code earlier}, each with its handoff. */
    private static boolean handoffRecorded(String path, Set<String> earlier) {
        Set<String> ids = requests(path);
        ids.removeAll(earlier);
        for (String id : ids) {
            if (probe.get("/bootui/api/activity/request/" + id + "/journal")
                    .json()
                    .path("handoffs")
                    .isEmpty()) {
                return false;
            }
        }
        return !ids.isEmpty();
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
