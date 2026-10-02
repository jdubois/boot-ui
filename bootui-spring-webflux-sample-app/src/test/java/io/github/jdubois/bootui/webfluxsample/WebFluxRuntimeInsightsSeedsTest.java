package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
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

    private List<String> subjects(String kind) {
        return observations.stream()
                .filter(observation -> observation.path("kind").asText().equals(kind))
                .filter(observation -> observation.path("status").asText().equals("OBSERVED"))
                .map(observation -> observation.path("subject").asText())
                .toList();
    }
}
