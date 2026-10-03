package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
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
 * D34): refines it once the application started, and disarms it when the application stops, which a live reload does
 * before the next start claims again. Disarming is idempotent, so the shutdown event and the bean's destruction may
 * both disarm.
 */
@Singleton
public class QuarkusAgentClaimLifecycle {

    private final AgentClaim claim;

    @Inject
    public QuarkusAgentClaimLifecycle(Instance<QuarkusAgentClaim> claim) {
        this(claim.isResolvable() ? claim.get().claim() : null);
    }

    QuarkusAgentClaimLifecycle(AgentClaim claim) {
        this.claim = claim;
    }

    void onStart(@Observes StartupEvent event) {
        if (claim != null && claim.armed()) {
            // The application archive's packages were claimed at static init; nothing more is known at startup.
            claim.refine(List.of());
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
