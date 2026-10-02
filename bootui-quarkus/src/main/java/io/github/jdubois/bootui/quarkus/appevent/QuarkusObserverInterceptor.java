package io.github.jdubois.bootui.quarkus.appevent;

import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Records each run of an application observer method in the runtime journal's {@code app-event} source ({@code
 * docs/PLAN-v2.md} §5.18, M4-8): the event's type, the observer, its transaction phase, and whether it failed, never the
 * event's fields. CDI notifies a transactional observer at once when no transaction is active, so none is skipped.
 * Events of framework packages are not recorded. It only observes.
 */
@BootUiObservedEvent
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
public class QuarkusObserverInterceptor {

    private static final String[] FRAMEWORK_PACKAGES = {
        "io.quarkus.",
        "io.vertx.",
        "io.smallrye.",
        "java.",
        "jakarta.",
        "javax.",
        "org.jboss.",
        "io.github.jdubois.bootui.core.",
        "io.github.jdubois.bootui.engine.",
        "io.github.jdubois.bootui.quarkus."
    };

    @Inject
    RuntimeJournal journal;

    @Inject
    CorrelationContextProvider correlation;

    @AroundInvoke
    Object aroundObserver(InvocationContext invocation) throws Exception {
        Observed observed = observed(invocation);
        if (observed == null) {
            return invocation.proceed();
        }
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            return invocation.proceed();
        } catch (Exception | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            record(invocation.getMethod(), observed, start, failure);
        }
    }

    private void record(Method method, Observed observed, long start, Throwable failure) {
        try {
            if (journal == null || !journal.records(JournalSource.APP_EVENT)) {
                return;
            }
            long duration = System.nanoTime() - start;
            journal.offer(RuntimeEvent.of(
                    JournalSource.APP_EVENT,
                    System.currentTimeMillis() - duration / 1_000_000,
                    duration,
                    correlation.current(),
                    Thread.currentThread().getName(),
                    null,
                    failure != null,
                    AppEventPayload.listener(
                            observed.type(),
                            method.getDeclaringClass().getSimpleName() + "#" + method.getName(),
                            observed.phase(),
                            failure == null ? AppEventPayload.RAN : AppEventPayload.FAILED,
                            failure == null ? null : failure.getClass().getName(),
                            start)));
        } catch (RuntimeException | LinkageError ex) {
            // Recording never changes how an event is delivered.
        }
    }

    /** The observed event's type and phase, or {@code null} when the event is a framework's or none is observed. */
    static Observed observed(InvocationContext invocation) {
        Method method = invocation.getMethod();
        Object[] parameters = invocation.getParameters();
        if (method == null || parameters == null) {
            return null;
        }
        Annotation[][] annotations = method.getParameterAnnotations();
        for (int index = 0; index < annotations.length && index < parameters.length; index++) {
            for (Annotation annotation : annotations[index]) {
                String phase = null;
                if (annotation instanceof Observes observes) {
                    phase = phase(observes.during());
                } else if (annotation instanceof ObservesAsync) {
                    phase = "ASYNC";
                }
                if (phase != null) {
                    String type = applicationType(parameters[index]);
                    return type == null ? null : new Observed(type, phase);
                }
            }
        }
        return null;
    }

    /** CDI's transaction phase in the journal's names, which follow Spring's. */
    static String phase(TransactionPhase during) {
        return switch (during) {
            case AFTER_SUCCESS -> "AFTER_COMMIT";
            case AFTER_FAILURE -> "AFTER_ROLLBACK";
            case AFTER_COMPLETION -> "AFTER_COMPLETION";
            case BEFORE_COMPLETION -> "BEFORE_COMMIT";
            default -> AppEventPayload.IMMEDIATE;
        };
    }

    static String applicationType(Object event) {
        if (event == null) {
            return null;
        }
        String name = event.getClass().getName();
        for (String prefix : FRAMEWORK_PACKAGES) {
            if (name.startsWith(prefix)) {
                return null;
            }
        }
        return name;
    }

    record Observed(String type, String phase) {}
}
