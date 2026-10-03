package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

/** Actual subclass transformation and automatic reset, without modifying the test JVM's java.lang.Thread. */
class ThreadSensorResetTests {

    private static final Class<?> OLD = bootuiagentit.probed.ResetThread.class;
    private static final Class<?> NEW = bootuiagentit.run.ResetThread.class;
    private static final String BRIDGE = "io/github/jdubois/bootui/agent/bridge/ThreadPropagation";

    @Test
    void changedPackageReclaimRestoresTheOldSubclassBeforeInstallingTheNewOne() throws Exception {
        Class.forName(ThreadSensor.TPE);
        if (ExecutorSensor.present(ThreadSensor.VIRTUAL_THREAD)) {
            Class.forName(ThreadSensor.VIRTUAL_THREAD);
        }
        Instrumentation actual = ByteBuddyAgent.getInstrumentation();
        Instrumentation scoped = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getAllLoadedClasses")) {
                        return new Class<?>[] {OLD, NEW};
                    }
                    try {
                        return method.invoke(actual, arguments);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        ThreadSensor sensor = spy(new ThreadSensor(scoped, false));
        set(sensor, "packages", List.of(OLD.getPackageName()));
        set(sensor, "generation", 1L);
        doAnswer(call -> {
                    set(sensor, "selfTestPassed", true);
                    return null;
                })
                .when(sensor)
                .selfTest(anyLong());
        CountDownLatch resetting = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Bytes observer = new Bytes();
        Thread worker = null;
        try {
            sensor.install();
            set(sensor, "selfTestPassed", true);
            actual.addTransformer(observer, true);
            actual.retransformClasses(OLD);
            assertThat(observer.oldBytes).contains(BRIDGE);
            doAnswer(call -> {
                        resetting.countDown();
                        assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
                        return call.callRealMethod();
                    })
                    .when(sensor)
                    .reset(anyLong());
            synchronized (sensor) {
                sensor.release();
                worker = worker(sensor);
            }
            assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();
            sensor.claimed(2, List.of(NEW.getPackageName()));
            proceed.countDown();
            join(worker);

            assertThat(observer.oldBytes)
                    .as("bytes restored by the automatic reset")
                    .doesNotContain(BRIDGE);
            actual.removeTransformer(observer);
            actual.addTransformer(observer, true);
            actual.retransformClasses(NEW);
            assertThat(observer.newBytes).as("replacement transformer").contains(BRIDGE);
            ThreadSensor.SubclassMatcher matcher = (ThreadSensor.SubclassMatcher) get(sensor, "installedSubclasses");
            assertThat(matcher.matches(new TypeDescription.ForLoadedType(OLD))).isFalse();
            assertThat(matcher.matches(new TypeDescription.ForLoadedType(NEW))).isTrue();
        } finally {
            proceed.countDown();
            try {
                if (worker != null) {
                    join(worker);
                }
                synchronized (sensor) {
                    sensor.release();
                    worker = worker(sensor);
                }
                join(worker);
            } finally {
                actual.removeTransformer(observer);
            }
        }
    }

    @Test
    void liveMatchingNarrowsButResetKeepsEveryPreviouslyClaimedPackage() {
        ThreadSensor.SubclassMatcher matcher = new ThreadSensor.SubclassMatcher(List.of(OLD.getPackageName()));
        matcher.update(List.of(NEW.getPackageName()));
        assertThat(matcher.matches(new TypeDescription.ForLoadedType(OLD))).isFalse();
        assertThat(matcher.matches(new TypeDescription.ForLoadedType(NEW))).isTrue();
        matcher.beginReset();
        assertThat(matcher.matches(new TypeDescription.ForLoadedType(OLD))).isTrue();
        assertThat(matcher.matches(new TypeDescription.ForLoadedType(NEW))).isTrue();
    }

    private static Thread worker(ThreadSensor sensor) throws Exception {
        return (Thread) get(sensor, "worker");
    }

    private static Object get(ThreadSensor sensor, String name) throws Exception {
        Field field = ThreadSensor.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(sensor);
    }

    private static void set(ThreadSensor sensor, String name, Object value) throws Exception {
        Field field = ThreadSensor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(sensor, value);
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
    }

    static final class Bytes implements ClassFileTransformer {

        volatile String oldBytes;
        volatile String newBytes;

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
            if (type == OLD) {
                oldBytes = new String(bytes, StandardCharsets.ISO_8859_1);
            } else if (type == NEW) {
                newBytes = new String(bytes, StandardCharsets.ISO_8859_1);
            }
            return null;
        }
    }
}
