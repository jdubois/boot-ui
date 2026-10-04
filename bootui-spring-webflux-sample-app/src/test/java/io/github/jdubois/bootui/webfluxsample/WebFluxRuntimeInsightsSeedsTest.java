package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

/**
 * The WebFlux sample's Runtime Insights seeds ({@code docs/PLAN-v2.md} M3-6): JDBC on the event loop and a per-note
 * loop are reported, while the same JDBC on {@code boundedElastic}, one statement for every note, and an asynchronous
 * client completing on the event loop are not.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/insight-seeds/application-bootui.properties"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebFluxRuntimeInsightsSeedsTest {

    @LocalServerPort
    int port;

    @Autowired
    RuntimeJournal journal;

    private final List<JsonNode> observations = new ArrayList<>();

    private final List<String> notExercised = new ArrayList<>();

    @BeforeAll
    void seed() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/insights/notes/on-event-loop").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/notes/one-by-one").status()).isEqualTo(200);
            assertThat(probe.get("/api/insights/notes/at-once").status()).isEqualTo(200);
            assertThat(probe.get("/api/notes").status()).isEqualTo(200);
            assertThat(probe.get("/api/sample/rest-client").status()).isEqualTo(200);
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();
        JsonNode report = probe.get("/bootui/api/runtime-insights").json();
        report.path("observations").forEach(observations::add);
        report.path("notExercised").forEach(route -> notExercised.add(route.asText()));
    }

    @Test
    void theWebFluxRoutesNoRequestReachedAreListedAndThoseExercisedAreNot() {
        // WebFlux describes its routes under Actuator's dispatcherHandlers, which the Mappings provider reads.
        assertThat(notExercised)
                .contains("GET /api/notes/{id}", "GET /api/errors/not-found")
                .doesNotContain("GET /api/notes", "GET /api/insights/notes/on-event-loop")
                .allSatisfy(route -> assertThat(route).doesNotContain("/bootui"));
    }

    @Test
    void jdbcOnTheEventLoopIsFoundButNotOnBoundedElasticNorAnAsynchronousClient() {
        assertThat(subjects("event-loop-blocking"))
                .contains("GET /api/insights/notes/on-event-loop")
                .doesNotContain("GET /api/notes", "GET /api/sample/rest-client", "GET /api/insights/notes/at-once");
    }

    @Test
    void aPerNoteLoopRepeatsItsSelectButOneStatementForEveryNoteDoesNot() {
        assertThat(subjects("repeated-selects"))
                .contains("GET /api/insights/notes/one-by-one")
                .doesNotContain("GET /api/insights/notes/at-once", "GET /api/notes");
    }

    /**
     * The cross-observation check on the WebFlux sample's counterexamples (M4-18e): JDBC offloaded to boundedElastic,
     * one statement for every note, and an asynchronous client fire no kind at all.
     */
    @Test
    void noObservationOfAnyKindFiresOnACounterexampleRoute() {
        List<String> counterexamples =
                List.of("GET /api/notes", "GET /api/insights/notes/at-once", "GET /api/sample/rest-client");
        assertThat(observations)
                .filteredOn(observation ->
                        counterexamples.contains(observation.path("subject").asText()))
                .filteredOn(observation -> !observation.path("status").asText().equals("INSUFFICIENT"))
                .extracting(observation -> observation.path("kind").asText() + " on "
                        + observation.path("subject").asText() + ": "
                        + observation.path("sentence").asText())
                .isEmpty();
    }

    @Test
    void eachMessageTheEchoHandlerReceivesIsAnExecutionNamedByItsMapping() throws Exception {
        new ReactorNettyWebSocketClient()
                .execute(
                        URI.create("ws://localhost:" + port + "/echo"),
                        session -> session.send(Mono.just(session.textMessage("secret note")))
                                .thenMany(session.receive().take(1))
                                .then())
                .block(Duration.ofSeconds(10));
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        assertThat(journal.entries())
                .extracting(entry -> entry.event())
                .filteredOn(event -> event.payload() instanceof WebSocketPayload)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.executionId()).isNotNull();
                    assertThat(event.payload())
                            .isEqualTo(WebSocketPayload.handled("handler:/echo", "/echo", 11L, false));
                });
    }

    private List<String> subjects(String kind) {
        return observations.stream()
                .filter(observation -> observation.path("kind").asText().equals(kind))
                .filter(observation -> observation.path("status").asText().equals("OBSERVED"))
                .map(observation -> observation.path("subject").asText())
                .toList();
    }
}
