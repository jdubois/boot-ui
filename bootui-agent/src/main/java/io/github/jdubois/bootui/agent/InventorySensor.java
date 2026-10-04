package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * The {@code inventory} sensor (PLAN-v2 §5.15, M5-3): which application methods ran in this run, and which code sources
 * loaded classes. Its own transformer adds, with {@code DECORATE}, one entry advice ({@link InventoryAdvice}) to every
 * non-abstract, non-native method and constructor of the classes in the claimed packages, except static initializers,
 * methods whose names start with {@code $}, the classes §5.13 never instruments (BootUI, the agent, Byte Buddy, CGLIB
 * and AOT proxies, ArC's generated beans, Hibernate proxies), synthetic classes, and classes loaded from a test root
 * ({@code test-classes}, Gradle's {@code build/classes/<language>/test}). Claimed classes already loaded are
 * retransformed when it installs, and at each claim and refine, those of its packages loaded but not yet instrumented
 * (in a class loader the transformer had not yet matched them in), marked late since they may have run unseen. A
 * second, plain {@code ClassFileTransformer} that never transforms counts the classes each code source defines.
 *
 * <p>The transformer matches the union of every package claimed since it was installed, not only the current claim's:
 * a narrower later claim neither leaves a refined package's classes in a new class loader uninstrumented, nor hides the
 * classes it instrumented from Byte Buddy's reset, which finds the classes to restore through the same matchers. The
 * union is cleared only once a release restored them.
 *
 * <p>It installs once, off the claiming thread, and self-tests with a bundled probe class whose advice must reach the
 * bridge. A failed self-test stops only this sensor: the bridge stops recording for the claim at once, then the
 * transformers are removed, and when that fails too, the bridge stops it for good. A release removes both transformers
 * and restores every instrumented class.
 */
final class InventorySensor {

    /** The self-test probe: instrumented whatever the claimed packages, and named by string so it loads late. */
    static final String PROBE = "io.github.jdubois.bootui.agent.InventoryProbe";

    /** Code-source locations of test roots, whose classes are never instrumented. */
    static final String[] TEST_ROOTS = {
        "/test-classes/", "/build/classes/java/test/", "/build/classes/kotlin/test/", "/build/classes/groovy/test/"
    };

    /** Every hook: its id, what it covers, and its kind. */
    static final String[][] HOOKS = {
        {"method entry", "(claimed packages)", "record"},
        {"class load", "(every class)", "record"}
    };

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;
    private static final int REFINE = 4;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final TransformStats stats = new TransformStats();
    /** Method ids matched while a type is transformed, until its outcome is known. */
    private final ConcurrentHashMap<String, IdList> pending = new ConcurrentHashMap<String, IdList>();
    /** Method ids of each instrumented type, so a retransformation the JVM rejects marks them failed. */
    private final ConcurrentHashMap<String, int[]> idsByType = new ConcurrentHashMap<String, int[]>();

    /**
     * Per class loader (weakly), the names of the classes the transformer instrumented or failed to, so a claim or a
     * refine retransforms only the loaded classes it never saw; {@link #BOOTSTRAP} stands for the bootstrap loader.
     */
    private final Map<ClassLoader, Set<String>> seen = new WeakHashMap<ClassLoader, Set<String>>();

    private final Set<String> seenInBootstrap = new HashSet<String>();

    private final ClassLoadRecorder recorder = new ClassLoadRecorder();
    /** The current claim's packages. */
    private volatile List<String> packages = Collections.emptyList();
    /** What the transformer matches: every package claimed since it was installed, cleared once a release restored. */
    private volatile List<String> matching = Collections.emptyList();
    /** The packages whose loaded, never-instrumented classes were retransformed for the current claim generation. */
    private volatile List<String> retransformedPackages = Collections.emptyList();

    private volatile ResettableClassFileTransformer transformer;
    private volatile boolean recording;
    private volatile boolean releasing;
    private volatile boolean countedBeforeClaim;
    private volatile String state = "off";
    private volatile long durationMillis = -1;
    private volatile long beforeClaimClasses = -1;
    private volatile long beforeClaimMillis = -1;
    private volatile long retransformMillis = -1;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    private volatile long generation;
    private volatile boolean stuck;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private Thread worker;
    private int jobs;

    InventorySensor(Instrumentation instrumentation, boolean privileged) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
    }

    /**
     * Installs the sensor once and self-tests it, off the claiming thread; then, and at every later claim, retransforms
     * the claimed packages' loaded classes the transformer never instrumented.
     */
    synchronized void claimed(long claimGeneration, List<String> claimedPackages) {
        generation = claimGeneration;
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        retransformedPackages = Collections.emptyList();
        if (stuck) {
            return;
        }
        widen(packages);
        if (transformer != null && selfTestPassed && (jobs & RELEASE) == 0) {
            schedule(jobs | REFINE);
            return;
        }
        // A release still pending runs first; the worker then installs again, and retransforms what it missed.
        schedule((jobs & RELEASE) | INSTALL | REFINE);
    }

    /** New claimed packages: their loaded classes never instrumented are retransformed, off the caller's thread. */
    synchronized void refined(List<String> claimedPackages) {
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        if (stuck || (jobs & RELEASE) != 0) {
            // The install that follows the release widens the matching to these packages itself.
            return;
        }
        widen(packages);
        if (transformer != null || jobs != 0 || worker != null) {
            schedule(jobs | REFINE);
        }
    }

    /** Adds {@code more} to the packages the transformer matches; only a release's reset narrows them. */
    private synchronized void widen(List<String> more) {
        List<String> current = matching;
        List<String> widened = null;
        for (String name : more) {
            if (!current.contains(name) && (widened == null || !widened.contains(name))) {
                if (widened == null) {
                    widened = new ArrayList<String>(current);
                }
                widened.add(name);
            }
        }
        if (widened != null) {
            matching = Collections.unmodifiableList(widened);
        }
    }

    /** Removes both transformers and restores every instrumented class, off the caller's thread. */
    synchronized void release() {
        releasing = true;
        schedule(RELEASE);
    }

    private void schedule(int next) {
        jobs = next;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-inventory", new Worker(), privileged);
            worker.start();
        }
    }

    private synchronized int nextJob() {
        int job = jobs;
        jobs = 0;
        if (job == 0) {
            worker = null;
        } else if ((job & INSTALL) != 0) {
            releasing = false;
        }
        return job;
    }

    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", CodeInventory.SENSOR);
        map.put("state", state);
        map.put("durationMillis", Long.valueOf(durationMillis));
        map.put("selfTestPassed", Boolean.valueOf(selfTestPassed));
        map.put("selfTestError", selfTestError);
        map.put("selfTestSteps", new LinkedHashMap<String, String>(selfTestSteps));
        map.put("packages", new ArrayList<String>(packages));
        map.put("matching", new ArrayList<String>(matching));
        map.put("retransformedPackages", new ArrayList<String>(retransformedPackages));
        map.put("classesBeforeClaim", Long.valueOf(beforeClaimClasses));
        map.put("beforeClaimMillis", Long.valueOf(beforeClaimMillis));
        map.put("retransformMillis", Long.valueOf(retransformMillis));
        List<Object> hooks = new ArrayList<Object>();
        Map<String, String> results = selfTest;
        for (String[] hook : HOOKS) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", hook[0]);
            row.put("kind", hook[2]);
            row.put("type", hook[1]);
            row.put("present", Boolean.TRUE);
            row.put("transformed", Boolean.valueOf(HOOKS[0] == hook ? transformer != null : recording));
            row.put("selfTest", results.getOrDefault(hook[0], "not-run"));
            hooks.add(row);
        }
        map.put("hooks", hooks);
        stats.putInto(map);
        return map;
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            int job;
            while ((job = nextJob()) != 0) {
                try {
                    if ((job & RELEASE) != 0) {
                        reset();
                    }
                    if ((job & INSTALL) != 0) {
                        if (transformer == null) {
                            install();
                        }
                        selfTest();
                    }
                    if ((job & REFINE) != 0 && transformer != null) {
                        retransformAdded();
                    }
                } catch (Throwable ex) {
                    state = "failed";
                    stats.failure("inventory: " + ex);
                    AgentBridge.message("the BootUI agent could not install its inventory sensor: " + ex);
                }
            }
        }
    }

    private void install() {
        long started = System.nanoTime();
        state = "installing";
        // Link everything recording calls before a transformer can call it inside class loading.
        CodeInventory.warm();
        ClassLoadRecorder.warm();
        warmSeen();
        if (!countedBeforeClaim) {
            // Counted before the recorder is added, so no class is counted both as loaded before and after the claim.
            countedBeforeClaim = true;
            long counting = System.nanoTime();
            countLoadedBeforeClaim();
            beforeClaimMillis = (System.nanoTime() - counting) / 1_000_000L;
        }
        instrumentation.addTransformer(recorder, false);
        recording = true;
        // A release's reset cleared the matching: start again from the current claim's packages. The refine job that
        // follows every install finds what this retransformation missed.
        widen(packages);
        long retransforming = System.nanoTime();
        InstallAction action = new InstallAction();
        transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
        retransformMillis = (System.nanoTime() - retransforming) / 1_000_000L;
        durationMillis = (System.nanoTime() - started) / 1_000_000L;
        state = "installed";
    }

    /** Classes already loaded when the sensor installs: counted once per code source, as loaded before the claim. */
    private void countLoadedBeforeClaim() {
        long counted = 0;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            try {
                String name = type.getName();
                // Arrays and hidden classes are never class definitions a transformer sees.
                if (type.isArray() || name.indexOf('/') >= 0 || ClassLoadRecorder.skipped(name.replace('.', '/'))) {
                    continue;
                }
                String location = ClassLoadRecorder.location(type.getProtectionDomain());
                if (location != null) {
                    CodeInventory.loadedBeforeClaim(location, name);
                    counted++;
                }
            } catch (Throwable ex) {
                // A class whose protection domain cannot be read: not counted.
            }
        }
        beforeClaimClasses = counted;
    }

    /**
     * The current claim's packages not yet handled in this claim generation: their loaded classes the transformer never
     * instrumented in their class loader (loaded before their package was matched) are retransformed.
     */
    private void retransformAdded() {
        List<String> added = new ArrayList<String>();
        long handling;
        synchronized (this) {
            handling = generation;
            for (String name : packages) {
                if (!retransformedPackages.contains(name)) {
                    added.add(name);
                }
            }
        }
        if (added.isEmpty()) {
            return;
        }
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            if (AgentInstaller.inPackages(name, added)
                    && !wasSeen(type.getClassLoader(), name)
                    && instrumentation.isModifiableClass(type)) {
                classes.add(type);
            }
        }
        for (Class<?> type : classes) {
            try {
                instrumentation.retransformClasses(type);
            } catch (Throwable ex) {
                stats.skipped(type.getName(), ex);
                rejected(type.getName());
            }
        }
        synchronized (this) {
            if (generation == handling) {
                List<String> merged = new ArrayList<String>(retransformedPackages);
                merged.addAll(added);
                retransformedPackages = Collections.unmodifiableList(merged);
            }
        }
    }

    /** Records that the transformer instrumented, or failed to, the class {@code typeName} of {@code loader}. */
    private boolean markSeen(ClassLoader loader, String typeName) {
        synchronized (seen) {
            Set<String> names = loader == null ? seenInBootstrap : seen.get(loader);
            if (names == null) {
                names = new HashSet<String>();
                seen.put(loader, names);
            }
            return names.add(typeName);
        }
    }

    /** Links what {@link #markSeen} uses, before the transformer's listener calls it inside class loading. */
    private static void warmSeen() {
        Map<ClassLoader, Set<String>> map = new WeakHashMap<ClassLoader, Set<String>>();
        Set<String> names = new HashSet<String>();
        names.add("warm.Up");
        map.put(InventorySensor.class.getClassLoader(), names);
        map.get(InventorySensor.class.getClassLoader()).contains("warm.Up");
        map.clear();
    }

    private boolean wasSeen(ClassLoader loader, String typeName) {
        synchronized (seen) {
            Set<String> names = loader == null ? seenInBootstrap : seen.get(loader);
            return names != null && names.contains(typeName);
        }
    }

    private void reset() {
        if (recording) {
            instrumentation.removeTransformer(recorder);
            recording = false;
        }
        ResettableClassFileTransformer installed = transformer;
        transformer = null;
        selfTestPassed = false;
        synchronized (this) {
            retransformedPackages = Collections.emptyList();
        }
        if (installed == null) {
            clearMatching();
            state = stuck ? "release-failed" : "released";
            return;
        }
        boolean restored = installed.reset(
                instrumentation,
                AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                        AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                        stats.redefinitionFailures()));
        CodeInventory.untrackAll();
        idsByType.clear();
        pending.clear();
        if (restored) {
            // Only now: the reset found the classes to restore through the matchers, so they had to stay this wide.
            clearMatching();
        }
        if (!restored) {
            stuck = true;
            CodeInventory.disable(generation, true, "its classes could not be restored");
        }
        state = restored ? "released" : "release-failed";
    }

    private void clearMatching() {
        synchronized (this) {
            matching = Collections.emptyList();
        }
        synchronized (seen) {
            seen.clear();
            seenInBootstrap.clear();
        }
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        @Override
        public ResettableClassFileTransformer run() {
            return builder().installOn(instrumentation);
        }
    }

    private AgentBuilder builder() {
        Advice advice = Advice.withCustomMapping()
                .bind(InventoryAdvice.MethodId.class, new MethodIdMapping())
                .to(InventoryAdvice.class);
        return stats.configure(new AgentBuilder.Default(), new Rejections())
                .with(new Tracking())
                .assureReadEdgeTo(instrumentation, CodeInventory.class)
                .ignore(new Unclaimed())
                .type(new Claimed())
                .transform(new Transform(advice));
    }

    // ---- matching --------------------------------------------------------------------------------------------------

    /** The method key {@code className#name+descriptor}, as the bridge keys ids. */
    static String key(TypeDescription type, MethodDescription method) {
        return type.getName() + "#" + method.getInternalName() + method.getDescriptor();
    }

    /** Whether a code source is a test root, whose classes are never instrumented. */
    static boolean testRoot(ProtectionDomain domain) {
        String location = ClassLoadRecorder.location(domain);
        if (location == null) {
            return false;
        }
        String normalized = location.endsWith("/") ? location : location + "/";
        for (String root : TEST_ROOTS) {
            if (normalized.contains(root)) {
                return true;
            }
        }
        return false;
    }

    /** §5.13's never-instrumented classes, and every class outside the claimed packages but the probe: by name only. */
    final class Unclaimed extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

        private final ElementMatcher<TypeDescription> ignored = AgentInstaller.ignored();

        @Override
        public boolean matches(TypeDescription target) {
            String name = target.getName();
            if (PROBE.equals(name)) {
                return false;
            }
            return !AgentInstaller.inPackages(name, matching) || ignored.matches(target);
        }
    }

    /** A claimed class that is neither synthetic nor loaded from a test root, or the probe. */
    final class Claimed implements AgentBuilder.RawMatcher {

        @Override
        public boolean matches(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain) {
            if (PROBE.equals(type.getName())) {
                return true;
            }
            // Never on whether a release is under way: Byte Buddy's reset finds the classes to restore through it.
            if (!AgentInstaller.inPackages(type.getName(), matching)) {
                return false;
            }
            return !type.isSynthetic() && !testRoot(protectionDomain);
        }
    }

    /** Instrumented methods: assigns each its id, and none past the bridge's limit, which then gets no advice. */
    final class Methods extends ElementMatcher.Junction.AbstractBase<MethodDescription> {

        @Override
        public boolean matches(MethodDescription method) {
            if (!(method.isMethod() || method.isConstructor())
                    || method.isAbstract()
                    || method.isNative()
                    || method.getInternalName().startsWith("$")) {
                return false;
            }
            TypeDescription type = method.getDeclaringType().asErasure();
            int id = CodeInventory.methodId(key(type, method));
            if (id < 0) {
                // Past the bridge's limit: named, so the engine says why this class's method is not tracked.
                CodeInventory.overLimit(type.getName());
                return false;
            }
            IdList ids = pending.get(type.getName());
            if (ids == null) {
                IdList fresh = new IdList();
                ids = pending.putIfAbsent(type.getName(), fresh);
                if (ids == null) {
                    ids = fresh;
                }
            }
            ids.add(id);
            return true;
        }
    }

    /** Binds the instrumented method's id, assigned by {@link Methods}, as an {@code int} constant. */
    static final class MethodIdMapping implements Advice.OffsetMapping {

        @Override
        public Target resolve(
                TypeDescription instrumentedType,
                MethodDescription instrumentedMethod,
                Assigner assigner,
                Advice.ArgumentHandler argumentHandler,
                Sort sort) {
            int id = CodeInventory.idOf(key(instrumentedType, instrumentedMethod));
            if (id < 0) {
                throw new IllegalStateException("no inventory id for " + key(instrumentedType, instrumentedMethod));
            }
            return new Target.ForStackManipulation(IntegerConstant.forValue(id));
        }
    }

    /** Adds the advice, unless a release is under way, so a class loaded while it waits is left alone. */
    final class Transform implements AgentBuilder.Transformer {

        private final Advice advice;

        Transform(Advice advice) {
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
            return builder.visit(advice.on(new Methods()));
        }
    }

    // ---- tracking --------------------------------------------------------------------------------------------------

    /** Tells the bridge which methods of each claimed class were instrumented, or failed to be. */
    final class Tracking extends AgentBuilder.Listener.Adapter {

        @Override
        public void onTransformation(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            IdList ids = pending.remove(type.getName());
            int[] array = ids == null ? new int[0] : ids.toArray();
            idsByType.put(type.getName(), array);
            // Loaded and never instrumented in its class loader: it ran uninstrumented until now. A loaded class seen
            // before is one another agent retransformed, which only re-applies the advice.
            boolean first = markSeen(classLoader, type.getName());
            CodeInventory.tracked(type.getName(), array, loaded && first && !PROBE.equals(type.getName()));
        }

        @Override
        public void onError(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable error) {
            IdList ids = pending.remove(typeName);
            if (ids != null || AgentInstaller.inPackages(typeName, matching)) {
                markSeen(classLoader, typeName);
                // Failed before any method matched: the ids of its last instrumentation, in any run, fail with it, so
                // an earlier run's tracking never outlives this failure. The class is named either way.
                int[] failed = ids != null ? ids.toArray() : idsByType.get(typeName);
                CodeInventory.transformFailed(typeName, failed == null ? new int[0] : failed);
            }
        }

        @Override
        public void onIgnored(TypeDescription type, ClassLoader classLoader, JavaModule module, boolean loaded) {
            // A loaded class of a matched package the transformer leaves alone (synthetic, from a test root): seen, so
            // no later claim retransforms it again for nothing.
            if (loaded && AgentInstaller.inPackages(type.getName(), matching)) {
                markSeen(classLoader, type.getName());
            }
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            pending.remove(typeName);
        }
    }

    /** A retransformation the JVM rejected for one class: its methods are not instrumented after all. */
    final class Rejections extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

        @Override
        public Iterable<? extends List<Class<?>>> onError(
                int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
            if (batch.size() == 1) {
                rejected(batch.get(0).getName());
            }
            return Collections.emptyList();
        }
    }

    private void rejected(String typeName) {
        int[] ids = idsByType.remove(typeName);
        CodeInventory.transformFailed(typeName, ids == null ? new int[0] : ids);
    }

    /** A small growable list of ids, written by the transforming thread and read once its outcome is known. */
    static final class IdList {

        private int[] ids = new int[8];
        private int size;

        synchronized void add(int id) {
            if (size == ids.length) {
                int[] grown = new int[size * 2];
                System.arraycopy(ids, 0, grown, 0, size);
                ids = grown;
            }
            ids[size++] = id;
        }

        synchronized int[] toArray() {
            int[] copy = new int[size];
            System.arraycopy(ids, 0, copy, 0, size);
            return copy;
        }
    }

    // ---- self-test -------------------------------------------------------------------------------------------------

    private void selfTest() {
        Map<String, String> steps = new LinkedHashMap<String, String>();
        long hits;
        CodeInventory.beginSelfTest();
        try {
            steps.put("probe", ExecutorSensor.step(new ProbeStep(), 5));
        } finally {
            hits = CodeInventory.endSelfTest();
        }
        Map<String, String> results = new LinkedHashMap<String, String>();
        String outcome = steps.get("probe");
        results.put(
                "method entry",
                hits > 0 ? "passed" : "ok".equals(outcome) ? "failed" : "not-exercised (" + outcome + ")");
        results.put("class load", recording ? "not-exercised" : "failed");
        selfTest = results;
        selfTestSteps = steps;
        if (hits > 0 && recording) {
            selfTestPassed = true;
            selfTestError = null;
            CodeInventory.enable();
        } else {
            selfTestPassed = false;
            selfTestError = "self-test failed: the probe's advice " + (hits > 0 ? "ran" : "never ran") + " " + steps;
            CodeInventory.disable(generation, false, selfTestError);
            AgentBridge.message(
                    "the BootUI agent's inventory sensor failed its self-test and was removed: " + selfTestError);
            reset();
            state = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
        }
    }

    /** Calls the probe, loading it now if needed, so the installed transformer instruments it. */
    static final class ProbeStep implements ExecutorSensor.Step {

        @Override
        public void run(int seconds) throws Exception {
            Class<?> probe = Class.forName(PROBE, true, InventorySensor.class.getClassLoader());
            Method ping = probe.getDeclaredMethod("ping", int.class);
            ping.setAccessible(true);
            Object answer = ping.invoke(null, Integer.valueOf(41));
            if (!Integer.valueOf(42).equals(answer)) {
                throw new IllegalStateException("the probe answered " + answer);
            }
        }
    }

    // ---- class loads -----------------------------------------------------------------------------------------------

    /**
     * Counts each class definition per code source and never transforms: redefinitions, classes without a code
     * source, and the JDK's, BootUI's own modules', and Byte Buddy's classes are ignored.
     */
    static final class ClassLoadRecorder implements ClassFileTransformer {

        /**
         * Skipped by name, before reading the protection domain: classes only the JDK defines, Byte Buddy, and BootUI's
         * own modules (not {@code io.github.jdubois.bootui.} as a whole, which BootUI's sample applications share). The
         * JDK's {@code javax.} and {@code com.sun.} classes are skipped by their {@code jrt:} location instead, since
         * libraries ship classes in those packages too.
         */
        static final String[] SKIPPED = {
            "java/",
            "jdk/",
            "sun/",
            "net/bytebuddy/",
            "io/github/jdubois/bootui/agent/",
            "io/github/jdubois/bootui/engine/",
            "io/github/jdubois/bootui/core/",
            "io/github/jdubois/bootui/spi/",
            "io/github/jdubois/bootui/autoconfigure/",
            "io/github/jdubois/bootui/quarkus/",
            "io/github/jdubois/bootui/client/",
            "io/github/jdubois/bootui/cli/",
            "io/github/jdubois/bootui/conformance/"
        };

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {
            if (classBeingRedefined != null || className == null || protectionDomain == null) {
                return null;
            }
            try {
                if (!skipped(className)) {
                    String location = location(protectionDomain);
                    if (location != null) {
                        CodeInventory.classLoaded(location, className);
                    }
                }
            } catch (Throwable ex) {
                // Never fail a class definition.
            }
            return null;
        }

        static boolean skipped(String internalName) {
            for (String prefix : SKIPPED) {
                if (internalName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /** A protection domain's code-source location as a string, or {@code null} for none or the JDK's image. */
        static String location(ProtectionDomain domain) {
            if (domain == null) {
                return null;
            }
            CodeSource source = domain.getCodeSource();
            URL url = source == null ? null : source.getLocation();
            if (url == null) {
                return null;
            }
            String location = url.toString();
            return location.startsWith("jrt:") ? null : location;
        }

        /** Links the recorder's own code before it is registered. */
        static void warm() {
            skipped("warm/Up");
            location(null);
        }
    }
}
