package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunStart;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.StartupStepTiming;
import io.github.jdubois.bootui.engine.resources.ResourceTrack;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The runtime journal on Spring MVC ({@code docs/PLAN-v2.md} §5.2): each application request publishes one
 * {@code HTTP} event with the template Spring MVC matched, and its SQL publishes {@code SQL} events carrying the same
 * request id, so the aggregates fold the statements into the route. BootUI's own requests never enter the journal.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.datasource.url=jdbc:h2:mem:bootui_journal;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/runtime-journal/application-bootui.properties",
            "bootui.resources.sample-interval=200ms"
        })
class SpringRuntimeJournalTest {

    @LocalServerPort
    int port;

    @Value("${spring.threads.virtual.enabled:false}")
    boolean virtualThreads;

    @Autowired
    RuntimeJournal journal;

    @Autowired
    JournalAggregates aggregates;

    @Test
    void theRunStartRecordsTheTimeToReadyTheSlowestBeansAndTheComparabilityFacts() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (aggregates.runStart() == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        RunStart start = aggregates.runStart();
        assertThat(start).isNotNull();
        assertThat(start.readyNanos()).isPositive();
        assertThat(start.slowestSteps()).isNotEmpty().allSatisfy(step -> {
            assertThat(step.name()).isEqualTo("spring.beans.instantiate");
            assertThat(step.bean()).isNotBlank();
        });
        assertThat(start.slowestSteps())
                .extracting(StartupStepTiming::durationNanos)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(start.facts().activeProfiles()).containsExactly("dev");
        assertThat(start.facts().dataSources()).containsValue("jdbc:h2:mem");
        assertThat(start.facts().journalSources()).contains("lifecycle", "http", "sql");
    }

    @Test
    void theResourceSamplerSweepsAtTheConfiguredIntervalWithABalancedLedger() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (aggregates.resourceTrack().points().size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        List<ResourceTrack.Point> points = aggregates.resourceTrack().points();
        assertThat(points).hasSizeGreaterThanOrEqualTo(3);
        ResourceTrack.Point last = points.get(points.size() - 1);
        assertThat(last.intervalNanos())
                .as("bootui.resources.sample-interval=200ms")
                .isLessThan(Duration.ofMillis(900).toNanos());
        assertThat(last.heapUsedBytes()).isPositive();
        assertThat(points).allSatisfy(point -> {
            if (point.processCpuNanos() >= 0) {
                assertThat(point.requestCpuNanos() + point.familiesCpuNanos() + point.internalCpuNanos())
                        .isEqualTo(point.processCpuNanos());
            }
        });
        assertThat(aggregates.resourceTrack().families()).contains(ResourceTrack.BOOTUI_FAMILY);
    }

    @Test
    void securityCacheAndExceptionEventsFoldIntoTheRouteThatProducedThem() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

