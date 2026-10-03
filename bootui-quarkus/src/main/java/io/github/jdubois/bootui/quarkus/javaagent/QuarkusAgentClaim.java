package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.javaagent.AgentClaim;

/**
 * This application start's claim on the BootUI Java agent, published as a synthetic bean by {@link BootUiAgentRecorder}
 * at static init ({@code docs/PLAN-v2.md} D34). It holds no claim when the JVM runs without the agent or when
 * {@code bootui.agent.enabled=false}; holding the claim here keeps the claim's capture and reopen functions strongly
 * reachable for the run, as the bridge holds them weakly.
 */
public final class QuarkusAgentClaim {

    private static final QuarkusAgentClaim NONE = new QuarkusAgentClaim(null);

    private final AgentClaim claim;

    QuarkusAgentClaim(AgentClaim claim) {
        this.claim = claim;
    }

    /** No claim. */
    public static QuarkusAgentClaim none() {
        return NONE;
    }

    /** This run's claim, or {@code null}. */
    public AgentClaim claim() {
        return claim;
    }
}
