package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.ProxyBoundaries.Boundary;
import io.github.jdubois.bootui.engine.insights.ProxyBypass;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SpringProxyBoundariesTests {

    private final SpringProxyBoundaries boundaries = new SpringProxyBoundaries(null);

    @Test
    void annotationsDeclaredOnAnInterfaceOrTheClassCountAndOnlyRequiringPropagationsAreTransactional() {
        assertThat(boundaries.resolve(frame(Orders.class, "place"))).isEqualTo(new Boundary(true, Set.of(), false));
        assertThat(boundaries.resolve(frame(Orders.class, "browse")))
                .as("SUPPORTS runs without a transaction when none is open")
                .isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(Notifier.class, "shipped"))).isEqualTo(new Boundary(false, Set.of(), true));
        assertThat(boundaries.resolve(frame(Prices.class, "price")))
                .as("a @Cacheable without names takes the class's @CacheConfig")
                .isEqualTo(new Boundary(false, Set.of("prices"), false));
    }

    @Test
    void unknownClassesAndOverloadsThatDisagreeAreNotResolvedWhileUnannotatedMethodsHaveNoBoundary() {
        assertThat(boundaries.resolve("com.example.Missing.run(Missing.java:1)"))
                .isNull();
        assertThat(boundaries.resolve(frame(Overloaded.class, "save"))).isNull();
        assertThat(boundaries.resolve(frame(Overloaded.class, "plain"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(Orders.class, "lambda$place$0"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.notApplicable()).isNull();
    }

    @Test
    void synchronousAndConditionalCachesAreNotJudgedButOtherBoundariesAndUnlessStillAre() {
        assertThat(boundaries.resolve(frame(Prices.class, "sync"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(Prices.class, "conditional"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(ConditionalPrices.class, "price"))).isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(ConditionalPriceImpl.class, "price")))
                .isEqualTo(Boundary.NONE);
        assertThat(boundaries.resolve(frame(Prices.class, "syncTransactional")))
                .isEqualTo(new Boundary(true, Set.of(), false));
        assertThat(boundaries.resolve(frame(Prices.class, "unless")))
                .isEqualTo(new Boundary(false, Set.of("prices"), false));
    }

    @Test
    void sqlBeforeASyncMissAndConditionSkippedSqlDoNotBecomeProxyBypasses() throws InterruptedException {
        assertBypass("sync", false, true, null);
        assertBypass("conditional", false, false, null);
        assertBypass("price", true, false, null);
        assertBypass("price", false, false, "@Cacheable(prices)");
        assertBypass("unless", true, false, null);
        assertBypass("unless", false, false, "@Cacheable(prices)");
        assertBypass("syncTransactional", false, true, "@Transactional");
    }

    private void assertBypass(String method, boolean cacheBefore, boolean cacheAfter, String annotation)
            throws InterruptedException {
        try (RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start())) {
            CorrelationContext correlation = CorrelationContext.forRequest("r1");
            RuntimeEvent cache = RuntimeEvent.of(
                    JournalSource.CACHE,
                    1_000,
                    0,
                    correlation,
                    "http-1",
                    null,
                    false,
                    new CachePayload("prices", "MISS", null));
            if (cacheBefore) {
                journal.offer(cache);
            }
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    1_000,
                    100,
                    correlation,
                    "http-1",
                    null,
                    false,
                    new SqlPayload(
                            "select * from prices",
                            null,
                            "db",
                            false,
                            ApplicationFrames.of(List.of(frame(Prices.class, method))),
                            null,
                            1_000)));
            if (cacheAfter) {
                journal.offer(cache);
            }
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    5_000_000,
                    correlation,
                    "http-1",
                    null,
                    false,
                    new HttpPayload("GET", "/prices", "/prices", null, 200)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            RuntimeInsightsService service =
                    new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
            service.setProxyBoundaries(boundaries);
            var findings = service.report().observations().stream()
                    .filter(observation -> observation.kind().equals(ProxyBypass.KIND))
                    .toList();
            if (annotation == null) {
                assertThat(findings).isEmpty();
            } else {
                assertThat(findings)
                        .singleElement()
                        .satisfies(finding -> assertThat(finding.sentence()).contains(annotation));
            }
        }
    }

    private static String frame(Class<?> type, String method) {
        return type.getName() + "." + method + "(" + type.getSimpleName() + ".java:1)";
    }

    interface OrderOperations {

        @Transactional
        void place();
    }

    static class Orders implements OrderOperations {

        @Override
        public void place() {}

        @Transactional(propagation = Propagation.SUPPORTS)
        public void browse() {}
    }

    @Async
    static class Notifier {

        public void shipped() {}
    }

    @CacheConfig(cacheNames = "prices")
    static class Prices {

        @Cacheable
        public long price() {
            return 1;
        }

        @Cacheable(sync = true)
        public long sync() {
            return 1;
        }

        @Cacheable(condition = "#root.args.length > 0")
        public long conditional() {
            return 1;
        }

        @Cacheable(sync = true)
        @Transactional
        public long syncTransactional() {
            return 1;
        }

        @Cacheable(unless = "#result == 1")
        public long unless() {
            return 1;
        }
    }

    @Cacheable(cacheNames = "prices", condition = "false")
    static class ConditionalPrices {
        public long price() {
            return 1;
        }
    }

    interface ConditionalPrice {
        @Cacheable(cacheNames = "prices", condition = "false")
        long price();
    }

    static class ConditionalPriceImpl implements ConditionalPrice {
        @Override
        public long price() {
            return 1;
        }
    }

    static class Overloaded {

        @Transactional
        public void save(String value) {}

        public void save(int value) {}

        public void plain() {}
    }
}
