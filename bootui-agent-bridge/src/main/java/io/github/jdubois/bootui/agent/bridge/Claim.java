package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One BootUI application's claim on the agent. Immutable: every transition replaces the whole record. The engine holds
 * the only strong references to {@code capture} and {@code reopen}, fresh objects for each claim, so a claim whose engine
 * is gone without disarming is detected as abandoned once they are collected.
 */
final class Claim {

    final long generation;
    final long token;
    final String slot;
    final String owner;
    final String application;
    final String mode;
    final List<String> packages;
    /** The sensors this application asked for, such as {@code executors}. */
    final List<String> sensors;
    /** Task class-name prefixes of wrappers that already propagate BootUI's context: never keyed. */
    final String[] skipTasks;
    /** Worker thread-name prefixes of executors that propagate BootUI's context themselves: never applied. */
    final String[] skipThreads;
    /** The transport ring's capacity this application asked for; the ring keeps the first claim's ({@link AgentRing}). */
    final int ringCapacity;
    /**
     * The application's bean classes by binary name, whose methods the {@code code-paths} sensor instruments (PLAN-v2
     * M5-4a): those of the claim and of every refine since.
     */
    final List<String> beanClasses;
    /** The code-paths sensor's tree pool and blob queue bounds, 0 for the defaults ({@link CodePaths}). */
    final int codePathsPool;

    final long codePathsQueueBytes;

    final long armedAt;
    final boolean armed;
    final WeakReference<Supplier<Object>> capture;
    final WeakReference<Function<Object, AutoCloseable>> reopen;

    Claim(
            long generation,
            long token,
            String owner,
            String application,
            String mode,
            List<String> packages,
            List<String> sensors,
            String[] skipTasks,
            String[] skipThreads,
            int ringCapacity,
            List<String> beanClasses,
            int codePathsPool,
            long codePathsQueueBytes,
            long armedAt,
            boolean armed,
            WeakReference<Supplier<Object>> capture,
            WeakReference<Function<Object, AutoCloseable>> reopen) {
        this.generation = generation;
        this.token = token;
        this.slot = slot(mode, application);
        this.owner = owner;
        this.application = application;
        this.mode = mode;
        this.packages = packages;
        this.sensors = sensors;
        this.skipTasks = skipTasks;
        this.skipThreads = skipThreads;
        this.ringCapacity = ringCapacity;
        this.beanClasses = beanClasses;
        this.codePathsPool = codePathsPool;
        this.codePathsQueueBytes = codePathsQueueBytes;
        this.armedAt = armedAt;
        this.armed = armed;
        this.capture = capture;
        this.reopen = reopen;
    }

    static String slot(String mode, String application) {
        return mode + ":" + application;
    }

    /** The engine that claimed is gone without disarming: its capture was collected. */
    boolean abandoned() {
        return armed && capture.get() == null;
    }

    /** The most bean classes a claim keeps, its refines included. */
    static final int MAX_BEAN_CLASSES = 50_000;

    Claim withPackages(List<String> more) {
        return refined(more, Collections.<String>emptyList());
    }

    /** This claim with {@code morePackages} and {@code moreBeanClasses} added, once each. */
    Claim refined(List<String> morePackages, List<String> moreBeanClasses) {
        List<String> merged = new ArrayList<String>(packages);
        for (int i = 0; i < morePackages.size(); i++) {
            String name = morePackages.get(i);
            if (!merged.contains(name)) {
                merged.add(name);
            }
        }
        List<String> beans = beanClasses;
        if (!moreBeanClasses.isEmpty()) {
            LinkedHashSet<String> union = new LinkedHashSet<String>(beanClasses);
            for (int i = 0; i < moreBeanClasses.size() && union.size() < MAX_BEAN_CLASSES; i++) {
                union.add(moreBeanClasses.get(i));
            }
            beans = Collections.unmodifiableList(new ArrayList<String>(union));
        }
        return new Claim(
                generation,
                token,
                owner,
                application,
                mode,
                Collections.unmodifiableList(merged),
                sensors,
                skipTasks,
                skipThreads,
                ringCapacity,
                beans,
                codePathsPool,
                codePathsQueueBytes,
                armedAt,
                armed,
                capture,
                reopen);
    }

    Claim disarmed() {
        return new Claim(
                generation,
                token,
                owner,
                application,
                mode,
                packages,
                sensors,
                skipTasks,
                skipThreads,
                ringCapacity,
                beanClasses,
                codePathsPool,
                codePathsQueueBytes,
                armedAt,
                false,
                capture,
                reopen);
    }

    boolean hasSensor(String sensor) {
        return sensors.contains(sensor);
    }

    /** Whether {@code name} starts with one of {@code prefixes}. */
    static boolean startsWithAny(String name, String[] prefixes) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < prefixes.length; i++) {
            if (name.startsWith(prefixes[i])) {
                return true;
            }
        }
        return false;
    }

    private static List<String> list(String[] values) {
        List<String> list = new ArrayList<String>();
        for (int i = 0; i < values.length; i++) {
            list.add(values[i]);
        }
        return list;
    }

    /** JDK types only, for the agent and for status. */
    Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("generation", Long.valueOf(generation));
        map.put("owner", owner);
        map.put("application", application);
        map.put("mode", mode);
        map.put("packages", new ArrayList<String>(packages));
        map.put("sensors", new ArrayList<String>(sensors));
        Map<String, Object> executors = new LinkedHashMap<String, Object>();
        executors.put("skipTasks", list(skipTasks));
        executors.put("skipThreads", list(skipThreads));
        map.put("executors", executors);
        map.put("ringCapacity", Integer.valueOf(ringCapacity));
        // A count only: the names can run to thousands, and status is read often. Transitions add the names.
        map.put("beanClassCount", Integer.valueOf(beanClasses.size()));
        Map<String, Object> codePaths = new LinkedHashMap<String, Object>();
        codePaths.put("poolSize", Integer.valueOf(codePathsPool));
        codePaths.put("queueBytes", Long.valueOf(codePathsQueueBytes));
        map.put("codePaths", codePaths);
        map.put("armedAt", Long.valueOf(armedAt));
        map.put("armed", Boolean.valueOf(armed));
        map.put("abandoned", Boolean.valueOf(abandoned()));
        return map;
    }
}
