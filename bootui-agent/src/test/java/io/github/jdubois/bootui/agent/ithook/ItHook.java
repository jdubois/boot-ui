package io.github.jdubois.bootui.agent.ithook;

import io.github.jdubois.bootui.agent.AgentTestHook;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The forked-JVM tests' hook, packaged only into the test variant of the agent jar: system properties enable the
 * diagnostic probe and the mutations the tests must catch.
 */
public final class ItHook implements AgentTestHook {

    /** Mutation: the first claim's context class loader, kept as a careless transformer would. */
    static volatile Object retained;

    @Override
    public List<String> probePackages() {
        String packages = System.getProperty("bootui.agent.it.probe", "");
        return packages.isEmpty() ? Collections.emptyList() : Arrays.asList(packages.split(","));
    }

    @Override
    public boolean throwingProbe() {
        return Boolean.getBoolean("bootui.agent.it.throwing");
    }

    @Override
    public boolean privilegedInstall() {
        return !Boolean.getBoolean("bootui.agent.it.unprivileged");
    }

    @Override
    public Set<String> omittedHooks() {
        String hooks = System.getProperty("bootui.agent.it.omit", "");
        return hooks.isEmpty() ? Collections.<String>emptySet() : new HashSet<String>(Arrays.asList(hooks.split(",")));
    }

    @Override
    public void onClaim(Map<String, Object> claim) {
        if (Boolean.getBoolean("bootui.agent.it.retain-context-loader") && retained == null) {
            retained = Thread.currentThread().getContextClassLoader();
        }
    }

    /** Mutation: the first side-effect sensors' job installing this sensor fails, as a broken install would. */
    @Override
    public void installingSideEffects(Set<String> sensors) {
        String sensor = System.getProperty("bootui.agent.it.fail-side-effects-install", "");
        if (!sensor.isEmpty() && sensors.contains(sensor) && FAILED_INSTALL.compareAndSet(false, true)) {
            throw new IllegalStateException("injected install failure for " + sensor);
        }
    }

    private static final AtomicBoolean FAILED_INSTALL = new AtomicBoolean();
}
