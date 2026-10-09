package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * Spring's own event multicaster, recording application events in the runtime journal's {@code app-event} source
 * ({@code docs/PLAN-v2.md} §5.18, M4-8): each publication, and each listener's run, with transactional listeners'
 * phases, deferrals, and the runs Spring skips because no transaction was active. It delivers every event exactly as
 * {@link SimpleApplicationEventMulticaster} does: it only observes. Framework events, by package, are not recorded,
 * and an event's fields or {@code toString()} never are.
 */
public class BootUiApplicationEventMulticaster extends SimpleApplicationEventMulticaster {

    private static final String[] FRAMEWORK_PACKAGES = {
        "org.springframework.",
        "java.",
        "jakarta.",
        "javax.",
        "org.apache.",
        "io.micrometer.",
        // BootUI's own modules; an application in another io.github.jdubois.bootui package, as the samples are, counts.
        "io.github.jdubois.bootui.core.",
        "io.github.jdubois.bootui.engine.",
        "io.github.jdubois.bootui.spi.",
        "io.github.jdubois.bootui.autoconfigure."
    };

    private static final boolean TRANSACTIONS =
            ClassUtils.isPresent("org.springframework.transaction.event.TransactionalApplicationListener", null);

    private final Supplier<RuntimeJournal> journal;
    private final Set<Object> observedTransactionalListeners = Collections.newSetFromMap(new WeakHashMap<>());

    public BootUiApplicationEventMulticaster(BeanFactory beanFactory, Supplier<RuntimeJournal> journal) {
        super(beanFactory);
        this.journal = journal;
    }

    @Override
    public void multicastEvent(ApplicationEvent event, ResolvableType eventType) {
        String type = applicationType(event);
        if (type != null) {
            try {
                record(
                        AppEventPayload.published(
                                type,
                                getApplicationListeners(event, resolve(event, eventType))
                                        .size()),
                        0,
                        false);
            } catch (RuntimeException | LinkageError ex) {
                // Recording never changes how an event is delivered.
            }
        }
        super.multicastEvent(event, eventType);
    }

