package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import java.lang.instrument.Instrumentation;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

/**
 * Builds and installs the agent's one {@code AgentBuilder} with PLAN-v2 D32's rules: {@code DECORATE}, batches of 64
 * split down to one class on failure, a listener naming every class still failing, a type pool falling back to
 * bootstrap-loaded types only, the ignore list of §5.13, and an install from a privileged agent thread. Install,
 * retransformation of packages added later, and release are jobs run one at a time, off the caller's thread, by a single
 * agent thread that ends when no job is left. A release stops adding advice at once, then restores every transformed
 * class: the matcher keeps the installed packages until then, since Byte Buddy's reset finds the classes to restore
 * through it. M5-1 has no production sensor: only the diagnostic probe, enabled by {@link AgentTestHook}, uses it.
 */
final class AgentInstaller {

    private static final int FAILURES = 20;

    private final Instrumentation instrumentation;
    private final AgentTestHook hook;
    private final Class<?> advice;
    private final AtomicInteger transformed = new AtomicInteger();
    private final AtomicInteger retransformed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger fallbacks = new AtomicInteger();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> fallbackTypes = Collections.synchronizedList(new ArrayList<String>());
    private final ArrayDeque<Runnable> jobs = new ArrayDeque<Runnable>();
    /** The packages wanted by the claims since the last release. */
    private volatile List<String> probePackages = Collections.emptyList();
    /** The packages the installed transformer matches, until its reset completes. */
    private volatile List<String> installedPackages = Collections.emptyList();

    private volatile ResettableClassFileTransformer transformer;
    private volatile boolean releasing;
    private volatile String state = "idle";
    private volatile long durationMillis = -1;
    private Thread worker;

    AgentInstaller(Instrumentation instrumentation, AgentTestHook hook) {
        this.instrumentation = instrumentation;
        this.hook = hook;
        this.advice = hook.throwingProbe() ? ThrowingProbeAdvice.class : ProbeAdvice.class;
    }

    /** Installs the probe for these packages, or adds them to the installed probe and retransforms what is loaded. */
    synchronized void install(List<String> packages) {
        if (packages.isEmpty()) {
            return;
        }
        releasing = false;
        List<String> added = merge(packages);
        if (added.isEmpty()) {
            return;
        }
        submit(new Install(added));
    }

