package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import java.util.List;

/**
 * What a {@code thread-locals} row shows for a thread local a scope reported ({@code docs/PLAN-v2.md} §5.16, M5-5f): its
 * holder, the static field the agent resolved it to, else a hint, else its class; and whether it is shown at all. A
 * framework's own thread locals, which its filters and scopes clear themselves, are dropped and counted per holder, as
 * are thread locals with an initial value outside the application's packages, which are per-thread caches filled by
 * {@code get()}. Spring Security's context is never dropped: a security context leaking between requests is what this
 * sensor is for. Never a value, never a {@code toString()}.
 */
final class ThreadLocalHolders {

    /**
     * Framework classes whose static fields, or whose static singletons' fields, hold thread locals the framework
     * clears itself, or that the panel names: the agent looks them up by exact name first.
     */
    static final List<String> HOLDER_CLASSES = List.of(
            "org.springframework.web.context.request.RequestContextHolder",
            "org.springframework.context.i18n.LocaleContextHolder",
            "org.springframework.transaction.support.TransactionSynchronizationManager",
            "org.springframework.aop.framework.AopContext",
            "org.springframework.security.core.context.ThreadLocalSecurityContextHolderStrategy",
            "org.springframework.security.core.context.InheritableThreadLocalSecurityContextHolderStrategy",
            "org.springframework.security.core.context.SecurityContextHolder",
            "org.slf4j.MDC",
            "org.apache.logging.log4j.ThreadContext",
            "org.jboss.logmanager.MDC",
            "org.jboss.logmanager.NDC",
            "io.micrometer.observation.SimpleObservationRegistry",
            "io.micrometer.context.ContextRegistry",
            "io.opentelemetry.context.ThreadLocalContextStorage",
            "io.opentelemetry.api.internal.TemporaryBuffers",
            "com.fasterxml.jackson.core.util.BufferRecyclers",
            "com.fasterxml.jackson.core.util.JsonRecyclerPools$ThreadLocalPool",
            "tools.jackson.core.util.JsonRecyclerPools$ThreadLocalPool",
            "io.netty.util.internal.InternalThreadLocalMap");

    /** Holders of thread locals the framework clears itself, or a per-thread cache it keeps: never a row. */
    static final List<String> EXCLUDED_PREFIXES = List.of(
            "org.springframework.web.context.request.RequestContextHolder",
            "org.springframework.context.i18n.LocaleContextHolder",
            "org.springframework.transaction.support.TransactionSynchronizationManager",
            "org.springframework.aop.framework.AopContext",
            "org.slf4j.",
            "ch.qos.logback.",
            "org.apache.logging.log4j.",
            "org.jboss.logmanager.",
            "io.micrometer.context.",
            "io.micrometer.observation.",
            "io.micrometer.tracing.",
            "io.opentelemetry.context.",
            "io.opentelemetry.api.internal.TemporaryBuffers",
            "com.fasterxml.jackson.core.util.BufferRecyclers",
            "com.fasterxml.jackson.core.util.JsonRecyclerPools",
            "tools.jackson.core.util.",
            "io.netty.util.internal.InternalThreadLocalMap");

    /** The agent's answer's places. */
    static final int HOLDER = 0;

    static final int INITIAL_VALUE = 1;
    static final int CLAIMED = 2;
    static final int HINT = 3;

    /** The reason a thread local with an initial value outside the application is dropped. */
    static final String PER_THREAD_CACHE = "(per-thread caches with an initial value outside the application)";

    /** How the agent names a thread local's holder, and is told which the engine excludes. */
    interface Resolver {

        /** The agent's answer, or {@code null} when out of time. */
        String[] holder(long generation, int id, int hash, String[] packages, String[] holders, long budgetNanos);

        void exclude(long generation, int id, int hash);
    }

    /** The agent's, through the bridge. */
    static final Resolver AGENT = new Resolver() {
        @Override
        public String[] holder(
                long generation, int id, int hash, String[] packages, String[] holders, long budgetNanos) {
            return AgentThreadLocals.holder(generation, id, hash, packages, holders, budgetNanos);
        }

        @Override
        public void exclude(long generation, int id, int hash) {
            AgentThreadLocals.exclude(generation, id, hash);
        }
    };

    /**
     * What a row shows of one thread local.
     *
     * @param target the holder, a hint, or the thread local's class, as the row's target
     * @param kind {@code left set}, {@code left set (inheritable)}, or {@code left set (with initial value)}
     * @param origin {@code application}, {@code library}, or {@code unknown}
     * @param excludedBy {@code null} when shown, else the holder or reason it is dropped under
     */
    record Holder(String target, String kind, String origin, String excludedBy) {}

    private ThreadLocalHolders() {}

    /**
     * Decides a thread local's row from the agent's {@code answer}, its runtime class {@code runtimeClass}, and the
     * bridge's {@code detail} bits.
     */
    static Holder decide(String[] answer, String runtimeClass, int detail) {
        String holder = answer == null || answer.length <= HOLDER ? null : answer[HOLDER];
        boolean initialValue = (detail & SideEffectsCatalog.DETAIL_SUPPLIED) != 0
                || (answer != null && answer.length > INITIAL_VALUE && "true".equals(answer[INITIAL_VALUE]));
        boolean claimed = answer != null && answer.length > CLAIMED && "true".equals(answer[CLAIMED]);
        String hint = answer == null || answer.length <= HINT || "collected".equals(answer[HINT]) ? null : answer[HINT];
        String kind = (detail & SideEffectsCatalog.DETAIL_INHERITABLE) != 0
                ? SideEffectsCatalog.LEFT_SET_INHERITABLE
                : initialValue ? SideEffectsCatalog.LEFT_SET_INITIAL_VALUE : SideEffectsCatalog.LEFT_SET;
        String origin = claimed
                ? SideEffectOrigins.APPLICATION
                : holder != null ? SideEffectOrigins.LIBRARY : SideEffectOrigins.UNKNOWN;
        String target = holder != null
                ? holder
                : hint != null
                        ? hint + " (holder not resolved)"
                        : "holder not resolved (" + (runtimeClass == null ? "java.lang.ThreadLocal" : runtimeClass)
                                + ")";
        String excluded = holder == null ? null : excludedPrefix(holder);
        if (excluded == null && hint != null && holder == null) {
            excluded = excludedPrefix(hint);
        }
        if (excluded != null) {
            return new Holder(target, kind, origin, holder != null ? holderClass(holder) : hint);
        }
        if (initialValue && !claimed) {
            return new Holder(target, kind, origin, PER_THREAD_CACHE);
        }
        return new Holder(target, kind, origin, null);
    }

    /** The excluded prefix {@code holder} starts with, or {@code null}. */
    static String excludedPrefix(String holder) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (holder.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /** A resolved holder's class: {@code com.example.Holder.FIELD (via …)} gives {@code com.example.Holder}. */
    static String holderClass(String holder) {
        String name = holder;
        int via = name.indexOf(" (");
        if (via > 0) {
            name = name.substring(0, via);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
