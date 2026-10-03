package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Follows this start's claim on the BootUI Java agent through the application's lifecycle ({@code docs/PLAN-v2.md}
 * D34): refines it once the application started and attaches the engine's {@link AgentHandoffs}, and disarms it when the application stops, which a live reload does
 * before the next start claims again. Disarming is idempotent, so the shutdown event and the bean's destruction may
 * both disarm.
 */
@Singleton
public class QuarkusAgentClaimLifecycle {

    private final AgentClaim claim;
    private final AgentHandoffs handoffs;

    @Inject
    public QuarkusAgentClaimLifecycle(Instance<QuarkusAgentClaim> claim, Instance<AgentHandoffs> handoffs) {
        this(claim.isResolvable() ? claim.get().claim() : null, handoffs.isResolvable() ? handoffs.get() : null);
    }

    QuarkusAgentClaimLifecycle(AgentClaim claim) {
        this(claim, null);
    }

    QuarkusAgentClaimLifecycle(AgentClaim claim, AgentHandoffs handoffs) {
        this.claim = claim;
        this.handoffs = handoffs;
    }

    void onStart(@Observes StartupEvent event) {
        if (claim != null && claim.armed()) {
            // The application archive's packages were claimed at static init; nothing more is known at startup.
            claim.refine(List.of());
            if (handoffs != null) {
                // The engine is ready: the agent starts propagating requests' context into JDK executors.
                claim.attach(handoffs);
            }
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        disarm();
    }

    @PreDestroy
    void disarm() {
        if (claim != null) {
            claim.disarm();
        }
    }
}