        assertThat(probe.get("/api/secure/products", Map.of("Authorization", basic))
                        .status())
                .isEqualTo(200);
        assertThat(probe.get("/api/sample/products").status()).isEqualTo(200);
        assertThat(probe.get("/api/sample/boom").status()).isEqualTo(500);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        assertThat(route("GET /api/secure/products").childCounts()).containsKey(JournalSource.SECURITY);
        assertThat(route("GET /api/sample/products").childCounts()).containsKey(JournalSource.CACHE);
        RouteStats boom = route("GET /api/sample/boom");
        assertThat(boom.childCounts()).containsKey(JournalSource.EXCEPTION);
        assertThat(boom.statusClasses().get(4)).isPositive();
        assertThat(aggregates.snapshot().exceptionGroups())
                .anySatisfy(group -> assertThat(group.routes()).containsKey("GET /api/sample/boom"));
    }

    @Test
    void authorizationDecisionsJoinTheirRequestWithHowTheCallerWasAuthenticated() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

        assertThat(probe.get("/api/secure/products", Map.of("Authorization", basic))
                        .status())
                .isEqualTo(200);
        assertThat(probe.get("/api/secure/products").status()).isEqualTo(401);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        List<RuntimeEvent> decisions = journal.entries().stream()
                .map(JournalEntry::event)
                .filter(event -> event.source() == JournalSource.AUTHORIZATION)
                .toList();
        assertThat(decisions).anySatisfy(event -> {
            AuthorizationPayload decision = (AuthorizationPayload) event.payload();
            assertThat(decision.target()).isEqualTo(AuthorizationPayload.REQUEST);
            assertThat(decision.granted()).isTrue();
            assertThat(decision.authentication()).isEqualTo(AuthorizationPayload.AUTHENTICATED);
            assertThat(decision.authorities()).isPositive();
            assertThat(event.requestId()).isNotBlank();
            assertThat(event.durationNanos()).isPositive();
        });
        assertThat(decisions).anySatisfy(event -> {
            AuthorizationPayload decision = (AuthorizationPayload) event.payload();
            assertThat(decision.granted()).isFalse();
            assertThat(decision.authentication()).isEqualTo(AuthorizationPayload.ANONYMOUS);
        });
        RouteStats secure = route("GET /api/secure/products");
        assertThat(secure.authorization().authenticated()).isPositive();
        assertThat(secure.authorization().denied()).isPositive();
    }

    @Test
    void eachRequestCarriesItsMeasuredResourcesOrWhyTheJvmCouldNotMeasureThem() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        for (int i = 0; i < 2; i++) {
            assertThat(probe.get("/api/sample/products").status()).isEqualTo(200);
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        List<ResourceUsage> usages = journal.entries().stream()
                .filter(entry -> entry.event().payload() instanceof HttpPayload http
                        && "/api/sample/products".equals(http.path()))
                .map(entry -> ((HttpPayload) entry.event().payload()).resources())
                .toList();
        assertThat(usages).hasSizeGreaterThanOrEqualTo(2).doesNotContainNull();
        if (virtualThreads && Runtime.version().feature() >= 21) {
            // spring.threads.virtual.enabled serves each request on a virtual thread, which the JVM does not measure.
            assertThat(usages).allSatisfy(usage -> {
                assertThat(usage.availability()).isEqualTo(ResourceUsage.Availability.UNAVAILABLE);
                assertThat(usage.unmeasuredReason()).isEqualTo(ResourceUsage.Unmeasured.VIRTUAL_THREAD);
            });
            assertThat(route("GET /api/sample/products").resources().unmeasuredRequests())
                    .isGreaterThanOrEqualTo(2);
        } else {
            assertThat(usages).allSatisfy(usage -> {
                assertThat(usage.availability()).isEqualTo(ResourceUsage.Availability.AVAILABLE);
                assertThat(usage.cpuNanos()).isPositive();
                assertThat(usage.allocatedBytes()).isPositive();
            });
            assertThat(route("GET /api/sample/products").resources().measuredRequests())
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void everyCollectionIsRecordedAsAGcEvent() throws Exception {
        long before = journal.status().accepted().getOrDefault(JournalSource.GC, 0L);

        System.gc();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (journal.status().accepted().getOrDefault(JournalSource.GC, 0L) == before
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(journal.status().accepted().getOrDefault(JournalSource.GC, 0L))
                .isGreaterThan(before);
    }

    @Test
    void aRequestsJournalProfileShowsItsSqlOnItsTimelineAndTheTablesItNamed() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);
        assertThat(probe.get("/api/sample/product-search?term=desk").status()).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();
        String requestId = journal.entries().stream()
                .filter(entry -> entry.event().payload() instanceof HttpPayload http
                        && "/api/sample/product-search".equals(http.path()))
                .map(entry -> entry.event().requestId())
                .findFirst()
                .orElseThrow();

        String body = probe.get("/bootui/api/activity/request/" + requestId + "/journal")
                .body();

        assertThat(body)
                .contains("\"available\":true")
                .contains("\"route\":\"GET /api/sample/product-search\"")
                .contains("\"source\":\"sql\"")
                .contains("\"source\":\"connection\"")
                .containsPattern("\"tables\":\\[\"[a-z_.]+\"");
    }

    private RouteStats route(String name) {
        return aggregates.snapshot().routes().stream()
                .filter(candidate -> candidate.route().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no route " + name + " in " + aggregates.snapshot().routes()));
    }

    @Test
    void requestsAndTheirSqlReachTheJournalAndFoldIntoTheMatchedRoute() throws Exception {
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        for (int i = 0; i < 3; i++) {
            assertThat(probe.get("/api/sample/product-search?term=console").status())
                    .isEqualTo(200);
        }
        assertThat(probe.get("/bootui/api/overview").status()).isEqualTo(200);
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

        RouteStats route = aggregates.snapshot().routes().stream()
                .filter(candidate -> candidate.route().equals("GET /api/sample/product-search"))
                .findFirst()
                .orElseThrow();
        assertThat(route.requests()).isEqualTo(3);
        assertThat(route.statusClasses().get(1)).isEqualTo(3);
        assertThat(route.childCounts().get(JournalSource.SQL)).isGreaterThanOrEqualTo(3);
        assertThat(route.statements()).isNotEmpty();
        assertThat(route.childCounts().get(JournalSource.CONNECTION))
                .as("each request's logical connections, published when released")
                .isGreaterThanOrEqualTo(3);
        assertThat(journal.entries())
                .filteredOn(entry -> entry.event().source() == JournalSource.SQL
                        && entry.event().requestId() != null
                        && entry.event().payload() instanceof SqlPayload sql
                        && sql.frames() != null)
                .as("SQL events carry the application frames above them, innermost first")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(
                                ((SqlPayload) entry.event().payload()).frames().frames())
                        .hasSizeBetween(2, ApplicationFrames.MAX_FRAMES)
                        .allSatisfy(frame -> assertThat(frame)
                                .startsWith("io.github.jdubois.bootui.sample.")
                                .doesNotContain("$$")));
        assertThat(journal.entries())
                .filteredOn(entry -> entry.event().source() == JournalSource.HTTP)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.event().threadKind()).isNotNull());
        assertThat(route.childCounts().get(JournalSource.TRANSACTION))
                .as("product-search runs in a Spring transaction")
                .isGreaterThanOrEqualTo(3);
        assertThat(aggregates.snapshot().transactionalMethods())
                .anySatisfy(method -> assertThat(method.transactions()).isGreaterThanOrEqualTo(3));
        assertThat(aggregates.snapshot().routes())
                .extracting(RouteStats::route)
                .noneMatch(name -> name.contains("/bootui"));
        assertThat(journal.status().accepted()).containsKeys(JournalSource.HTTP, JournalSource.SQL);
        assertThat(journal.status().droppedTotal()).isZero();
    }
}
