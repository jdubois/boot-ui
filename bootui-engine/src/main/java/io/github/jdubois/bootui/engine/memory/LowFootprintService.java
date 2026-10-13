package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.MemoryOffloadReport;
import io.github.jdubois.bootui.core.dto.SuspendedComponentDto;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>Low-footprint mode</b>: a user-controlled switch that does what <b>Free BootUI memory</b> does once, then keeps
 * BootUI from refilling what it freed. Turning it on empties every {@link MemoryOffloadable} store, pauses every
 * {@link LowFootprintSuspendable} and {@link IdleReclaimable} component, and requests a garbage collection; turning it
 * off resumes them, and recording restarts from live traffic. No data comes back.
 *
 * <p>The state is in memory only and never expires: it is off after a restart, and it stays on until the developer turns
 * it off. While it is on, {@link #active()} is the resume hold that an idle tracker consults, so console activity never
 * resumes a component the developer paused on purpose.</p>
 *
 * <p>As {@link MemoryOffloadService} does, this reads the live singletons the adapter already holds, once each, and
 * never creates a bean nor links an optional integration. A component created after the mode was turned on is not
 * paused. The Java agent is not paused either: when it is attached, the status says so.</p>
 *
 * <p>Calls are serialized, so two clicks cannot interleave. A component that fails is reported and never stops the
 * others.</p>
 */
public final class LowFootprintService {

    /** The warning shown while the BootUI Java agent is attached. */
    public static final String AGENT_WARNING = "The BootUI Java agent is attached and keeps instrumenting your"
            + " application; low-footprint mode does not pause it. For better results, run these panels without the"
            + " agent.";

    private static final Logger log = Logger.getLogger(LowFootprintService.class.getName());

    private final Supplier<? extends Collection<?>> candidates;
    private final MemoryOffloadService offload;
    private final BooleanSupplier agentAttached;
    private final LongSupplier clock;

    private volatile boolean active;
    private Long since;
    private List<Held> held = List.of();

    /**
     * @param candidates the live singletons the adapter holds, re-read on every call
     * @param offload the Free BootUI memory action run when the mode is turned on
     */
    public LowFootprintService(Supplier<? extends Collection<?>> candidates, MemoryOffloadService offload) {
        this(candidates, offload, () -> AgentBridgeAccess.locate().attached(), System::currentTimeMillis);
    }

    LowFootprintService(
            Supplier<? extends Collection<?>> candidates,
            MemoryOffloadService offload,
            BooleanSupplier agentAttached,
            LongSupplier clock) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.offload = Objects.requireNonNull(offload, "offload");
        this.agentAttached = agentAttached;
        this.clock = clock;
    }

    /** Whether the mode is on: the resume hold, one volatile read. */
    public boolean active() {
        return active;
    }

    /** The current state, from memory only: nothing is paused, resumed, or scanned. */
    public synchronized LowFootprintStatus status() {
        return snapshot(held.stream().map(Held::result).toList(), null, failures(held));
    }

    /**
     * Turns the mode on: pauses every component, empties every store, requests a garbage collection. Turning it on
     * again changes nothing and returns the current state.
     */
    public synchronized LowFootprintStatus enable() {
        if (active) {
            return status();
        }
        // The hold is raised first, so an idle tracker cannot resume a component while the others are being paused.
        active = true;
        since = clock.getAsLong();
        List<Held> suspended = new ArrayList<>();
        for (LowFootprintSuspendable component : components()) {
            suspended.add(suspend(component));
        }
        held = List.copyOf(suspended);
        MemoryOffloadReport report = offload.offload();
        return snapshot(held.stream().map(Held::result).toList(), report, failures(held));
    }

    /** Turns the mode off: resumes every component that was paused. Turning it off again changes nothing. */
    public synchronized LowFootprintStatus disable() {
        if (!active) {
            return status();
        }
        List<String> warnings = new ArrayList<>();
        for (Held component : held) {
            if (!component.result().suspended()) {
                continue;
            }
            try {
                component.component().resumeFromLowFootprint();
            } catch (RuntimeException ex) {
                String id = component.result().id();
                log.log(Level.WARNING, "BootUI could not resume its " + id + " component", ex);
                warnings.add("Could not resume " + component.result().label() + ": " + describe(ex));
            }
        }
        held = List.of();
        since = null;
        active = false;
        return snapshot(List.of(), null, warnings);
    }

    private LowFootprintStatus snapshot(
            List<SuspendedComponentDto> components, MemoryOffloadReport report, List<String> failures) {
        boolean attached = attached();
        List<String> warnings = new ArrayList<>();
        if (active && attached) {
            warnings.add(AGENT_WARNING);
        }
        warnings.addAll(failures);
        return new LowFootprintStatus(active, active ? since : null, attached, components, warnings, report);
    }

    private boolean attached() {
        try {
            return agentAttached.getAsBoolean();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static List<String> failures(List<Held> held) {
        List<String> failures = new ArrayList<>();
        for (Held component : held) {
            if (component.result().failure() != null) {
                failures.add("Could not pause " + component.result().label() + ": "
                        + component.result().failure());
            }
        }
        return failures;
    }

    private List<LowFootprintSuspendable> components() {
        Collection<?> live = candidates.get();
        if (live == null || live.isEmpty()) {
            return List.of();
        }
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<LowFootprintSuspendable> components = new ArrayList<>();
        for (Object candidate : live) {
            if (candidate == null || seen.put(candidate, Boolean.TRUE) != null) {
                continue;
            }
            if (candidate instanceof LowFootprintSuspendable suspendable) {
                for (LowFootprintSuspendable part : parts(suspendable)) {
                    if (seen.put(part, Boolean.TRUE) == null || part == suspendable) {
                        components.add(part);
                    }
                }
            } else if (candidate instanceof IdleReclaimable reclaimable) {
                components.add(new IdleComponent(reclaimable));
            }
        }
        components.sort(Comparator.comparing(LowFootprintService::idOf));
        return components;
    }

    private static List<LowFootprintSuspendable> parts(LowFootprintSuspendable suspendable) {
        try {
            List<LowFootprintSuspendable> parts = suspendable.suspendableParts();
            return parts == null || parts.isEmpty() ? List.of(suspendable) : parts;
        } catch (RuntimeException ex) {
            return List.of(suspendable);
        }
    }

    private static Held suspend(LowFootprintSuspendable component) {
        String id = idOf(component);
        String label = id;
        try {
            label = Objects.requireNonNullElse(component.suspendLabel(), id);
            component.suspendForLowFootprint();
            return new Held(component, new SuspendedComponentDto(id, label, true, null));
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not pause its " + id + " component", ex);
            return new Held(component, new SuspendedComponentDto(id, label, false, describe(ex)));
        }
    }

    private static String describe(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + message;
    }

    private static String idOf(LowFootprintSuspendable component) {
        try {
            String id = component.suspendId();
            return id == null || id.isBlank() ? component.getClass().getSimpleName() : id;
        } catch (RuntimeException ex) {
            return component.getClass().getSimpleName();
        }
    }

    private record Held(LowFootprintSuspendable component, SuspendedComponentDto result) {}

    /** An idle-reclaimable component, which the mode pauses with the idle contract, named as its offload id if any. */
    private record IdleComponent(IdleReclaimable delegate) implements LowFootprintSuspendable {

        @Override
        public String suspendId() {
            if (delegate instanceof MemoryOffloadable store) {
                return store.offloadId();
            }
            return delegate.getClass().getSimpleName();
        }

        @Override
        public String suspendLabel() {
            if (delegate instanceof MemoryOffloadable store) {
                return store.offloadLabel();
            }
            return delegate.getClass().getSimpleName();
        }

        @Override
        public void suspendForLowFootprint() {
            delegate.suspendForIdle();
        }

        @Override
        public void resumeFromLowFootprint() {
            delegate.resumeFromIdle();
        }
    }
}
