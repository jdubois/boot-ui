package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import org.springframework.util.ClassUtils;
import reactor.core.scheduler.Schedulers;

/**
 * Spring WebFlux's {@link ThreadKindClassifier} ({@code docs/PLAN-v2.md} §5.1), from thread types rather than names.
 * Reactor marks the threads that must not block with its {@code NonBlocking} interface: Reactor Netty's event loops,
 * which are also Netty {@code FastThreadLocalThread}s, are {@link ThreadKind#EVENT_LOOP}, and the others, such as
 * {@code parallel}, are {@link ThreadKind#REACTOR_SCHEDULER}. A blocking-capable thread running a request, scheduled
 * run, or consumed message, such as {@code boundedElastic}, is a {@link ThreadKind#WORKER}.
 */
public final class ReactiveThreadKinds implements ThreadKindClassifier {

    private static final Class<?> NETTY_THREAD = resolve("io.netty.util.concurrent.FastThreadLocalThread");

    @Override
    public ThreadKind current() {
        Thread thread = Thread.currentThread();
        if (ThreadKinds.isVirtual(thread)) {
            return ThreadKind.VIRTUAL_THREAD;
        }
        if (Schedulers.isNonBlockingThread(thread)) {
            return NETTY_THREAD != null && NETTY_THREAD.isInstance(thread)
                    ? ThreadKind.EVENT_LOOP
                    : ThreadKind.REACTOR_SCHEDULER;
        }
        return BootUiCorrelation.current().isEmpty() ? ThreadKind.OTHER : ThreadKind.WORKER;
    }

    private static Class<?> resolve(String name) {
        try {
            return ClassUtils.forName(name, ReactiveThreadKinds.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }
}
