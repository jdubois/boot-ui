package bootuiagentit;

import bootuiinventoryapp.App;
import bootuiinventoryapp.Color;
import bootuiinventoryapp.Derived;
import bootuiinventoryapp.Point;
import bootuiinventoryapp.TestRootOnly;
import bootuiinventoryextra.Extra;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The inventory sensor's behaviors (PLAN-v2 M5-3), in a forked JVM beside the agent, claimed with the inventory sensor
 * on {@code bootuiinventoryapp}, whose classes come from a jar, and with a harness engine whose context is a
 * thread-local request id and route. Prints one PASS or FAIL line per behavior, then the bridge's status.
 */
public final class InventoryBehaviors {

    static final String BRIDGE = "io.github.jdubois.bootui.agent.bridge.";
    static final String REQUEST = "00000000000000ab";
    static final long REQUEST_BITS = 0xabL;

    static final ThreadLocal<String[]> CONTEXT = new ThreadLocal<>();
    static final ThreadLocal<int[]> CAPTURE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    static final AtomicBoolean REENTERED = new AtomicBoolean();
    static final AtomicReference<Runnable> INSIDE_CAPTURE = new AtomicReference<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();

    static Class<?> bridge;
    static Class<?> inventory;
    static Class<?> ring;
    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;

    private InventoryBehaviors() {}

