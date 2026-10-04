package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Code Paths against the real bridge class from the test class path, driven as the inlined advice would drive it: one
 * {@code CodePaths.enter} and {@code exit} per instrumented call, owned through the claim's handoffs.
 */
class CodePathsServiceTests {

    private static final String REQUEST = "00000000000000ab";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private CodePathsService service;
    private AgentClaim claim;

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
        if (service != null) {
            service.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aRequestsFragmentsBecomeItsTree() {
        start(AgentSensorSettings.defaults());
        int controller = CodeInventory.methodId("shop.OrderController#place()V");
        int service = CodeInventory.methodId("shop.OrderService#price()I");

        context.set(CorrelationContext.forRequest(REQUEST));
        call(controller, () -> call(service, null));
        context.set(CorrelationContext.NONE);

        RequestTree tree = this.service.tree(REQUEST);
        assertThat(tree).isNotNull();
        assertThat(tree.method()).containsExactly(RequestTree.REQUEST, controller, service);
        assertThat(tree.parent()).containsExactly(-1, 0, 1);
        assertThat(this.service.status()).containsEntry("fragments", 1L).containsEntry("openTrees", 1);

        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(this.service.recent()).extracting(RequestTree::requestId).containsExactly(REQUEST);
    }

    @Test
    void aFrequentCheapMethodIsExcludedThroughTheBridge() {
        start(AgentSensorSettings.defaults());
        int controller = CodeInventory.methodId("shop.OrderController#list()V");
        int getter = CodeInventory.methodId("shop.Order#getId()J");
        context.set(CorrelationContext.forRequest(REQUEST));
        // Advice calls take far longer than 2 µs here only when the JVM is cold: 60,000 calls of an empty getter.
        call(controller, () -> {
            for (int i = 0; i < 60_000; i++) {
                call(getter, null);
            }
        });
        context.set(CorrelationContext.NONE);
        service.tree(REQUEST);

        // The window ends a second later, at the next fragment.
        clock.addAndGet(1_000_000_000L);
        context.set(CorrelationContext.forRequest(REQUEST));
        call(controller, null);
        context.set(CorrelationContext.NONE);
        service.tree(REQUEST);

        assertThat(CodePaths.EXCLUDED[getter]).isEqualTo((byte) 1);
        assertThat(CodePaths.EXCLUDED[controller]).isZero();
        assertThat(service.excludedMethods()).containsExactly("shop.Order#getId()J");
    }

    @Test
    void anotherGenerationsFragmentsAreDroppedAndCounted() {
        start(AgentSensorSettings.defaults());
        long[] stale = Blobs.request(claim.generation() - 1, 1L)
                .between(0L, 1L)
                .node(-1, 1, 0, 1L, 1L, 0L)
                .blob();
        AgentRecordDrainer drainer = claim.drainer();
        assertThat(drainer).isNotNull();

        // Routed exactly as the drainer routes a blob.
        service.tree(REQUEST);
        java.util.function.Consumer<long[]> route = routeOf(drainer);
        route.accept(stale);
        route.accept(new long[] {42L});

        assertThat(service.status()).containsEntry("staleFragments", 1L).containsEntry("malformedFragments", 1L);
    }

    @Test
    void withoutTheSensorInTheClaimNothingIsRouted() {
        start(new AgentSensorSettings(List.of("executors", "inventory"), List.of(), List.of(), null));

        assertThat(service.status()).containsEntry("generation", null);
        assertThat(claim.drainer().running()).isFalse();
    }

    @Test
    void closingTheServiceStopsTheDrainerAndDisarmingClosesIt() {
        start(AgentSensorSettings.defaults());
        AgentRecordDrainer drainer = claim.drainer();
        assertThat(drainer.running()).isTrue();

        service.close();
        assertThat(drainer.running()).as("its last route is gone").isFalse();

        claim.disarm();
        assertThat(drainer.closed()).isTrue();
        assertThat(claim.drainer()).isNull();
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().equals(AgentRecordDrainer.THREAD_NAME) && thread.isAlive());
    }

    private void start(AgentSensorSettings sensors) {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class), "shop", "shop-owner", "dev", List.of("shop"), sensors);
        claim.attach(new AgentHandoffs(context::get, null, null));
        service = new CodePathsService(AgentBridgeAccess.bind(AgentBridge.class), () -> claim, () -> null, clock::get);
        service.start();
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<long[]> routeOf(AgentRecordDrainer drainer) {
        try {
            java.lang.reflect.Field field = AgentRecordDrainer.class.getDeclaredField("codePathsRoute");
            field.setAccessible(true);
            return (java.util.function.Consumer<long[]>) field.get(drainer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void call(int id, Runnable body) {
        int token = CodePaths.enter(id);
        try {
            if (body != null) {
                body.run();
            }
        } finally {
            CodePaths.exit(token);
        }
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