    /** Stops matching at once, then restores every transformed class, after any job still running. */
    synchronized void release() {
        releasing = true;
        probePackages = Collections.emptyList();
        submit(new Release());
    }

    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("state", state);
        map.put("advice", advice.getSimpleName());
        map.put("probePackages", new ArrayList<String>(probePackages));
        map.put("transformed", Integer.valueOf(transformed.get()));
        map.put("retransformed", Integer.valueOf(retransformed.get()));
        map.put("failed", Integer.valueOf(failed.get()));
        map.put("skipped", Integer.valueOf(skipped.get()));
        map.put("poolFallbacks", Integer.valueOf(fallbacks.get()));
        map.put("durationMillis", Long.valueOf(durationMillis));
        synchronized (this) {
            map.put("running", Boolean.valueOf(worker != null));
        }
        synchronized (failures) {
            map.put("failures", new ArrayList<String>(failures));
        }
        synchronized (fallbackTypes) {
            map.put("fallbackTypes", new ArrayList<String>(fallbackTypes));
        }
        return map;
    }

    private void submit(Runnable job) {
        jobs.add(job);
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-worker", new Worker(), hook.privilegedInstall());
            worker.start();
        }
    }

    private synchronized Runnable next() {
        Runnable job = jobs.poll();
        if (job == null) {
            worker = null;
        }
        return job;
    }

    private synchronized List<String> merge(List<String> packages) {
        List<String> merged = new ArrayList<String>(probePackages);
        List<String> added = new ArrayList<String>();
        for (String name : packages) {
            if (!merged.contains(name)) {
                merged.add(name);
                added.add(name);
            }
        }
        probePackages = Collections.unmodifiableList(merged);
        return added;
    }

    private AgentBuilder builder() {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64))
                .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting())
                .with(new RedefinitionFailures())
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(new BootstrapFallbackPoolStrategy())
                .with(new Transformations())
                .ignore(ignored())
                .type(new ProbeMatcher())
                .transform(new ProbeTransformer(advice));
    }

    /** §5.13: BootUI itself, the agent, Byte Buddy, generated proxies, and the JDK are never instrumented by a probe. */
    static ElementMatcher.Junction<TypeDescription> ignored() {
        return ElementMatchers.<TypeDescription>nameStartsWith("io.github.jdubois.bootui.agent.")
                .or(ElementMatchers.nameStartsWith("io.github.jdubois.bootui.engine."))
                .or(ElementMatchers.nameStartsWith("io.github.jdubois.bootui.core."))
                .or(ElementMatchers.nameStartsWith("io.github.jdubois.bootui.spi."))
                .or(ElementMatchers.nameStartsWith("io.github.jdubois.bootui.autoconfigure."))
                .or(ElementMatchers.nameStartsWith("io.github.jdubois.bootui.quarkus."))
                .or(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .or(ElementMatchers.nameStartsWith("java."))
                .or(ElementMatchers.nameStartsWith("javax."))
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("sun."))
                .or(ElementMatchers.nameStartsWith("com.sun."))
                .or(ElementMatchers.nameContains("$$"))
                .or(ElementMatchers.nameContains("$HibernateProxy$"))
                .or(ElementMatchers.nameEndsWith("_Subclass"))
                .or(ElementMatchers.nameEndsWith("_ClientProxy"))
                .or(ElementMatchers.nameEndsWith("_Bean"))
                .or(ElementMatchers.nameContains("$MockitoMock$"))
                .or(ElementMatchers.nameContains("$ByteBuddy$"))
                .or(ElementMatchers.nameContains("$Proxy"))
                .or(ElementMatchers.nameStartsWith("io.opentelemetry.javaagent."))
                .or(ElementMatchers.nameStartsWith("com.intellij.rt."))
                .or(ElementMatchers.nameStartsWith("org.jacoco.agent.rt."));
    }

    private void failure(String text) {
        synchronized (failures) {
            if (failures.size() < FAILURES) {
                failures.add(text);
            }
        }
    }

    /** Runs the queued jobs one at a time; a failing job is reported and the next one still runs. */
    final class Worker implements Runnable {

        @Override
        public void run() {
            Runnable job;
            while ((job = next()) != null) {
                try {
                    job.run();
                } catch (Throwable ex) {
                    state = "failed";
                    failure("install: " + ex);
                    AgentBridge.message("the BootUI agent could not install its transformer: " + ex);
                }
            }
        }
    }

    /** The first install retransforms everything loaded that matches; later ones only the classes of added packages. */
    final class Install implements Runnable {

        private final List<String> added;

        Install(List<String> added) {
            this.added = added;
        }

        @Override
        public void run() {
            if (releasing) {
                return;
            }
            installedPackages = probePackages;
            if (transformer != null) {
                retransform(added);
                return;
            }
            long started = System.nanoTime();
            state = "installing";
            InstallAction action = new InstallAction();
            transformer = hook.privilegedInstall()
                    ? (ResettableClassFileTransformer) AgentThreads.privileged(action)
                    : action.run();
            durationMillis = (System.nanoTime() - started) / 1_000_000L;
            state = "installed";
        }
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        @Override
        public ResettableClassFileTransformer run() {
            return builder().installOn(instrumentation);
        }
    }

    /** Restores every transformed class, in batches split on failure; a class that cannot be restored is named. */
    final class Release implements Runnable {

        @Override
        public void run() {
            ResettableClassFileTransformer installed = transformer;
            transformer = null;
            if (installed == null) {
                return;
            }
            boolean restored = installed.reset(
                    instrumentation,
                    AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                    AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                    new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                            AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                            new RedefinitionFailures()));
            installedPackages = Collections.emptyList();
            state = restored ? "released" : "release-failed";
        }
    }

    private void retransform(List<String> packages) {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (inPackages(type.getName(), packages) && instrumentation.isModifiableClass(type)) {
                try {
                    instrumentation.retransformClasses(type);
                } catch (Throwable ex) {
                    skipped.incrementAndGet();
                    failure(type.getName() + ": " + ex);
                }
            }
        }
    }

    static boolean inPackages(String name, List<String> packages) {
        for (String prefix : packages) {
            if (name.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Matches the probe's current packages, so packages added later apply to classes loaded later. */
    final class ProbeMatcher extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

        @Override
        public boolean matches(TypeDescription target) {
            return !target.isInterface() && inPackages(target.getName(), installedPackages);
        }
    }

    /** Adds no advice once a release is requested, so a class loaded while it waits is left alone. */
    final class ProbeTransformer implements AgentBuilder.Transformer {

        private final Class<?> advice;

        ProbeTransformer(Class<?> advice) {
            this.advice = advice;
        }

        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                ProtectionDomain protectionDomain) {
            if (releasing) {
                return builder;
            }
            return builder.visit(Advice.to(advice)
                    .on(ElementMatchers.isMethod()
                            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                            .and(ElementMatchers.not(ElementMatchers.isNative()))
                            .and(ElementMatchers.not(ElementMatchers.isSynthetic()))));
        }
    }

    final class Transformations extends AgentBuilder.Listener.Adapter {

        @Override
        public void onTransformation(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            transformed.incrementAndGet();
            if (loaded) {
                retransformed.incrementAndGet();
            }
        }

        @Override
        public void onError(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable error) {
            failed.incrementAndGet();
            failure(typeName + ": " + error);
        }
    }

    /** Byte Buddy swallows retransformation failures by default: count and name each class still failing alone. */
    final class RedefinitionFailures extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

        @Override
        public Iterable<? extends List<Class<?>>> onError(
                int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
            if (batch.size() == 1) {
                skipped.incrementAndGet();
                failure(batch.get(0).getName() + ": " + throwable);
            }
            return Collections.emptyList();
        }
    }

    /**
     * Another agent can add bootstrap types it appended at runtime (OpenTelemetry's {@code VirtualFieldInstalledMarker})
     * to a class as it loads; live-phase bootstrap appends are not readable as resources. Falls back to the loaded type,
     * through the bootstrap class loader only: loading application classes from a transformer risks circularity skips
     * and class loader deadlocks.
     */
    final class BootstrapFallbackPoolStrategy implements AgentBuilder.PoolStrategy {

        @Override
        public TypePool typePool(ClassFileLocator locator, ClassLoader classLoader) {
            return new BootstrapFallbackPool(locator);
        }

        @Override
        public TypePool typePool(ClassFileLocator locator, ClassLoader classLoader, String name) {
            return new BootstrapFallbackPool(locator);
        }
    }

    final class BootstrapFallbackPool extends TypePool.Default {

        BootstrapFallbackPool(ClassFileLocator locator) {
            super(new TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST);
        }

        @Override
        protected Resolution doDescribe(String name) {
            Resolution resolution = super.doDescribe(name);
            if (resolution.isResolved()) {
                return resolution;
            }
            try {
                Class<?> type = Class.forName(name, false, null);
                if (fallbacks.incrementAndGet() <= FAILURES) {
                    fallbackTypes.add(name);
                }
                return new Resolution.Simple(TypeDescription.ForLoadedType.of(type));
            } catch (Throwable ex) {
                return resolution;
            }
        }
    }
}
