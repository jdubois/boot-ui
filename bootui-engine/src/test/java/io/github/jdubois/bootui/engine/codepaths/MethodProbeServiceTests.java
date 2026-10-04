package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import io.github.jdubois.bootui.core.dto.CodePathsProbeDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbesReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Method probes' engine side ({@code docs/PLAN-v2.md} M5-8) against the real bridge class from the test class path,
 * with a fake agent that accepts every probe and installs nothing: the test drives the bridge as the agent's worker and
 * inlined advice would.
 */
class MethodProbeServiceTests {

    private static final String REQUEST = "00000000000000ab";
    private static final String QUOTE = "shop.QuoteService#quote(I)J";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final List<Map<String, Object>> agentCalls = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final AgentEvidence evidence = new AgentEvidence(panel -> !hidden.contains(panel), null);
    private AgentClaim claim;
    private CodePathsService codePaths;
    private MethodProbeService probes;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            agentCalls.add(request);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        CodeInventory.methodId(QUOTE);
        CodeInventory.methodId("shop.QuoteService#total()J");
    }

    @AfterEach
    void resetAgent() {
        if (codePaths != null) {
            codePaths.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aStartedProbeRecordsItsInvocationsAsMetadata() {
        start(() -> null);

        CodePathsProbeDto started = probes.start(QUOTE);

        assertThat(started.state()).isEqualTo("starting");
        assertThat(started.method()).isEqualTo(QUOTE);
        assertThat(agentCalls).anySatisfy(call -> assertThat(call).containsEntry("op", "method-probe"));
        long id = Long.parseLong(started.id());
        MethodProbes.activate(0, id);
        MethodProbes.advised(0, id, "(I)J");
        context.set(CorrelationContext.forRequest(REQUEST));
        MethodProbes.exit(0, id, MethodProbes.enter(0, id), null);
        MethodProbes.exit(0, id, MethodProbes.enter(0, id), new IllegalArgumentException("not recorded"));
        context.set(CorrelationContext.NONE);
        MethodProbes.exit(0, id, MethodProbes.enter(0, id), null);

        CodePathsProbeDto probe = probes.probe(started.id());

        assertThat(probe.state()).isEqualTo("active");
        assertThat(probe.waitingForClass()).isFalse();
        assertThat(probe.async()).isFalse();
        assertThat(probe.invocations()).isEqualTo(3);
        assertThat(probe.hits()).hasSize(3);
        assertThat(probe.hits().get(0).requestId()).isEqualTo(REQUEST);
        assertThat(probe.hits().get(0).outcome()).isEqualTo("returned");
        assertThat(probe.hits().get(0).threadKind()).isEqualTo("platform");
        assertThat(probe.hits().get(1).outcome()).isEqualTo("threw");
        assertThat(probe.hits().get(1).exceptionType()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(probe.hits().get(2).requestId()).isNull();
        assertThat(probe.hits())
                .allSatisfy(hit -> assertThat(hit.durationMicros()).isNotNegative());

        CodePathsProbesReport report = probes.report();
        assertThat(report.available()).isTrue();
        assertThat(report.maxActive()).isEqualTo(5);
        assertThat(report.maxInvocations()).isEqualTo(20);
        assertThat(report.windowSeconds()).isEqualTo(60);
        assertThat(report.probes())
                .singleElement()
                .satisfies(listed -> assertThat(listed.hits()).hasSize(3));
        assertThat(report.limitations()).anySatisfy(text -> assertThat(text).contains("never an argument"));
    }

    @Test
    void aStopEndsTheProbe() {
        start(() -> null);
        CodePathsProbeDto started = probes.start(QUOTE);
        MethodProbes.activate(0, Long.parseLong(started.id()));

        CodePathsProbeDto stopped = probes.stop(started.id());

        assertThat(stopped.state()).isEqualTo("ending");
        assertThat(stopped.endReason()).isEqualTo("stopped");
    }

    @Test
    void invalidMethodsAreBadRequestsAndRefusalsAreConflicts() {
        start(() -> null);

        assertThatThrownBy(() -> probes.start(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> probes.start("other.Service#run"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the application's packages");
        assertThatThrownBy(() -> probes.start("shop.QuoteService#unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Neither Code Inventory nor Code Paths knows");
        probes.start(QUOTE);
        assertThatThrownBy(() -> probes.start(QUOTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already probing");
        assertThatThrownBy(() -> probes.probe("999")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> probes.probe("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void probesAreUnavailableWithCodePaths() {
        start(() -> "Requires the BootUI agent's code-paths sensor.");

        assertThat(probes.report().available()).isFalse();
        assertThat(probes.report().unavailableReason()).startsWith("Requires the BootUI agent's code-paths sensor");
        assertThatThrownBy(() -> probes.start(QUOTE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theAgentsOwnReasonComesBeforeTheHiddenPanels() {
        start(() -> "Requires the BootUI agent's code-paths sensor.");
        hidden.add(BootUiPanels.CODE_PATHS);

        assertThat(probes.report().unavailableReason()).startsWith("Requires the BootUI agent's code-paths sensor");
    }

    @Test
    void withoutTheAgentProbesAreUnavailable() {
        MethodProbeService absent =
                new MethodProbeService(AgentBridgeAccess.absent(), () -> null, () -> null, AgentEvidence.open());

        assertThat(absent.report().available()).isFalse();
        assertThat(absent.report().probes()).isEmpty();
        assertThatThrownBy(() -> absent.start(QUOTE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aHiddenPanelShowsNoProbeAndAHiddenHttpExchangesNoRequestId() {
        start(() -> null);
        long id = Long.parseLong(probes.start(QUOTE).id());
        MethodProbes.activate(0, id);
        context.set(CorrelationContext.forRequest(REQUEST));
        MethodProbes.exit(0, id, MethodProbes.enter(0, id), null);
        context.set(CorrelationContext.NONE);

        hidden.add(BootUiPanels.HTTP_EXCHANGES);
        CodePathsProbesReport withoutRequests = probes.report();
        assertThat(withoutRequests.probes().get(0).hits().get(0).requestId()).isNull();
        assertThat(withoutRequests.limitations()).contains(MethodProbeService.LIMITATION_REQUESTS);

        hidden.add(BootUiPanels.CODE_PATHS);
        CodePathsProbesReport hiddenPanel = probes.report();
        assertThat(hiddenPanel.available()).isFalse();
        assertThat(hiddenPanel.unavailableReason()).isEqualTo("The Code Paths panel is disabled.");
        assertThat(hiddenPanel.probes()).isEmpty();
        assertThatThrownBy(() -> probes.probe(String.valueOf(id))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearRecordingDropsEndedProbesAndEarlierInvocationsAndIsCounted() throws Exception {
        start(() -> null);
        long ended = Long.parseLong(probes.start(QUOTE).id());
        MethodProbes.activate(0, ended);
        MethodProbes.exit(0, ended, MethodProbes.enter(0, ended), null);
        probes.stop(String.valueOf(ended));
        MethodProbes.removed(0, ended, null);
        long live = Long.parseLong(probes.start("shop.QuoteService#total()J").id());
        MethodProbes.activate(0, live);
        MethodProbes.exit(0, live, MethodProbes.enter(0, live), null);
        assertThat(probes.report().probes()).hasSize(2);
        assertThat(evidence.status().stores())
                .anySatisfy(row ->
                        assertThat(row.counts()).containsEntry("probes", 2L).containsEntry("probeHits", 2L));
        Thread.sleep(5);

        evidence.clear();

        List<CodePathsProbeDto> after = probes.report().probes();
        assertThat(after).singleElement().satisfies(probe -> {
            assertThat(probe.id()).isEqualTo(String.valueOf(live));
            assertThat(probe.hits()).isEmpty();
        });
        assertThat(evidence.lastCleared()).contains("1 method probe");
    }

    @Test
    void aProbeEndingAtAClearIsGoneOnceItEnded() throws Exception {
        start(() -> null);
        long id = Long.parseLong(probes.start(QUOTE).id());
        MethodProbes.activate(0, id);
        probes.stop(String.valueOf(id));
        assertThat(probes.report().probes())
                .singleElement()
                .satisfies(probe -> assertThat(probe.state()).isEqualTo("ending"));
        Thread.sleep(5);

        evidence.clear();
        MethodProbes.removed(0, id, null);

        assertThat(probes.report().probes()).isEmpty();
    }

    @Test
    void aProbeStartingAtAClearThatFailsAfterItIsKept() throws Exception {
        start(() -> null);
        long id = Long.parseLong(probes.start(QUOTE).id());
        Thread.sleep(5);
        evidence.clear();
        Thread.sleep(5);

        MethodProbes.failed(0, id, "the JVM did not apply the probe");

        assertThat(probes.report().probes()).singleElement().satisfies(probe -> {
            assertThat(probe.state()).isEqualTo("failed");
            assertThat(probe.endedAt()).isNotNull();
        });
    }

    @Test
    void anAsyncMethodIsFlagged() {
        assertThat(MethodProbeService.async("()Lreactor/core/publisher/Mono;")).isTrue();
        assertThat(MethodProbeService.async("(I)Ljava/util/concurrent/CompletableFuture;"))
                .isTrue();
        assertThat(MethodProbeService.async("(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"))
                .isTrue();
        assertThat(MethodProbeService.async("(I)J")).isFalse();
        assertThat(MethodProbeService.async(null)).isFalse();
    }

    @Test
    void aNewRunStartsWithNoProbes() {
        start(() -> null);
        probes.start(QUOTE);
        claim.disarm();

        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("shop"),
                AgentSensorSettings.defaults());

        assertThat(probes.report().probes()).isEmpty();
    }

    private void start(java.util.function.Supplier<String> unavailable) {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("shop"),
                AgentSensorSettings.defaults());
        claim.attach(new AgentHandoffs(context::get, null, null));
        codePaths = new CodePathsService(AgentBridgeAccess.bind(AgentBridge.class), () -> claim, unavailable, evidence);
        probes = codePaths.probes();
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
