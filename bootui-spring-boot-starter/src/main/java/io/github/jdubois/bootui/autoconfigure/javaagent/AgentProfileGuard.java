package io.github.jdubois.bootui.autoconfigure.javaagent;

import io.github.jdubois.bootui.autoconfigure.BootUiActivation;
import org.springframework.core.env.Environment;

/**
 * Keeps the BootUI Java agent out of a JVM whose profile disables BootUI, such as {@code prod}, even when
 * {@code bootui.enabled=ON} forces BootUI on there: the agent instruments the application's classes and must never be
 * attached to a production JVM, as Quarkus never claims it in production mode. Only
 * {@code bootui.agent.allow-in-disabled-profiles=true} claims it anyway.
 */
public final class AgentProfileGuard {

    /** The explicit opt-in that claims the agent although BootUI was forced on despite a disabled profile. */
    public static final String ALLOW_IN_DISABLED_PROFILES = "bootui.agent.allow-in-disabled-profiles";

    private AgentProfileGuard() {}

    /**
     * Why this application does not claim the agent, or {@code null} when it may: BootUI was forced on despite the
     * disabled profile {@link BootUiActivation#forcedDespiteProfile()} without
     * {@value #ALLOW_IN_DISABLED_PROFILES}.
     */
    public static String refusal(BootUiActivation activation, Environment environment) {
        if (activation == null || activation.forcedDespiteProfile() == null) {
            return null;
        }
        if ("true"
                .equalsIgnoreCase(
                        environment.getProperty(ALLOW_IN_DISABLED_PROFILES, "false").strip())) {
            return null;
        }
        return "BootUI was forced on with bootui.enabled=ON despite the disabled profile '"
                + activation.forcedDespiteProfile()
                + "', so this application does not claim the BootUI Java agent: never attach it to a production JVM."
                + " Set " + ALLOW_IN_DISABLED_PROFILES + "=true to claim it anyway.";
    }
}
