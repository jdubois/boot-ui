package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The runtime journal on Quarkus ({@code docs/PLAN-v2.md} §5.2): each application request publishes one {@code HTTP}
 * event when its body ends, and its blocking SQL, run on a worker thread, publishes a {@code SQL} event carrying the
 * same request id, so the aggregates fold the statement into the route the declared JAX-RS mappings name. BootUI's own
 * requests never enter the journal.
 */
@QuarkusTest
class BootUiQuarkusRuntimeJournalTest {

    @TestHTTPResource
    URL baseUrl;

    @Inject
    RuntimeJournal journal;

    @Inject
    JournalAggregates aggregates;

    @Test
    void requestsAndTheirSqlReachTheJournalAndFoldIntoTheirDeclaredRoute() throws Exception {
        long before = sqlChildrenOf(route());

        for (int i = 0; i < 3; i++) {
            assertThat(status("/it/sql")).isEqualTo(200);
        }
        assertThat(status("/bootui/api/overview")).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        RouteStats route = route();
        assertThat(route).as("the /it/sql route in the journal's aggregates").isNotNull();
        assertThat(route.requests()).isGreaterThanOrEqualTo(3);
        assertThat(route.statusClasses().get(1)).isGreaterThanOrEqualTo(3);
        assertThat(sqlChildrenOf(route) - before)
                .as("SQL folded into the route")
                .isGreaterThanOrEqualTo(3);
        assertThat(route.childCounts().get(JournalSource.CONNECTION))
                .as("each request's logical connections, published when released")
                .isGreaterThanOrEqualTo(3);
        assertThat(aggregates.snapshot().routes())
                .extracting(RouteStats::route)
                .noneMatch(name -> name.contains("/bootui"));

        List<JournalEntry> entries = journal.entries();
        Set<String> requestIds = entries.stream()
                .filter(entry -> entry.event().source() == JournalSource.HTTP)
                .map(entry -> entry.event().requestId())
                .collect(Collectors.toSet());
        assertThat(entries)
                .filteredOn(entry -> entry.event().source() == JournalSource.SQL
                        && entry.event().thread() != null
                        && entry.event().thread().startsWith("executor-thread"))
                .isNotEmpty()
                .allSatisfy(entry -> {
                    assertThat(requestIds).contains(entry.event().requestId());
                    assertThat(entry.event().threadKind()).isEqualTo(ThreadKind.WORKER);
                });
    }

    @Test
    void aBlockingRequestsCpuCoversItsWorkerSegment() throws Exception {
        String body = body("/it/cpu");
        long workerCpuNanos = Long.parseLong(body.substring(0, body.indexOf(':')));
        assertThat(body).as("served on a worker thread").contains("executor-thread");

        ResourceUsage usage = awaitResources("/it/cpu");

        assertThat(usage.availability()).isEqualTo(ResourceUsage.Availability.AVAILABLE);
        assertThat(usage.segments())
                .as("the event loop's segment and the worker's")
                .isGreaterThanOrEqualTo(2);
        assertThat(usage.cpuNanos()).isGreaterThanOrEqualTo(workerCpuNanos);
        assertThat(usage.allocatedBytes()).isPositive();
        assertThat(aggregates.snapshot().routes())
                .filteredOn(route -> route.route().equals("GET /it/cpu"))
                .singleElement()
                .satisfies(route ->
                        assertThat(route.resources().measuredRequests()).isPositive());
    }

    @Test
    void anExceptionFoldsIntoTheRouteThatThrewIt() throws Exception {
        assertThat(status("/it/boom")).isEqualTo(500);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        assertThat(aggregates.snapshot().routes())
                .filteredOn(route -> route.route().equals("GET /it/boom"))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.childCounts()).containsKey(JournalSource.EXCEPTION);
                    assertThat(route.statusClasses().get(4)).isPositive();
                });
    }

    private ResourceUsage awaitResources(String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();
            ResourceUsage usage = journal.entries().stream()
                    .filter(entry -> entry.event().payload() instanceof HttpPayload http
                            && path.equals(http.path())
                            && http.resources() != null)
                    .map(entry -> ((HttpPayload) entry.event().payload()).resources())
                    .findFirst()
                    .orElse(null);
            if (usage != null) {
                return usage;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no measured HTTP event for " + path);
    }

    private String body(String path) throws Exception {
        URI uri = baseUrl.toURI().resolve(path);
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private int status(String path) throws Exception {
        URI uri = baseUrl.toURI().resolve(path);
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private RouteStats route() {
        return aggregates.snapshot().routes().stream()
                .filter(route -> route.route().equals("GET /it/sql"))
                .findFirst()
                .orElse(null);
    }

    private static long sqlChildrenOf(RouteStats route) {
        return route == null ? 0 : route.childCounts().getOrDefault(JournalSource.SQL, 0L);
    }
}
