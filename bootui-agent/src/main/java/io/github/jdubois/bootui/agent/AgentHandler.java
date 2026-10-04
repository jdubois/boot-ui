package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import io.github.jdubois.bootui.agent.bridge.ThreadPropagation;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The agent's side of the bridge: the bridge calls it on every claim transition and for status. Calls are serialized
 * here. The bridge calls the agent after its compare-and-set, outside any lock, so calls can arrive out of order: every
 * transition carries a generation from one JVM-wide sequence, and a claim or release older than the last one applied is
 * ignored, as is a refine or disarm of another generation than the current claim's. A claim asking for the
 * {@code executors}, {@code threads}, {@code inventory}, or {@code code-paths} sensor installs it once and self-tests
 * it (PLAN-v2 M5-2, M5-3, M5-4a), and a release removes it; a claim without a sensor installs nothing, unless
 * {@link AgentTestHook} enables the diagnostic probe. The {@code inventory} and {@code code-paths} sensors share one
 * transformer ({@link ApplicationMethodsSensor}): a claim asking for neither removes it, since its advice on every
 * application method would otherwise stay for a claim that never reads it. A {@code method-probe} of the current armed
 * generation queues one method probe ({@link MethodProbeSensor}, PLAN-v2 M5-8) for the class loaders of the current run:
 * the context class loader chain of the thread that claimed or refined, held weakly.
 */
final class AgentHandler implements Function<Map<String, Object>, Map<String, Object>> {

    private final Instrumentation instrumentation;
    private final AgentTestHook hook;
    private final String version;
    private final String loadMode;
    private final String jarPath;
    private final long startupMicros;
    private AgentInstaller installer;
    private ExecutorSensor executors;
    private ThreadSensor threads;
    private ApplicationMethodsSensor applicationMethods;
    private MethodProbeSensor methodProbes;
    /** The current run's class loaders: the claiming and refining threads' context class loader chains, weakly. */
    private List<WeakReference<ClassLoader>> runLoaders = Collections.emptyList();
    /** Whether the current claim asked for the inventory or code-paths sensor: refines reach them only then. */
    private boolean applicationMethodsClaimed;

    private long generation;
    private boolean armed;
    private List<String> packages = Collections.emptyList();
    private List<String> claimedSensors = Collections.emptyList();

    AgentHandler(
            Instrumentation instrumentation,
            AgentTestHook hook,
            String version,
            String loadMode,
            String jarPath,
            long startupMicros) {
        this.instrumentation = instrumentation;
        this.hook = hook;
        this.version = version;
        this.loadMode = loadMode;
        this.jarPath = jarPath;
        this.startupMicros = startupMicros;
    }

