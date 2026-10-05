package io.github.jdubois.bootui.engine.support;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Every thread local BootUI's own modules declare: a {@link ThreadLocal} whose class is BootUI's, so the BootUI
 * agent's {@code thread-locals} sensor ({@code docs/PLAN-v2.md} §5.16, M5-5f) never reports one, by class, with no
 * registration and nothing to look up. An architecture test keeps every BootUI module's thread locals this class.
 *
 * @param <T> the value's type
 */
public class BootUiThreadLocal<T> extends ThreadLocal<T> {

    /** A thread local whose value starts as {@code null}. */
    public BootUiThreadLocal() {}

    /** A thread local whose value starts as {@code initial} supplies it, as {@link ThreadLocal#withInitial}. */
    public static <S> BootUiThreadLocal<S> withInitial(Supplier<? extends S> initial) {
        return new Supplied<>(initial);
    }

    private static final class Supplied<T> extends BootUiThreadLocal<T> {

        private final Supplier<? extends T> initial;

        Supplied(Supplier<? extends T> initial) {
            this.initial = Objects.requireNonNull(initial, "initial");
        }

        @Override
        protected T initialValue() {
            return initial.get();
        }
    }
}
