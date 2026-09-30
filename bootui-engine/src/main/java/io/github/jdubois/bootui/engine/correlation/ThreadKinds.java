package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.ThreadKind;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Shared helpers for {@link ThreadKindClassifier}s ({@code docs/PLAN-v2.md} §5.1), and the replaceable classifier a
 * recorder reads. Every read is fully guarded, so a failing classifier never disrupts the recorded work.
 */
public final class ThreadKinds {

    private static final MethodHandle IS_VIRTUAL = isVirtualHandle();

    /** Classifies only virtual threads, everything else as {@link ThreadKind#OTHER}. */
    public static final ThreadKindClassifier DEFAULT =
            () -> isVirtual(Thread.currentThread()) ? ThreadKind.VIRTUAL_THREAD : ThreadKind.OTHER;

    private volatile ThreadKindClassifier classifier = DEFAULT;

    /** Replaces the classifier; {@code null} restores {@link #DEFAULT}. */
    public void set(ThreadKindClassifier classifier) {
        this.classifier = classifier == null ? DEFAULT : classifier;
    }

    /** The kind of the calling thread, {@link ThreadKind#OTHER} when the classifier fails. */
    public ThreadKind current() {
        try {
            ThreadKind kind = classifier.current();
            return kind == null ? ThreadKind.OTHER : kind;
        } catch (RuntimeException | LinkageError ex) {
            return ThreadKind.OTHER;
        }
    }

    /**
     * Whether {@code thread} is a virtual thread. BootUI's engine targets Java 17, so {@code Thread.isVirtual()} is
     * looked up once and reads {@code false} on a JDK without virtual threads.
     */
    public static boolean isVirtual(Thread thread) {
        if (IS_VIRTUAL == null || thread == null) {
            return false;
        }
        try {
            return (boolean) IS_VIRTUAL.invoke(thread);
        } catch (Throwable ex) {
            return false;
        }
    }

    private static MethodHandle isVirtualHandle() {
        try {
            return MethodHandles.publicLookup()
                    .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            return null;
        }
    }
}