    @Override
    public synchronized Map<String, Object> apply(Map<String, Object> request) {
        String op = String.valueOf(request.get("op"));
        long requested = request.get("generation") instanceof Long ? (Long) request.get("generation") : -1L;
        switch (op) {
            case "claim":
                if (requested < generation) {
                    return answer("ignored", "an older claim generation");
                }
                generation = requested;
                armed = true;
                runLoaders = loaders(Collections.<WeakReference<ClassLoader>>emptyList());
                if (methodProbes != null) {
                    methodProbes.runLoaders(runLoaders);
                }
                packages = strings(request.get("packages"));
                claimedSensors = strings(request.get("sensors"));
                hook.onClaim(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(request)));
                List<String> probe = hook.probePackages();
                if (!probe.isEmpty()) {
                    installer().install(probe);
                }
                if (claimedSensors.contains(TaskPropagation.SENSOR)) {
                    executors().claimed(generation);
                }
                if (claimedSensors.contains(ThreadPropagation.SENSOR)) {
                    threads().claimed(generation, packages);
                }
                boolean inventory = claimedSensors.contains(CodeInventory.SENSOR);
                boolean codePaths = claimedSensors.contains(CodePaths.SENSOR);
                applicationMethodsClaimed = inventory || codePaths;
                if (applicationMethodsClaimed) {
                    applicationMethods()
                            .claimed(generation, packages, strings(request.get("beanClasses")), inventory, codePaths);
                } else if (applicationMethods != null) {
                    // Its advice would otherwise stay on every method for a claim that never reads it.
                    applicationMethods.release();
                }
                return answer("ok", null);
            case "refine":
                if (requested == generation) {
                    runLoaders = loaders(runLoaders);
                    if (methodProbes != null) {
                        methodProbes.runLoaders(runLoaders);
                    }
                    packages = strings(request.get("packages"));
                    if (threads != null) {
                        threads.refined(packages);
                    }
                    if (applicationMethods != null && applicationMethodsClaimed) {
                        applicationMethods.refined(packages, strings(request.get("beanClasses")));
                    }
                }
                return answer("ok", null);
            case "disarm":
                if (requested == generation) {
                    armed = false;
                }
                return answer("ok", null);
            case "release":
                if (requested < generation) {
                    return answer("ignored", "an older release");
                }
                generation = requested;
                armed = false;
                claimedSensors = Collections.emptyList();
                if (installer != null) {
                    installer.release();
                }
                if (executors != null) {
                    executors.release();
                }
                if (threads != null) {
                    threads.release();
                }
                applicationMethodsClaimed = false;
                if (applicationMethods != null) {
                    applicationMethods.release();
                }
                return answer("ok", null);
            case "method-probe":
                if (requested != generation || !armed) {
                    return answer(AgentBridge.FAILED, "the run that asked for the probe ended");
                }
                methodProbes()
                        .add(new MethodProbeSensor.Request(
                                ((Number) request.get("slot")).intValue(),
                                ((Number) request.get("id")).longValue(),
                                String.valueOf(request.get("className")),
                                String.valueOf(request.get("methodName")),
                                request.get("descriptor") == null ? null : String.valueOf(request.get("descriptor")),
                                runLoaders));
                return answer("ok", null);
            case "status":
                return status();
            default:
                return answer(AgentBridge.FAILED, "unknown operation " + op);
        }
    }

    private ThreadSensor threads() {
        if (threads == null) {
            threads = new ThreadSensor(instrumentation, hook.privilegedInstall(), hook.omittedHooks());
        }
        return threads;
    }

    private MethodProbeSensor methodProbes() {
        if (methodProbes == null) {
            methodProbes = new MethodProbeSensor(instrumentation, hook.privilegedInstall());
            methodProbes.runLoaders(runLoaders);
        }
        return methodProbes;
    }

    /** {@code known}, plus the calling thread's context class loader and its parents not in it yet. */
    private static List<WeakReference<ClassLoader>> loaders(List<WeakReference<ClassLoader>> known) {
        List<WeakReference<ClassLoader>> list = new ArrayList<WeakReference<ClassLoader>>();
        List<ClassLoader> seen = new ArrayList<ClassLoader>();
        for (WeakReference<ClassLoader> reference : known) {
            ClassLoader loader = reference.get();
            if (loader != null) {
                seen.add(loader);
                list.add(reference);
            }
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            boolean present = false;
            for (ClassLoader candidate : seen) {
                present |= candidate == loader;
            }
            if (!present) {
                seen.add(loader);
                list.add(new WeakReference<ClassLoader>(loader));
            }
            loader = loader.getParent();
        }
        return Collections.unmodifiableList(list);
    }

    private ApplicationMethodsSensor applicationMethods() {
        if (applicationMethods == null) {
            applicationMethods = new ApplicationMethodsSensor(instrumentation, hook.privilegedInstall());
        }
        return applicationMethods;
    }

    private ExecutorSensor executors() {
        if (executors == null) {
            executors = new ExecutorSensor(instrumentation, hook.privilegedInstall(), hook.omittedHooks());
        }
        return executors;
    }

    private AgentInstaller installer() {
        if (installer == null) {
            installer = new AgentInstaller(instrumentation, hook);
        }
        return installer;
    }

    private Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", "ok");
        map.put("version", version);
        map.put("loadMode", loadMode);
        map.put("jar", jarPath);
        map.put("startupMicros", Long.valueOf(startupMicros));
        map.put("generation", Long.valueOf(generation));
        map.put("armed", Boolean.valueOf(armed));
        map.put("packages", new ArrayList<String>(packages));
        List<Object> sensors = new ArrayList<Object>();
        if (executors != null) {
            sensors.add(active(executors.status()));
        }
        if (threads != null) {
            sensors.add(active(threads.status()));
        }
        if (applicationMethods != null) {
            sensors.add(active(applicationMethods.inventoryStatus()));
            sensors.add(active(applicationMethods.codePathsStatus()));
        }
        map.put("sensors", sensors);
        map.put("installer", installer == null ? null : installer.status());
        map.put("methodProbes", methodProbes == null ? null : methodProbes.status());
        return map;
    }

    /** Whether the armed claim enabled the sensor: a sensor an earlier claim installed may still be reported. */
    private Map<String, Object> active(Map<String, Object> sensor) {
        sensor.put("active", Boolean.valueOf(armed && claimedSensors.contains(sensor.get("id"))));
        return sensor;
    }

    private static Map<String, Object> answer(String status, String reason) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        return map;
    }

    private static List<String> strings(Object value) {
        List<String> list = new ArrayList<String>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return Collections.unmodifiableList(list);
    }
}
