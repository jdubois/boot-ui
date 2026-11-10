package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.autoconfigure.journal.LogbackThreadLocalHolders;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/** Real scanner/resolver and Logback, without HTTP timing or a pre-warmed worker hiding the first-log case. */
public final class ThreadLocalLoggingScenario {

    private static final String REQUEST = "00000000000000cd";
    private static final String GUARD = "ch.qos.logback.core.util.ReentryGuard$ReentryGuardImpl";

    private ThreadLocalLoggingScenario() {}

    public static void main(String[] args) throws Exception {
        AgentBridgeAccess access = AgentBridgeAccess.locate();
        AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
        AgentClaim claim = AgentClaim.claim(
                access,
                "thread-local-logging",
                "regression",
                AgentClaim.TEST,
                List.of("io.github.jdubois.bootui.sample"),
                new AgentSensorSettings(List.of("thread-locals"), List.of(), List.of(), null, 65_536));
        require(claim.armed(), "agent claim armed");
        claim.attach(new AgentHandoffs(context::get, null, null));
        SideEffectsService service = new SideEffectsService(
                access,
                () -> claim,
                () -> null,
                id -> new JavaAgentService.SideEffectsCoverage(
                        SideEffectsSensorDto.RECORDING, null, List.of(), 0L, Map.of()),
                new AgentEvidence(panel -> true, null));
        service.setRequestRoutes(ids -> Map.of(REQUEST, "GET /nulled"));
        service.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!access.status().toString().contains("scannerInstalled=true")) {
                require(System.nanoTime() < deadline, "thread-local scanner installed: " + access.status());
                Thread.sleep(25);
            }

            if ("unregistered".equals(args[0])) {
                // The original resolver cannot reach this console-owned instance key.
                firstLogOnFreshWorker(context);
                SideEffectsSensorReport before = await(
                        service,
                        report -> report.rows().stream()
                                .anyMatch(row -> "GET /nulled".equals(row.attribution())
                                        && "holder not resolved (java.lang.ThreadLocal)".equals(row.target())));
                require(before.limitations().stream().noneMatch(line -> line.contains(GUARD)), "guard was unresolved");
                System.out.println("BEFORE: first console log plus tenant set(null) produced an unresolved leftover");
            } else {
                LogbackThreadLocalHolders holders = LogbackThreadLocalHolders.install();
                require(holders != null, "native Logback holder adapter installed");
                try {
                    firstLogOnFreshWorker(context);
                    SideEffectsSensorReport after = await(
                            service,
                            report -> report.rows().isEmpty()
                                    && report.limitations().stream().anyMatch(line -> line.contains(GUARD)));
                    require(after.rows().isEmpty(), "tenant nulled; framework guard excluded, not hidden as unknown");
                    System.out.println("AFTER: no nulled-request row; exact Logback guard exclusion counted");
                } finally {
                    holders.destroy();
                }
            }
        } finally {
            service.close();
            claim.disarm();
        }
    }

    private static void firstLogOnFreshWorker(AtomicReference<CorrelationContext> context) throws Exception {
        var logger = LoggerFactory.getLogger(ThreadLocalLoggingScenario.class);
        var worker = Executors.newSingleThreadExecutor();
        context.set(CorrelationContext.forRequest(REQUEST));
        try {
            worker.submit(() -> {
                        ThreadLocal<String> tenant = new ThreadLocal<>();
                        long scope = AgentThreadLocals.open();
                        require(scope != 0, "fresh pool worker's scope opened");
                        try {
                            tenant.set("tenant-secret-nulled");
                            tenant.set(null);
                            logger.warn("first console log on a fresh worker");
                        } finally {
                            AgentThreadLocals.close(scope);
                        }
                    })
                    .get(30, TimeUnit.SECONDS);
        } finally {
            context.set(CorrelationContext.NONE);
            worker.shutdownNow();
            require(worker.awaitTermination(10, TimeUnit.SECONDS), "pool worker stopped");
        }
    }

    private static SideEffectsSensorReport await(
            SideEffectsService service, Predicate<SideEffectsSensorReport> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        SideEffectsSensorReport report;
        do {
            report = service.sensor("thread-locals", null, null);
            if (expected.test(report)) {
                return report;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Expected thread-local report: " + report);
    }

    private static void require(boolean condition, String requirement) {
        if (!condition) {
            throw new IllegalStateException(requirement);
        }
    }
}
