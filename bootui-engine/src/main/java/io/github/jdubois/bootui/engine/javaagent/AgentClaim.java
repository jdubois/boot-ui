package io.github.jdubois.bootui.engine.javaagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One application run's claim on the BootUI Java agent ({@code docs/PLAN-v2.md} §5.13, D34). The adapter claims as
 * early as it can, refines the claim with the packages it learns once its context started, and disarms it when the run
 * ends; a DevTools restart or Quarkus live reload claims again in the same slot, replacing this claim.
 *
 * <p>The bridge holds the claim's capture and reopen functions weakly, so the claim keeps them strongly reachable for as
 * long as it is reachable itself, and each claim makes fresh ones: a claim whose run is gone, with nothing holding it,
 * is abandoned and taken over. They delegate to the {@link AgentHandoffs} the adapter {@linkplain #attach attaches}
 * once its engine is ready: until then, and once the claim is disarmed, capture answers {@code null} and reopen opens
 * nothing. A DevTools restart or a live reload claims again with new functions, so a task submitted in the previous run
 * that ends after the restart closes against that run's handoffs, or none: its late end is lost.
 */
public final class AgentClaim {

    /** A claim granted to this run. */
    public static final String ARMED = "armed";

    /** A claim refused because another application holds the agent. */
    public static final String HELD = "held";

    /** A run that ended its claim. */
    public static final String DISARMED = "disarmed";

    /** A release that removed the agent's transformers. */
    public static final String RELEASED = "released";

    /** A token whose claim was replaced or ended. */
    public static final String STALE = "stale";

    /** A transition the agent failed. */
    public static final String FAILED = "failed";

    /** No compatible, started agent. */
    public static final String UNAVAILABLE = AgentBridgeAccess.UNAVAILABLE;

    /** Development mode: takes the agent over from a test run. */
    public static final String DEV = "dev";

    /** Test mode. */
    public static final String TEST = "test";

    private final AgentBridgeAccess access;
    private final String application;
    private final String owner;
    private final String mode;
    private final List<String> packages;
    private final AgentSensorSettings sensors;
    private final AtomicBoolean ended = new AtomicBoolean();

    // Strongly reachable for as long as this claim is: the bridge only holds them weakly. Each claim builds its own
    // capturing lambdas, never a cached non-capturing lambda or a method reference, so they die with the claim.
    private final Supplier<Object> capture;
    private final Function<Object, AutoCloseable> reopen;

    private volatile Map<String, Object> result;
    private volatile Long token;
    private volatile Long generation;
    private volatile AgentHandoffs handoffs;
    private AgentRecordDrainer drainer;

    private AgentClaim(
            AgentBridgeAccess access,
            String application,
            String owner,
            String mode,
            List<String> packages,
            AgentSensorSettings sensors) {
        this.access = access;
        this.application = application;
        this.owner = owner;
        this.mode = mode;
        this.packages = List.copyOf(packages);
        this.sensors = sensors == null ? AgentSensorSettings.defaults() : sensors;
        this.capture = () -> captureContext();
        this.reopen = snapshot -> reopenContext(snapshot);
    }

    /**
     * Claims the agent for this run. Never throws: the answer's status says whether the claim is armed, held by another
     * application, failed, or unavailable.
     *
     * @param mode {@value #DEV} or {@value #TEST}
     * @param packages the application's package prefixes
     */
    public static AgentClaim claim(
            AgentBridgeAccess access, String application, String owner, String mode, List<String> packages) {
        return claim(access, application, owner, mode, packages, AgentSensorSettings.defaults());
    }

    /**
     * Claims the agent for this run, asking for the sensors {@code sensors} names. Never throws.
     *
     * @param mode {@value #DEV} or {@value #TEST}
     * @param packages the application's package prefixes
     * @param sensors the sensors and their options, or {@code null} for the defaults
     */
    public static AgentClaim claim(
            AgentBridgeAccess access,
            String application,
            String owner,
            String mode,
            List<String> packages,
            AgentSensorSettings sensors) {
        return claim(access, application, owner, mode, packages, sensors, List.of());
    }

    /**
     * Claims the agent for this run, asking for the sensors {@code sensors} names, with the application's bean classes
     * already known, as Quarkus knows them at build time, for the {@code code-paths} sensor. Never throws.
     *
     * @param mode {@value #DEV} or {@value #TEST}
     * @param packages the application's package prefixes
     * @param sensors the sensors and their options, or {@code null} for the defaults
     * @param beanClasses the application's bean classes by binary name, in its packages
     */
    public static AgentClaim claim(
            AgentBridgeAccess access,
            String application,
            String owner,
            String mode,
            List<String> packages,
            AgentSensorSettings sensors,
            List<String> beanClasses) {
        AgentClaim claim = new AgentClaim(
                access == null ? AgentBridgeAccess.absent() : access,
                application,
                owner,
                TEST.equals(mode) ? TEST : DEV,
                packages == null ? List.of() : clean(packages),
                sensors);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", claim.application);
        request.put("owner", claim.owner);
        request.put("mode", claim.mode);
        request.put("packages", new ArrayList<>(claim.packages));
        request.put("sensors", new ArrayList<>(claim.sensors.sensors()));
        Map<String, Object> executors = new LinkedHashMap<>();
        executors.put("skipTasks", new ArrayList<>(claim.sensors.skipTasks()));
        executors.put("skipThreads", new ArrayList<>(claim.sensors.skipThreads()));
        request.put("executors", executors);
        request.put("ringCapacity", claim.sensors.ringCapacity());
        request.put("beanClasses", beanClasses == null ? List.of() : clean(beanClasses));
        Map<String, Object> answer = claim.access.claim(request, claim.capture, claim.reopen);
        claim.result = answer;
        claim.generation = AgentBridgeAccess.number(answer, "generation");
        if (ARMED.equals(answer.get("status")) && answer.get("token") instanceof Long granted) {
            claim.token = granted;
        } else {
            claim.ended.set(true);
            if ("failed".equals(answer.get("status"))) {
                // The bridge recorded the claim before the agent failed it, and returned no token to disarm it with:
                // release the slot so recording stops and other applications are not held.
                claim.access.release(claim.application, claim.mode);
            }
        }
        return claim;
    }

    /**
     * Removes the agent's transformers because BootUI is disabled for this application: a run that never claimed, so
     * without a token. Leaves an armed claim of another application alone.
     */
    public static Map<String, Object> release(AgentBridgeAccess access, String application, String mode) {
        AgentBridgeAccess bridge = access == null ? AgentBridgeAccess.absent() : access;
        return bridge.release(application, TEST.equals(mode) ? TEST : DEV);
    }

    /**
     * Adds packages to this run's claim, such as the auto-configuration packages known once the context started. Does
     * nothing once the claim ended or was never armed.
     */
    public Map<String, Object> refine(List<String> packages) {
        return refine(packages, List.of());
    }

    /**
     * Adds packages and bean classes to this run's claim, as known once the context started: the {@code code-paths}
     * sensor instruments the bean classes' methods, and retransforms those already loaded. Does nothing once the claim
     * ended or was never armed.
     */
    public Map<String, Object> refine(List<String> packages, List<String> beanClasses) {
        Long granted = token;
        if (granted == null || ended.get()) {
            return answer(STALE, "this claim is not armed");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("packages", packages == null ? List.of() : clean(packages));
        request.put("beanClasses", beanClasses == null ? List.of() : clean(beanClasses));
        Map<String, Object> answer = access.refine(granted, request);
        if (ARMED.equals(answer.get("status"))) {
            result = answer;
        }
        return answer;
    }

    /**
     * Hands the agent this run's engine side of executor propagation, once the engine is ready: from now on, capture
     * and reopen delegate to {@code handoffs}. Does nothing once the claim ended.
     */
    public void attach(AgentHandoffs handoffs) {
        if (!ended.get()) {
            this.handoffs = handoffs;
        }
    }

    /** Takes the engine side back: capture answers {@code null} and reopen opens nothing. Idempotent. */
    public void detach() {
        this.handoffs = null;
    }

    /** The handoffs attached, or {@code null}. */
    public AgentHandoffs handoffs() {
        return handoffs;
    }

    /** The sensors this claim asked for. */
    public AgentSensorSettings sensors() {
        return sensors;
    }

    /**
     * This claim's one drainer of the agent ({@code docs/PLAN-v2.md} M5-4a), created on first use, which each sensor's
     * service routes its records to; {@code null} once the claim ended or when it was never armed. Disarming closes it.
     */
    public synchronized AgentRecordDrainer drainer() {
        if (token == null || ended.get()) {
            return null;
        }
        if (drainer == null) {
            drainer = new AgentRecordDrainer(this, access);
        }
        return drainer;
    }

    /** Ends this run's claim: recording stops and the agent keeps its transformers (D34). Idempotent. */
    public Map<String, Object> disarm() {
        detach();
        Long granted = token;
        if (granted == null || !ended.compareAndSet(false, true)) {
            return answer(STALE, "this claim already ended or was never armed");
        }
        AgentRecordDrainer running;
        synchronized (this) {
            // Ended now: drainer() creates no other.
            running = drainer;
        }
        Map<String, Object> answer = access.disarm(granted);
        if (DISARMED.equals(answer.get("status"))) {
            result = answer;
        }
        if (running != null) {
            // After the bridge stopped recording: a last drain, then the thread ends and the routes are forgotten.
            running.close();
        }
        return answer;
    }

    /**
     * Drains the agent's transport ring into {@code sink} with this claim's token, while it is armed
     * ({@code docs/PLAN-v2.md} M5-3): the sink receives one reused {@code long[]} per record and copies what it keeps.
     * A stale or ended claim, or a second concurrent drainer, drains nothing. Returns how many records were drained.
     */
    public int drain(Consumer<long[]> sink) {
        Long granted = token;
        if (granted == null || ended.get() || sink == null) {
            return 0;
        }
        return access.drain(granted, sink);
    }

    /**
     * Hands the code-paths sensor's queued fragments to {@code sink} with this claim's token, while it is armed
     * ({@code docs/PLAN-v2.md} M5-4a): the sink owns each {@code long[]} blob. Returns how many were drained.
     */
    public int drainCodePaths(Consumer<long[]> sink) {
        Long granted = token;
        if (granted == null || ended.get() || sink == null) {
            return 0;
        }
        return access.drainCodePaths(granted, sink);
    }

    /**
     * Excludes method {@code id} from the code-paths sensor for the rest of this run, as the engine's adaptive exclusion
     * decides. Returns whether it is excluded.
     */
    public boolean excludeCodePathsMethod(int id) {
        Long granted = token;
        if (granted == null || ended.get()) {
            return false;
        }
        return access.excludeCodePathsMethod(granted, id);
    }

    /** The bridge this claim was made through. */
    public AgentBridgeAccess access() {
        return access;
    }

    /** The status of the claim's first answer: {@value #ARMED}, {@value #HELD}, {@value #FAILED}, or {@value #UNAVAILABLE}. */
    public String claimStatus() {
        Map<String, Object> answer = result;
        return answer == null ? UNAVAILABLE : String.valueOf(answer.get("status"));
    }

    /** The bridge's last answer to this claim: its claim, refine, or disarm. */
    public Map<String, Object> result() {
        Map<String, Object> answer = result;
        return answer == null ? Map.of() : answer;
    }

    /** Whether this run holds an armed claim that it has not disarmed. */
    public boolean armed() {
        return token != null && !ended.get();
    }

    /** Whether this run's claim ended: disarmed, or never armed. */
    public boolean ended() {
        return ended.get();
    }

    /** The claim's generation, or {@code null} when it was never granted. */
    public Long generation() {
        return generation;
    }

    public String application() {
        return application;
    }

    /**
     * The packages the agent instruments for this claim, as the bridge last answered its claim or refine: the claimed
     * packages and those refined since. Falls back to {@link #packages()}.
     */
    public List<String> claimedPackages() {
        Map<String, Object> bridgeClaim = AgentBridgeAccess.map(result(), "claim");
        List<String> names = new ArrayList<>();
        for (Object item : AgentBridgeAccess.items(bridgeClaim, "packages")) {
            if (item != null) {
                names.add(String.valueOf(item));
            }
        }
        return names.isEmpty() ? packages : List.copyOf(names);
    }

    /** When the bridge armed this claim, in epoch milliseconds, or {@code null}. */
    public Long armedAt() {
        return AgentBridgeAccess.number(AgentBridgeAccess.map(result(), "claim"), "armedAt");
    }

    /** The claim's slot, {@code mode:application}, which the agent keeps across a run's restarts. */
    public String slot() {
        return mode + ":" + application;
    }

    public String owner() {
        return owner;
    }

    public String mode() {
        return mode;
    }

    public List<String> packages() {
        return packages;
    }

    /** The capture function the bridge holds weakly: one per claim. */
    Supplier<Object> capture() {
        return capture;
    }

    /** The reopen function the bridge holds weakly: one per claim. */
    Function<Object, AutoCloseable> reopen() {
        return reopen;
    }

    /** The submitting thread's snapshot from the attached handoffs, or {@code null} without them. */
    private Object captureContext() {
        AgentHandoffs attached = handoffs;
        return attached == null ? null : attached.capture();
    }

    /** The handoff the attached handoffs open, or {@code null} without them. */
    private AutoCloseable reopenContext(Object snapshot) {
        AgentHandoffs attached = handoffs;
        return attached == null ? null : attached.reopen(snapshot);
    }

    private static List<String> clean(List<String> packages) {
        List<String> names = new ArrayList<>();
        for (String name : packages) {
            if (name != null) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty() && !names.contains(trimmed)) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }

    private static Map<String, Object> answer(String status, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("reason", reason);
        return map;
    }
}