    @Override
    protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
        String type = applicationType(event);
        if (type == null) {
            super.invokeListener(listener, event);
            return;
        }
        String phase = AppEventPayload.IMMEDIATE;
        if (TRANSACTIONS) {
            String transactional = Transactional.classify(this, listener, event, type);
            if (Transactional.DELEGATED.equals(transactional)) {
                // Deferred to its phase, or skipped: the listener's own adapter decides, and the fate is recorded.
                super.invokeListener(listener, event);
                return;
            }
            if (transactional != null) {
                phase = transactional;
            }
        }
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            super.invokeListener(listener, event);
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            listenerRan(type, name(listener), phase, start, failure);
        }
    }

    void listenerRan(String type, String listener, String phase, long startNanos, Throwable failure) {
        try {
            record(
                    AppEventPayload.listener(
                            type,
                            listener,
                            phase,
                            failure == null ? AppEventPayload.RAN : AppEventPayload.FAILED,
                            failure == null ? null : failure.getClass().getName(),
                            startNanos),
                    System.nanoTime() - startNanos,
                    failure != null);
        } catch (RuntimeException | LinkageError ex) {
            // Recording never changes how an event is delivered.
        }
    }

    void record(AppEventPayload payload, long durationNanos, boolean failed) {
        RuntimeJournal target = journal.get();
        if (target == null || !target.records(JournalSource.APP_EVENT)) {
            return;
        }
        CorrelationContext context = BootUiCorrelation.current();
        target.offer(RuntimeEvent.of(
                JournalSource.APP_EVENT,
                System.currentTimeMillis() - durationNanos / 1_000_000,
                durationNanos,
                context,
                Thread.currentThread().getName(),
                null,
                failed,
                payload));
    }

    /** The application type an event carries, its payload's for a payload event, or {@code null} for a framework one. */
    static String applicationType(ApplicationEvent event) {
        Object subject = event instanceof PayloadApplicationEvent<?> payload ? payload.getPayload() : event;
        if (subject == null) {
            return null;
        }
        String name = subject.getClass().getName();
        for (String prefix : FRAMEWORK_PACKAGES) {
            if (name.startsWith(prefix)) {
                return null;
            }
        }
        return name;
    }

    /** A listener as {@code Class#method}, or its class's simple name. */
    static String name(ApplicationListener<?> listener) {
        if (listener instanceof ApplicationListenerMethodAdapter adapter) {
            Method method = adapter.getTargetMethod();
            return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
        return ClassUtils.getUserClass(listener).getSimpleName();
    }

    private static ResolvableType resolve(ApplicationEvent event, ResolvableType eventType) {
        return eventType != null ? eventType : ResolvableType.forInstance(event);
    }

    /** The only class that names {@code spring-tx}, loaded when it is on the classpath. */
    private static final class Transactional {

        /** What {@link #classify} returns when the listener's adapter decides, and its fate is already recorded. */
        static final String DELEGATED = "DELEGATED";

        /**
         * Classifies {@code listener} for the event being delivered: {@code null} when it is not transactional; {@link
         * #DELEGATED} when it is deferred to its phase, recorded again when it runs, or skipped for lack of a
         * transaction, both recorded now; or {@code IMMEDIATE} when it runs at once through {@code fallbackExecution}.
         */
        static String classify(
                BootUiApplicationEventMulticaster multicaster,
                ApplicationListener<?> listener,
                ApplicationEvent event,
                String type) {
            if (!(listener instanceof org.springframework.transaction.event.TransactionalApplicationListener<?> tal)) {
                return null;
            }
            String name = name(listener);
            String phase = tal.getTransactionPhase().name();
            observe(multicaster, tal, name, phase);
            if (transactionActive(event)) {
                multicaster.record(
                        AppEventPayload.listener(type, name, phase, AppEventPayload.DEFERRED, null, -1), 0, false);
                return DELEGATED;
            }
            if (fallback(listener)) {
                return AppEventPayload.IMMEDIATE;
            }
            multicaster.record(
                    AppEventPayload.listener(type, name, phase, AppEventPayload.SKIPPED_NO_TRANSACTION, null, -1),
                    0,
                    false);
            return DELEGATED;
        }

        private static boolean transactionActive(ApplicationEvent event) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    && org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive()) {
                return true;
            }
            if (event.getSource() instanceof org.springframework.transaction.reactive.TransactionContext context) {
                var manager = new org.springframework.transaction.reactive.TransactionSynchronizationManager(context);
                return manager.isSynchronizationActive() && manager.isActualTransactionActive();
            }
            return false;
        }

        private static void observe(
                BootUiApplicationEventMulticaster multicaster,
                org.springframework.transaction.event.TransactionalApplicationListener<?> listener,
                String name,
                String phase) {
            synchronized (multicaster.observedTransactionalListeners) {
                if (!multicaster.observedTransactionalListeners.add(listener)) {
                    return;
                }
            }
            Map<ApplicationEvent, Long> started = new ConcurrentHashMap<>();
            listener.addCallback(
                    new org.springframework.transaction.event.TransactionalApplicationListener
                            .SynchronizationCallback() {
                        @Override
                        public void preProcessEvent(ApplicationEvent event) {
                            started.put(event, System.nanoTime());
                        }

                        @Override
                        public void postProcessEvent(ApplicationEvent event, Throwable failure) {
                            Long start = started.remove(event);
                            String type = applicationType(event);
                            if (start != null && type != null) {
                                multicaster.listenerRan(type, name, phase, start, failure);
                            }
                        }
                    });
        }

        private static boolean fallback(ApplicationListener<?> listener) {
            if (listener instanceof ApplicationListenerMethodAdapter adapter) {
                org.springframework.transaction.event.TransactionalEventListener annotation =
                        AnnotatedElementUtils.findMergedAnnotation(
                                adapter.getTargetMethod(),
                                org.springframework.transaction.event.TransactionalEventListener.class);
                return annotation != null && annotation.fallbackExecution();
            }
            return false;
        }
    }
}
