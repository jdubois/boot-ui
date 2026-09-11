package io.github.jdubois.bootui.spi;

/**
 * Optional capture-time correlation, never an event index or a context propagation mechanism.
 * Adapters snapshot this at the start of an already observed operation, not on a completion thread.
 */
@FunctionalInterface
public interface InvocationContextProvider {

    Context EMPTY = new Context(null, null);
    InvocationContextProvider NO_OP = () -> EMPTY;

    Context current();

    /** A local invocation span, or the active host span when no local invocation is available. */
    record Context(String traceId, String invocationId) {}

    static Context snapshot(InvocationContextProvider provider) {
        try {
            Context context = provider.current();
            return context == null ? EMPTY : context;
        } catch (RuntimeException | LinkageError ignored) {
            // Optional diagnostics must not change the observed operation's outcome.
            return EMPTY;
        }
    }
}
