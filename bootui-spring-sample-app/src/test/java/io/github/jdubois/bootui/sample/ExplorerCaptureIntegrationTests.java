package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.explorer.ExplorerBeanAdvisor;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.sample.explorer.ExplorerDemoFailure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP -> MVC proxy -> transaction/cache -> Spring Data/JDBC, no manually supplied span. */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:explorer_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/explorer-integration-overrides.properties"
        })
class ExplorerCaptureIntegrationTests {
    @LocalServerPort
    int port;

    @Autowired
    ExplorerBeanAdvisor advisor;

    @Autowired
    ExceptionStore exceptions;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void realColdRequestHasExactBeanSqlAndCacheEdgesButCacheHitSkipsRepositoryBody() throws Exception {
        assertThat(get("/api/explorer-demo/2").statusCode()).isEqualTo(200);
        ExplorerEventDto cold = awaitJourney("/api/explorer-demo/2");
        assertThat(cold.invocations())
                .extracting(ExplorerInvocationDto::role)
                .contains("CONTROLLER", "SERVICE", "REPOSITORY");
        assertThat(cold.invocations()).anyMatch(call -> call.typeName().endsWith(".ProductRepository"));
        assertThat(cold.sqlReferences()).anyMatch(sql -> sql.identifiers().contains("sample_products"));
        assertThat(cold.cacheOperations())
                .extracting(ExplorerCacheDto::operation)
                .contains("MISS", "PUT");
        assertThat(cold.links()).anyMatch(link -> link.eventId().startsWith("sql-"));
        assertThat(cold.invocations()).anyMatch(call -> cold.event().id().equals(call.parentId()));

        assertThat(get("/api/explorer-demo/2").statusCode()).isEqualTo(200);
        ExplorerEventDto hit =
                awaitNewJourney("/api/explorer-demo/2", cold.event().id());
        assertThat(hit.cacheOperations())
                .extracting(ExplorerCacheDto::operation)
                .contains("HIT");
        assertThat(hit.invocations())
                .extracting(ExplorerInvocationDto::role)
                .contains("CONTROLLER", "SERVICE")
                .doesNotContain("REPOSITORY");
        assertThat(hit.sqlReferences()).isEmpty();
        assertThat(hit.related()).noneMatch(event -> "SQL".equals(event.type()));
    }

    @Test
    void handledAndEscapingFailuresHaveObservedOutcomesButOnlyOneCapturedOccurrence() throws Exception {
        long before = sampleFailureCount();
        assertThat(get("/api/explorer-demo/handled").statusCode()).isEqualTo(200);
        ExplorerEventDto handled = awaitJourney("/api/explorer-demo/handled");
        assertThat(handled.event().status()).isEqualTo(200);
        assertThat(handled.invocations())
                .filteredOn(call -> "CONTROLLER".equals(call.role()))
                .allMatch(call -> !call.failed());
        assertThat(handled.invocations())
                .filteredOn(ExplorerInvocationDto::failed)
                .hasSize(2);
        assertThat(sampleFailureCount()).isEqualTo(before);
        assertThat(get("/api/explorer-demo/failure").statusCode()).isEqualTo(500);
        ExplorerEventDto failed = awaitJourney("/api/explorer-demo/failure");
        assertThat(failed.invocations())
                .filteredOn(ExplorerInvocationDto::failed)
                .hasSize(3);
        assertThat(sampleFailureCount()).isEqualTo(before + 1);
    }

    private long sampleFailureCount() {
        return exceptions.groups().stream()
                .filter(group -> ExplorerDemoFailure.class.getName().equals(group.exceptionClassName()))
                .mapToLong(ExceptionStore.GroupSummary::count)
                .sum();
    }

    private ExplorerEventDto awaitJourney(String path) throws Exception {
        return awaitNewJourney(path, null);
    }

    private ExplorerEventDto awaitNewJourney(String path, String previous) throws Exception {
        ExplorerEventDto latest = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            ExplorerReport report = json.readValue(get("/bootui/api/explorer").body(), ExplorerReport.class);
            assertThat(report.setup().beanCaptureEnabled()).isTrue();
            assertThat(report.setup().beanDetailAvailable())
                    .as(report.setup().reason())
                    .isTrue();
            for (ActivityEntryDto event : report.activity().entries()) {
                if ("REQUEST".equals(event.type())
                        && path.equals(event.path())
                        && !event.id().equals(previous)) {
                    latest = json.readValue(
                            get("/bootui/api/explorer/events/" + event.id()).body(), ExplorerEventDto.class);
                    if (!latest.invocations().isEmpty()
                            && latest.invocations().stream()
                                    .anyMatch(call -> event.id().equals(call.parentId()))) {
                        return latest;
                    }
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertThat(latest).as("Expected traced real MVC journey at " + path).isNotNull();
        assertThat(latest.invocations()).as(latest.warnings().toString()).isNotEmpty();
        return latest;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
