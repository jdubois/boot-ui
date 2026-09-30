package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral seam for the {@link ThreadKind} of the calling thread ({@code docs/PLAN-v2.md} §5.1). Each adapter
 * provides one that knows its own threads. Recorders call it on the thread that starts the work, so an implementation
 * must be cheap and must never block.
 */
@FunctionalInterface
public interface ThreadKindClassifier {

    /** The kind of the calling thread, never {@code null}. */
    ThreadKind current();
}
