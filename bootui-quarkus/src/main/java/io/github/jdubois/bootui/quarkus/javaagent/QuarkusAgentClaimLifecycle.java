package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
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
import java.util.function.Supplier;

/**
 * Follows this start's claim on the BootUI Java agent through the application's lifecycle ({@code docs/PLAN-v2.md}
 * D34): refines it once the application started, attaches the engine's {@link AgentHandoffs}, and starts Code
 * Inventory, and disarms it when the application stops, which a live reload does
 * before the next start claims again. Disarming is idempotent, so the shutdown event and the bean's destruction may
 * both disarm.
 */
@Singleton
public class QuarkusAgentClaimLifecycle {

    private final AgentClaim claim;
    private final AgentHandoffs handoffs;
    private final Supplier<CodeInventoryService> codeInventory;

    @Inject
    public QuarkusAgentClaimLifecycle(
            Instance<QuarkusAgentClaim> claim,
            Instance<AgentHandoffs> handoffs,
            Instance<CodeInventoryService> codeInventory) {
        this(
                claim.isResolvable() ? claim.get().claim() : null,
                handoffs.isResolvable() ? handoffs.get() : null,
                () -> codeInventory.isResolvable() ? codeInventory.get() : null);
    }

    QuarkusAgentClaimLifecycle(AgentClaim claim) {
        this(claim, null);
    }

    QuarkusAgentClaimLifecycle(AgentClaim claim, AgentHandoffs handoffs) {
        this(claim, handoffs, () -> null);
    }

    QuarkusAgentClaimLifecycle(AgentClaim claim, AgentHandoffs handoffs, Supplier<CodeInventoryService> codeInventory) {
        this.claim = claim;
        this.handoffs = handoffs;
        this.codeInventory = codeInventory == null ? () -> null : codeInventory;
    }

    void onStart(@Observes StartupEvent event) {
        if (claim != null && claim.armed()) {
            // The application archive's packages were claimed at static init; nothing more is known at startup.
            claim.refine(List.of());
            if (handoffs != null) {
                // The engine is ready: the agent starts propagating requests' context into JDK executors.
                claim.attach(handoffs);
            }
            // Code Inventory's drainer and scan of this start's class files (PLAN-v2 §5.15).
            CodeInventoryService inventory = codeInventory.get();
            if (inventory != null) {
                inventory.start();
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
