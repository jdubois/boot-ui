package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class JournalBuildingBlocksTests {

    @Test
    void sourcesParseTheirPropertyNamesIgnoringCaseAndBlanks() {
        assertThat(JournalSource.parse(" HTTP, sql ,, rest-client,gc,resources"))
                .containsExactlyInAnyOrder(
                        JournalSource.HTTP,
                        JournalSource.SQL,
                        JournalSource.REST_CLIENT,
                        JournalSource.GC,
                        JournalSource.RESOURCES);
        assertThat(JournalSource.parse("")).isEmpty();
        assertThat(JournalSource.parse(null)).isEmpty();
        assertThat(JournalSource.all()).hasSize(JournalSource.values().length);
    }

    @Test
    void anUnknownSourceIsRejectedWithTheValidOnes() {
        assertThatThrownBy(() -> JournalSource.parse("sql, jdbc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'jdbc'")
                .hasMessageContaining("http, sql, transaction");
    }

    @Test
    void theDefaultByteBoundIsTheSmallerOf32MegabytesAndFivePercentOfTheHeap() {
        long megabyte = 1024L * 1024;

        assertThat(RuntimeJournalSettings.defaultMaxBytes(4096 * megabyte)).isEqualTo(32 * megabyte);
        assertThat(RuntimeJournalSettings.defaultMaxBytes(256 * megabyte)).isEqualTo(256 * megabyte * 5 / 100);
        assertThat(RuntimeJournalSettings.defaultMaxBytes(Long.MAX_VALUE)).isEqualTo(32 * megabyte);
        assertThat(RuntimeJournalSettings.defaultMaxBytes(-1)).isEqualTo(32 * megabyte);
    }

    @Test
    void defaultsRecordEverySourceAndReserveTheQueuesLastTenPercent() {
        RuntimeJournalSettings defaults = RuntimeJournalSettings.defaults();

        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.maxEvents()).isEqualTo(50_000);
        assertThat(defaults.queueCapacity()).isEqualTo(10_000);
        assertThat(defaults.routineQueueLimit()).isEqualTo(9_000);
        assertThat(defaults.sources()).isEqualTo(JournalSource.all());
        assertThat(defaults.records(JournalSource.LOG)).isTrue();
        assertThat(RuntimeJournalSettings.disabled().records(JournalSource.SQL)).isFalse();
        assertThat(new RuntimeJournalSettings(true, 0, 0, 0, 200, -5, EnumSet.of(JournalSource.SQL)))
                .satisfies(clamped -> {
                    assertThat(clamped.maxEvents()).isEqualTo(1);
                    assertThat(clamped.maxBytes()).isEqualTo(1);
                    assertThat(clamped.queueCapacity()).isEqualTo(1);
                    assertThat(clamped.reservedSharePercent()).isEqualTo(100);
                    assertThat(clamped.routineQueueLimit()).isEqualTo(1);
                });
    }

    @Test
    void propertiesMapToSettingsWithTheDefaultsWhereUnset() {
        RuntimeJournalSettings unset = RuntimeJournalSettings.of(true, 50_000, null, 10_000, null);
        RuntimeJournalSettings explicit = RuntimeJournalSettings.of(false, 10, 1_024L, 20, "sql,http");

        assertThat(unset.maxBytes())
                .isEqualTo(RuntimeJournalSettings.defaultMaxBytes(
                        Runtime.getRuntime().maxMemory()));
        assertThat(unset.sources()).isEqualTo(JournalSource.all());
        assertThat(explicit.enabled()).isFalse();
        assertThat(explicit.maxBytes()).isEqualTo(1_024);
        assertThat(explicit.sources()).containsExactlyInAnyOrder(JournalSource.SQL, JournalSource.HTTP);
        assertThatThrownBy(() -> RuntimeJournalSettings.of(true, 10, null, 10, "sql,jdbc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byteSizesParseAsSpringAndQuarkusWriteThem() {
        assertThat(RuntimeJournalSettings.parseBytes("1024")).isEqualTo(1024);
        assertThat(RuntimeJournalSettings.parseBytes("512B")).isEqualTo(512);
        assertThat(RuntimeJournalSettings.parseBytes("64kb")).isEqualTo(64L * 1024);
        assertThat(RuntimeJournalSettings.parseBytes("32MB")).isEqualTo(32L * 1024 * 1024);
        assertThat(RuntimeJournalSettings.parseBytes(" 32M ")).isEqualTo(32L * 1024 * 1024);
        assertThat(RuntimeJournalSettings.parseBytes("1G")).isEqualTo(1024L * 1024 * 1024);
        assertThat(RuntimeJournalSettings.parseBytes(null)).isNull();
        assertThat(RuntimeJournalSettings.parseBytes(" ")).isNull();
        assertThatThrownBy(() -> RuntimeJournalSettings.parseBytes("32 megabytes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootui.runtime-journal.max-bytes");
    }

    @Test
    void logEventsAreRecordedFromWarnUpAndKeepABoundedTemplate() {
        assertThat(LogPayload.isRecorded("WARN")).isTrue();
        assertThat(LogPayload.isRecorded("ERROR")).isTrue();
        assertThat(LogPayload.isRecorded("FATAL")).isTrue();
        assertThat(LogPayload.isRecorded("SEVERE")).isTrue();
        assertThat(LogPayload.isRecorded("INFO")).isFalse();
        assertThat(LogPayload.isRecorded(null)).isFalse();
        assertThat(new LogPayload("l", "WARN", "x".repeat(800), null).template())
                .hasSize(LogPayload.MAX_TEMPLATE_LENGTH);
    }

    @Test
    void applicationFramesKeepTheFirstFourApplicationFramesInnermostFirst() {
        ApplicationFrames frames = ApplicationFrames.select(Stream.of(
                frame("java.lang.reflect.Method", "invoke", "Method.java", 580),
                frame("com.example.orders.OrderRepository", "find", "OrderRepository.java", 12),
                frame("org.hibernate.internal.SessionImpl", "find", "SessionImpl.java", 10),
                frame("com.example.orders.OrderService", "place", "OrderService.java", 34),
                frame("com.example.orders.OrderService$$SpringCGLIB$$0", "place", "<generated>", -1),
                frame("com.example.orders.OrderService_Subclass", "place", null, -1),
                frame("com.example.orders.OrderController", "post", "OrderController.java", 56),
                frame("com.example.orders.Filter", "doFilter", null, -1),
                frame("com.example.orders.Main", "main", "Main.java", 1)));

        assertThat(frames.frames())
                .containsExactly(
                        "com.example.orders.OrderRepository.find(OrderRepository.java:12)",
                        "com.example.orders.OrderService.place(OrderService.java:34)",
                        "com.example.orders.OrderController.post(OrderController.java:56)",
                        "com.example.orders.Filter.doFilter(Unknown Source)");
        assertThat(frames.callSite()).isEqualTo("com.example.orders.OrderRepository.find(OrderRepository.java:12)");
        assertThat(ApplicationFrames.select(Stream.of(frame("java.lang.Thread", "run", "Thread.java", 1))))
                .isNull();
    }

    @Test
    void internedFramesAreSharedAcrossEventsAndCountOnlyWhenTheDictionaryIsFull() {
        JournalDictionary dictionary = new JournalDictionary(100, 100_000);
        ApplicationFrames first =
                ApplicationFrames.of(List.of(new String("A.a(A.java:1)"), new String("B.b(B.java:2)")));
        ApplicationFrames second = ApplicationFrames.of(List.of(new String("A.a(A.java:1)")));

        ApplicationFrames sharedFirst = first.interned(dictionary);
        ApplicationFrames sharedSecond = second.interned(dictionary);

        assertThat(sharedSecond.frames().get(0)).isSameAs(sharedFirst.frames().get(0));
        assertThat(sharedFirst.estimatedBytes()).isEqualTo(16 + 16);
        assertThat(first.estimatedBytes()).isGreaterThan(sharedFirst.estimatedBytes());
        ApplicationFrames full = first.interned(new JournalDictionary(0, 0));
        assertThat(full.estimatedBytes()).isEqualTo(first.estimatedBytes());
    }

    @Test
    void theJournalInternsPayloadFramesBeforeItRetainsThem() {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        try {
            for (int i = 0; i < 2; i++) {
                journal.offer(RuntimeEvent.of(
                        JournalSource.SQL,
                        i,
                        1,
                        null,
                        null,
                        null,
                        false,
                        new SqlPayload(
                                "select 1",
                                new String("A.a(A.java:1)"),
                                null,
                                false,
                                ApplicationFrames.of(List.of(new String("A.a(A.java:1)"))))));
            }
            journal.dispatchPending();

            List<JournalEntry> entries = journal.entries();
            SqlPayload newest = (SqlPayload) entries.get(0).event().payload();
            SqlPayload oldest = (SqlPayload) entries.get(1).event().payload();
            assertThat(newest.frames().frames().get(0))
                    .isSameAs(oldest.frames().frames().get(0));
            assertThat(newest.callSite()).isSameAs(oldest.frames().frames().get(0));
            assertThat(journal.status().dictionaryEntries()).isEqualTo(1);
        } finally {
            journal.close();
        }
    }

    private static StackWalker.StackFrame frame(String className, String method, String file, int line) {
        StackWalker.StackFrame frame = mock(StackWalker.StackFrame.class);
        when(frame.getClassName()).thenReturn(className);
        when(frame.getMethodName()).thenReturn(method);
        when(frame.getFileName()).thenReturn(file);
        when(frame.getLineNumber()).thenReturn(line);
        return frame;
    }

    @Test
    void anEventKnowsHowItIsCorrelatedAndWhatItRetains() {
        RuntimeEvent byRequest = RuntimeEvent.of(
                JournalSource.SQL, 1, 1, CorrelationContext.forRequest("r1"), "worker", null, false, () -> 100);
        RuntimeEvent byTrace = RuntimeEvent.of(
                JournalSource.SQL,
                1,
                1,
                CorrelationContext.NONE.withTrace("0af7651916cd43dd8448eb211c80319c", null),
                null,
                null,
                false,
                null);
        RuntimeEvent unowned = RuntimeEvent.of(JournalSource.SQL, 1, 1, null, null, null, false, null);

        assertThat(byRequest.correlationTier()).isEqualTo(CorrelationTier.REQUEST_ID);
        assertThat(byTrace.correlationTier()).isEqualTo(CorrelationTier.TRACE_ID);
        assertThat(unowned.correlationTier()).isNull();
        assertThat(byRequest.estimatedBytes())
                .isEqualTo(RuntimeEvent.ENVELOPE_BYTES
                        + RuntimeEvent.stringBytes("r1")
                        + RuntimeEvent.stringBytes("worker")
                        + 100);
        assertThat(unowned.estimatedBytes()).isEqualTo(RuntimeEvent.ENVELOPE_BYTES);
    }

    @Test
    void theResourcesSourcePublishesNoEvents() {
        assertThat(JournalSource.RESOURCES.publishesEvents()).isFalse();
        assertThatThrownBy(() -> RuntimeEvent.of(JournalSource.RESOURCES, 1, 1, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resources");
    }

    @Test
    void theDictionaryInternsOnceAndStopsAtItsBounds() {
        JournalDictionary dictionary = new JournalDictionary(2, 10_000);

        int orders = dictionary.intern("/api/orders/{id}");
        assertThat(dictionary.intern("/api/orders/{id}")).isEqualTo(orders);
        int products = dictionary.intern("/api/products");
        assertThat(dictionary.intern("/api/customers")).isEqualTo(JournalDictionary.NOT_INTERNED);
        assertThat(dictionary.intern(null)).isEqualTo(JournalDictionary.NOT_INTERNED);

        assertThat(dictionary.lookup(orders)).isEqualTo("/api/orders/{id}");
        assertThat(dictionary.lookup(products)).isEqualTo("/api/products");
        assertThat(dictionary.lookup(JournalDictionary.NOT_INTERNED)).isNull();
        assertThat(dictionary.size()).isEqualTo(2);

        JournalDictionary small = new JournalDictionary(100, 100);
        assertThat(small.intern("x".repeat(200))).isEqualTo(JournalDictionary.NOT_INTERNED);
        assertThat(small.bytes()).isZero();
    }
}
