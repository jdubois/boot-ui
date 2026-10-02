package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code orm} journal source on Quarkus ({@code docs/PLAN-v2.md} §5.18, M4-9): BootUI's deployment processor
 * makes Hibernate create its session listener, and a request that saves then queries records one Hibernate session
 * under it, with its measured statements and the auto-flushes that wrote before each query. It runs in an application of
 * its own, so the statements it causes never reach the other tests' SQL Trace assertions.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusOrmSessionEventsTest.OwnApplication.class)
class BootUiQuarkusOrmSessionEventsTest {

    /** A profile of its own, so Quarkus starts a separate application for this test. */
    public static class OwnApplication implements QuarkusTestProfile {}

    @TestHTTPResource
    URL baseUrl;

    @Test
    void aSessionThatSavesThenQueriesIsAnOrmRowUnderItsRequest() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.post("/demo/tags/auto-flush", Map.of()).status()).isEqualTo(200);

        JsonNode orm = null;
        JsonNode request = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (orm == null && System.nanoTime() < deadline) {
            JsonNode feed = probe.get("/bootui/api/activity?source=journal").json();
            for (JsonNode entry : feed.path("entries")) {
                if ("ORM".equals(entry.path("type").asText())) {
                    orm = entry;
                } else if ("REQUEST".equals(entry.path("type").asText())
                        && entry.path("path").asText().endsWith("/demo/tags/auto-flush")) {
                    request = entry;
                }
            }
            if (orm == null) {
                Thread.sleep(100);
            }
        }

        assertThat(orm).as("an ORM row in the journal's feed").isNotNull();
        assertThat(request).isNotNull();
        assertThat(orm.path("parentId").asText()).isEqualTo(request.path("id").asText());
        assertThat(orm.path("detail").asText())
                .as("three inserts, three counts, and the id sequence; each insert auto-flushed before its count")
                .matches("[6-9] statements · \\d+ flushes \\(3 auto\\) · \\d+ entities");

        JsonNode profile = probe.get(
                        "/bootui/api/activity/request/" + request.path("id").asText() + "/journal")
                .json();
        assertThat(profile.path("orm").path("sessions").asInt()).isEqualTo(1);
        assertThat(profile.path("orm").path("autoFlushes").asInt()).isEqualTo(3);
        assertThat(profile.path("orm").path("statementMicros").asLong())
                .as("Quarkus ORM statements are measured by the session, not timed as preparations")
                .isPositive();
        long autoFlushes = 0;
        for (JsonNode item : profile.path("timeline")) {
            if ("Auto-flush before a query".equals(item.path("label").asText())) {
                autoFlushes++;
            }
        }
        assertThat(autoFlushes)
                .as("each auto-flush is on the request's timeline")
                .isEqualTo(3);
    }
}
