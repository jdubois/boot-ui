package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.Exclusions;
import java.lang.instrument.Instrumentation;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
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
    private final TransformStats stats = new TransformStats();
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
        stats.putInto(map);
        map.put("durationMillis", Long.valueOf(durationMillis));
        synchronized (this) {
            map.put("running", Boolean.valueOf(worker != null));
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
        return stats.configure(new AgentBuilder.Default())
                .ignore(ignored())
                .type(new ProbeMatcher())
                .transform(new ProbeTransformer(advice));
    }

    /**
     * §5.13: BootUI itself, the agent, Byte Buddy, generated proxies, and the JDK are never instrumented, by name, as the
     * bridge's {@link Exclusions} lists them: one list the engine's Code Inventory reads too.
     */
    static ElementMatcher.Junction<TypeDescription> ignored() {
        ElementMatcher.Junction<TypeDescription> matcher = ElementMatchers.none();
        for (String prefix : Exclusions.prefixes()) {
            matcher = matcher.or(ElementMatchers.<TypeDescription>nameStartsWith(prefix));
        }
        for (String part : Exclusions.contains()) {
            matcher = matcher.or(ElementMatchers.<TypeDescription>nameContains(part));
        }
        for (String suffix : Exclusions.suffixes()) {
            matcher = matcher.or(ElementMatchers.<TypeDescription>nameEndsWith(suffix));
        }
        return matcher;
    }

    private void failure(String text) {
        stats.failure(text);
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
                            stats.redefinitionFailures()));
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
                    stats.skipped(type.getName(), ex);
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
}
