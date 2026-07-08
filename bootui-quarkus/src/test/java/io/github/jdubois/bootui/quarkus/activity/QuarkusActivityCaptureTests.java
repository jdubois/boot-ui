package io.github.jdubois.bootui.quarkus.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.InMemoryActivityStore;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link QuarkusActivityCapture}'s lifecycle: with persistence disabled (the default), {@link
 * QuarkusActivityCapture#onStart} must start no background thread and {@link
 * QuarkusActivityCapture#onStop} must not fail even though no poller was ever created; with persistence
 * enabled, the capture poller thread must start on {@code onStart} and stop on {@code onStop} — mirroring
 * the Spring adapter's {@code shutdownStopsCapturePollerThreadWhenPersistenceEnabled}.
 */
class QuarkusActivityCaptureTests {

    @Test
    void onStartDoesNotStartCapturePollerThreadWhenPersistenceDisabled() throws Exception {
        QuarkusActivityCapture capture = new QuarkusActivityCapture(
                new SwitchableActivityStore(new InMemoryActivityStore(10)), disabledSettings(), liveActivityResource());

        capture.onStart(null);

        assertThat(awaitThreadNamed("bootui-activity-capture")).isNull();

        // Must not throw even though onStart created no poller.
        capture.onStop(null);
    }

    @Test
    void onStartAndOnStopControlTheCapturePollerThreadWhenPersistenceEnabled() throws Exception {
        QuarkusActivityCapture capture = new QuarkusActivityCapture(
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                enabledSettings(Duration.ofMillis(50)),
                liveActivityResource());

        capture.onStart(null);
        Thread captureThread = awaitThreadNamed("bootui-activity-capture");
        assertThat(captureThread)
                .as("capture poller thread should have started")
                .isNotNull();

        capture.onStop(null);

        assertThat(awaitNotAlive(captureThread)).isTrue();
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return settings(false, Duration.ofSeconds(2));
    }

    private static ActivityPersistenceSettings enabledSettings(Duration captureInterval) {
        return settings(true, captureInterval);
    }

    private static ActivityPersistenceSettings settings(boolean enabled, Duration captureInterval) {
        return new ActivityPersistenceSettings(
                enabled,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                500,
                Duration.ofDays(7),
                "instance-a",
                captureInterval);
    }

    private static LiveActivityResource liveActivityResource() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.<String, String>of(), "test", 1000))
                .build();
        return new LiveActivityResource(
                new HttpExchangeBuffer(50),
                new QuarkusExposurePolicy(config),
                new UnsatisfiedInstance<>(),
                new ExceptionStore(10, 10, 10),
                new ExceptionsService(new QuarkusExposurePolicy(config)),
                new UnsatisfiedInstance<EmailCaptureService>(),
                new SecurityEventBuffer(10),
                new QuarkusPanelAvailability(config),
                null,
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                disabledSettings(),
                new UnsatisfiedInstance<>());
    }

    private static Thread awaitThreadNamed(String name) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (name.equals(thread.getName())) {
                    return thread;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static boolean awaitNotAlive(Thread thread) throws InterruptedException {
        for (int i = 0; i < 100 && thread.isAlive(); i++) {
            Thread.sleep(10);
        }
        return !thread.isAlive();
    }

    /** Minimal always-unsatisfied {@link Instance} fake — see {@code LiveActivityResourceTests} for details. */
    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no SqlTraceRecorder bean produced in this test");
        }
    }
}
