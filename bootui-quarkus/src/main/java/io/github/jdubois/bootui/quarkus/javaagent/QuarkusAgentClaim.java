package io.github.jdubois.bootui.quarkus.javaagent;

import io.github.jdubois.bootui.engine.javaagent.AgentClaim;

/**
 * This application start's claim on the BootUI Java agent, published as a synthetic bean by {@link BootUiAgentRecorder}
 * at static init ({@code docs/PLAN-v2.md} D34). It holds no claim when the JVM runs without the agent or when
 * {@code bootui.agent.enabled=false}; holding the claim here keeps the claim's capture and reopen functions strongly
 * reachable for the run, as the bridge holds them weakly. It also carries the build-time {@code bootui.agent.enabled},
 * so the Java Agent panel reports the value the claim was decided with, never a runtime value it cannot change.
 */
public final class QuarkusAgentClaim {

    private static final QuarkusAgentClaim NONE = new QuarkusAgentClaim(null, true);

    private static final QuarkusAgentClaim DISABLED = new QuarkusAgentClaim(null, false);

    private final AgentClaim claim;
    private final boolean enabled;

    QuarkusAgentClaim(AgentClaim claim) {
        this(claim, true);
    }

    private QuarkusAgentClaim(AgentClaim claim, boolean enabled) {
        this.claim = claim;
        this.enabled = enabled;
    }

    /** No claim, with agent support enabled: the JVM runs without the agent. */
    public static QuarkusAgentClaim none() {
        return NONE;
    }

    /** No claim, because the build set {@code bootui.agent.enabled=false}. */
    public static QuarkusAgentClaim disabled() {
        return DISABLED;
    }

    /** This run's claim, or {@code null}. */
    public AgentClaim claim() {
        return claim;
    }

    /** The build-time {@code bootui.agent.enabled}. */
    public boolean enabled() {
        return enabled;
    }
}
