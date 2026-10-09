package io.github.jdubois.bootui.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For BootUI's own tests only: a service the published jar never contains. The test variant of the agent jar adds an
 * implementation under {@code inst/}, through which the forked-JVM tests enable the diagnostic probe and inject the
 * mutations the leak test must catch. JDK types only, so test code compiled against the unrelocated classes can
 * implement it.
 */
public interface AgentTestHook {

    /** Packages the diagnostic probe advises: an empty list, the default, installs no transformer at all. */
    default List<String> probePackages() {
        return Collections.emptyList();
    }

    /** Whether the probe's advice throws after counting, to prove advice failures never reach the application. */
    default boolean throwingProbe() {
        return false;
    }

    /** Whether agent threads are created and the transformer installed inside a privileged block (JDK 17 to 23). */
    default boolean privilegedInstall() {
        return true;
    }

    /**
     * Sensor hooks to leave out of the transformer, by the hook ids the sensors report, so the mutation tests can prove
     * each hook's own self-test fails while its siblings still pass.
     */
    default Set<String> omittedHooks() {
        return Collections.emptySet();
    }

    /** Called with each claim's description, on the claiming thread. */
    default void onClaim(Map<String, Object> claim) {}

    /**
     * Called on the side-effect sensors' worker, inside its job, just before it installs the transformer of {@code
     * sensors}, the side-effect sensors not yet installed and passing, so a mutation test can fail one job and prove
     * that only the groups it touched are disabled.
     */
    default void installingSideEffects(Set<String> sensors) {}

    /** The default hook: nothing enabled, nothing injected. */
    AgentTestHook NONE = new AgentTestHook() {};
}
