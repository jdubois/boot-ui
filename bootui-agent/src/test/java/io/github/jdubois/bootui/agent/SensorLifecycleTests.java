package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real sensor workers with controlled transformation operations, including the dequeued-but-not-reset window. */
class SensorLifecycleTests {

    @Test
    void aFailedThreadResetPreventsAnAlreadyQueuedReinstall() throws Exception {
        Harness harness = new Harness(true);
        CountDownLatch resetting = new CountDownLatch(1);
        CountDownLatch allowReset = new CountDownLatch(1);
        harness.gateTransformerReset(resetting, allowReset, false);
        Thread worker;
        synchronized (harness.sensor) {
            harness.release();
            worker = harness.worker();
        }
        try {
            assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();
            harness.claim(2);
        } finally {
            allowReset.countDown();
            join(worker);
        }
        assertThat(harness.operations).containsExactly("reset");
        assertThat(get(harness.sensor, "transformer")).isNull();
        assertThat(get(harness.sensor, "selfTestPassed")).isEqualTo(false);
        assertThat(get(harness.sensor, "state")).isEqualTo("release-failed");
        harness.claim(3);
        assertThat(harness.worker()).isNull();
    }

    @Test
    void aReclaimCancellingReleaseStillRetransformsNewThreadSubclassPackages() throws Exception {
        Harness harness = new Harness(true);
        when(harness.instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[] {ApplicationThread.class});
        when(harness.instrumentation.isModifiableClass(ApplicationThread.class)).thenReturn(true);
        ThreadSensor sensor = (ThreadSensor) harness.sensor;
        Thread worker;
        synchronized (sensor) {
            sensor.release();
            worker = harness.worker();
            sensor.claimed(2, List.of(SensorLifecycleTests.class.getPackageName()));
        }
        join(worker);

        verify(harness.instrumentation).retransformClasses(ApplicationThread.class);
        assertThat(harness.operations).isEmpty();
        assertThat(get(sensor, "transformer")).isSameAs(harness.installed);
    }

