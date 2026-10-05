package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

/**
 * Method probes' agent side (PLAN-v2 §5.14, M5-8): installs and removes one Byte Buddy transformer per probe, each
 * advising one method of one class with {@link MethodProbeAdvice}, or {@link MethodProbeShapesAdvice} for a probe that
 * records argument and return shapes, off the caller's thread, on one agent thread that
 * runs only while a probe is pending or installed.
 *
 * <p><b>Which class.</b> Only the copies of the class that the current run's class loaders defined: those on the
 * context class loader chain of the thread that claimed or refined (a DevTools restart's or a Quarkus reload's), so a
 * previous run's copy, still loaded, is never retransformed and never feeds that run's stale state into the shared
 * application-methods transformer's bookkeeping. While the run's loaders have not loaded the class yet, the probe waits
 * for it, active, and advises it as it loads; its window bounds the wait. The method is resolved from the class file,
 * without loading anything: its descriptor, when the request named none, must be unique.
 *
 * <p><b>Install and removal.</b> {@link MethodProbes#activate} starts the probe's window just before the install. The
 * transformer uses {@code DECORATE} and retransformation, like the application-methods transformer, whose inventory
 * and code-paths advice the JVM re-applies to the class at every retransformation, before or after this one. A failure
 * Byte Buddy sees, a retransformation the JVM rejects (through the redefinition listener, since Byte Buddy's reset
 * reports success even then), or no method advised fails the probe and removes the transformer. The worker polls each
 * installed probe every {@value #POLL_MILLIS} ms and removes it once the bridge ended it (its invocations, its window, a
 * stop, or the end of its run), reporting the removal, or why it failed, to the bridge. Whatever the removal does, the
 * bound holds by the probe's state in the bridge.
 */
final class MethodProbeSensor {

    static final long POLL_MILLIS = 50;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final TransformStats stats = new TransformStats();
    private final List<Request> pending = new ArrayList<Request>();
    private final Map<Long, Installed> installed = new ConcurrentHashMap<Long, Installed>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger removals = new AtomicInteger();
    private final AtomicInteger removalFailures = new AtomicInteger();
    private volatile String lastFailure;
    /** The current run's class loaders, weakly, as the handler last saw them at a claim or refine. */
    private volatile List<WeakReference<ClassLoader>> runLoaders = Collections.emptyList();

    private boolean warmed;
    private Thread worker;

