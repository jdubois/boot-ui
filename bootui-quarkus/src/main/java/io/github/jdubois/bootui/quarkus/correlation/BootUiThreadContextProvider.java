package io.github.jdubois.bootui.quarkus.correlation;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ManagedTasks;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.Map;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;

/**
 * Carries the submitting request's correlation into tasks that SmallRye Context Propagation hands to managed executors
 * on Quarkus ({@code docs/PLAN-v2.md} D30, M4-15), such as {@code ManagedExecutor} tasks, so their work is an execution
 * of the request that submitted it. Registered as a service; the context type is BootUI's own, so propagating or
 * clearing it never touches another context.
 */
public final class BootUiThreadContextProvider implements ThreadContextProvider {

    public static final String CONTEXT_TYPE = "BootUI";

    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> props) {
        CorrelationContext context = ManagedTasks.taskContext(QuarkusRequestCorrelation.current());
        return () -> {
            // The same request already current on this thread keeps its context: no empty nested execution.
            BootUiCorrelation.Scope scope = ManagedTasks.open(context);
            return scope == null ? () -> {} : scope::close;
        };
    }

    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> props) {
        return () -> {
            BootUiCorrelation.Scope scope = BootUiCorrelation.openCleared();
            return scope::close;
        };
    }

    @Override
    public String getThreadContextType() {
        return CONTEXT_TYPE;
    }
}
