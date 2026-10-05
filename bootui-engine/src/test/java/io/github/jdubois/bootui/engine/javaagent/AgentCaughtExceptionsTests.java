package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CaughtExceptions;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.CaughtExceptionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine side of the caught-exceptions sensor (PLAN-v2 M5-6a) against the real bridge class from the test class
 * path, driven as the agent's inserted calls would drive it, owned through the claim's handoffs: each record becomes one
 * journal event with its owner, site, and class, never the exception's message, and nothing made before a Clear
 * recording is published after it.
 */
class AgentCaughtExceptionsTests {

    private static final String REQUEST = "00000000000000ab";
    private static final String SECRET = "hunter2-secret";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final List<RuntimeEvent> events = new ArrayList<>();
    private final RuntimeEventSink sink = new RuntimeEventSink() {
        @Override
        public boolean offer(RuntimeEvent event) {
            events.add(event);
            return true;
        }

        @Override
        public boolean records(JournalSource source) {
            return true;
        }
    };
    private AgentClaim claim;
    private AgentCaughtExceptions caught;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void resetAgent() {
        if (caught != null) {
            caught.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aCaughtExceptionIsOneJournalEventWithItsOwnerSiteAndClassNeverItsMessage() {
        start(List.of(AgentSensorSettings.CAUGHT_EXCEPTIONS));
        caught.setRuntimeEventSink(sink);
        int site = CaughtExceptions.site(
                "com/example/Shop#buy(Ljava/lang/String;)V#0#java/io/IOException|java/sql/SQLException",
                "java/io/IOException|java/sql/SQLException");
        CaughtExceptions.siteRead(site, CaughtExceptions.FLAG_EXIT_HANDLER, 42);
        context.set(CorrelationContext.forRequest(REQUEST));

        CaughtExceptions.caught(new IOException(SECRET), site);
        claim.drainer().drainNow();

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.AGENT_CAUGHT_EXCEPTIONS);
            assertThat(event.requestId()).isEqualTo(REQUEST);
            assertThat(event.executionId()).isNull();
            assertThat(event.thread()).isEqualTo(Thread.currentThread().getName());
            assertThat(event.toString()).doesNotContain(SECRET);
            CaughtExceptionPayload payload = (CaughtExceptionPayload) event.payload();
            assertThat(payload.kind()).isEqualTo(CaughtExceptionPayload.CAUGHT);
            assertThat(payload.siteClass()).isEqualTo("com.example.Shop");
            assertThat(payload.siteMethod()).isEqualTo("buy(Ljava/lang/String;)V");
            assertThat(payload.line()).isEqualTo(42);
            assertThat(payload.declaredTypes()).containsExactly("java/io/IOException", "java/sql/SQLException");
            assertThat(payload.siteFlags() & CaughtExceptions.FLAG_EXIT_HANDLER).isNotZero();
            assertThat(payload.exceptionClass()).isEqualTo("java.io.IOException");
            assertThat(payload.family()).isEqualTo("io");
            assertThat(payload.count()).isEqualTo(1L);
        });
        assertThat(caught.counters()).containsEntry("published", 1L);
    }

    @Test
    void aRethrowIsAThrownEventNamingWhereItWasFoundAndTheOwnerItWasCaughtUnder() {
        start(List.of(AgentSensorSettings.CAUGHT_EXCEPTIONS));
        caught.setRuntimeEventSink(sink);
        int site = CaughtExceptions.site("com/example/Shop#pay()V#0#java/lang/IllegalStateException", "x");
        int outer = CaughtExceptions.site("com/example/Checkout#run()V#0#java/lang/IllegalStateException", "x");
        context.set(CorrelationContext.forRequest(REQUEST));
        IllegalStateException thrown = new IllegalStateException();

        CaughtExceptions.caught(thrown, site);
        context.set(CorrelationContext.NONE);
        CaughtExceptions.leaving(thrown, outer);
        claim.drainer().drainNow();

        assertThat(events).hasSize(2);
        CaughtExceptionPayload payload = (CaughtExceptionPayload) events.get(1).payload();
        assertThat(payload.kind()).isEqualTo(CaughtExceptionPayload.THROWN);
        assertThat(payload.siteMethod()).isEqualTo("pay()V");
        assertThat(payload.foundBy()).isEqualTo(CaughtExceptionPayload.EXIT);
        assertThat(payload.foundAt()).isEqualTo("com.example.Checkout#run()V");
        assertThat(payload.identity()).isEqualTo(((CaughtExceptionPayload) events.get(0).payload()).identity());
        assertThat(events.get(1).requestId()).isEqualTo(REQUEST);
    }

    @Test
    void occurrencesCountedRatherThanReportedAreOneUntrackedEventWithTheirCount() {
        start(List.of(AgentSensorSettings.CAUGHT_EXCEPTIONS));
        caught.setRuntimeEventSink(sink);
        int site = CaughtExceptions.site("com/example/Shop#loop()V#0#java/lang/RuntimeException", "x");
        context.set(CorrelationContext.forRequest(REQUEST));
        for (int i = 0; i < CaughtExceptions.PER_SITE + 3; i++) {
            CaughtExceptions.caught(new RuntimeException(), site);
        }
        CaughtExceptions.flushThread();
        claim.drainer().drainNow();

        CaughtExceptionPayload last = (CaughtExceptionPayload) events.get(events.size() - 1).payload();
        assertThat(last.kind()).isEqualTo(CaughtExceptionPayload.UNTRACKED);
        assertThat(last.count()).isEqualTo(3L);
        assertThat(last.identity()).isZero();
    }

    @Test
    void recordsMadeBeforeAClearAreNeverPublishedAfterIt() throws Exception {
        start(List.of(AgentSensorSettings.CAUGHT_EXCEPTIONS));
        try (RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 2, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false)) {
            caught.setRuntimeEventSink(journal);
            int site = CaughtExceptions.site("com/example/Shop#clear()V#0#java/lang/RuntimeException", "x");
            context.set(CorrelationContext.forRequest(REQUEST));
            CaughtExceptions.caught(new RuntimeException(), site);

            journal.clear();
            claim.drainer().drainNow();
            assertThat(caught.counters()).containsEntry("beforeClear", 1L).containsEntry("published", 0L);

            Thread.sleep(5);
            CaughtExceptions.caught(new RuntimeException(), site);
            claim.drainer().drainNow();
            assertThat(caught.counters()).containsEntry("published", 1L);
        }
    }

    @Test
    void aClaimWithoutTheSensorRoutesNothing() {
        start(List.of(AgentSensorSettings.EXECUTORS));
        caught.setRuntimeEventSink(sink);
        CaughtExceptions.enable();
        context.set(CorrelationContext.forRequest(REQUEST));

        CaughtExceptions.caught(new RuntimeException(), CaughtExceptions.site("com/example/Shop#x()V#0#y", "y"));
        AgentRecordDrainer drainer = claim.drainer();
        if (drainer != null) {
            drainer.drainNow();
        }

        assertThat(events).isEmpty();
        assertThat(caught.counters()).containsEntry("published", 0L);
    }

    @Test
    void executionIdsKeepTheirKind() {
        assertThat(AgentCaughtExceptions.executionId(0xcdL, AgentCaughtExceptions.EXECUTION_ASYNC))
                .isEqualTo("async-00000000000000cd");
        assertThat(AgentCaughtExceptions.executionId(0xcdL, AgentCaughtExceptions.EXECUTION_TASK))
                .isEqualTo("task-00000000000000cd");
        assertThat(AgentCaughtExceptions.executionId(0xcdL, AgentCaughtExceptions.EXECUTION_OWN))
                .isEqualTo("00000000000000cd");
        assertThat(AgentCaughtExceptions.executionId(0L, AgentCaughtExceptions.EXECUTION_OWN)).isNull();
    }

    private void start(List<String> sensors) {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("com.example"),
                new AgentSensorSettings(sensors, List.of(), List.of(), null));
        claim.attach(new AgentHandoffs(context::get, null, null));
        CaughtExceptions.enable();
        caught = new AgentCaughtExceptions(AgentBridgeAccess.bind(AgentBridge.class), () -> claim);
        caught.start();
    }

    private static void resetBridge() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
