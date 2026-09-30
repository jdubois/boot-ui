package io.github.jdubois.bootui.quarkus.scheduled;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;

/**
 * Opens a BootUI execution context around each invocation of a {@code @Scheduled} method ({@code docs/PLAN-v2.md}
 * §5.1), so the SQL, exceptions, and REST client calls the run makes on its thread carry its execution id.
 *
 * <p>Quarkus reports a run through {@code SuccessfulExecution} and {@code FailedExecution} events fired on the same
 * thread right after a blocking method returns, so the interceptor hands the id over to
 * {@link QuarkusScheduledTaskRunRecorder} through {@link #takeCompleted}, keyed by the method, and the run nests its
 * children. A method returning a {@code Uni} or {@code CompletionStage} completes later, possibly on another thread:
 * its run then carries no execution id rather than a guessed one. The scheduler's own single-instance
 * {@code JobInstrumenter} SPI stays free for OpenTelemetry.</p>
 *
 * <p>Ordered before platform interceptors such as {@code @Transactional}, so their work runs inside the context. It
 * imports no {@code io.quarkus.scheduler} type.</p>
 */
@BootUiScheduledExecution
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class QuarkusScheduledExecutionInterceptor {

    private static final ThreadLocal<Completed> COMPLETED = new ThreadLocal<>();

    @AroundInvoke
    Object aroundScheduledRun(InvocationContext invocation) throws Exception {
        CorrelationContext correlation = CorrelationContext.forExecution(RequestIds.next());
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
            return invocation.proceed();
        } finally {
            COMPLETED.set(new Completed(describe(invocation.getMethod()), correlation.executionId()));
        }
    }

    /**
     * The execution id of the run of {@code methodDescription} that just completed on this thread, or {@code null};
     * clears the hand-over either way, so an id is never reused by a later run.
     *
     * @param methodDescription the scheduler's {@code Class#method} description of the run
     */
    static String takeCompleted(String methodDescription) {
        Completed completed = COMPLETED.get();
        COMPLETED.remove();
        return completed != null && completed.method().equals(methodDescription) ? completed.executionId() : null;
    }

    static String describe(Method method) {
        return method == null ? "" : method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private record Completed(String method, String executionId) {}
}
