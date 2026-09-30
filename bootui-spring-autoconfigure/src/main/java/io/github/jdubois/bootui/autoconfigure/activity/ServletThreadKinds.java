package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;

/**
 * Spring MVC's {@link ThreadKindClassifier} ({@code docs/PLAN-v2.md} §5.1). A servlet request, a scheduled run, or a
 * consumed message runs on the one thread BootUI opened its correlation scope on: a pooled {@link ThreadKind#WORKER},
 * or a {@link ThreadKind#VIRTUAL_THREAD} when the application enables virtual threads. A thread without a BootUI
 * scope is {@link ThreadKind#OTHER}.
 */
public final class ServletThreadKinds implements ThreadKindClassifier {

    @Override
    public ThreadKind current() {
        if (ThreadKinds.isVirtual(Thread.currentThread())) {
            return ThreadKind.VIRTUAL_THREAD;
        }
        return BootUiCorrelation.current().isEmpty() ? ThreadKind.OTHER : ThreadKind.WORKER;
    }
}
