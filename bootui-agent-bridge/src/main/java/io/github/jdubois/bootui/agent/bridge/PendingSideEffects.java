package io.github.jdubois.bootui.agent.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** The primitive owners of scopes that can buffer side effects, never their frames or application objects. */
final class PendingSideEffects {

    static final int CAPACITY = 1024;
    private static final AtomicReference<State> STATE = new AtomicReference<State>();
    private static final AtomicLong IDS = new AtomicLong();

    private PendingSideEffects() {}

    static void claimed(long generation) {
        while (true) {
            State current = STATE.get();
            if (current != null && current.generation >= generation) {
                return;
            }
            if (STATE.compareAndSet(current, new State(generation))) {
                return;
            }
        }
    }

    static long open(long generation, long request, long execution, int kind) {
        if (request == 0L && execution == 0L) {
            return 0L;
        }
        State state = STATE.get();
        if (state == null || state.generation != generation) {
            return 0L;
        }
        return state.open(request, execution, kind);
    }

    static void closed(long generation, long id) {
        State state = STATE.get();
        if (id != 0L && state != null && state.generation == generation) {
            state.begin();
            try {
                if (state.owners.remove(Long.valueOf(id)) != null) {
                    state.size.decrementAndGet();
                } else {
                    state.unknown.compareAndSet(null, "an owned scope lost its pending marker");
                }
            } finally {
                state.end();
            }
        }
    }

    static void unknown(long generation, String reason) {
        State state = STATE.get();
        if (state != null && state.generation == generation) {
            state.begin();
            try {
                state.unknown.compareAndSet(null, reason);
            } finally {
                state.end();
            }
        }
    }

    static void frameFailed(CodePaths.Frame frame) {
        if (frame.slotGeneration != null) {
            if (frame.slotRequest == null || frame.slotExecution == null) {
                int slots = Math.min(frame.slots, frame.slotGeneration.length);
                for (int i = 0; i < slots; i++) {
                    unknown(frame.slotGeneration[i], "owner-slot metadata was incomplete during error cleanup");
                }
                if (frame.sideEffects != null && frame.sideEffects.size > 0) {
                    unknown(frame.sideEffects.generation, "buffer ownership was incomplete during error cleanup");
                }
                return;
            }
            int slots = Math.min(frame.slots, frame.slotGeneration.length);
            for (int i = 0; i < slots; i++) {
                if (frame.slotRequest[i] != 0L || frame.slotExecution[i] != 0L) {
                    unknown(frame.slotGeneration[i], "an owned scope encountered error cleanup");
                }
            }
        }
    }

    static Map<String, Object> snapshot(long token) {
        Claim claim = AgentBridge.current();
        State state = STATE.get();
        if (claim == null
                || !claim.armed
                || claim.token != token
                || state == null
                || state.generation != claim.generation) {
            return unavailable("pending ownership does not belong to this armed claim");
        }
        Map<String, Object> snapshot = state.snapshot();
        Claim after = AgentBridge.current();
        if (STATE.get() != state
                || after == null
                || !after.armed
                || after.token != token
                || after.generation != state.generation) {
            snapshot.put("qualified", Boolean.FALSE);
            snapshot.put("unknownReason", "the claim changed while pending ownership was read");
        }
        return snapshot;
    }

    private static Map<String, Object> unavailable(String reason) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("qualified", Boolean.FALSE);
        result.put("unknownReason", reason);
        return result;
    }

    static void warm() {
        State state = new State(-1L);
        long id = state.open(1L, 0L, 0);
        state.snapshot();
        state.owners.remove(Long.valueOf(id));
        unavailable("warm");
    }

    static void reset() {
        STATE.set(null);
        IDS.set(0L);
    }

    private static final class Owner {
        final long request;
        final long execution;
        final int kind;
        final long startedMillis;

        Owner(long request, long execution, int kind) {
            this.request = request;
            this.execution = execution;
            this.kind = kind;
            this.startedMillis = System.currentTimeMillis();
        }
    }

    private static final class State {
        final long generation;
        final AtomicLong revision = new AtomicLong();
        final AtomicInteger writers = new AtomicInteger();
        final AtomicInteger size = new AtomicInteger();
        final AtomicReference<String> unknown = new AtomicReference<String>();
        final ConcurrentHashMap<Long, Owner> owners = new ConcurrentHashMap<Long, Owner>();

        State(long generation) {
            this.generation = generation;
        }

        void begin() {
            writers.incrementAndGet();
            revision.incrementAndGet();
        }

        void end() {
            revision.incrementAndGet();
            writers.decrementAndGet();
        }

        long open(long request, long execution, int kind) {
            begin();
            boolean reserved = false;
            boolean stored = false;
            try {
                reserved = true;
                if (size.incrementAndGet() > CAPACITY) {
                    unknown.compareAndSet(null, "pending ownership exceeded its marker bound");
                    return 0L;
                }
                long id = IDS.incrementAndGet();
                if (id <= 0L) {
                    unknown.compareAndSet(null, "pending marker identities were exhausted");
                    return 0L;
                }
                owners.put(Long.valueOf(id), new Owner(request, execution, kind));
                stored = true;
                return id;
            } catch (Throwable ex) {
                unknown.compareAndSet(null, "pending ownership could not be registered");
                AgentBridge.error(ex);
                return 0L;
            } finally {
                if (reserved && !stored) {
                    size.decrementAndGet();
                }
                end();
            }
        }

        Map<String, Object> snapshot() {
            return snapshot(owners.values());
        }

        Map<String, Object> snapshot(Iterable<Owner> values) {
            long before = revision.get();
            int active = writers.get();
            ArrayList<long[]> pending = new ArrayList<long[]>();
            boolean complete = true;
            for (Owner owner : values) {
                if (pending.size() == CAPACITY) {
                    complete = false;
                    break;
                }
                pending.add(new long[] {generation, owner.request, owner.execution, owner.kind, owner.startedMillis});
            }
            boolean qualified = complete && active == 0 && writers.get() == 0 && before == revision.get();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("generation", Long.valueOf(generation));
            result.put("revision", Long.valueOf(before));
            result.put("writers", Integer.valueOf(active));
            result.put("qualified", Boolean.valueOf(qualified));
            result.put(
                    "unknownReason",
                    complete ? unknown.get() : "pending ownership exceeded its snapshot bound while scopes changed");
            result.put("owners", pending);
            return result;
        }
    }
}