    MethodProbeSensor(Instrumentation instrumentation, boolean privileged) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
    }

    /** The current run's class loaders, at each claim and refine. */
    void runLoaders(List<WeakReference<ClassLoader>> loaders) {
        runLoaders = loaders == null ? Collections.<WeakReference<ClassLoader>>emptyList() : loaders;
    }

    /** Queues a probe the bridge placed, for the worker to install. */
    synchronized void add(Request request) {
        pending.add(request);
        if (worker == null) {
            Thread thread = AgentThreads.newThread("bootui-agent-method-probes", new Worker(), privileged);
            // Assigned once started: a thread that could not start must not stand for a worker for the JVM's life.
            thread.start();
            worker = thread;
        } else {
            LockSupport.unpark(worker);
        }
    }

    /** Status for the agent's status: counters and the installed probes; JDK types only. */
    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("installed", Integer.valueOf(installed.size()));
        synchronized (this) {
            map.put("pending", Integer.valueOf(pending.size()));
            map.put("running", Boolean.valueOf(worker != null));
        }
        map.put("installFailures", Integer.valueOf(failures.get()));
        map.put("removals", Integer.valueOf(removals.get()));
        map.put("removalFailures", Integer.valueOf(removalFailures.get()));
        map.put("lastFailure", lastFailure);
        stats.putInto(map);
        return map;
    }

    private synchronized List<Request> takePending() {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<Request> taken = new ArrayList<Request>(pending);
        pending.clear();
        return taken;
    }

    /** Whether the worker may stop: nothing pending, nothing installed. Clears the worker when so. */
    private synchronized boolean done() {
        if (pending.isEmpty() && installed.isEmpty()) {
            worker = null;
            return true;
        }
        return false;
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    for (Request request : takePending()) {
                        try {
                            install(request);
                        } catch (Throwable ex) {
                            // One failing probe never strands the others taken with it.
                            fail(request, "install: " + ex);
                        }
                    }
                    for (Installed probe : new ArrayList<Installed>(installed.values())) {
                        if (probe.problem != null) {
                            // A class that loaded after the probe started, and could not be advised.
                            installed.remove(Long.valueOf(probe.request.id));
                            reset(probe);
                            fail(probe.request, probe.problem);
                        } else if (MethodProbes.poll(probe.request.slot, probe.request.id) != MethodProbes.ACTIVE) {
                            remove(probe);
                        }
                    }
                } catch (Throwable ex) {
                    lastFailure = "method probes: " + ex;
                    AgentBridge.message("the BootUI agent's method probes failed: " + ex);
                }
                if (done()) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS));
            }
        }
    }

    // ---- install ---------------------------------------------------------------------------------------------------

    private void install(Request request) {
        if (!warmed) {
            MethodProbes.warm();
            warmed = true;
        }
        List<Class<?>> loaded = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals(request.className)) {
                loaded.add(type);
            }
        }
        List<ClassLoader> runLoaders = request.loaders();
        List<Class<?>> targets = new ArrayList<Class<?>>();
        for (Class<?> type : loaded) {
            if (containsIdentity(runLoaders, type.getClassLoader())) {
                targets.add(type);
            }
        }
        if (runLoaders.isEmpty()) {
            // The run's class loaders are unknown, as when they were all collected: every loaded copy.
            targets.addAll(loaded);
        }
        for (Class<?> type : targets) {
            if (!instrumentation.isModifiableClass(type)) {
                fail(request, "the JVM cannot retransform " + request.className);
                return;
            }
        }
        List<ClassLoader> matching = new ArrayList<ClassLoader>();
        if (targets.isEmpty()) {
            matching.addAll(runLoaders);
        } else {
            for (Class<?> type : targets) {
                matching.add(type.getClassLoader());
            }
        }
        String descriptor;
        try {
            descriptor = resolve(request, matching);
        } catch (IllegalArgumentException ex) {
            fail(request, ex.getMessage());
            return;
        }
        if (MethodProbes.poll(request.slot, request.id) != MethodProbes.STARTING) {
            // Stopped, or its run ended, before it was installed: nothing to remove.
            MethodProbes.removed(request.slot, request.id, null);
            return;
        }
        // Installed before it is active: a probe reported active must advise a class its run loads from then on. Its
        // advice records nothing until activation.
        Installed probe = new Installed(request, descriptor, matching);
        Install action = new Install(probe);
        try {
            probe.transformer =
                    privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
        } catch (Throwable ex) {
            fail(request, "install: " + ex);
            return;
        }
        installed.put(Long.valueOf(request.id), probe);
        String problem = probe.problem;
        if (problem == null && !targets.isEmpty() && !probe.advised) {
            problem = "the JVM did not apply the probe to " + request.className;
        }
        if (problem != null) {
            installed.remove(Long.valueOf(request.id));
            reset(probe);
            fail(request, problem);
        } else if (!MethodProbes.activate(request.slot, request.id)) {
            // Stopped, or its run ended, while it was being installed.
            remove(probe);
        } else if (probe.advised) {
            // Only now: the transformer ran inside the retransformation, before the JVM installed the new code.
            MethodProbes.advised(request.slot, request.id, descriptor);
        }
    }

    /**
     * The probed method's descriptor, read from the class file of the first loader that has it, without loading or
     * initializing anything: the request's when it names one and the class file has it; the only method of that name
     * otherwise. Without a class file to read, the request's descriptor is trusted, and a request without one fails.
     */
    static String resolve(Request request, List<ClassLoader> loaders) {
        TypeDescription type = null;
        for (ClassLoader loader : loaders) {
            try {
                TypePool.Resolution resolution = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(loader))
                        .describe(request.className);
                if (resolution.isResolved()) {
                    type = resolution.resolve();
                    break;
                }
            } catch (Throwable ex) {
                // An unreadable class file: try the next loader.
            }
        }
        if (type == null) {
            if (request.descriptor == null) {
                throw new IllegalArgumentException("the class file of " + request.className
                        + " cannot be read to resolve " + request.methodName + ": name its descriptor");
            }
            return request.descriptor;
        }
        List<String> found = new ArrayList<String>();
        for (MethodDescription method : type.getDeclaredMethods()) {
            if (method.isMethod()
                    && !method.isAbstract()
                    && !method.isNative()
                    && method.getInternalName().equals(request.methodName)
                    && (request.descriptor == null || request.descriptor.equals(method.getDescriptor()))) {
                found.add(method.getDescriptor());
            }
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(request.className + " declares no method "
                    + request.methodName + (request.descriptor == null ? "" : request.descriptor)
                    + " with code to probe");
        }
        throw new IllegalArgumentException(
                request.methodName + " is overloaded in " + request.className + ": name one of " + found);
    }

    private final class Install implements PrivilegedAction<ResettableClassFileTransformer> {

        private final Installed probe;

        Install(Installed probe) {
            this.probe = probe;
        }

        @Override
        public ResettableClassFileTransformer run() {
            Advice advice = Advice.withCustomMapping()
                    .bind(MethodProbeAdvice.Slot.class, Integer.valueOf(probe.request.slot))
                    .bind(MethodProbeAdvice.ProbeId.class, Long.valueOf(probe.request.id))
                    .to(probe.request.shapes ? MethodProbeShapesAdvice.class : MethodProbeAdvice.class);
            return stats.configure(new AgentBuilder.Default(), new Rejections(probe))
                    .with(new Outcomes(probe))
                    .assureReadEdgeTo(instrumentation, MethodProbes.class)
                    .ignore(AgentInstaller.ignored())
                    .type(new Target(probe))
                    .transform(new Transform(probe, advice))
                    .installOn(instrumentation);
        }
    }

    // ---- removal ---------------------------------------------------------------------------------------------------

    private void remove(Installed probe) {
        installed.remove(Long.valueOf(probe.request.id));
        if (MethodProbes.END_RUN.equals(MethodProbes.endReason(probe.request.slot, probe.request.id))
                && !probe.inRun(runLoaders)) {
            // A previous run's copy, as after a DevTools restart or a Quarkus reload: only the transformer goes, so the
            // shared application-methods bookkeeping never sees that copy retransformed beside the new run's loading.
            String problem = deregister(probe);
            removals.incrementAndGet();
            MethodProbes.removed(
                    probe.request.slot,
                    probe.request.id,
                    problem != null
                            ? "failed: " + problem
                            : "deregistered: the previous run's copy of the class keeps the advice, inert, until it"
                                    + " is unloaded");
            return;
        }
        String problem = reset(probe);
        removals.incrementAndGet();
        if (problem != null) {
            removalFailures.incrementAndGet();
            lastFailure = problem;
        }
        MethodProbes.removed(probe.request.slot, probe.request.id, problem == null ? null : "failed: " + problem);
    }

    /** Removes the transformer without retransforming anything: {@code null}, or why it could not be removed. */
    private String deregister(Installed probe) {
        ResettableClassFileTransformer transformer = probe.transformer;
        probe.transformer = null;
        probe.removing = true;
        if (transformer == null) {
            return null;
        }
        try {
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED);
            return null;
        } catch (Throwable ex) {
            removalFailures.incrementAndGet();
            lastFailure = "the transformer could not be removed: " + ex;
            return lastFailure;
        }
    }

    /** Removes the transformer and restores the class: {@code null}, or why the JVM did not restore it. */
    private String reset(Installed probe) {
        ResettableClassFileTransformer transformer = probe.transformer;
        probe.transformer = null;
        if (transformer == null) {
            return null;
        }
        Rejections rejections = new Rejections(probe);
        probe.removing = true;
        try {
            transformer.reset(
                    instrumentation,
                    AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                    AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                    new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                            AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                            stats.redefinitionFailures(),
                            rejections));
        } catch (Throwable ex) {
            return "the transformer could not be removed: " + ex;
        }
        return rejections.error;
    }

    private void fail(Request request, String reason) {
        failures.incrementAndGet();
        lastFailure = request.className + "#" + request.methodName + ": " + reason;
        MethodProbes.failed(request.slot, request.id, reason);
    }

    private static boolean containsIdentity(List<ClassLoader> loaders, ClassLoader loader) {
        for (ClassLoader candidate : loaders) {
            if (candidate == loader) {
                return true;
            }
        }
        return false;
    }

    // ---- matching --------------------------------------------------------------------------------------------------

    /** The probed class, by name, in one of the probe's class loaders. */
    static final class Target implements AgentBuilder.RawMatcher {

        private final Installed probe;

        Target(Installed probe) {
            this.probe = probe;
        }

        @Override
        public boolean matches(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain) {
            return type.getName().equals(probe.request.className) && probe.matches(classLoader);
        }
    }

    /** Advises the probed method, unless the probe is being removed. */
    static final class Transform implements AgentBuilder.Transformer {

        private final Installed probe;
        private final Advice advice;

        Transform(Installed probe, Advice advice) {
            this.probe = probe;
            this.advice = advice;
        }

        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                ProtectionDomain protectionDomain) {
            if (probe.removing) {
                return builder;
            }
            probe.matched.set(0);
            return builder.visit(advice.on(new Method(probe)));
        }
    }

    /** The probed method: its name and resolved descriptor, with code. Counts its matches. */
    static final class Method extends ElementMatcher.Junction.AbstractBase<MethodDescription> {

        private final Installed probe;

        Method(Installed probe) {
            this.probe = probe;
        }

        @Override
        public boolean matches(MethodDescription method) {
            boolean match = method.isMethod()
                    && !method.isAbstract()
                    && !method.isNative()
                    && method.getInternalName().equals(probe.request.methodName)
                    && method.getDescriptor().equals(probe.descriptor);
            if (match) {
                probe.matched.incrementAndGet();
            }
            return match;
        }
    }

    /** Whether each transformation of the probed class advised the method, or why it failed. */
    static final class Outcomes extends AgentBuilder.Listener.Adapter {

        private final Installed probe;

        Outcomes(Installed probe) {
            this.probe = probe;
        }

        @Override
        public void onTransformation(
                TypeDescription type, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamic) {
            if (probe.removing || !type.getName().equals(probe.request.className)) {
                return;
            }
            if (probe.matched.get() == 1) {
                probe.advised = true;
                if (!loaded) {
                    // A class being defined runs nothing before its definition completes. A retransformed one is
                    // reported once the retransformation returned.
                    MethodProbes.advised(probe.request.slot, probe.request.id, probe.descriptor);
                }
            } else if (probe.problem == null) {
                probe.problem = "no method " + probe.request.methodName + probe.descriptor + " was advised in "
                        + type.getName();
            }
        }

        @Override
        public void onError(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable error) {
            if (!probe.removing && typeName.equals(probe.request.className) && probe.problem == null) {
                probe.problem = "the probe could not be applied to " + typeName + ": " + error;
            }
        }
    }

    /** A retransformation of the probed class the JVM rejected, which Byte Buddy's own listeners do not report. */
    static final class Rejections extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

        private final Installed probe;
        volatile String error;

        Rejections(Installed probe) {
            this.probe = probe;
        }

        @Override
        public Iterable<? extends List<Class<?>>> onError(
                int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
            for (Class<?> type : batch) {
                if (type.getName().equals(probe.request.className) && error == null) {
                    error = "the JVM rejected retransforming " + type.getName() + ": " + throwable;
                    if (!probe.removing && probe.problem == null) {
                        probe.problem = error;
                    }
                }
            }
            return Collections.emptyList();
        }
    }

    // ---- state -----------------------------------------------------------------------------------------------------

    /** A probe the bridge placed: its slot, id, and method, and the run's class loaders when it was asked for. */
    static final class Request {

        final int slot;
        final long id;
        final String className;
        final String methodName;
        final String descriptor;
        /** Whether the probe records argument and return shapes, with {@link MethodProbeShapesAdvice}. */
        final boolean shapes;

        private final List<WeakReference<ClassLoader>> loaders;

        Request(
                int slot,
                long id,
                String className,
                String methodName,
                String descriptor,
                boolean shapes,
                List<WeakReference<ClassLoader>> loaders) {
            this.slot = slot;
            this.id = id;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.shapes = shapes;
            this.loaders = loaders;
        }

        List<ClassLoader> loaders() {
            List<ClassLoader> list = new ArrayList<ClassLoader>();
            for (WeakReference<ClassLoader> reference : loaders) {
                ClassLoader loader = reference.get();
                if (loader != null) {
                    list.add(loader);
                }
            }
            return list;
        }
    }

    /** An installed probe. Its class loaders are held weakly, so it never keeps a run's loader reachable. */
    static final class Installed {

        final Request request;
        final String descriptor;
        final List<WeakReference<ClassLoader>> loaders = new ArrayList<WeakReference<ClassLoader>>();
        final AtomicInteger matched = new AtomicInteger();
        volatile ResettableClassFileTransformer transformer;
        volatile boolean advised;
        volatile boolean removing;
        volatile String problem;

        Installed(Request request, String descriptor, List<ClassLoader> matching) {
            this.request = request;
            this.descriptor = descriptor;
            for (ClassLoader loader : matching) {
                loaders.add(new WeakReference<ClassLoader>(loader));
            }
        }

        /** Whether one of the probe's class loaders is one of {@code run}'s: with none known, yes. */
        boolean inRun(List<WeakReference<ClassLoader>> run) {
            if (loaders.isEmpty()) {
                return true;
            }
            for (WeakReference<ClassLoader> mine : loaders) {
                ClassLoader loader = mine.get();
                for (WeakReference<ClassLoader> current : run) {
                    if (loader != null && current.get() == loader) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Whether {@code loader} is one of the probe's: with none known, any. */
        boolean matches(ClassLoader loader) {
            if (loaders.isEmpty()) {
                return true;
            }
            for (WeakReference<ClassLoader> reference : loaders) {
                if (reference.get() == loader) {
                    return true;
                }
            }
            return false;
        }
    }
}
