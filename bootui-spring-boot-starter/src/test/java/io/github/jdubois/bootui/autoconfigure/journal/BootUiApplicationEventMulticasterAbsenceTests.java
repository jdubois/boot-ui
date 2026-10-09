package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.events.OrderPlaced;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.util.ClassUtils;

class BootUiApplicationEventMulticasterAbsenceTests {

    @ParameterizedTest
    @ValueSource(strings = {"reactor.", "org.springframework.transaction."})
    void observesImmediateExecutionWithoutOptionalDependencies(String excluded) throws Exception {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String[] entries = classPath.split(Pattern.quote(File.pathSeparator));
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = new File(entries[i]).toURI().toURL();
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        // Reload the producer and Spring together: filtering only the context's loader cannot hide their linked APIs.
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(excluded)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Thread.currentThread().setContextClassLoader(loader);
            try {
                loader.loadClass(AbsenceFixture.class.getName())
                        .getMethod("verify", String.class)
                        .invoke(null, excluded);
            } catch (InvocationTargetException failure) {
                StringWriter trace = new StringWriter();
                failure.getCause().printStackTrace(new PrintWriter(trace));
                throw new AssertionError("Isolated " + excluded + " fixture failed:\n" + trace);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public static class AbsenceFixture {

        public static void verify(String excluded) throws Exception {
            boolean reactorAbsent = excluded.equals("reactor.");
            assertThat(ClassUtils.isPresent(
                            reactorAbsent
                                    ? "reactor.core.publisher.Mono"
                                    : "org.springframework.transaction.event.TransactionalApplicationListener",
                            Thread.currentThread().getContextClassLoader()))
                    .isFalse();
            try (RuntimeJournal journal = new RuntimeJournal(
                            new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                            RunIdentity.start());
                    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(
                        "applicationEventMulticaster",
                        BootUiApplicationEventMulticaster.class,
                        () -> new BootUiApplicationEventMulticaster(context.getBeanFactory(), () -> journal));
                int[] immediateRuns = {0};
                if (reactorAbsent) {
                    context.registerBean(TransactionalEventListenerFactory.class);
                    context.registerBean(ReactorFreeListener.class);
                } else {
                    context.addApplicationListener(
                            ApplicationListener.forPayload((OrderPlaced event) -> immediateRuns[0]++));
                }
                context.refresh();
                context.publishEvent(new OrderPlaced(1));
                assertThat(reactorAbsent ? context.getBean(ReactorFreeListener.class).runs : immediateRuns[0])
                        .isEqualTo(1);
                assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
                assertThat(journal.entries())
                        .filteredOn(entry -> entry.event().payload() instanceof AppEventPayload event
                                && AppEventPayload.LISTENER.equals(event.kind()))
                        .singleElement()
                        .satisfies(entry -> {
                            AppEventPayload listener =
                                    (AppEventPayload) entry.event().payload();
                            assertThat(listener.phase()).isEqualTo(AppEventPayload.IMMEDIATE);
                            assertThat(listener.outcome()).isEqualTo(AppEventPayload.RAN);
                        });
            }
        }
    }

    public static class ReactorFreeListener {

        int runs;

        @TransactionalEventListener(fallbackExecution = true)
        void fallback(OrderPlaced event) {
            runs++;
        }
    }
}
