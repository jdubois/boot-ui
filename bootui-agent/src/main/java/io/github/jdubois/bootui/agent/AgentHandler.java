package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CaughtExceptions;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
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
 * it (PLAN-v2 M5-2, M5-3, M5-4a), as does a claim asking for a side-effect sensor such as {@code processes} (M5-5a, one
 * transformer for every side-effect hook, {@link SideEffectsSensor}), and a release removes it; a claim without a sensor installs nothing, unless
 * {@link AgentTestHook} enables the diagnostic probe. The {@code inventory} and {@code code-paths} sensors share one
 * transformer ({@link ApplicationMethodsSensor}) with the {@code caught-exceptions} sensor (M5-6a) and the {@code
 * blocking} sensor's call-site visit (M5-5c): a claim asking for none of them removes it, since its advice on every
 * application method would otherwise stay for a claim that never reads it. A {@code method-probe} of the current armed
 * generation queues one method probe ({@link MethodProbeSensor}, PLAN-v2 M5-8) for the class loaders of the current run:
 * the context class loader chain of the thread that claimed or refined, held weakly. A {@code sensors} switch of the
 * current generation (PLAN-v2 M5-14) installs or removes the {@code threads} and side-effect sensors at run time, in the
 * order of the claim's {@code sensorsRevision}.
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

    private SideEffectsSensor sideEffects;
    private ResourcesSensor resources;
    /** Whether the current claim asked for the inventory or code-paths sensor: refines reach them only then. */
    private boolean applicationMethodsClaimed;

    private long generation;
    private boolean armed;
    private List<String> packages = Collections.emptyList();
    private List<String> claimedSensors = Collections.emptyList();
    /** The current claim's last runtime switch applied: a switch arriving after a later one is ignored. */
    private long sensorsRevision;

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
                // Before any transformer is installed: the transformers' listener marks the agent's work through these
                // bridge classes, which must never first load inside a transformation (a ClassCircularityError there
                // would stick to their constant-pool entries for the JVM's life).
                SideEffects.agentWork(true);
                SideEffects.agentWork(false);
                packages = strings(request.get("packages"));
                claimedSensors = strings(request.get("sensors"));
                sensorsRevision = number(request.get("sensorsRevision"));
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
                } else if (threads != null && switchedOff(request, ThreadPropagation.SENSOR)) {
                    // Switched off at run time (PLAN-v2 M5-14): java.lang.Thread is restored, even when this claim
                    // reached the agent before the switch did.
                    threads.release();
                }
                boolean inventory = claimedSensors.contains(CodeInventory.SENSOR);
                boolean codePaths = claimedSensors.contains(CodePaths.SENSOR);
                boolean caught = claimedSensors.contains(CaughtExceptions.SENSOR);
                // The blocking sensor's Thread.sleep and Object.wait call sites are rewritten in application classes.
                boolean blocking = claimedSensors.contains(SideEffects.BLOCKING);
                applicationMethodsClaimed = inventory || codePaths || caught || blocking;
                if (applicationMethodsClaimed) {
                    applicationMethods()
                            .claimed(
                                    generation,
                                    packages,
                                    strings(request.get("beanClasses")),
                                    inventory,
                                    codePaths,
                                    caught,
                                    blocking);
                } else if (applicationMethods != null) {
                    // Its advice would otherwise stay on every method for a claim that never reads it.
                    applicationMethods.release();
                }
                // Computed here: SideEffectsSensor links Byte Buddy, which a claim without a sensor never loads.
                int sideEffectsMask = sideEffectsMask(claimedSensors);
                if (sideEffectsMask != 0) {
                    sideEffects().claimed(sideEffectsMask);
                } else if (sideEffects != null) {
                    // Its hooks would otherwise stay on JDK classes for a claim that never reads them.
                    sideEffects.release();
                }
                // The resources sensor's close hooks are a transformer of their own (M5-5g).
                if (claimedSensors.contains(SideEffects.RESOURCES)) {
                    resources().claimed();
                } else if (resources != null) {
                    resources.release();
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
            case "sensors":
                return switched(request, requested);
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
                if (sideEffects != null) {
                    sideEffects.release();
                }
                if (resources != null) {
                    resources.release();
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
                                Boolean.TRUE.equals(request.get("shapes")),
                                runLoaders));
                return answer("ok", null);
            case "status":
                return status();
            default:
                return answer(AgentBridge.FAILED, "unknown operation " + op);
        }
    }

    /**
     * A runtime switch of the current claim's sensors (PLAN-v2 M5-14): installs or removes the {@code threads} sensor,
     * reinstalls the side-effect sensors' transformer for their new mask, or removes it, and installs or removes the
     * {@code resources} sensor's close hooks. Applied to the current claim's
     * generation even once it is disarmed, so a sensor switched off just before the run ended is still removed; ignored
     * for another generation or after a later switch, since calls can arrive out of order.
     */
    private Map<String, Object> switched(Map<String, Object> request, long requested) {
        long revision = number(request.get("sensorsRevision"));
        if (requested != generation) {
            if (requested < generation
                    && threads != null
                    && !claimedSensors.contains(ThreadPropagation.SENSOR)
                    && !strings(request.get("sensors")).contains(ThreadPropagation.SENSOR)) {
                // A threads switch-off that a newer claim overtook: neither wants java.lang.Thread instrumented.
                threads.release();
            }
            return answer("ignored", "another claim's switch");
        }
        if (revision <= sensorsRevision) {
            return answer("ignored", "an older switch");
        }
        sensorsRevision = revision;
        List<String> previous = claimedSensors;
        List<String> next = strings(request.get("sensors"));
        claimedSensors = next;
        boolean hadThreads = previous.contains(ThreadPropagation.SENSOR);
        boolean wantsThreads = next.contains(ThreadPropagation.SENSOR);
        if (wantsThreads && !hadThreads) {
            threads().claimed(generation, packages);
        } else if (!wantsThreads && hadThreads && threads != null) {
            threads.release();
        }
        int before = sideEffectsMask(previous);
        int after = sideEffectsMask(next);
        if (after != before) {
            if (after != 0) {
                sideEffects().claimed(after);
            } else if (sideEffects != null) {
                sideEffects.release();
            }
        }
        boolean hadResources = previous.contains(SideEffects.RESOURCES);
        boolean wantsResources = next.contains(SideEffects.RESOURCES);
        if (wantsResources && !hadResources) {
            resources().claimed();
        } else if (!wantsResources && hadResources && resources != null) {
            resources.release();
        }
        return answer("ok", null);
    }

    /** Whether the claim {@code request} describes switched {@code sensor} off at run time. */
    private static boolean switchedOff(Map<String, Object> request, String sensor) {
        Object overrides = request.get("sensorOverrides");
        return overrides instanceof Map && Boolean.FALSE.equals(((Map<?, ?>) overrides).get(sensor));
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
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

    /**
     * The mask bits of the side-effect sensors among {@code sensors} whose hooks the side-effect transformers carry:
     * every one but {@code resources}, whose close hooks are its own transformer's ({@link ResourcesSensor}).
     */
    static int sideEffectsMask(List<String> sensors) {
        int bits = 0;
        for (String sensor : sensors) {
            bits |= SideEffects.bit(sensor);
        }
        return bits & ~SideEffects.MASK_RESOURCES;
    }

    private ResourcesSensor resources() {
        if (resources == null) {
            resources = new ResourcesSensor(instrumentation, hook.privilegedInstall(), hook.omittedHooks());
        }
        return resources;
    }

    private SideEffectsSensor sideEffects() {
        if (sideEffects == null) {
            sideEffects = new SideEffectsSensor(instrumentation, hook.privilegedInstall(), hook.omittedHooks());
        }
        return sideEffects;
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
            if (applicationMethods.caughtEver()) {
                // Opt-in: reported once a claim asked for it, as the other opt-in sensors are.
                sensors.add(active(applicationMethods.caughtStatus()));
            }
        }
        if (sideEffects != null) {
            for (Map<String, Object> row : sideEffects.status()) {
                if (SideEffects.BLOCKING.equals(row.get("id")) && applicationMethods != null) {
                    // Its call-site hooks are the application-methods transformer's visit.
                    withCallSites(row, applicationMethods);
                }
                sensors.add(active(row));
            }
        }
        Map<String, Object> resourcesRow = resources == null ? null : resources.status();
        if (resourcesRow != null) {
            sensors.add(active(resourcesRow));
        }
        map.put("sensors", sensors);
        map.put("installer", installer == null ? null : installer.status());
        map.put("methodProbes", methodProbes == null ? null : methodProbes.status());
        return map;
    }

    /** Adds the blocking sensor's call-site hooks and their visit's status to its row. */
    @SuppressWarnings("unchecked")
    static void withCallSites(Map<String, Object> row, ApplicationMethodsSensor applicationMethods) {
        Object hooks = row.get("hooks");
        List<Object> merged =
                hooks instanceof List ? new ArrayList<Object>((List<Object>) hooks) : new ArrayList<Object>();
        merged.addAll(applicationMethods.blockingHooks());
        row.put("hooks", merged);
        row.putAll(applicationMethods.blockingCallSites());
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