    @Test
    void aCancelledReleaseKeepsTheVerifiedAsyncHooksButAReleaseForgetsThem() throws Exception {
        TaskPropagation.asyncApplies(TaskPropagation.APPLY_ASYNC_SUPPLY, true);
        TaskPropagation.asyncApplies(TaskPropagation.APPLY_ASYNC_RUN, true);
        try {
            Harness harness = new Harness(false);
            Thread worker;
            synchronized (harness.sensor) {
                harness.release();
                worker = harness.worker();
                harness.claim(2);
            }
            join(worker);
            assertThat(executors()).containsEntry("asyncSupplyApplies", true).containsEntry("asyncRunApplies", true);

            synchronized (harness.sensor) {
                harness.release();
                worker = harness.worker();
            }
            join(worker);

            assertThat(harness.operations).containsExactly("reset");
            assertThat(executors()).containsEntry("asyncSupplyApplies", false).containsEntry("asyncRunApplies", false);
        } finally {
            ExecutorSensor.unverifyAsync();
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> executors() {
        return (java.util.Map<String, Object>) AgentBridge.status().get("executors");
    }

    static final class ApplicationThread extends Thread {

        @Override
        public void run() {}
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reclaimCancelsAReleaseBeforeTheWorkerDequeuesIt(boolean threads) throws Exception {
        Harness harness = new Harness(threads);
        Thread worker;
        synchronized (harness.sensor) {
            harness.release();
            worker = harness.worker();
            harness.claim(2);
        }
        join(worker);

        assertThat(harness.operations).isEmpty();
        assertThat(get(harness.sensor, "transformer")).isSameAs(harness.installed);
        assertThat(get(harness.sensor, "selfTestPassed")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reclaimAfterReleaseWasDequeuedReinstallsAfterReset(boolean threads) throws Exception {
        Harness harness = new Harness(threads);
        CountDownLatch resetting = new CountDownLatch(1);
        CountDownLatch allowReset = new CountDownLatch(1);
        harness.gateReset(resetting, allowReset);
        Thread worker;
        synchronized (harness.sensor) {
            harness.release();
            worker = harness.worker();
        }
        try {
            assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(get(harness.sensor, "transformer")).isSameAs(harness.installed);
            assertThat(get(harness.sensor, "selfTestPassed")).isEqualTo(true);
            harness.claim(2);
        } finally {
            allowReset.countDown();
            join(worker);
        }

        assertThat(harness.operations).containsExactly("reset", "install", "test:2");
        assertThat(get(harness.sensor, "transformer")).isSameAs(harness.reinstalled);
        assertThat(get(harness.sensor, "selfTestPassed")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reclaimDuringTransformerResetReinstallsButALaterReleaseWins(boolean threads) throws Exception {
        for (boolean releaseLast : List.of(false, true)) {
            Harness harness = new Harness(threads);
            CountDownLatch resetting = new CountDownLatch(1);
            CountDownLatch allowReset = new CountDownLatch(1);
            harness.gateTransformerReset(resetting, allowReset);
            Thread worker;
            synchronized (harness.sensor) {
                harness.release();
                worker = harness.worker();
            }
            try {
                assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();
                harness.claim(2);
                if (releaseLast) {
                    harness.release();
                }
            } finally {
                allowReset.countDown();
                join(worker);
            }
            assertThat(harness.operations)
                    .containsExactlyElementsOf(releaseLast ? List.of("reset") : List.of("reset", "install", "test:2"));
            assertThat(get(harness.sensor, "transformer")).isSameAs(releaseLast ? null : harness.reinstalled);
            assertThat(get(harness.sensor, "selfTestPassed")).isEqualTo(!releaseLast);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aReleaseQueuedAfterAClaimStillRemovesTheSensor(boolean threads) throws Exception {
        Harness harness = new Harness(threads);
        Thread worker;
        synchronized (harness.sensor) {
            harness.claim(2);
            harness.release();
            worker = harness.worker();
        }
        join(worker);

        assertThat(harness.operations).containsExactly("reset");
        assertThat(get(harness.sensor, "transformer")).isNull();
        assertThat(get(harness.sensor, "selfTestPassed")).isEqualTo(false);
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(5000);
        assertThat(worker.isAlive()).as("sensor worker terminated").isFalse();
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static final class Harness {

        final Object sensor;
        final Instrumentation instrumentation = mock(Instrumentation.class);
        final List<String> operations = new ArrayList<>();
        final ResettableClassFileTransformer installed = mock(ResettableClassFileTransformer.class);
        final ResettableClassFileTransformer reinstalled = mock(ResettableClassFileTransformer.class);

        Harness(boolean threads) throws Exception {
            sensor = threads
                    ? spy(new ThreadSensor(instrumentation, false))
                    : spy(new ExecutorSensor(instrumentation, false));
            set(sensor, "transformer", installed);
            set(sensor, "selfTestPassed", true);
            if (threads) {
                set(sensor, "generation", 1L);
            }
            gateTransformerReset(null, null);
            if (sensor instanceof ThreadSensor threadSensor) {
                doAnswer(call -> install()).when(threadSensor).install();
                doAnswer(call -> tested(call.getArgument(0))).when(threadSensor).selfTest(anyLong());
            } else {
                ExecutorSensor executorSensor = (ExecutorSensor) sensor;
                doAnswer(call -> install()).when(executorSensor).install();
                doAnswer(call -> tested(call.getArgument(0)))
                        .when(executorSensor)
                        .selfTest(anyLong());
            }
        }

        void claim(long generation) {
            if (sensor instanceof ThreadSensor threadSensor) {
                threadSensor.claimed(generation, List.of());
            } else {
                ((ExecutorSensor) sensor).claimed(generation);
            }
        }

        void release() {
            if (sensor instanceof ThreadSensor threadSensor) {
                threadSensor.release();
            } else {
                ((ExecutorSensor) sensor).release();
            }
        }

        Thread worker() throws Exception {
            return (Thread) get(sensor, "worker");
        }

        void gateReset(CountDownLatch entered, CountDownLatch proceed) {
            if (sensor instanceof ThreadSensor threadSensor) {
                doAnswer(call -> {
                            gate(entered, proceed);
                            return call.callRealMethod();
                        })
                        .when(threadSensor)
                        .reset(anyLong());
            } else {
                doAnswer(call -> {
                            gate(entered, proceed);
                            return call.callRealMethod();
                        })
                        .when((ExecutorSensor) sensor)
                        .reset();
            }
        }

        void gateTransformerReset(CountDownLatch entered, CountDownLatch proceed) {
            gateTransformerReset(entered, proceed, true);
        }

        void gateTransformerReset(CountDownLatch entered, CountDownLatch proceed, boolean restored) {
            doAnswer(call -> {
                        operations.add("reset");
                        if (entered != null) {
                            gate(entered, proceed);
                        }
                        return restored;
                    })
                    .when(installed)
                    .reset(
                            any(),
                            any(),
                            any(AgentBuilder.RedefinitionStrategy.BatchAllocator.class),
                            any(AgentBuilder.RedefinitionStrategy.Listener.class));
        }

        private Object install() throws Exception {
            operations.add("install");
            set(sensor, "transformer", reinstalled);
            return null;
        }

        private Object tested(long generation) throws Exception {
            operations.add("test:" + generation);
            set(sensor, "selfTestPassed", true);
            return null;
        }

        private static void gate(CountDownLatch entered, CountDownLatch proceed) throws InterruptedException {
            entered.countDown();
            assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