    public static void main(String[] args) throws Exception {
        bridge = Class.forName(BRIDGE + "AgentBridge", true, null);
        inventory = Class.forName(BRIDGE + "CodeInventory", true, null);
        ring = Class.forName(BRIDGE + "AgentRing", true, null);
        // Loaded and run before the claim, so the sensor retransforms App when it installs.
        App.beforeClaim();

        long token = claim();
        awaitSelfTest();
        long generation = (Long) inventory.getMethod("currentGeneration").invoke(null);

        check(
                "a method called before the claim is not executed yet",
                !executed(key("beforeClaim()Ljava/lang/String;")));
        App.beforeClaim();
        check(
                "a method called before the claim then after is executed after",
                executed(key("beforeClaim()Ljava/lang/String;")));
        check(
                "a class instrumented after it loaded is late for that run",
                late(id(key("beforeClaim()Ljava/lang/String;"))));

        CONTEXT.remove();
        App.atStartup();
        check("a first call without a request sets its flag", executed(key("atStartup()Ljava/lang/String;")));

        CONTEXT.set(new String[] {REQUEST, "/greet"});
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread caller = new Thread(() -> {
                CONTEXT.set(new String[] {REQUEST, "/greet"});
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    return;
                }
                for (int call = 0; call < 1000; call++) {
                    App.greet("world");
                }
            });
            callers.add(caller);
            caller.start();
        }
        start.countDown();
        for (Thread caller : callers) {
            caller.join(10_000);
        }
        new App().describe();
        App.greet("again");
        drain(token);
        int greet = id(key("greet(Ljava/lang/String;)Ljava/lang/String;"));
        List<long[]> greetHits = firstHits(greet);
        check(
                "a method flips exactly once under concurrent first calls (" + greetHits.size() + " records)",
                executed(greet) && greetHits.size() == 1);
        check(
                "a first hit carries its request id and route",
                greetHits.size() == 1
                        && greetHits.get(0)[5] == REQUEST_BITS
                        && "/greet".equals(interned(generation, greetHits.get(0)[6])));
        check(
                "a first call without a request records nothing",
                firstHits(id(key("atStartup()Ljava/lang/String;"))).isEmpty());
        check("constructors are instrumented", executed(key("<init>()V")));
        check(
                "default interface methods are instrumented",
                executed("bootuiinventoryapp.Shape#describe()Ljava/lang/String;"));
        check("abstract interface methods are not", id("bootuiinventoryapp.Shape#area()D") < 0);

        int never = id(key("neverCalled()Ljava/lang/String;"));
        check(
                "a never-called method is tracked and stays unset",
                never >= 0 && tracking(never) == 1 && !executed(never));
        check("a class is tracked in the run it was instrumented in", trackedThisRun(never));
        check("static initializers are not instrumented", id(key("<clinit>()V")) < 0);
        check(
                "methods whose names start with $ are not instrumented",
                App.$dollar().equals("dollar") && id(key("$dollar()Ljava/lang/String;")) < 0);
        check(
                "a class loaded from a test root is not instrumented",
                TestRootOnly.run().equals("test root")
                        && id("bootuiinventoryapp.TestRootOnly#run()Ljava/lang/String;") < 0);

        constructorsRecordsEnumsAndTransformFailures();

        // Class loads per code source.
        String used = bootuiinventorylib.used.UsedLib.name();
        drain(token);
        Map<String, Object> usedJar = codeSource("inventory-lib-used.jar");
        check(
                "a jar whose class loads is counted (" + used + ": " + usedJar + ")",
                usedJar != null && ((Long) usedJar.get("loaded")) >= 1L);
        check(
                "a jar's first class load carries its request",
                usedJar != null
                        && classLoads(((Integer) usedJar.get("id")).longValue()).stream()
                                .anyMatch(record -> record[5] == REQUEST_BITS));
        check("a jar no class loads from is absent", codeSource("inventory-lib-unused.jar") == null);

        boolean previous =
                (Boolean) bridge.getMethod("bootUiWork", boolean.class).invoke(null, true);
        try {
            Class.forName("bootuiinventorylib.bootui.ScannedLib", true, InventoryBehaviors.class.getClassLoader());
        } finally {
            bridge.getMethod("bootUiWork", boolean.class).invoke(null, previous);
        }
        check("classes BootUI's own work loads are not counted", codeSource("inventory-lib-bootui.jar") == null);

        // Re-entrancy: a capture that loads a new class and runs an instrumented method.
        AtomicReference<Throwable> insideFailure = new AtomicReference<>();
        INSIDE_CAPTURE.set(() -> {
            try {
                Class.forName(
                        "bootuiinventorylib.reentrant.LoadedInCapture",
                        true,
                        InventoryBehaviors.class.getClassLoader());
                App.calledFromCapture();
            } catch (Throwable ex) {
                insideFailure.set(ex);
            }
        });
        Throwable outerFailure = null;
        try {
            App.reentrant();
        } catch (Throwable ex) {
            outerFailure = ex;
        }
        INSIDE_CAPTURE.set(null);
        drain(token);
        int inside = id(key("calledFromCapture()Ljava/lang/String;"));
        Map<String, Object> reentrantJar = codeSource("inventory-lib-reentrant.jar");
        check(
                "a capture that loads a class and runs an instrumented method never recurses (" + insideFailure.get()
                        + ", " + outerFailure + ")",
                !REENTERED.get()
                        && insideFailure.get() == null
                        && outerFailure == null
                        && executed(key("reentrant()Ljava/lang/String;"))
                        && executed(inside)
                        && firstHits(inside).isEmpty()
                        && reentrantJar != null
                        && classLoads(((Integer) reentrantJar.get("id")).longValue()).stream()
                                .allMatch(record -> record[5] == 0L));

        // A new claim generation: a new run.
        long next = claim();
        long nextGeneration = (Long) inventory.getMethod("currentGeneration").invoke(null);
        boolean reset = !executed(greet);
        int stale = drainCount(token);
        App.greet("next run");
        drain(next);
        check(
                "a new claim generation resets executed (" + generation + " -> " + nextGeneration + ")",
                reset
                        && executed(greet)
                        && nextGeneration > generation
                        && stale == 0
                        && firstHits(greet).stream().anyMatch(record -> record[2] == nextGeneration));
        check(
                "a class instrumented before its run started is not late in it",
                !late(id(key("beforeClaim()Ljava/lang/String;"))));
        // Its advice is live, so a call is still seen; but no class loader loaded it in this run, which is how the
        // engine tells a DevTools restart's class not loaded yet from one instrumented in this run.
        check(
                "a class instrumented in an earlier run is not tracked in a new run until it is instrumented again",
                !trackedThisRun(never) && tracking(never) == 1 && !trackedThisRun(greet) && executed(greet));

        Class<?>[] extras = refinedPackagesSurviveNarrowerClaims(next);

        afterRelease(extras);
        aClaimWithoutTheSensorRemovesIt();

        Map<String, Object> status = status();
        System.out.println("INVENTORY=" + status.get("inventory"));
        System.out.println("RING=" + status.get("ring"));
        System.out.println("SENSOR=" + sensor());
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    /**
     * Constructors delegating to {@code super(args)} and {@code this(...)}, a record, an enum, and a class whose method
     * is too large for any advice, loaded after the install.
     */
    static void constructorsRecordsEnumsAndTransformFailures() throws Exception {
        Derived derived = new Derived();
        check(
                "constructors delegating to super(args) and this(...) are instrumented and behave",
                "DEFAULT".equals(derived.name())
                        && derived.length() == 7
                        && "NAMED".equals(new Derived("named").name())
                        && executed("bootuiinventoryapp.Derived#<init>()V")
                        && executed("bootuiinventoryapp.Derived#<init>(Ljava/lang/String;)V")
                        && executed("bootuiinventoryapp.Base#<init>(Ljava/lang/String;)V"));

        Point point = new Point(1, 2);
        boolean rejected = false;
        try {
            new Point(-1, 0);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(
                "a record's constructor, accessors, and object methods are instrumented and behave",
                point.sum() == 3
                        && point.x() == 1
                        && point.equals(new Point(1, 2))
                        && "Point[x=1, y=2]".equals(point.toString())
                        && rejected
                        && executed("bootuiinventoryapp.Point#<init>(II)V")
                        && executed("bootuiinventoryapp.Point#x()I")
                        && !executed("bootuiinventoryapp.Point#y()I")
                        && executed("bootuiinventoryapp.Point#toString()Ljava/lang/String;")
                        && executed("bootuiinventoryapp.Point#equals(Ljava/lang/Object;)Z"));
        check(
                "a class loaded after the install is not late",
                tracking(id("bootuiinventoryapp.Point#sum()I")) == 1 && !late(id("bootuiinventoryapp.Point#sum()I")));

        boolean hasDollarValues = false;
        for (Method method : Color.class.getDeclaredMethods()) {
            hasDollarValues |= method.getName().equals("$values");
        }
        check(
                "an enum's constructor, values, and valueOf are instrumented and behave, its $values is not",
                Color.values().length == 2
                        && Color.valueOf("GREEN") == Color.GREEN
                        && "red".equals(Color.RED.label())
                        && executed("bootuiinventoryapp.Color#<init>(Ljava/lang/String;I)V")
                        && executed("bootuiinventoryapp.Color#values()[Lbootuiinventoryapp/Color;")
                        && executed("bootuiinventoryapp.Color#valueOf(Ljava/lang/String;)Lbootuiinventoryapp/Color;")
                        && hasDollarValues
                        && id("bootuiinventoryapp.Color#$values()[Lbootuiinventoryapp/Color;") < 0);

        long failuresBefore = (Long) inventoryStatus().get("transformFailures");
        Class<?> huge = Class.forName("bootuiinventoryapp.Huge", true, InventoryBehaviors.class.getClassLoader());
        Object big = huge.getMethod("big").invoke(null);
        Object small = huge.getMethod("small").invoke(null);
        int smallId = id("bootuiinventoryapp.Huge#small()I");
        int bigId = id("bootuiinventoryapp.Huge#big()I");
        Map<String, Object> inventoryNow = inventoryStatus();
        check(
                "a class that fails to transform still runs, and its methods are tracked as failed, never executed ("
                        + inventoryNow.get("methodsFailed") + " failed methods)",
                Integer.valueOf(1).equals(big)
                        && Integer.valueOf(2).equals(small)
                        && smallId >= 0
                        && bigId >= 0
                        && tracking(smallId) == 2
                        && tracking(bigId) == 2
                        && !executed(smallId)
                        && (Long) inventoryNow.get("transformFailures") == failuresBefore + 1
                        && (Long) inventoryNow.get("methodsFailed") >= 2L);
        check(
                "a class that fails to transform is named as failed, and not tracked in its run",
                failedClasses().contains("bootuiinventoryapp.Huge") && !trackedThisRun(smallId));
    }

    /**
     * A refine adds a package whose class already loaded: it is retransformed, late. The package stays instrumented
     * when a narrower claim follows, in a class loader created before it and one created after it, under the same
     * method ids, and a refine of that claim adds nothing twice. Returns {@code Extra} as loaded by the application's
     * class loader and by both fresh class loaders.
     */
    static Class<?>[] refinedPackagesSurviveNarrowerClaims(long token) throws Exception {
        String extraRun = "bootuiinventoryextra.Extra#run()Ljava/lang/String;";
        String extraSecond = "bootuiinventoryextra.Extra#second()Ljava/lang/String;";
        // Loaded, uninstrumented: its package is not claimed yet.
        String before = Extra.run();
        boolean unclaimed = id(extraRun) < 0;
        refine(token, "bootuiinventoryextra");
        boolean notYet = !executed(extraRun);
        Extra.run();
        int runId = id(extraRun);
        check(
                "a refine instruments the loaded classes of the package it adds, late",
                "extra".equals(before)
                        && unclaimed
                        && runId >= 0
                        && tracking(runId) == 1
                        && notYet
                        && executed(runId)
                        && late(runId));

        URL extraJar = Extra.class.getProtectionDomain().getCodeSource().getLocation();
        Class<?> second = new URLClassLoader(new URL[] {extraJar}, ClassLoader.getPlatformClassLoader())
                .loadClass(Extra.class.getName());

        // A new claim asking only for the base package, as after a DevTools restart, and a class loader created after
        // it.
        long third = claim();
        Class<?> third0 = new URLClassLoader(new URL[] {extraJar}, ClassLoader.getPlatformClassLoader())
                .loadClass(Extra.class.getName());
        boolean resetRun = !executed(extraRun);
        Object answer = third0.getMethod("run").invoke(null);
        check("a class a fresh class loader loads in a new run is tracked in it", trackedThisRun(runId));
        check(
                "the same class in a class loader created after a narrower claim keeps its method ids and flips them",
                second != Extra.class
                        && third0 != Extra.class
                        && third0 != second
                        && "extra".equals(answer)
                        && resetRun
                        && id(extraRun) == runId
                        && executed(runId)
                        && !late(runId));

        refine(third, "bootuiinventoryextra");
        check(
                "after a narrower claim and its refine, a refined package's classes in every class loader are"
                        + " instrumented",
                !executed(extraSecond)
                        && "second".equals(second.getMethod("second").invoke(null))
                        && !executed(extraSecond)
                        && "second".equals(third0.getMethod("second").invoke(null))
                        && executed(extraSecond)
                        && !late(runId));
        // Narrower again, with no refine: the release must still restore every class instrumented.
        claim();
        return new Class<?>[] {Extra.class, second, third0};
    }

    /** After the release, both transformers are gone: no advice reaches the bridge and no class load is counted. */
    static void afterRelease(Class<?>[] extras) throws Exception {
        bridge.getMethod("release", String.class, String.class).invoke(null, "inventory-behaviors", "dev");
        Object state = null;
        for (int i = 0; i < 400 && !"released".equals(state); i++) {
            Thread.sleep(25);
            state = sensor().get("state");
        }
        long slowPath = (Long) inventoryStatus().get("slowPathCalls");
        String answer = App.afterRelease();
        String loaded = bootuiinventorylib.after.AfterRelease.name();
        boolean extrasAnswer = true;
        for (Class<?> extra : extras) {
            extrasAnswer &=
                    "extra after release".equals(extra.getMethod("afterRelease").invoke(null));
        }
        long after = (Long) inventoryStatus().get("slowPathCalls");
        check(
                "release restores the classes (" + state + ", " + (after - slowPath) + " advice calls)",
                "released".equals(state)
                        && "after release".equals(answer)
                        && after == slowPath
                        && !executed(key("afterRelease()Ljava/lang/String;"))
                        && "AfterRelease".equals(loaded)
                        && codeSource("inventory-lib-after.jar") == null);
        check(
                "release restores the classes of packages refined before a narrower claim, in every class loader",
                extrasAnswer
                        && after == slowPath
                        && !executed("bootuiinventoryextra.Extra#afterRelease()Ljava/lang/String;"));
    }

    /** A claim of the sensor installs it again; a later claim that does not ask for it removes it. */
    static void aClaimWithoutTheSensorRemovesIt() throws Exception {
        claim();
        awaitState("installed");
        boolean reinstalled = Boolean.TRUE.equals(sensor().get("selfTestPassed"));
        claim(List.of());
        Object state = awaitState("released");
        long slowPath = (Long) inventoryStatus().get("slowPathCalls");
        String answer = App.neverCalled();
        long after = (Long) inventoryStatus().get("slowPathCalls");
        check(
                "a claim without the inventory sensor removes its advice (" + state + ", " + (after - slowPath)
                        + " advice calls)",
                reinstalled && "released".equals(state) && "never".equals(answer) && after == slowPath);
    }

    static Object awaitState(String expected) throws Exception {
        Object state = null;
        for (int i = 0; i < 400; i++) {
            Map<String, Object> sensor = sensor();
            state = sensor.get("state");
            if (expected.equals(state)
                    && (!"installed".equals(expected) || Boolean.TRUE.equals(sensor.get("selfTestPassed")))) {
                return state;
            }
            Thread.sleep(25);
        }
        return state;
    }

    static long claim() throws Exception {
        return claim(List.of("inventory"));
    }

    @SuppressWarnings("unchecked")
    static long claim(List<String> sensors) throws Exception {
        Object marker = new Object();
        capture = () -> {
            int[] depth = CAPTURE_DEPTH.get();
            if (depth[0] > 0) {
                REENTERED.set(true);
            }
            depth[0]++;
            try {
                Runnable inside = INSIDE_CAPTURE.getAndSet(null);
                if (inside != null) {
                    inside.run();
                }
                String[] context = CONTEXT.get();
                return context == null || marker == null
                        ? null
                        : new Object[] {context[0], null, null, null, context[1], null, null, 1L, 1L};
            } finally {
                depth[0]--;
            }
        };
        reopen = snapshot -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "inventory-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiinventoryapp"));
        request.put("sensors", sensors);
        request.put("ringCapacity", 4096);
        Method claim = bridge.getMethod("claim", Map.class, Supplier.class, Function.class);
        Map<String, Object> result = (Map<String, Object>) claim.invoke(null, request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        return (Long) result.get("token");
    }

    /** Refines the claim with {@code token} and waits for the sensor to retransform for it. */
    @SuppressWarnings("unchecked")
    static void refine(long token, String extraPackage) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("packages", List.of(extraPackage));
        Map<String, Object> result = (Map<String, Object>)
                bridge.getMethod("refine", long.class, Map.class).invoke(null, token, request);
        System.out.println("REFINE=" + result.get("status"));
        for (int i = 0; i < 400; i++) {
            Object done = sensor().get("retransformedPackages");
            if (done instanceof List<?> list && list.contains(extraPackage)) {
                return;
            }
            Thread.sleep(25);
        }
        System.out.println("REFINE_TIMEOUT=" + sensor());
    }

    @SuppressWarnings("unchecked")
    static void awaitSelfTest() throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> sensor = sensor();
            if (Boolean.TRUE.equals(sensor.get("selfTestPassed")) || sensor.get("selfTestError") != null) {
                System.out.println("SELF_TEST_inventory=" + sensor.get("selfTestPassed") + " "
                        + sensor.get("selfTestError") + " " + sensor.get("hooks"));
                System.out.println("INSTALL_MILLIS=" + sensor.get("durationMillis"));
                return;
            }
            Thread.sleep(25);
        }
        System.out.println("SELF_TEST_TIMEOUT=" + status());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> status() throws Exception {
        return (Map<String, Object>) bridge.getMethod("status").invoke(null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> inventoryStatus() throws Exception {
        return (Map<String, Object>) inventory.getMethod("status").invoke(null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sensor() throws Exception {
        Map<String, Object> agent = (Map<String, Object>) status().get("agent");
        for (Object item : (List<Object>) agent.get("sensors")) {
            Map<String, Object> sensor = (Map<String, Object>) item;
            if ("inventory".equals(sensor.get("id"))) {
                return sensor;
            }
        }
        return Map.of();
    }

    static String key(String method) {
        return "bootuiinventoryapp.App#" + method;
    }

    static int id(String key) throws Exception {
        return (Integer) inventory.getMethod("idOf", String.class).invoke(null, key);
    }

    static boolean executed(String key) throws Exception {
        return executed(id(key));
    }

    static boolean executed(int id) throws Exception {
        return bit("executed", id);
    }

    static boolean late(int id) throws Exception {
        return bit("late", id);
    }

    static boolean trackedThisRun(int id) throws Exception {
        return bit("trackedThisRun", id);
    }

    static List<String> failedClasses() throws Exception {
        Map<String, Object> snapshot = snapshot();
        return snapshot == null ? List.of() : List.of((String[]) snapshot.get("failedClasses"));
    }

    static boolean bit(String bitset, int id) throws Exception {
        Map<String, Object> snapshot = snapshot();
        if (id < 0 || snapshot == null) {
            return false;
        }
        long[] bits = (long[]) snapshot.get(bitset);
        return (id >>> 6) < bits.length && (bits[id >>> 6] & (1L << (id & 63))) != 0;
    }

    static int tracking(int id) throws Exception {
        Map<String, Object> snapshot = snapshot();
        if (id < 0 || snapshot == null) {
            return -1;
        }
        byte[] states = (byte[]) snapshot.get("tracking");
        return id < states.length ? states[id] : -1;
    }

    /** The current run's snapshot, as the engine reads it for its claim generation. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> snapshot() throws Exception {
        long generation = (Long) inventory.getMethod("currentGeneration").invoke(null);
        return (Map<String, Object>) inventory.getMethod("snapshot", long.class).invoke(null, generation);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> codeSource(String jar) throws Exception {
        for (Map<String, Object> source :
                (List<Map<String, Object>>) inventory.getMethod("codeSources").invoke(null)) {
            if (String.valueOf(source.get("location")).endsWith(jar)) {
                return source;
            }
        }
        return null;
    }

    static void drain(long token) throws Exception {
        drainCount(token);
    }

    static int drainCount(long token) throws Exception {
        Consumer<long[]> sink = record -> RECORDS.add(record.clone());
        return (Integer) ring.getMethod("drain", long.class, Consumer.class).invoke(null, token, sink);
    }

    static String interned(long generation, long id) throws Exception {
        String[] strings =
                (String[]) ring.getMethod("interned", long.class, int.class).invoke(null, generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static List<long[]> firstHits(int id) {
        return RECORDS.stream()
                .filter(record -> record[1] == 1L && record[4] == id)
                .toList();
    }

    static List<long[]> classLoads(long sourceId) {
        return RECORDS.stream()
                .filter(record -> record[1] == 2L && record[4] == sourceId)
                .toList();
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
