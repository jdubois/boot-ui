package io.github.jdubois.bootui.quarkus.correlation;

import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import io.vertx.core.Context;

/**
 * Quarkus's {@link ThreadKindClassifier} ({@code docs/PLAN-v2.md} §5.1), from Vert.x's own knowledge of its threads:
 * a Vert.x event loop is a {@link ThreadKind#EVENT_LOOP}, a Vert.x worker or Quarkus executor thread a
 * {@link ThreadKind#WORKER}, a virtual thread a {@link ThreadKind#VIRTUAL_THREAD}, and any other thread
 * {@link ThreadKind#OTHER}.
 */
public final class QuarkusThreadKinds implements ThreadKindClassifier {

    @Override
    public ThreadKind current() {
        if (ThreadKinds.isVirtual(Thread.currentThread())) {
            return ThreadKind.VIRTUAL_THREAD;
        }
        if (Context.isOnEventLoopThread()) {
            return ThreadKind.EVENT_LOOP;
        }
        return Context.isOnWorkerThread() ? ThreadKind.WORKER : ThreadKind.OTHER;
    }
}
