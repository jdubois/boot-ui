package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The sample's Runtime Insights seeds ({@code docs/PLAN-v2.md} M3-6): each seeded route is reported by its observation,
 * and each counterexample beside it is not. This is the honesty gate's sample-level check on Spring MVC.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_insight_seeds;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/insight-seeds/application-bootui.properties"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeInsightsSeedsTest {

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    @LocalServerPort
    int port;

    @Autowired
    RuntimeJournal journal;

    private List<JsonNode> observations;

    @BeforeAll
    void seed() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/insights/orders").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/orders/joined").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/orders/report").status()).isEqualTo(200);
            assertThat(probe.get("/api/sample/products").status()).isEqualTo(200);
        }
        assertThat(probe.get("/api/insights/orders/1").status()).isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/2/confirm", JSON).status()).isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/3/ship", JSON).status()).isEqualTo(200);
        assertThat(probe.get("/api/insights/orders/4/price-check").status()).isEqualTo(200);
        assertThat(probe.get("/api/insights/orders/4/price-check-after-commit").status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/5/recalculate", JSON).status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/5/recalculate-through-bean", JSON)
                        .status())
                .isEqualTo(200);
        assertThat(probe.post("/api/insights/orders/6/import", JSON).status()).isEqualTo(200);
        assertThat(probe.request("POST", "/api/insights/orders", JSON, "{not json")
                        .status())
                .isEqualTo(400);
        for (String action : List.of("notify", "notify-in-transaction", "archive", "restore")) {
            assertThat(probe.post("/api/insights/orders/1/" + action, JSON).status())
                    .isEqualTo(200);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(probe.post("/api/insights/tags/auto-flush", JSON).status())
                    .isEqualTo(200);
            assertThat(probe.post("/api/insights/tags/read-then-write", JSON).status())
                    .isEqualTo(200);
        }
        assertThat(probe.post("/api/insights/debug/reset-totals", JSON).status())
                .isEqualTo(200);
        assertThat(probe.get("/api/insights/reports/payroll").status()).isEqualTo(403);
        assertThat(probe.get("/api/insights/reports/PAYROLL").status()).isEqualTo(200);
        assertThat(probe.get("/api/insights/reports/summary").status()).isEqualTo(200);
        sendStompMessages();
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        observations = new ArrayList<>();
        probe.get("/bootui/api/runtime-insights").json().path("observations").forEach(observations::add);
    }

    @Test
    void repeatedSelectsAreFoundOnTheLineByLineLoopButNotOnTheJoin() {
        assertThat(subjects("repeated-selects", "OBSERVED"))
                .contains("GET /api/insights/orders")
                .doesNotContain("GET /api/insights/orders/joined", "GET /api/sample/products");
    }

    @Test
    void aGetThatWritesIsAskedAboutButThePublicCatalogReadIsNot() {
        assertThat(subjects("safe-method-dml", "OBSERVED"))
                .contains("GET /api/insights/orders/{id}")
                .doesNotContain("GET /api/sample/products", "GET /api/insights/orders");
    }

    @Test
    void aRequiresNewWriteHoldsTwoConnectionsAndSplitsTheWritesUnlikeOneTransaction() {
        assertThat(subjects("connections-per-request", null))
                .contains("POST /api/insights/orders/{id}/confirm")
                .doesNotContain("POST /api/insights/orders/{id}/ship");
        assertThat(subjects("split-transaction-writes", "OBSERVED"))
                .contains("POST /api/insights/orders/{id}/confirm")
                .doesNotContain("POST /api/insights/orders/{id}/ship");
    }

    @Test
    void aRemoteCallInsideTheTransactionIsFoundButNotOneAfterItsCommit() {
        assertThat(subjects("transaction-across-remote-call", null))
                .contains("GET /api/insights/orders/{id}/price-check")
                .doesNotContain("GET /api/insights/orders/{id}/price-check-after-commit");
    }

    @Test
    void aSelfInvocationBypassesTheProxyButACallThroughTheBeanDoesNot() {
        assertThat(subjects("proxy-bypass", "OBSERVED"))
                .contains("POST /api/insights/orders/{id}/recalculate")
                .doesNotContain("POST /api/insights/orders/{id}/recalculate-through-bean");
    }

    @Test
    void aRolledBackImportAnsweredWith200IsAnErrorBehindASuccess() {
        assertThat(subjects("errors-behind-2xx", null))
                .contains("POST /api/insights/orders/{id}/import")
                .doesNotContain("POST /api/insights/orders/{id}/ship");
    }

    @Test
    void statementsWhileTheResponseIsWrittenAreLazySqlAfterTheHandler() {
        assertThat(subjects("lazy-sql-after-handler", "OBSERVED"))
                .contains("GET /api/insights/orders/report")
                .doesNotContain("GET /api/insights/orders");
    }

    @Test
    void anUnreadableBodyIsAFrameworkWarningOnItsRoute() {
        assertThat(subjects("framework-warnings-by-route", null)).contains("POST /api/insights/orders");
    }

    @Test
    void anAnonymousWriteIsReachAndAnAnonymousSuccessOnADeniedRouteIsReportedButThePublicCatalogIsNot() {
        assertThat(subjects("anonymous-data-reach", "OBSERVED"))
                .contains("POST /api/insights/debug/reset-totals")
                .doesNotContain("GET /api/sample/products", "GET /api/insights/orders");
        assertThat(subjects("anonymous-success-on-restricted-route", "OBSERVED"))
                .contains("GET /api/insights/reports/{name}")
                .doesNotContain("GET /api/sample/products");
    }

    @Test
    void aListenerSkippedForLackOfATransactionIsFoundButNotTheSameEventPublishedInOne() {
        assertThat(subjects("transactional-listener-skipped", "OBSERVED"))
                .contains("POST /api/insights/orders/{id}/notify")
                .doesNotContain("POST /api/insights/orders/{id}/notify-in-transaction");
    }

    @Test
    void anAfterCommitWriteInNoTransactionIsFoundButNotOneInItsOwnTransaction() {
        assertThat(subjects("after-commit-writes", "OBSERVED"))
                .contains("POST /api/insights/orders/{id}/archive")
                .doesNotContain("POST /api/insights/orders/{id}/restore");
    }

    @Test
    void frameworkEventsAreNeverRecordedAsApplicationEvents() {
        assertThat(journal.entries())
                .extracting(entry -> entry.event().payload())
                .filteredOn(AppEventPayload.class::isInstance)
                .map(payload -> ((AppEventPayload) payload).eventType())
                .isNotEmpty()
                .allMatch(type -> type.startsWith("io.github.jdubois.bootui.sample."));
    }

    @Test
    void savingBeforeEachQueryIsAnAutoFlushPatternButQueryingFirstIsNot() {
        assertThat(subjects("orm-auto-flush", "OBSERVED"))
                .contains("POST /api/insights/tags/auto-flush")
                .doesNotContain("POST /api/insights/tags/read-then-write");
    }

    @Test
    void aStompHandlersRepeatedSelectsAreFoundUnderItsMessageButNotTheJoinedHandler() {
        assertThat(subjects("repeated-selects", "OBSERVED"))
                .contains("consume websocket:/app/insights/rooms/{room}/orders")
                .doesNotContain("consume websocket:/app/insights/rooms/{room}/orders-joined");
        assertThat(journal.entries())
                .extracting(entry -> entry.event().payload())
                .filteredOn(WebSocketPayload.class::isInstance)
                .extracting(payload -> ((WebSocketPayload) payload).destination())
                .as("each room is one template, never one destination per room")
                .containsOnly("/app/insights/rooms/{room}/orders", "/app/insights/rooms/{room}/orders-joined");
    }

    /**
     * The cross-observation check on the sample's counterexamples (M4-18e): a route seeded as one kind's counterexample
     * fires no kind but those its own work justifies, not only no instance of the kind it stands beside. The sample
     * serves its seeds anonymously, so each write it commits is a fact of {@code anonymous-data-reach}. The engine's
     * {@code ObservationHonestyHarnessTests} replays the same shapes with incomplete evidence.
     */
    @Test
    void aCounterexampleRouteFiresOnlyTheKindsItsOwnWorkJustifies() {
        Set<String> anonymousWrite = Set.of("anonymous-data-reach");
        Map<String, Set<String>> justified = new LinkedHashMap<>();
        justified.put("GET /api/insights/orders/joined", Set.of());
        justified.put("GET /api/sample/products", Set.of());
        justified.put("POST /api/insights/orders/{id}/ship", anonymousWrite);
        justified.put("GET /api/insights/orders/{id}/price-check-after-commit", Set.of());
        justified.put("POST /api/insights/orders/{id}/recalculate-through-bean", anonymousWrite);
        justified.put("POST /api/insights/orders/{id}/notify-in-transaction", anonymousWrite);
        // Its AFTER_COMMIT listener writes in a REQUIRES_NEW transaction while the committed one still holds its
        // connection: two connections at once and two independent units, which is why it is not an after-commit write.
        justified.put(
                "POST /api/insights/orders/{id}/restore",
                Set.of("anonymous-data-reach", "connections-per-request", "split-transaction-writes"));
        justified.put("POST /api/insights/tags/read-then-write", anonymousWrite);
        justified.put("consume websocket:/app/insights/rooms/{room}/orders-joined", Set.of());

        assertThat(justified.keySet())
                .as("every counterexample route ran, so its silence is a result")
                .allSatisfy(subject -> assertThat(exercised()).contains(subject));
        assertThat(observations)
                .filteredOn(observation ->
                        justified.containsKey(observation.path("subject").asText()))
                .filteredOn(observation -> !observation.path("status").asText().equals("INSUFFICIENT"))
                .filteredOn(observation -> !justified
                        .get(observation.path("subject").asText())
                        .contains(observation.path("kind").asText()))
                .extracting(observation -> observation.path("kind").asText() + " on "
                        + observation.path("subject").asText() + ": "
                        + observation.path("sentence").asText())
                .isEmpty();
    }

    /** The routes and message handlers the journal recorded, named as observations name their subject. */
    private Set<String> exercised() {
        Set<String> subjects = new HashSet<>();
        for (var entry : journal.entries()) {
            if (entry.event().payload() instanceof HttpPayload http && http.routeTemplate() != null) {
                subjects.add(http.method() + " " + http.routeTemplate());
            } else if (entry.event().payload() instanceof WebSocketPayload message && message.destination() != null) {
                subjects.add("consume websocket:" + message.destination());
            }
        }
        return subjects;
    }

    private long handledMessages() throws InterruptedException {
        journal.awaitDrained(Duration.ofSeconds(1));
        return journal.entries().stream()
                .filter(entry -> entry.event().payload() instanceof WebSocketPayload)
                .count();
    }

    /** Sends one message to each STOMP seed in each of three rooms, over a raw WebSocket to the sample's SockJS endpoint. */
    private void sendStompMessages() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession session = client.connectAsync(
                        "ws://localhost:" + port + "/ws/websocket", new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        for (int room = 1; room <= 3; room++) {
            session.send("/app/insights/rooms/" + room + "/orders", "");
            session.send("/app/insights/rooms/" + room + "/orders-joined", "");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (handledMessages() < 6 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(handledMessages()).as("every message ran its handler").isEqualTo(6);
        session.disconnect();
        client.stop();
    }

    /**
     * The default list (docs/PLAN-v2.md M4-19): the seeded run lists each seed's observation except where a rule leaves
     * it out, and every row it leaves out says why.
     */
    @Test
    void theDefaultListLeavesOutNoiseAndDuplicatesWithTheirReasonAndListsD29sKinds() {
        assertThat(listed("repeated-selects")).contains("GET /api/insights/orders", "GET /api/insights/orders/report");
        assertThat(unlisted("lazy-sql-after-handler"))
                .as("Repeated SELECTs already reports the report's statement from the same call site")
                .contains("GET /api/insights/orders/report");
        assertThat(listed("lazy-sql-after-handler")).isEmpty();
        // D29's kinds are listed once their counterexample fixtures pass the cross-observation harness (M4-18e).
        assertThat(listed("transactional-listener-skipped")).contains("POST /api/insights/orders/{id}/notify");
        assertThat(listed("after-commit-writes")).contains("POST /api/insights/orders/{id}/archive");
        assertThat(listed("orm-auto-flush")).contains("POST /api/insights/tags/auto-flush");
        for (String kind : List.of("transactional-listener-skipped", "after-commit-writes", "orm-auto-flush")) {
            assertThat(unlisted(kind)).as(kind).isEmpty();
        }
        assertThat(unlisted("exception-hotspots"))
                .as("the unreadable body answered 400")
                .contains("POST /api/insights/orders");
        assertThat(listed("exception-hotspots")).contains("Behind 4xx responses");
        assertThat(unlisted("framework-warnings-by-route")).contains("POST /api/insights/orders");
        assertThat(listed("route-time-breakdown"))
                .as("only prominent routes, and the seeds' routes are short")
                .doesNotContain("GET /api/insights/orders", "GET /api/sample/products");
        assertThat(observations)
                .filteredOn(observation -> !observation.path("listed").asBoolean())
                .allSatisfy(observation ->
                        assertThat(observation.path("unlistedReason").asText()).isNotBlank());
        assertThat(listed("anonymous-data-reach")).contains("POST /api/insights/debug/reset-totals");
    }

    private List<String> listed(String kind) {
        return subjectsListed(kind, true);
    }

    private List<String> unlisted(String kind) {
        return subjectsListed(kind, false);
    }

    private List<String> subjectsListed(String kind, boolean listed) {
        return observations.stream()
                .filter(observation -> observation.path("kind").asText().equals(kind))
                .filter(observation -> observation.path("listed").asBoolean() == listed)
                .map(observation -> observation.path("subject").asText())
                .toList();
    }

    private List<String> subjects(String kind, String status) {
        return observations.stream()
                .filter(observation -> observation.path("kind").asText().equals(kind))
                .filter(observation ->
                        status == null || observation.path("status").asText().equals(status))
                .map(observation -> observation.path("subject").asText())
                .toList();
    }
}
