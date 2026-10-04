package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.RecordComponentDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * The application-methods transformer (PLAN-v2 §5.14, §5.15, M5-3, M5-4a), shared by the {@code inventory} and
 * {@code code-paths} sensors: one Byte Buddy transformer, with {@code DECORATE}, on the classes of the claimed packages,
 * applying one advice visit per sensor the current claim asks for. It installs when a claim asks for either sensor, a
 * claim asking for another set retransforms the classes whose visits change, and a claim asking for neither, or a
 * release, removes it and restores every class.
 *
 * <p><b>Inventory.</b> One entry advice ({@link InventoryAdvice}) on every non-abstract, non-native method and
 * constructor of the claimed classes, except static initializers, methods whose names start with {@code $}, the classes
 * §5.13 never instruments ({@code Exclusions}), synthetic classes, and classes loaded from a test root
 * ({@code test-classes}, Gradle's {@code build/classes/<language>/test}). Claimed classes already loaded are
 * retransformed when it installs, and at each claim and refine, those of its packages loaded but not yet instrumented
 * (in a class loader the transformer had not yet matched them in), marked late since they may have run unseen. A
 * second, plain {@code ClassFileTransformer} that never transforms counts the classes each code source defines.
 *
 * <p><b>Code paths.</b> An entry and exit advice ({@link CodePathsAdvice}) on the public and protected instance methods
 * of the application's bean classes in the claimed packages, sent by the adapters at claim or refine and kept as a union
 * across claims, so a DevTools restart's or a live reload's classes get their advice as they load; not on constructors,
 * static methods, {@code $}-prefixed, synthetic and bridge methods, {@code equals}, {@code hashCode}, {@code toString},
 * record accessors, or classes annotated {@code @ConfigurationProperties}; the adapters also leave out of the bean
 * classes those a {@code @Bean @ConfigurationProperties} method creates. New bean classes already loaded are
 * retransformed at the refine that names them. A class whose transformation fails with the code-paths visit, as one
 * pushing a method past the JVM's 64 KB code limit, never gets that visit again, and is retransformed with the
 * inventory's alone, so its inventory is not lost with it. Retransformations run in batches of {@value #BATCH}, a
 * rejected batch split in halves down to the class the JVM rejects.
 *
 * <p>The transformer matches the union of every package claimed since it was installed, not only the current claim's:
 * a narrower later claim neither leaves a refined package's classes in a new class loader uninstrumented, nor hides the
 * classes it instrumented from Byte Buddy's reset, which finds the classes to restore through the same matchers. The
 * unions are cleared only once a release restored them.
 *
 * <p>It installs once, off the claiming thread, and self-tests each visit with a bundled probe class whose advice must
 * reach the bridge: the inventory's on entry, the code paths' on entry, on return, and on a throwable. A failed
 * self-test stops only that sensor: the bridge stops recording for the claim at once, then its visit is removed, and
 * when that fails too, the bridge stops it for good.
 */
final class ApplicationMethodsSensor {

    /** The inventory self-test probe: instrumented whatever the claimed packages, named by string so it loads late. */
    static final String PROBE = "io.github.jdubois.bootui.agent.InventoryProbe";

    /** The code-paths self-test probe, likewise. */
    static final String CODE_PATHS_PROBE = "io.github.jdubois.bootui.agent.CodePathsProbe";

    /** The annotation whose classes the code-paths sensor leaves alone: configuration holders, not components. */
    static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    /** Code-source locations of test roots, whose classes are never instrumented. */
    static final String[] TEST_ROOTS = {
        "/test-classes/", "/build/classes/java/test/", "/build/classes/kotlin/test/", "/build/classes/groovy/test/"
    };

    /** The inventory sensor's hooks: its id, what it covers, and its kind. */
    static final String[][] HOOKS = {
        {"method entry", "(claimed packages)", "record"},
        {"class load", "(every class)", "record"}
    };

    /** The code-paths sensor's hook. */
    static final String[][] CODE_PATHS_HOOKS = {{"bean methods", "(bean classes)", "record"}};

    /** The most bean class names kept across claims. */
    static final int MAX_BEAN_CLASSES = 50_000;

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;
    private static final int REFINE = 4;
    private static final int SWITCH = 8;
    private static final int BEANS = 16;
    private static final int RETRY = 32;

    /** Classes retransformed in one call, as Byte Buddy's reset does; a batch the JVM rejects is split in halves. */
    static final int BATCH = 64;

    /** Attempts, 20 ms apart, to find a class whose code-paths visit failed as it loaded, to retry it. */
    private static final int RETRY_ROUNDS = 10;

    /** Visit bits, per type being transformed. */
    private static final int VISIT_INVENTORY = 1;

    private static final int VISIT_CODE_PATHS = 2;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final TransformStats stats = new TransformStats();
    private final InventoryDefinitions inventoryDefinitions = new InventoryDefinitions();
    private final ConcurrentHashMap<Integer, Advice> inventoryAdviceByDefinition = new ConcurrentHashMap<>();
    /** Method ids matched while a type is transformed, until its outcome is known. */
    private final ConcurrentHashMap<String, IdList> pending = new ConcurrentHashMap<String, IdList>();
    /** Method ids of each instrumented type, so a retransformation the JVM rejects marks them failed. */
    private final ConcurrentHashMap<String, int[]> idsByType = new ConcurrentHashMap<String, int[]>();
    /** The visits applied to each type being transformed, until its outcome is known. */
    private final ConcurrentHashMap<String, Integer> visits = new ConcurrentHashMap<String, Integer>();
    /** The bean classes instrumented with the code-paths visit. */
    private final Set<String> codePathsTypes = ConcurrentHashMap.newKeySet();
    /**
     * Classes whose transformation failed with the code-paths visit, as one pushing a method past the JVM's 64 KB code
     * limit: never given that visit again, until a release restored them.
     */
    private final Set<String> codePathsRejected = ConcurrentHashMap.newKeySet();
    /** Those of them waiting to be retransformed with the inventory visit alone, with the ids to fail if they are not. */
    private final ConcurrentHashMap<String, int[]> inventoryRetries = new ConcurrentHashMap<String, int[]>();

    /**
     * Per class loader (weakly), the names of the classes the transformer instrumented with the inventory visit or
     * failed to, so a claim or a refine retransforms only the loaded classes it never saw; {@link #seenInBootstrap}
     * stands for the bootstrap loader.
     */
    private final Map<ClassLoader, Set<String>> seen = new WeakHashMap<ClassLoader, Set<String>>();

    private final Set<String> seenInBootstrap = new HashSet<String>();

    private final ClassLoadRecorder recorder = new ClassLoadRecorder();
    /** The current claim's packages. */
    private volatile List<String> packages = Collections.emptyList();
    /** What the transformer matches: every package claimed since it was installed, cleared once a release restored. */
    private volatile List<String> matching = Collections.emptyList();
    /** Every bean class named since the transformer was installed, cleared once a release restored. */
    private volatile Set<String> beanClasses = Collections.emptySet();
    /** Bean classes named since the last retransformation for them. */
    private final List<String> addedBeans = new ArrayList<String>();
    /** The packages whose loaded, never-instrumented classes were retransformed for the current claim generation. */
    private volatile List<String> retransformedPackages = Collections.emptyList();

    /** Which visits the transformer applies now. */
    private volatile boolean inventoryOn;

    private volatile boolean codePathsOn;
    /** Which visits applied when the last retransformation for a switch ran, to tell what changed. */
    private boolean appliedInventory;

    private boolean appliedCodePaths;
    /** Whether each visit was ever on, for its status. */
    private volatile boolean inventoryEver;

    private volatile boolean codePathsEver;

    private volatile ResettableClassFileTransformer transformer;
    private volatile boolean recording;
    private volatile boolean releasing;
    /** While a reset restores the classes: the matcher must find every class either visit may have instrumented. */
    private volatile boolean restoring;

    private volatile boolean countedBeforeClaim;
    private volatile String state = "off";
    private volatile long durationMillis = -1;
    private volatile long beforeClaimClasses = -1;
    private volatile long beforeClaimMillis = -1;
    private volatile long retransformMillis = -1;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    private volatile Map<String, String> codePathsSelfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> codePathsSelfTestSteps = new LinkedHashMap<String, String>();
    private volatile long generation;
    private volatile boolean stuck;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private volatile boolean codePathsSelfTestPassed;
    private volatile String codePathsSelfTestError;
    private volatile String inventoryFailure;
    private volatile String codePathsFailure;
    private Thread worker;
    private int jobs;

    ApplicationMethodsSensor(Instrumentation instrumentation, boolean privileged) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
    }

    /**
     * A claim asking for the inventory sensor, the code-paths sensor, or both: installs the transformer once and
     * self-tests each visit, off the claiming thread; then, and at every later claim, retransforms the claimed packages'
     * loaded classes the transformer never instrumented, the classes whose visits change, and the bean classes named for
     * the first time.
     */
    synchronized void claimed(
            long claimGeneration,
            List<String> claimedPackages,
            Collection<String> claimedBeans,
            boolean inventory,
            boolean codePaths) {
        generation = claimGeneration;
        if (inventory) {
            Set<ClassLoader> existing = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                existing.add(type.getClassLoader());
            }
            for (ClassLoader loader : existing) {
                inventoryDefinitions.observed(loader, true);
            }
            inventoryDefinitions.claimed(claimGeneration);
            CodeInventory.activateDefinition(
                    inventoryDefinitions.token(ApplicationMethodsSensor.class.getClassLoader()), claimGeneration);
        }
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        retransformedPackages = Collections.emptyList();
        if (stuck) {
            return;
        }
        widen(packages);
        boolean newBeans = addBeans(claimedBeans);
        boolean switched = inventory != inventoryOn || codePaths != codePathsOn;
        inventoryOn = inventory;
        codePathsOn = codePaths;
        inventoryEver |= inventory;
        codePathsEver |= codePaths;
        if (!inventory) {
            inventoryFailure = null;
        }
        if (!codePaths) {
            codePathsFailure = null;
        }
        if (transformer != null && (jobs & RELEASE) == 0) {
            schedule(jobs | REFINE | (switched ? SWITCH : 0) | (newBeans ? BEANS : 0) | (selfTested() ? 0 : INSTALL));
            return;
        }
        // A release still pending runs first; the worker then installs again, and retransforms what it missed.
        schedule((jobs & RELEASE) | INSTALL | REFINE);
    }

    /** Whether every visit on passed its self-test. */
    private boolean selfTested() {
        return (!inventoryOn || selfTestPassed) && (!codePathsOn || codePathsSelfTestPassed);
    }

    /**
     * New claimed packages and bean classes: the loaded classes never instrumented are retransformed, off the caller's
     * thread.
     */
    synchronized void refined(List<String> claimedPackages, Collection<String> claimedBeans) {
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        if (stuck || (jobs & RELEASE) != 0) {
            // The install that follows the release widens the matching to these packages itself.
            addBeans(claimedBeans);
            return;
        }
        widen(packages);
        boolean newBeans = addBeans(claimedBeans);
        if (transformer != null || jobs != 0 || worker != null) {
            schedule(jobs | REFINE | (newBeans ? BEANS : 0));
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

    /** Adds {@code more} to the bean classes; whether any was new. Only a release's reset clears them. */
    private synchronized boolean addBeans(Collection<String> more) {
        if (more == null || more.isEmpty()) {
            return false;
        }
        Set<String> current = beanClasses;
        Set<String> widened = null;
        for (String name : more) {
            if (name != null && !current.contains(name)) {
                if (widened == null) {
                    widened = new HashSet<String>(current);
                }
                if (widened.size() >= MAX_BEAN_CLASSES) {
                    break;
                }
                if (widened.add(name)) {
                    addedBeans.add(name);
                }
            }
        }
        if (widened == null) {
            return false;
        }
        beanClasses = Collections.unmodifiableSet(widened);
        return true;
    }

    /** Removes both transformers and restores every instrumented class, off the caller's thread. */
    synchronized void release() {
        releasing = true;
        schedule(RELEASE);
    }

    private void schedule(int next) {
        jobs = next;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-application-methods", new Worker(), privileged);
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

    /** Whether the worker has no job left: every claim's retransformation and self-test ran. */
    synchronized boolean idle() {
        return worker == null && jobs == 0;
    }

    /** The inventory sensor's status row. */
    Map<String, Object> inventoryStatus() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", CodeInventory.SENSOR);
        map.put("state", visitState(inventoryOn, inventoryEver, inventoryFailure));
        map.put("idle", Boolean.valueOf(idle()));
        map.put("durationMillis", Long.valueOf(durationMillis));
        map.put("selfTestPassed", Boolean.valueOf(inventoryOn && selfTestPassed));
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
            row.put("transformed", Boolean.valueOf(HOOKS[0] == hook ? transformer != null && inventoryOn : recording));
            row.put("selfTest", results.getOrDefault(hook[0], "not-run"));
            hooks.add(row);
        }
        map.put("hooks", hooks);
        // The transformer is shared: its counters go to the inventory's row whenever it applies, else to the other's.
        if (inventoryOn || !codePathsOn) {
            stats.putInto(map);
        }
        return map;
    }

    /** The code-paths sensor's status row. */
    Map<String, Object> codePathsStatus() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", CodePaths.SENSOR);
        map.put("state", visitState(codePathsOn, codePathsEver, codePathsFailure));
        map.put("idle", Boolean.valueOf(idle()));
        map.put("durationMillis", Long.valueOf(durationMillis));
        map.put("selfTestPassed", Boolean.valueOf(codePathsOn && codePathsSelfTestPassed));
        map.put("selfTestError", codePathsSelfTestError);
        map.put("selfTestSteps", new LinkedHashMap<String, String>(codePathsSelfTestSteps));
        map.put("packages", new ArrayList<String>(packages));
        map.put("beanClasses", Integer.valueOf(beanClasses.size()));
        map.put("instrumentedTypes", Integer.valueOf(codePathsTypes.size()));
        List<Object> hooks = new ArrayList<Object>();
        Map<String, String> results = codePathsSelfTest;
        for (String[] hook : CODE_PATHS_HOOKS) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", hook[0]);
            row.put("kind", hook[2]);
            row.put("type", hook[1]);
            row.put("present", Boolean.TRUE);
            row.put("transformed", Boolean.valueOf(transformer != null && codePathsOn));
            row.put("selfTest", results.getOrDefault(hook[0], "not-run"));
            hooks.add(row);
        }
        map.put("hooks", hooks);
        if (!inventoryOn && codePathsOn) {
            stats.putInto(map);
        } else {
            // Counted once, on the inventory's row, so the Java Agent panel never sums the same transformer twice.
            map.put("sharedTransformer", CodeInventory.SENSOR);
        }
        return map;
    }

    /** A visit's state: the transformer's while it applies, else whether it was removed or never applied. */
    private String visitState(boolean on, boolean ever, String failure) {
        if (failure != null) {
            return failure;
        }
        if (on) {
            return state;
        }
        return ever ? "released" : "off";
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
                    if ((job & INSTALL) != 0 && transformer == null) {
                        if (!inventoryOn && !codePathsOn) {
                            continue;
                        }
                        install();
                    }
                    if ((job & SWITCH) != 0 && transformer != null) {
                        switchVisits();
                    }
                    if ((job & (INSTALL | SWITCH)) != 0 && transformer != null) {
                        selfTest();
                    }
                    if ((job & REFINE) != 0 && transformer != null) {
                        retransformAdded();
                    }
                    if ((job & BEANS) != 0 && transformer != null) {
                        retransformBeans();
                    }
                    if ((job & RETRY) != 0) {
                        retryInventoryAlone();
                    }
                } catch (Throwable ex) {
                    state = "failed";
                    stats.failure("application methods: " + ex);
                    AgentBridge.message("the BootUI agent could not install its application-methods sensors: " + ex);
                }
            }
        }
    }

    private void install() {
        long started = System.nanoTime();
        state = "installing";
        // Link everything recording calls before a transformer can call it inside class loading.
        CodeInventory.warm();
        CodePaths.warm();
        ClassLoadRecorder.warm();
        warmSeen();
        if (inventoryOn) {
            startRecorder();
        }
        synchronized (this) {
            appliedInventory = inventoryOn;
            appliedCodePaths = codePathsOn;
            addedBeans.clear();
        }
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

    /**
     * Adds the class-load recorder, then walks the classes already loaded: added first, so no class defined during the
     * walk goes unseen, and walked twice, so a class whose definition raced the recorder's addition is found by the
     * second walk. The first walk ever counts them as loaded before the claim (a class the recorder already counted is
     * not counted again); every later walk, as after the recorder was stopped, only keeps their names.
     */
    private void startRecorder() {
        if (recording) {
            return;
        }
        instrumentation.addTransformer(recorder, false);
        recording = true;
        CodeInventory.recorderStarted();
        long counting = System.nanoTime();
        if (!countedBeforeClaim) {
            countedBeforeClaim = true;
            countLoadedBeforeClaim(true);
            beforeClaimMillis = (System.nanoTime() - counting) / 1_000_000L;
        } else {
            countLoadedBeforeClaim(false);
        }
        countLoadedBeforeClaim(false);
        CodeInventory.recorderWalked();
    }

    private void stopRecorder() {
        if (recording) {
            instrumentation.removeTransformer(recorder);
            recording = false;
            CodeInventory.recorderStopped();
        }
    }

    /**
     * Applies a changed set of visits to the loaded classes: when the inventory visit is turned on or off, every loaded
     * class of the matched packages (the inventory's tracking starts over), else only the loaded bean classes.
     */
    private void switchVisits() {
        boolean inventory;
        boolean codePaths;
        boolean inventoryChanged;
        synchronized (this) {
            inventory = inventoryOn;
            codePaths = codePathsOn;
            inventoryChanged = inventory != appliedInventory;
            if (inventory == appliedInventory && codePaths == appliedCodePaths) {
                return;
            }
            appliedInventory = inventory;
            appliedCodePaths = codePaths;
            addedBeans.clear();
        }
        if (inventoryChanged) {
            if (inventory) {
                startRecorder();
            } else {
                stopRecorder();
                CodeInventory.untrackAll();
                selfTestPassed = false;
                selfTest = new LinkedHashMap<String, String>();
            }
            // The inventory's view of what it saw starts over, so every loaded class it instruments now is late.
            synchronized (seen) {
                seen.clear();
                seenInBootstrap.clear();
            }
            idsByType.clear();
            synchronized (this) {
                retransformedPackages = Collections.emptyList();
            }
        }
        if (!codePaths) {
            codePathsSelfTestPassed = false;
            codePathsSelfTest = new LinkedHashMap<String, String>();
            codePathsTypes.clear();
        }
        List<String> names = matching;
        Set<String> beans = beanClasses;
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            boolean probe = PROBE.equals(name) || CODE_PATHS_PROBE.equals(name);
            boolean candidate =
                    probe || (AgentInstaller.inPackages(name, names) && (inventoryChanged || beans.contains(name)));
            if (candidate && instrumentation.isModifiableClass(type)) {
                classes.add(type);
            }
        }
        retransform(classes);
    }

    /** The bean classes named since the last retransformation for them, already loaded, get the code-paths visit. */
    private void retransformBeans() {
        List<String> added;
        synchronized (this) {
            added = new ArrayList<String>(addedBeans);
            addedBeans.clear();
        }
        if (added.isEmpty() || !codePathsOn) {
            return;
        }
        Set<String> wanted = new HashSet<String>(added);
        List<String> names = matching;
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            if (wanted.contains(name)
                    && AgentInstaller.inPackages(name, names)
                    && instrumentation.isModifiableClass(type)) {
                classes.add(type);
            }
        }
        retransform(classes);
    }

    /** Retransforms {@code classes} in batches of {@value #BATCH}. */
    private void retransform(List<Class<?>> classes) {
        long started = System.nanoTime();
        for (int from = 0; from < classes.size(); from += BATCH) {
            retransformBatch(classes.subList(from, Math.min(classes.size(), from + BATCH)));
        }
        stats.retransformedFor(System.nanoTime() - started);
    }

    /**
     * Retransforms {@code batch} in one call. The JVM retransforms none of a batch it rejects, so its halves are
     * retransformed apart, down to the one class it rejects.
     */
    private void retransformBatch(List<Class<?>> batch) {
        try {
            instrumentation.retransformClasses(batch.toArray(new Class<?>[0]));
        } catch (Throwable ex) {
            if (batch.size() == 1) {
                stats.skipped(batch.get(0).getName(), ex);
                rejected(batch.get(0).getName());
                return;
            }
            int half = batch.size() / 2;
            retransformBatch(batch.subList(0, half));
            retransformBatch(batch.subList(half, batch.size()));
        }
    }

    /** A class's code-paths visit failed with its inventory's: it is retransformed with the inventory's alone. */
    private synchronized void retryLater(String typeName, int[] ids) {
        inventoryRetries.put(typeName, ids);
        if ((jobs & RELEASE) == 0) {
            schedule(jobs | RETRY);
        }
    }

    /**
     * Retransforms the classes whose code-paths visit failed, now that it no longer applies to them, so their inventory
     * visit applies alone. A class that failed as it loaded may not be listed as loaded yet: it is looked for a few
     * times; one never found, as when its definition failed, has its inventory marked failed as before.
     */
    private void retryInventoryAlone() {
        for (int round = 0; round < RETRY_ROUNDS && !inventoryRetries.isEmpty(); round++) {
            if (round > 0) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (transformer == null || releasing) {
                inventoryRetries.clear();
                return;
            }
            List<Class<?>> classes = new ArrayList<Class<?>>();
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (inventoryRetries.containsKey(type.getName()) && instrumentation.isModifiableClass(type)) {
                    classes.add(type);
                }
            }
            for (Class<?> type : classes) {
                inventoryRetries.remove(type.getName());
            }
            retransform(classes);
        }
        for (String typeName : new ArrayList<String>(inventoryRetries.keySet())) {
            int[] ids = inventoryRetries.remove(typeName);
            if (inventoryOn && ids != null) {
                CodeInventory.transformFailed(typeName, ids);
            }
        }
    }

    /**
     * The classes already loaded: with {@code count}, counted once per code source as loaded before the claim, else only
     * their names kept. A class defined without a code-source location is reported as such either way.
     */
    private void countLoadedBeforeClaim(boolean count) {
        long counted = 0;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            try {
                String name = type.getName();
                // Arrays and hidden classes are never class definitions a transformer sees.
                if (type.isArray() || name.indexOf('/') >= 0 || ClassLoadRecorder.skipped(name.replace('.', '/'))) {
                    continue;
                }
                ProtectionDomain domain = type.getProtectionDomain();
                String location = ClassLoadRecorder.location(domain);
                if (location != null) {
                    if (count) {
                        CodeInventory.loadedBeforeClaim(location, name);
                        counted++;
                    } else {
                        CodeInventory.loadedWhileUnrecorded(location, name);
                    }
                } else if (ClassLoadRecorder.unlocated(type.getClassLoader(), domain)) {
                    CodeInventory.classLoadedWithoutLocation(name);
                }
            } catch (Throwable ex) {
                // A class whose protection domain cannot be read: not counted.
            }
        }
        if (count) {
            beforeClaimClasses = counted;
        }
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
        Set<String> beans = beanClasses;
        boolean inventory = inventoryOn;
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            if (AgentInstaller.inPackages(name, added)
                    && (inventory || beans.contains(name))
                    && !wasSeen(type.getClassLoader(), name)
                    && instrumentation.isModifiableClass(type)) {
                classes.add(type);
            }
        }
        retransform(classes);
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
        map.put(ApplicationMethodsSensor.class.getClassLoader(), names);
        map.get(ApplicationMethodsSensor.class.getClassLoader()).contains("warm.Up");
        map.clear();
    }

    private boolean wasSeen(ClassLoader loader, String typeName) {
        synchronized (seen) {
            Set<String> names = loader == null ? seenInBootstrap : seen.get(loader);
            return names != null && names.contains(typeName);
        }
    }

    private void reset() {
        stopRecorder();
        ResettableClassFileTransformer installed = transformer;
        transformer = null;
        selfTestPassed = false;
        codePathsSelfTestPassed = false;
        synchronized (this) {
            retransformedPackages = Collections.emptyList();
            appliedInventory = false;
            appliedCodePaths = false;
        }
        if (installed == null) {
            clearMatching();
            state = stuck ? "release-failed" : "released";
            return;
        }
        boolean restored;
        restoring = true;
        try {
            restored = installed.reset(
                    instrumentation,
                    AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                    AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                    new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                            AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                            stats.redefinitionFailures()));
        } finally {
            restoring = false;
        }
        CodeInventory.untrackAll();
        idsByType.clear();
        pending.clear();
        visits.clear();
        codePathsTypes.clear();
        if (restored) {
            // Only now: the reset found the classes to restore through the matchers, so they had to stay this wide.
            clearMatching();
        }
        if (!restored) {
            stuck = true;
            CodeInventory.disable(generation, true, "its classes could not be restored");
            CodePaths.disable(generation, true, "its classes could not be restored");
        }
        state = restored ? "released" : "release-failed";
    }

    private void clearMatching() {
        synchronized (this) {
            matching = Collections.emptyList();
            beanClasses = Collections.emptySet();
            addedBeans.clear();
        }
        codePathsRejected.clear();
        inventoryRetries.clear();
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
        Advice codePathsAdvice = Advice.withCustomMapping()
                .bind(InventoryAdvice.MethodId.class, new MethodIdMapping())
                .to(CodePathsAdvice.class);
        return stats.configure(new AgentBuilder.Default(), new Rejections())
                .with(new Tracking())
                .assureReadEdgeTo(instrumentation, CodeInventory.class)
                .ignore(new Unclaimed())
                .type(new Claimed())
                .transform(new Transform(codePathsAdvice));
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

    static boolean probe(String name) {
        return PROBE.equals(name) || CODE_PATHS_PROBE.equals(name);
    }

    /** §5.13's never-instrumented classes, and every class outside the claimed packages but the probes: by name only. */
    final class Unclaimed extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

        private final ElementMatcher<TypeDescription> ignored = AgentInstaller.ignored();

        @Override
        public boolean matches(TypeDescription target) {
            String name = target.getName();
            if (probe(name)) {
                return false;
            }
            return !AgentInstaller.inPackages(name, matching) || ignored.matches(target);
        }
    }

    /**
     * A claimed class that is neither synthetic nor loaded from a test root, and, unless the inventory visit applies,
     * a bean class; or a probe.
     */
    final class Claimed implements AgentBuilder.RawMatcher {

        @Override
        public boolean matches(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain) {
            String name = type.getName();
            if (probe(name)) {
                return true;
            }
            // Never on whether a release is under way: Byte Buddy's reset finds the classes to restore through it.
            if (!AgentInstaller.inPackages(name, matching)) {
                return false;
            }
            if (!inventoryOn && !restoring && !beanClasses.contains(name)) {
                return false;
            }
            return !type.isSynthetic() && !testRoot(protectionDomain);
        }
    }

    /** Inventory-instrumented methods: assigns each its id, and none past the bridge's limit, which then gets none. */
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

    /**
     * Code-paths-instrumented methods: public and protected instance methods with code, but {@code $}-prefixed,
     * synthetic, and bridge methods, the {@code Object} methods applications override, and record accessors.
     */
    static final class CodePathsMethods extends ElementMatcher.Junction.AbstractBase<MethodDescription> {

        private final Set<String> recordComponents;

        CodePathsMethods(Set<String> recordComponents) {
            this.recordComponents = recordComponents;
        }

        @Override
        public boolean matches(MethodDescription method) {
            if (!method.isMethod()
                    || method.isStatic()
                    || method.isAbstract()
                    || method.isNative()
                    || method.isSynthetic()
                    || method.isBridge()
                    || !(method.isPublic() || method.isProtected())) {
                return false;
            }
            String name = method.getInternalName();
            String descriptor = method.getDescriptor();
            if (name.startsWith("$")
                    || ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(descriptor))
                    || ("hashCode".equals(name) && "()I".equals(descriptor))
                    || ("toString".equals(name) && "()Ljava/lang/String;".equals(descriptor))
                    || (recordComponents.contains(name)
                            && method.getParameters().isEmpty())) {
                return false;
            }
            return CodeInventory.methodId(key(method.getDeclaringType().asErasure(), method)) >= 0;
        }
    }

    /** The names of a record's components, or none. */
    static Set<String> recordComponents(TypeDescription type) {
        try {
            if (!type.isRecord()) {
                return Collections.emptySet();
            }
            Set<String> names = new HashSet<String>();
            for (RecordComponentDescription.InDefinedShape component : type.getRecordComponents()) {
                names.add(component.getActualName());
            }
            return names;
        } catch (Throwable ex) {
            return Collections.emptySet();
        }
    }

    /** Whether the class is annotated {@code @ConfigurationProperties}: a configuration holder, never timed. */
    static boolean configurationProperties(TypeDescription type) {
        try {
            for (AnnotationDescription annotation : type.getDeclaredAnnotations()) {
                if (CONFIGURATION_PROPERTIES.equals(
                        annotation.getAnnotationType().getName())) {
                    return true;
                }
            }
        } catch (Throwable ex) {
            // An annotation type that cannot be described: not this one.
        }
        return false;
    }

    /** Binds the instrumented method's id, assigned by the method matchers, as an {@code int} constant. */
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
                throw new IllegalStateException("no method id for " + key(instrumentedType, instrumentedMethod));
            }
            return new Target.ForStackManipulation(IntegerConstant.forValue(id));
        }
    }

    /**
     * Adds the visits that apply to the type, unless a release is under way, so a class loaded while it waits is left
     * alone: the inventory's to every matched class but the code-paths probe, the code paths' to the bean classes and
     * the code-paths probe.
     */
    final class Transform implements AgentBuilder.Transformer {

        private final Advice codePathsAdvice;

        Transform(Advice codePathsAdvice) {
            this.codePathsAdvice = codePathsAdvice;
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
            String name = type.getName();
            boolean codePathsProbe = CODE_PATHS_PROBE.equals(name);
            int applied = 0;
            DynamicType.Builder<?> visited = builder;
            if (inventoryOn && !codePathsProbe) {
                int definition = inventoryDefinitions.token(classLoader);
                Advice definitionAdvice = inventoryAdviceByDefinition.computeIfAbsent(
                        definition,
                        token -> Advice.withCustomMapping()
                                .bind(InventoryAdvice.MethodId.class, new MethodIdMapping())
                                .bind(InventoryAdvice.DefinitionToken.class, token)
                                .to(InventoryAdvice.class));
                visited = visited.visit(definitionAdvice.on(new Methods()));
                applied |= VISIT_INVENTORY;
            }
            if (codePathsOn
                    && (codePathsProbe || beanClasses.contains(name))
                    && !configurationProperties(type)
                    && !codePathsRejected.contains(name)) {
                visited = visited.visit(codePathsAdvice.on(new CodePathsMethods(recordComponents(type))));
                applied |= VISIT_CODE_PATHS;
            }
            visits.put(name, Integer.valueOf(applied));
            return visited;
        }
    }

    // ---- tracking --------------------------------------------------------------------------------------------------

    /** Tells the bridge which methods of each claimed class were instrumented, or failed to be. */
    final class Tracking extends AgentBuilder.Listener.Adapter {

        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            if (inventoryOn) {
                // Only the claim's snapshot proves a loader pre-existing; loaded can also mean the install gap.
                inventoryDefinitions.observed(classLoader, false);
            }
        }

        @Override
        public void onTransformation(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            String name = type.getName();
            Integer applied = visits.remove(name);
            int bits = applied == null ? 0 : applied.intValue();
            if ((bits & VISIT_CODE_PATHS) != 0 && !CODE_PATHS_PROBE.equals(name)) {
                codePathsTypes.add(name);
            } else {
                codePathsTypes.remove(name);
            }
            IdList ids = pending.remove(name);
            // Loaded and never instrumented in its class loader: it ran uninstrumented until now. A loaded class seen
            // before is one another agent retransformed, which only re-applies the advice.
            boolean first = markSeen(classLoader, name);
            if ((bits & VISIT_INVENTORY) == 0) {
                return;
            }
            int[] array = ids == null ? new int[0] : ids.toArray();
            int[] previous = loaded && !first ? idsByType.get(name) : null;
            if (previous != null) {
                // Transformed again in its class loader, as by a HotSwap: the JVM may still refuse a redefinition that
                // adds methods after this transformer ran, so only the methods the class already had are tracked; the
                // others are tracked from its next load.
                array = retained(array, previous);
            }
            idsByType.put(name, array);
            int definition = inventoryDefinitions.token(classLoader);
            CodeInventory.tracked(name, array, loaded && first && !PROBE.equals(name), definition);
        }

        @Override
        public void onError(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable error) {
            Integer applied = visits.remove(typeName);
            codePathsTypes.remove(typeName);
            IdList ids = pending.remove(typeName);
            if (applied != null && (applied.intValue() & VISIT_CODE_PATHS) != 0) {
                // The code-paths visit may be what failed, as one pushing a method past the JVM's 64 KB code limit:
                // never applied to the class again, so a retransformation with the inventory's alone keeps that one.
                codePathsRejected.add(typeName);
                stats.failure("code paths left out of " + typeName + ": " + error);
                if ((applied.intValue() & VISIT_INVENTORY) != 0) {
                    int[] failed = ids != null ? ids.toArray() : idsByType.get(typeName);
                    retryLater(typeName, failed == null ? new int[0] : failed);
                    return;
                }
            }
            boolean inventory = applied == null ? inventoryOn : (applied.intValue() & VISIT_INVENTORY) != 0;
            if (inventory && (ids != null || AgentInstaller.inPackages(typeName, matching))) {
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
            if (loaded && inventoryOn && AgentInstaller.inPackages(type.getName(), matching)) {
                markSeen(classLoader, type.getName());
            }
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            pending.remove(typeName);
            visits.remove(typeName);
        }
    }

    /** The ids of {@code ids} also in {@code previous}, in their order. */
    static int[] retained(int[] ids, int[] previous) {
        int count = 0;
        int[] kept = new int[ids.length];
        for (int id : ids) {
            for (int old : previous) {
                if (id == old) {
                    kept[count++] = id;
                    break;
                }
            }
        }
        if (count == kept.length) {
            return kept;
        }
        int[] trimmed = new int[count];
        System.arraycopy(kept, 0, trimmed, 0, count);
        return trimmed;
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
        codePathsTypes.remove(typeName);
        if (!inventoryOn) {
            return;
        }
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

    /** Self-tests each visit that applies and has not passed since it was last installed or switched on. */
    private void selfTest() {
        if (inventoryOn && !selfTestPassed) {
            selfTestInventory();
        }
        if (codePathsOn && !codePathsSelfTestPassed) {
            selfTestCodePaths();
        }
    }

    private void selfTestInventory() {
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
            inventoryFailure = null;
            CodeInventory.enable();
        } else {
            selfTestPassed = false;
            selfTestError = "self-test failed: the probe's advice " + (hits > 0 ? "ran" : "never ran") + " " + steps;
            CodeInventory.disable(generation, false, selfTestError);
            AgentBridge.message(
                    "the BootUI agent's inventory sensor failed its self-test and was removed: " + selfTestError);
            removeVisit(true);
            inventoryFailure = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
        }
    }

    private void selfTestCodePaths() {
        Map<String, String> steps = new LinkedHashMap<String, String>();
        CodePathsStep step = new CodePathsStep();
        CodePaths.beginSelfTest();
        try {
            steps.put("probe", ExecutorSensor.step(step, 5));
        } finally {
            CodePaths.endSelfTest();
        }
        String outcome = steps.get("probe");
        boolean passed = "ok".equals(outcome);
        Map<String, String> results = new LinkedHashMap<String, String>();
        results.put(CODE_PATHS_HOOKS[0][0], passed ? "passed" : "failed (" + outcome + ")");
        codePathsSelfTest = results;
        codePathsSelfTestSteps = steps;
        if (passed) {
            codePathsSelfTestPassed = true;
            codePathsSelfTestError = null;
            codePathsFailure = null;
            CodePaths.enable();
        } else {
            codePathsSelfTestPassed = false;
            codePathsSelfTestError = "self-test failed: " + outcome;
            CodePaths.disable(generation, false, codePathsSelfTestError);
            AgentBridge.message("the BootUI agent's code-paths sensor failed its self-test and was removed: "
                    + codePathsSelfTestError);
            removeVisit(false);
            codePathsFailure = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
        }
    }

    /** Removes one visit after its self-test failed: the whole transformer when it was the last one. */
    private void removeVisit(boolean inventory) {
        synchronized (this) {
            if (inventory) {
                inventoryOn = false;
            } else {
                codePathsOn = false;
            }
        }
        if (!inventoryOn && !codePathsOn) {
            reset();
        } else {
            switchVisits();
        }
    }

    /** Calls the inventory probe, loading it now if needed, so the installed transformer instruments it. */
    static final class ProbeStep implements ExecutorSensor.Step {

        @Override
        public void run(int seconds) throws Exception {
            Class<?> probe = Class.forName(PROBE, true, ApplicationMethodsSensor.class.getClassLoader());
            Method ping = probe.getDeclaredMethod("ping", int.class);
            ping.setAccessible(true);
            Object answer = ping.invoke(null, Integer.valueOf(41));
            if (!Integer.valueOf(42).equals(answer)) {
                throw new IllegalStateException("the probe answered " + answer);
            }
        }
    }

    /**
     * Calls the code-paths probe: its nested calls must see the depth their entries pushed, and the depth must be back
     * where it started after a return and after a throwable, so both exit paths pop.
     */
    static final class CodePathsStep implements ExecutorSensor.Step {

        @Override
        public void run(int seconds) throws Exception {
            CodePathsProbe probe = new CodePathsProbe();
            int before = CodePaths.depth();
            int[] seen = probe.ping();
            int afterReturn = CodePaths.depth();
            String thrown = null;
            try {
                probe.fail();
            } catch (IllegalStateException expected) {
                thrown = expected.getMessage();
            }
            int afterThrow = CodePaths.depth();
            String found = "entry depths " + seen[0] + "/" + seen[1] + ", after return " + (afterReturn - before)
                    + ", in throw " + thrown + ", after throw " + (afterThrow - before);
            if (seen[0] != before + 1
                    || seen[1] != before + 2
                    || afterReturn != before
                    || !String.valueOf(before + 1).equals(thrown)
                    || afterThrow != before) {
                throw new IllegalStateException(found);
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
            if (classBeingRedefined != null || className == null) {
                return null;
            }
            try {
                if (!skipped(className)) {
                    String location = location(protectionDomain);
                    if (location != null) {
                        CodeInventory.classLoaded(location, className);
                    } else if (unlocated(loader, protectionDomain)) {
                        CodeInventory.classLoadedWithoutLocation(className);
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

        /**
         * Whether a class with no code-source location is one no code source can account for: defined by a class loader
         * (the bootstrap loader's are the JDK's) without a location, never the JDK's {@code jrt:} image.
         */
        static boolean unlocated(ClassLoader loader, ProtectionDomain domain) {
            if (loader == null) {
                return false;
            }
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL url = source == null ? null : source.getLocation();
            return url == null || !"jrt".equals(url.getProtocol());
        }

        /** Links the recorder's own code before it is registered. */
        static void warm() {
            skipped("warm/Up");
            location(null);
            unlocated(null, null);
        }
    }
}
