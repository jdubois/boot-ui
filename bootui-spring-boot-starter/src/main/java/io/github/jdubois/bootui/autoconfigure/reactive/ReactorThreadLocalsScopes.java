package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import reactor.core.scheduler.Schedulers;

/**
 * The BootUI agent's {@code thread-locals} scopes on Spring WebFlux ({@code docs/PLAN-v2.md} §5.16, M5-5f): a request's
 * blocking work runs on a Reactor scheduler's pooled worker, as {@code boundedElastic}, inside a task the scheduler
 * runs. A schedule hook opens an unowned scope around each such task, before Reactor's context propagation sets any
 * thread local there, and closes it after every thread local it set was restored, so the order in which context
 * propagation's accessors set and restore their values never reads as a leak; {@link
 * BootUiCorrelationThreadLocalAccessor} names the scope's owner when it makes a request's context current inside. A
 * task no request's context reaches is never reported. Registered only while the agent's bridge carries the sensor,
 * and removed when the context closes.
 */
public final class ReactorThreadLocalsScopes implements AutoCloseable {

    static final String HOOK = "bootui-thread-locals";

    private ReactorThreadLocalsScopes() {}

    /** Registers the schedule hook, or nothing without the agent: what to close when the context closes. */
    public static ReactorThreadLocalsScopes register() {
        ReactorThreadLocalsScopes scopes = new ReactorThreadLocalsScopes();
        if (AgentThreadLocals.bound()) {
            Schedulers.onScheduleHook(HOOK, Scoped::new);
        }
        return scopes;
    }

    @Override
    public void close() {
        Schedulers.resetOnScheduleHook(HOOK);
    }

    /** A scheduler's task inside a thread-locals scope. */
    static final class Scoped implements Runnable {

        private final Runnable task;

        Scoped(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            long scope = AgentThreadLocals.openUnowned();
            try {
                task.run();
            } finally {
                AgentThreadLocals.close(scope);
            }
        }
    }
}
