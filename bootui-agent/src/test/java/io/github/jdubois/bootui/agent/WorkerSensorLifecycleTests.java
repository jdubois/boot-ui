package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real application-methods and probe workers, paused at instrumentation boundaries rather than by elapsed time. */
class WorkerSensorLifecycleTests {

    private static final Class<?> FIRST = bootuiagentit.probed.ResetThread.class;
    private static final Class<?> SECOND = bootuiagentit.run.ResetThread.class;

    @BeforeEach
    @AfterEach
    void resetBridge() throws Exception {
        var reset = AgentBridge.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void aReleaseQueuedAfterAnApplicationClaimWinsBeforeTheWorkerDequeuesIt() throws Exception {
        try (Harness harness = new Harness(true)) {
            synchronized (harness.application) {
                harness.claim();
                harness.release();
            }
            harness.joinWorkers();

            harness.assertApplicationReleased();
            assertThat(harness.instrumentation.operations).isEmpty();
        }
    }

    @Test
    void claimBetweenApplicationReleaseDequeueAndResetStillReinstalls() throws Exception {
        try (Harness harness = new Harness(true)) {
            harness.installApplication();
            Gate beforeReset = harness.instrumentation.gate(Point.RECORDER_REMOVAL);
            harness.release();
            beforeReset.await();
            assertThat(get(harness.application, "transformer")).isNotNull();
            Thread worker = harness.applicationWorker();

            harness.claim(List.of("shop.next"), List.of());
            assertThat(harness.applicationWorker()).isSameAs(worker);
            beforeReset.open();
            harness.joinWorkers();

            harness.assertApplicationInstalled();
            assertThat(harness.application.inventoryStatus())
                    .containsEntry("packages", List.of("shop.next"))
                    .containsEntry("matching", List.of("shop.next"))
                    .containsEntry("retransformedPackages", List.of("shop.next"));
            assertThat(harness.instrumentation.operations).containsExactly("reset", "install");
        }
    }

    @Test
    void reclaimAndRefineDuringApplicationResetKeepPackagesAndRetransformBeans() throws Exception {
        Class<?> claimedBean = bean("shop.next.Bean");
        Class<?> refinedBean = bean("shop.refined.Bean");
        try (Harness harness = new Harness(true, claimedBean, refinedBean)) {
            harness.installApplication();
            Gate reset = harness.instrumentation.gate(Point.RESET);
            harness.release();
            reset.await();
            assertThat(get(harness.application, "transformer")).isNull();
            harness.assertApplicationUnverified();
            Thread worker = harness.applicationWorker();

            harness.claim(List.of("shop.next"), List.of("shop.next.Bean"));
            harness.refine(List.of("shop.refined"), List.of("shop.refined.Bean"));
            assertThat(harness.applicationWorker()).isSameAs(worker);
            reset.open();
            harness.joinWorkers();

            harness.assertApplicationInstalled();
            assertThat(harness.application.inventoryStatus())
                    .containsEntry("packages", List.of("shop.next", "shop.refined"))
                    .containsEntry("matching", List.of("shop.next", "shop.refined"))
                    .containsEntry("retransformedPackages", List.of("shop.next", "shop.refined"));
            assertThat(get(harness.application, "beanClasses"))
                    .isEqualTo(Set.of("shop.next.Bean", "shop.refined.Bean"));
            assertThat(harness.instrumentation.retransformed).contains(claimedBean, refinedBean);
            assertThat(get(harness.application, "codePathsTypes"))
                    .isEqualTo(Set.of("shop.next.Bean", "shop.refined.Bean"));
            assertThat(claimedBean
                            .getMethod("run")
                            .invoke(claimedBean.getConstructor().newInstance()))
                    .isEqualTo(1);
            assertThat(refinedBean
                            .getMethod("run")
                            .invoke(refinedBean.getConstructor().newInstance()))
                    .isEqualTo(1);
            assertThat(harness.instrumentation.operations).containsExactly("reset", "install");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reclaimDuringApplicationResetReinstallsUnlessALaterReleaseWins(boolean releaseLast) throws Exception {
        try (Harness harness = new Harness(true)) {
            harness.installApplication();
            Gate reset = harness.instrumentation.gate(Point.RESET);
            harness.release();
            reset.await();

            assertThat(get(harness.application, "transformer")).isNull();
            harness.assertApplicationUnverified();
            Thread worker = harness.applicationWorker();
            harness.claim(List.of("shop.next"), List.of());
            assertThat(harness.applicationWorker()).isSameAs(worker);
            if (releaseLast) {
                harness.release();
            }
            reset.open();
            harness.joinWorkers();

            if (releaseLast) {
                harness.assertApplicationReleased();
                assertThat(harness.instrumentation.operations).containsExactly("reset");
            } else {
                harness.assertApplicationInstalled();
                assertThat(harness.application.inventoryStatus())
                        .containsEntry("packages", List.of("shop.next"))
                        .containsEntry("matching", List.of("shop.next"));
                assertThat(harness.instrumentation.operations).containsExactly("reset", "install");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Failure.class,
            names = {"FALSE", "THROW"})
    void aFailedApplicationResetCannotRunAnAlreadyQueuedReinstall(Failure failure) throws Exception {
        try (Harness harness = new Harness(true)) {
            harness.installApplication();
            Gate reset = harness.instrumentation.gate(Point.RESET, failure, 0);
            harness.release();
            reset.await();
            harness.claim(List.of("shop.next"), List.of());
            reset.open();
            harness.joinWorkers();

            assertThat(harness.instrumentation.operations).containsExactly("reset");
            harness.assertApplicationResetFailed();
            if (failure == Failure.THROW) {
                assertThat(AgentBridge.messages())
                        .anySatisfy(message -> assertThat(message)
                                .contains(
                                        "could not restore its application-methods classes", "injected RESET failure"));
            }

            harness.claim(List.of("shop.later"), List.of());
            harness.joinWorkers();
            assertThat(harness.instrumentation.operations).containsExactly("reset");
            harness.assertApplicationResetFailed();
        }
    }

    @Test
    void releaseDuringApplicationInstallRemovesTheTransformerAndReadiness() throws Exception {
        try (Harness harness = new Harness(true)) {
            Gate install = harness.instrumentation.gate(Point.INSTALL);
            harness.claim();
            install.await();
            assertThat(harness.application.inventoryStatus()).containsEntry("state", "installing");
            harness.assertApplicationUnverified();
            harness.release();
            install.open();
            harness.joinWorkers();

            harness.assertApplicationReleased();
            assertThat(AgentBridge.messages())
                    .anySatisfy(message -> assertThat(message).contains("inventory sensor failed its self-test"));
            assertThat(harness.instrumentation.operations).containsExactly("install", "reset");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reclaimDuringApplicationInstallUsesTheLatestClaimUnlessALaterReleaseWins(boolean releaseLast)
            throws Exception {
        Class<?> claimedBean = bean("shop.next.Bean");
        try (Harness harness = new Harness(true, claimedBean)) {
            Gate install = harness.instrumentation.gate(Point.INSTALL);
            harness.claim();
            install.await();
            Thread worker = harness.applicationWorker();
            harness.claim(List.of("shop.next"), List.of("shop.next.Bean"));
            assertThat(harness.applicationWorker()).isSameAs(worker);
            harness.assertApplicationUnverified();
            if (releaseLast) {
                harness.release();
            }
            install.open();
            harness.joinWorkers();

            if (releaseLast) {
                harness.assertApplicationRemoved();
                assertThat(harness.instrumentation.operations).containsExactly("install", "reset");
            } else {
                harness.assertApplicationInstalled();
                assertThat(harness.application.inventoryStatus())
                        .containsEntry("packages", List.of("shop.next"))
                        .containsEntry("matching", List.of("shop", "shop.next"))
                        .containsEntry("retransformedPackages", List.of("shop.next"));
                assertThat(get(harness.application, "beanClasses")).isEqualTo(Set.of("shop.next.Bean"));
                assertThat(get(harness.application, "codePathsTypes")).isEqualTo(Set.of("shop.next.Bean"));
                assertThat(harness.instrumentation.retransformed).contains(claimedBean);
                assertThat(harness.instrumentation.operations).containsExactly("install");
            }
        }
    }

    @Test
    void aFailedApplicationInstallCannotAdvertiseReadiness() throws Exception {
        try (Harness harness = new Harness(true)) {
            Gate install = harness.instrumentation.gate(Point.INSTALL, Failure.THROW, 0);
            harness.claim();
            install.await();
            harness.assertApplicationUnverified();
            install.open();
            harness.joinWorkers();

            assertThat(harness.application.inventoryStatus())
                    .containsEntry("state", "failed")
                    .containsEntry("idle", true);
            harness.assertApplicationUnverified();
            assertThat(get(harness.application, "transformer")).isNull();
            assertThat(harness.application.inventoryStatus().get("failures"))
                    .asList()
                    .anySatisfy(reason -> assertThat(reason.toString())
                            .contains("application methods:", "Could not install class file transformer"));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Point.class,
            names = {"ENUMERATION", "INSTALL"})
    void reclaimAfterProbeDequeueNeverActivatesTheOldProbeAndDrainsTheNewRequest(Point point) throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.claim();
            Gate oldWork = harness.instrumentation.gate(point);
            Probe old = harness.start(FIRST);
            oldWork.await();
            assertThat(harness.probes.status()).containsEntry("pending", 0).containsEntry("running", true);
            Thread worker = harness.probeWorker();

            harness.claim();
            Probe next = harness.start(FIRST);
            assertThat(harness.probeWorker()).isSameAs(worker);
            assertThat(harness.probes.status()).containsEntry("pending", 1);
            Gate newInstall = harness.instrumentation.gate(Point.INSTALL);
            oldWork.open();
            newInstall.await();

            assertThat(MethodProbes.poll(old.slot, old.id)).isEqualTo(-1);
            assertThat(harness.describe(old))
                    .containsEntry("state", "ended")
                    .containsEntry("endReason", MethodProbes.END_RUN)
                    .containsEntry("activatedAt", null);
            assertThat(MethodProbes.poll(next.slot, next.id)).isEqualTo(MethodProbes.STARTING);
            harness.release();
            newInstall.open();
            harness.joinWorkers();

            harness.assertProbesDrained();
            assertThat(harness.describe(next)).containsEntry("state", "ended").containsEntry("activatedAt", null);
            assertThat(harness.instrumentation.operations)
                    .containsExactlyElementsOf(
                            point == Point.INSTALL
                                    ? List.of("install", "reset", "install", "reset")
                                    : List.of("install", "reset"));
        }
    }

    @Test
    void aNewProbeQueuedDuringTheOldInstallCanBecomeActiveOnTheSameWorker() throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.claim();
            Gate oldInstall = harness.instrumentation.gate(Point.INSTALL);
            Probe old = harness.start(FIRST);
            oldInstall.await();
            Thread worker = harness.probeWorker();
            harness.claim();
            Probe next;
            Probe marker;
            synchronized (harness.probes) {
                next = harness.start(FIRST);
                marker = harness.start(SECOND);
            }
            // The second dequeued request is the checkpoint after the first one's activation, not a timed poll.
            Gate afterActivation = harness.instrumentation.gate(Point.ENUMERATION, Failure.NONE, 1);
            oldInstall.open();
            afterActivation.await();

            assertThat(harness.probeWorker()).isSameAs(worker);
            assertThat(MethodProbes.poll(old.slot, old.id)).isEqualTo(-1);
            assertThat(MethodProbes.poll(next.slot, next.id)).isEqualTo(MethodProbes.ACTIVE);
            assertThat(harness.describe(next)).containsEntry("state", "active").containsEntry("advised", true);
            assertThat(harness.probes.status()).containsEntry("installed", 1).containsEntry("pending", 0);
            assertThat(MethodProbes.poll(marker.slot, marker.id)).isEqualTo(MethodProbes.STARTING);
            harness.release();
            afterActivation.open();
            harness.joinWorkers();

            harness.assertProbesDrained();
            assertThat(harness.describe(next)).containsEntry("state", "ended").containsEntry("removal", "removed");
            assertThat(harness.instrumentation.operations).containsExactly("install", "reset", "install", "reset");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Failure.class,
            names = {"NONE", "THROW"})
    void reclaimDuringProbeResetAndALaterReleaseRetireEverySlotEvenWhenResetFails(Failure failure) throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.claim();
            Gate install = harness.instrumentation.gate(Point.INSTALL);
            Probe old;
            synchronized (harness.probes) {
                old = harness.start(FIRST);
                harness.start(SECOND);
            }
            install.await();
            Gate afterActivation = harness.instrumentation.gate(Point.ENUMERATION);
            install.open();
            afterActivation.await();
            assertThat(MethodProbes.poll(old.slot, old.id)).isEqualTo(MethodProbes.ACTIVE);
            assertThat(harness.describe(old)).containsEntry("advised", true);
            Gate reset = harness.instrumentation.gate(Point.RETRANSFORM, failure, 0);
            harness.release();
            afterActivation.open();
            reset.await();

            assertThat(harness.probes.status()).containsEntry("installed", 0);
            assertThat(MethodProbes.poll(old.slot, old.id)).isEqualTo(MethodProbes.ENDING);
            Thread worker = harness.probeWorker();
            harness.claim();
            Probe next = harness.start(FIRST);
            assertThat(harness.probeWorker()).isSameAs(worker);
            harness.release();
            reset.open();
            harness.joinWorkers();

            harness.assertProbesDrained();
            assertThat(harness.describe(old)).containsEntry("state", "ended");
            assertThat(harness.describe(next)).containsEntry("state", "ended").containsEntry("activatedAt", null);
            assertThat(harness.probes.status()).containsEntry("removalFailures", failure == Failure.THROW ? 1 : 0);
            if (failure == Failure.THROW) {
                assertThat(harness.describe(old).get("removal"))
                        .asString()
                        .contains("failed:", "injected RETRANSFORM failure");
            } else {
                assertThat(harness.describe(old)).containsEntry("removal", "removed");
            }
            assertThat(harness.instrumentation.operations).containsExactly("install", "reset");
        }
    }

    @Test
    void aFailedProbeInstallCannotStrandAnotherAlreadyDequeuedRequest() throws Exception {
        try (Harness harness = new Harness(false)) {
            harness.claim();
            Gate install = harness.instrumentation.gate(Point.INSTALL, Failure.THROW, 0);
            Probe failed;
            Probe next;
            synchronized (harness.probes) {
                failed = harness.start(FIRST);
                next = harness.start(SECOND);
            }
            install.await();
            assertThat(harness.probes.status()).containsEntry("pending", 0);
            Gate nextWork = harness.instrumentation.gate(Point.ENUMERATION);
            install.open();
            nextWork.await();

            assertThat(harness.describe(failed))
                    .containsEntry("state", "failed")
                    .containsEntry("activatedAt", null);
            assertThat(harness.describe(failed).get("failure"))
                    .asString()
                    .contains("install:", "Could not install class file transformer");
            assertThat(MethodProbes.poll(failed.slot, failed.id)).isEqualTo(-1);
            assertThat(harness.probes.status())
                    .containsEntry("installFailures", 1)
                    .containsEntry("installed", 0);
            harness.release();
            nextWork.open();
            harness.joinWorkers();

            harness.assertProbesDrained();
            assertThat(harness.describe(next)).containsEntry("state", "ended").containsEntry("activatedAt", null);
        }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Class<?> bean(String name) {
        Class<?> bean = new ByteBuddy()
                .subclass(Object.class)
                .name(name)
                .defineMethod("run", int.class, Modifier.PUBLIC)
                .intercept(FixedValue.value(1))
                .make()
                .load(
                        WorkerSensorLifecycleTests.class.getClassLoader(),
                        ClassLoadingStrategy.Default.WRAPPER_PERSISTENT)
                .getLoaded();
        assertThat(ApplicationMethodsSensor.testRoot(bean.getProtectionDomain()))
                .isFalse();
        return bean;
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(15_000);
        assertThat(worker.isAlive()).as("sensor worker terminated").isFalse();
    }

    enum Point {
        ENUMERATION,
        INSTALL,
        RECORDER_REMOVAL,
        RESET,
        RETRANSFORM;

        boolean matches(String method, Object[] arguments) {
            return switch (this) {
                case ENUMERATION -> method.equals("getAllLoadedClasses") && probeDequeue();
                case INSTALL ->
                    method.equals("addTransformer") && arguments[0] instanceof ResettableClassFileTransformer;
                case RECORDER_REMOVAL ->
                    method.equals("removeTransformer") && !(arguments[0] instanceof ResettableClassFileTransformer);
                case RESET ->
                    method.equals("removeTransformer") && arguments[0] instanceof ResettableClassFileTransformer;
                case RETRANSFORM -> method.equals("retransformClasses");
            };
        }

        private static boolean probeDequeue() {
            // Byte Buddy also enumerates classes during install/reset: only the sensor's direct call is a dequeue.
            return StackWalker.getInstance()
                    .walk(frames -> frames.dropWhile(
                                    frame -> !frame.getMethodName().equals("getAllLoadedClasses"))
                            .skip(1)
                            .findFirst()
                            .map(frame -> frame.getClassName().equals(MethodProbeSensor.class.getName()))
                            .orElse(false));
        }
    }

    enum Failure {
        NONE,
        FALSE,
        THROW
    }

    static final class Gate {

        final Point point;
        final Failure failure;
        final AtomicInteger skip;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        Gate(Point point, Failure failure, int skip) {
            this.point = point;
            this.failure = failure;
            this.skip = new AtomicInteger(skip);
        }

        void await() throws InterruptedException {
            assertThat(entered.await(15, TimeUnit.SECONDS))
                    .as("%s reached", point)
                    .isTrue();
        }

        void open() {
            proceed.countDown();
        }

        void block() throws InterruptedException {
            entered.countDown();
            assertThat(proceed.await(15, TimeUnit.SECONDS))
                    .as("%s released", point)
                    .isTrue();
        }
    }

    static final class GatedInstrumentation implements AutoCloseable {

        final Instrumentation actual = ByteBuddyAgent.getInstrumentation();
        final Instrumentation scoped;
        final List<String> operations = new CopyOnWriteArrayList<>();
        final List<Class<?>> retransformed = new CopyOnWriteArrayList<>();
        final List<ClassFileTransformer> registered = new CopyOnWriteArrayList<>();
        final List<Gate> gates = new ArrayList<>();
        final AtomicReference<Gate> next = new AtomicReference<>();
        final Class<?>[] types;

        GatedInstrumentation(Class<?>[] types) {
            this.types = types;
            scoped = (Instrumentation) Proxy.newProxyInstance(
                    Instrumentation.class.getClassLoader(),
                    new Class<?>[] {Instrumentation.class},
                    (proxy, method, arguments) -> {
                        if (Point.INSTALL.matches(method.getName(), arguments)) {
                            operations.add("install");
                        } else if (Point.RESET.matches(method.getName(), arguments)) {
                            operations.add("reset");
                        }
                        Gate gate = next.get();
                        if (gate != null
                                && gate.point.matches(method.getName(), arguments)
                                && gate.skip.getAndDecrement() <= 0
                                && next.compareAndSet(gate, null)) {
                            gate.block();
                            if (gate.failure == Failure.FALSE) {
                                return false;
                            }
                            if (gate.failure == Failure.THROW) {
                                String reason = "injected " + gate.point + " failure";
                                if (gate.point == Point.RETRANSFORM) {
                                    throw new UnmodifiableClassException(reason);
                                }
                                throw new IllegalStateException(reason);
                            }
                        }
                        if (method.getName().equals("getAllLoadedClasses")) {
                            return types.clone();
                        }
                        try {
                            Object result = method.invoke(actual, arguments);
                            if (method.getName().equals("addTransformer")) {
                                registered.add((ClassFileTransformer) arguments[0]);
                            } else if (method.getName().equals("removeTransformer") && Boolean.TRUE.equals(result)) {
                                registered.remove(arguments[0]);
                            } else if (method.getName().equals("retransformClasses")) {
                                retransformed.addAll(List.of((Class<?>[]) arguments[0]));
                            }
                            return result;
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }

        Gate gate(Point point) {
            return gate(point, Failure.NONE, 0);
        }

        Gate gate(Point point, Failure failure, int skip) {
            Gate gate = new Gate(point, failure, skip);
            assertThat(next.compareAndSet(null, gate))
                    .as("previous gate already entered")
                    .isTrue();
            gates.add(gate);
            return gate;
        }

        void openGates() {
            next.set(null);
            gates.forEach(Gate::open);
        }

        @Override
        public void close() throws Exception {
            for (ClassFileTransformer transformer : registered) {
                actual.removeTransformer(transformer);
            }
            actual.retransformClasses(types);
        }
    }

    static final class Ownership {

        final Supplier<Object> capture = new Supplier<>() {
            @Override
            public Object get() {
                return null;
            }
        };
        final Function<Object, AutoCloseable> reopen = new Function<>() {
            @Override
            public AutoCloseable apply(Object snapshot) {
                return null;
            }
        };
    }

    record Probe(int slot, long id) {}

    static final class Harness implements AutoCloseable {

        final boolean applicationMethods;
        final GatedInstrumentation instrumentation;
        final ApplicationMethodsSensor application;
        final MethodProbeSensor probes;
        final List<Ownership> owners = new ArrayList<>();
        final List<Thread> workers = new ArrayList<>();
        long token;
        long generation;

        Harness(boolean applicationMethods, Class<?>... beans) throws Exception {
            this.applicationMethods = applicationMethods;
            Class<?>[] types = applicationMethods
                    ? new Class<?>[] {
                        Class.forName(ApplicationMethodsSensor.PROBE),
                        Class.forName(ApplicationMethodsSensor.CODE_PATHS_PROBE)
                    }
                    : new Class<?>[] {FIRST, SECOND};
            List<Class<?>> scopedTypes = new ArrayList<>(List.of(types));
            scopedTypes.addAll(List.of(beans));
            instrumentation = new GatedInstrumentation(scopedTypes.toArray(Class<?>[]::new));
            application = new ApplicationMethodsSensor(instrumentation.scoped, false);
            probes = new MethodProbeSensor(instrumentation.scoped, false);
            // Slot retirement must be the worker's, never the bridge's elapsed-time rescue path.
            Field stuck = MethodProbes.class.getDeclaredField("stuckNanos");
            stuck.setAccessible(true);
            stuck.setLong(null, Long.MAX_VALUE);
            assertThat(AgentBridge.install(this::handle)).isTrue();
        }

        Map<String, Object> handle(Map<String, Object> request) {
            try {
                switch (String.valueOf(request.get("op"))) {
                    case "claim" -> {
                        if (applicationMethods) {
                            synchronized (application) {
                                application.claimed(
                                        ((Number) request.get("generation")).longValue(),
                                        strings(request.get("packages")),
                                        strings(request.get("beanClasses")),
                                        true,
                                        true);
                                remember(applicationWorker());
                            }
                        } else {
                            probes.runLoaders(List.of(new WeakReference<>(FIRST.getClassLoader())));
                        }
                    }
                    case "release" -> {
                        if (applicationMethods) {
                            synchronized (application) {
                                application.release();
                                remember(applicationWorker());
                            }
                        } else {
                            LockSupport.unpark(probeWorker());
                        }
                    }
                    case "refine" ->
                        application.refined(strings(request.get("packages")), strings(request.get("beanClasses")));
                    case "method-probe" -> {
                        synchronized (probes) {
                            probes.add(new MethodProbeSensor.Request(
                                    ((Number) request.get("slot")).intValue(),
                                    ((Number) request.get("id")).longValue(),
                                    String.valueOf(request.get("className")),
                                    String.valueOf(request.get("methodName")),
                                    String.valueOf(request.get("descriptor")),
                                    false,
                                    List.of(new WeakReference<>(FIRST.getClassLoader()))));
                            remember(probeWorker());
                        }
                    }
                    default -> {}
                }
                return Map.of("status", "ok");
            } catch (Exception ex) {
                throw new IllegalStateException("lifecycle fixture failed", ex);
            }
        }

        void claim() {
            claim(
                    applicationMethods ? List.of("shop") : List.of(FIRST.getPackageName(), SECOND.getPackageName()),
                    List.of());
        }

        void claim(List<String> packages, List<String> beans) {
            Ownership owner = new Ownership();
            owners.add(owner);
            Map<String, Object> answer = AgentBridge.claim(
                    Map.of(
                            "application",
                            "worker-lifecycle",
                            "mode",
                            "dev",
                            "packages",
                            packages,
                            "beanClasses",
                            beans,
                            "sensors",
                            List.of("inventory", "code-paths")),
                    owner.capture,
                    owner.reopen);
            assertThat(answer).containsEntry("status", AgentBridge.ARMED);
            token = ((Number) answer.get("token")).longValue();
            generation = ((Number) answer.get("generation")).longValue();
        }

        void installApplication() throws Exception {
            claim();
            joinWorkers();
            assertApplicationInstalled();
            instrumentation.operations.clear();
            instrumentation.retransformed.clear();
        }

        void release() {
            assertThat(AgentBridge.release("worker-lifecycle", "dev")).containsEntry("status", AgentBridge.RELEASED);
        }

        void refine(List<String> packages, List<String> beans) {
            assertThat(AgentBridge.refine(token, Map.of("packages", packages, "beanClasses", beans)))
                    .containsEntry("status", AgentBridge.ARMED);
        }

        Probe start(Class<?> type) {
            String method = type.getName() + "#run()V";
            assertThat(CodeInventory.methodId(method)).isNotNegative();
            Map<String, Object> answer = MethodProbes.start(token, Map.of("method", method));
            assertThat(answer).containsEntry("status", MethodProbes.STARTED);
            long id = ((Number) ((Map<?, ?>) answer.get("probe")).get("id")).longValue();
            for (int slot = 0; slot < MethodProbes.SLOTS; slot++) {
                if (MethodProbes.poll(slot, id) >= 0) {
                    return new Probe(slot, id);
                }
            }
            throw new AssertionError("started probe has no slot");
        }

        Map<String, Object> describe(Probe probe) {
            return MethodProbes.list().stream()
                    .filter(row -> Long.valueOf(probe.id).equals(row.get("id")))
                    .findFirst()
                    .orElseThrow();
        }

        Thread applicationWorker() throws Exception {
            synchronized (application) {
                return (Thread) get(application, "worker");
            }
        }

        Thread probeWorker() throws Exception {
            synchronized (probes) {
                return (Thread) get(probes, "worker");
            }
        }

        void remember(Thread worker) {
            if (worker != null && !workers.contains(worker)) {
                workers.add(worker);
            }
        }

        void joinWorkers() throws InterruptedException {
            for (Thread worker : workers) {
                join(worker);
            }
        }

        void assertApplicationUnverified() {
            assertThat(application.inventoryStatus()).containsEntry("selfTestPassed", false);
            assertThat(application.codePathsStatus()).containsEntry("selfTestPassed", false);
        }

        void assertApplicationInstalled() throws Exception {
            assertThat(application.inventoryStatus())
                    .containsEntry("state", "installed")
                    .containsEntry("selfTestPassed", true)
                    .containsEntry("idle", true);
            assertThat(application.codePathsStatus())
                    .containsEntry("state", "installed")
                    .containsEntry("selfTestPassed", true);
            assertThat(get(application, "transformer")).isNotNull();
            assertThat(get(application, "recording")).isEqualTo(true);
        }

        void assertApplicationReleased() throws Exception {
            assertThat(application.inventoryStatus())
                    .containsEntry("state", "released")
                    .containsEntry("selfTestError", null);
            assertThat(application.codePathsStatus()).containsEntry("selfTestError", null);
            assertApplicationRemoved();
        }

        void assertApplicationRemoved() throws Exception {
            assertThat(application.inventoryStatus()).containsEntry("idle", true);
            assertApplicationUnverified();
            assertThat(get(application, "transformer")).isNull();
            assertThat(get(application, "recording")).isEqualTo(false);
            assertThat(get(application, "matching")).isEqualTo(List.of());
            assertThat(get(application, "beanClasses")).isEqualTo(Set.of());
        }

        void assertApplicationResetFailed() throws Exception {
            assertThat(application.inventoryStatus())
                    .containsEntry("state", "release-failed")
                    .containsEntry("idle", true);
            assertThat(application.codePathsStatus()).containsEntry("state", "release-failed");
            assertApplicationUnverified();
            assertThat(get(application, "transformer")).isNull();
            assertThat(get(application, "stuck")).isEqualTo(true);
            assertThat(get(application, "releasing")).isEqualTo(true);
            assertThat(CodeInventory.snapshot(generation)).containsEntry("disabled", true);
            assertThat(CodeInventory.status()).containsEntry("disabledReason", "its classes could not be restored");
            assertThat(CodePaths.status())
                    .containsEntry("active", false)
                    .containsEntry("disabledReason", "its classes could not be restored");
        }

        void assertProbesDrained() {
            assertThat(probes.status())
                    .containsEntry("pending", 0)
                    .containsEntry("installed", 0)
                    .containsEntry("running", false);
            assertThat(MethodProbes.status()).containsEntry("inUse", 0).containsEntry("reaped", 0L);
        }

        @Override
        public void close() throws Exception {
            instrumentation.openGates();
            try {
                release();
                joinWorkers();
            } finally {
                try {
                    instrumentation.close();
                } finally {
                    Reference.reachabilityFence(owners);
                }
            }
        }

        private static List<String> strings(Object value) {
            return ((List<?>) value).stream().map(String::valueOf).toList();
        }
    }
}
