package bootuiagentit;

import bootuiagentit.probed.Greeter;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** The program the forked-JVM tests run beside the agent; prints {@code KEY=value} lines the tests read. */
public final class ChildMain {

    static final String BRIDGE = "io.github.jdubois.bootui.agent.bridge.AgentBridge";

    /**
     * A pool defined outside every run and living across them, as an application-wide executor does: its threads keep
     * whatever a reopened context leaves in their thread-locals, unlike the common pool's, which the JDK erases.
     */
    public static final java.util.concurrent.ExecutorService SHARED_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(1, task -> {
                Thread thread = new Thread(task, "shared-pool-worker");
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            });

    private ChildMain() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "status" -> status();
            case "probe" -> probe();
            case "claim-only" -> claimOnly();
            case "self-test" -> selfTest(args[1]);
            case "mockito" -> mockito();
            case "behaviors" -> Behaviors.main(new String[] {"agent"});
            case "thread-behaviors" -> ThreadBehaviors.main(new String[0]);
            case "inventory-behaviors" -> InventoryBehaviors.main(new String[0]);
            case "inventory-reload" -> InventoryReload.main(new String[] {args[1], args[2], args[3], args[4]});
            case "inventory-mockito" -> InventoryMockito.main(new String[] {args[1]});
            case "code-paths-behaviors" -> CodePathsBehaviors.main(new String[] {args[1]});
            case "code-paths-mockito" -> CodePathsMockito.main(new String[] {args[1]});
            case "hotswap-behaviors" -> HotSwapBehaviors.main(new String[] {args[1]});
            case "probe-behaviors" -> ProbeBehaviors.main(new String[0]);
            case "side-effects-behaviors" -> SideEffectsBehaviors.main(new String[] {args[1]});
            case "blocking-behaviors" -> BlockingBehaviors.main(new String[] {args[1]});
            case "network-behaviors" -> NetworkBehaviors.main(new String[] {args[1]});
            case "runs" -> runs(Integer.parseInt(args[1]), args[2]);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void status() throws Exception {
        Class<?> bridge = bridge();
        System.out.println("BRIDGE=" + (bridge == null ? "none" : "bootstrap"));
        if (bridge != null) {
            System.out.println("STATUS=" + bridge.getMethod("status").invoke(null));
        }
        System.out.println("LOADERS=" + loader("io.github.jdubois.bootui.agent.AgentBootstrap") + ","
                + loader("io.github.jdubois.bootui.agent.AgentClassLoader"));
    }

    /** Claims, waits, and prints the status: the published jar must install nothing. */
    /**
     * Claims with {@code sensors} (comma-separated), waits for each sensor's self-test, and prints each sensor's
     * status, the bridge's executor counters, and which apply hook ran a {@code supplyAsync} and a {@code runAsync}
     * task on a pool.
     */
    @SuppressWarnings("unchecked")
    static void selfTest(String sensors) throws Exception {
        Class<?> bridge = bridge();
        ThreadLocal<String> context = new ThreadLocal<>();
        java.util.List<String> applied = new java.util.concurrent.CopyOnWriteArrayList<>();
        Supplier<Object> capture = () -> context.get() == null
                ? null
                : new Object[] {
                    context.get(), null, null, null, null, null, null, System.currentTimeMillis(), System.nanoTime()
                };
        Function<Object, AutoCloseable> reopen = argument -> {
            Object[] call = (Object[]) argument;
            applied.add(((Object[]) call[0])[0] + " " + call[2]);
            return () -> {};
        };
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "self-test");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", List.of(sensors.split(",")));
        bridge.getMethod("claim", Map.class, Supplier.class, Function.class).invoke(null, request, capture, reopen);
        for (Map<String, Object> sensor : SensorWait.awaitSettled(List.of(sensors.split(",")))) {
            System.out.println("SENSOR_" + sensor.get("id") + "=" + sensor);
        }
        // Started after the claim: a worker already looping keeps running the runWorker it entered before it.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(1);
        pool.submit(() -> {}).get();
        context.set("request-1");
        java.util.concurrent.CompletableFuture.supplyAsync(() -> "value", pool).get();
        java.util.concurrent.CompletableFuture.runAsync(() -> {}, pool).get();
        context.remove();
        pool.shutdown();
        System.out.println("APPLIED=" + applied);
        Map<String, Object> status =
                (Map<String, Object>) bridge.getMethod("status").invoke(null);
        System.out.println("EXECUTORS=" + status.get("executors"));
    }

    static void claimOnly() throws Exception {
        Class<?> bridge = bridge();
        Object marker = new Object();
        Supplier<Object> capture = () -> marker;
        Function<Object, AutoCloseable> reopen = snapshot -> () -> {};
        Object result = bridge.getMethod("claim", Map.class, Supplier.class, Function.class)
                .invoke(
                        null,
                        Map.of("application", "claim", "packages", List.of("bootuiagentit.probed")),
                        capture,
                        reopen);
        Greeter.greet("published");
        Thread.sleep(500);
        System.out.println("CLAIM=" + result);
        System.out.println("INSTALLER=" + installer(bridge));
        System.out.println("CAPTURE_ALIVE=" + (capture != null && reopen != null));
    }

    /**
     * Claims with the probe on {@code bootuiagentit.probed}: {@code Greeter} is loaded before the claim, so it is
     * retransformed; {@code Task}, a {@code Runnable} other agents instrument too, is loaded after it. Then releases.
     */
    @SuppressWarnings("unchecked")
    static void probe() throws Exception {
        Greeter.greet("before the claim");
        Class<?> bridge = bridge();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "probe");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit.probed"));
        Object marker = new Object();
        Supplier<Object> capture = () -> marker;
        Function<Object, AutoCloseable> reopen = snapshot -> () -> {};
        Method claim = bridge.getMethod("claim", Map.class, Supplier.class, Function.class);
        Map<String, Object> result = (Map<String, Object>) claim.invoke(null, request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        awaitInstalled(bridge);
        String greeting = Greeter.greet("agent");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        pool.submit(new bootuiagentit.probed.Task()).get();
        pool.shutdown();
        System.out.println("GREETING=" + greeting);
        System.out.println("HITS=" + counter(bridge, "probeHits"));
        Map<String, Object> released = (Map<String, Object>)
                bridge.getMethod("release", String.class, String.class).invoke(null, "probe", "dev");
        System.out.println("RELEASE=" + released.get("status"));
        long before = counter(bridge, "probeHits");
        for (int i = 0; i < 50 && "installed".equals(installer(bridge).get("state")); i++) {
            Thread.sleep(50);
        }
        Greeter.greet("again");
        System.out.println("HITS_AFTER_RELEASE=" + (counter(bridge, "probeHits") - before));
        System.out.println("INSTALLER=" + installer(bridge));
        System.out.println("CAPTURE_ALIVE=" + (capture != null && reopen != null));
    }

    /**
     * Mockito's inline mock maker, attached as an agent, spies a final class the probe advises: both agents' transformers
     * apply to the same class, and the spy must still work while the probe keeps counting.
     */
    @SuppressWarnings("unchecked")
    static void mockito() throws Exception {
        Class<?> bridge = bridge();
        Object marker = new Object();
        Supplier<Object> capture = () -> marker;
        Function<Object, AutoCloseable> reopen = snapshot -> () -> {};
        Map<String, Object> result =
                (Map<String, Object>) bridge.getMethod("claim", Map.class, Supplier.class, Function.class)
                        .invoke(
                                null,
                                Map.of("application", "mockito", "packages", List.of("bootuiagentit.probed")),
                                capture,
                                reopen);
        System.out.println("CLAIM=" + result.get("status"));
        awaitInstalled(bridge);
        long before = counter(bridge, "probeHits");
        bootuiagentit.probed.Task spy = org.mockito.Mockito.spy(new bootuiagentit.probed.Task());
        spy.run();
        org.mockito.Mockito.verify(spy).run();
        System.out.println("MOCKITO=ok");
        System.out.println("HITS=" + (counter(bridge, "probeHits") - before));
        System.out.println("INSTALLER=" + installer(bridge));
        System.out.println("CAPTURE_ALIVE=" + (capture != null && reopen != null));
    }

    /** Simulated DevTools runs, each in its own child-first class loader, then a heap dump. */
    static void runs(int count, String dump) throws Exception {
        // From a jar when given, as a run's classes are outside a test root, so the inventory sensor instruments them.
        String runJar = System.getProperty("bootui.agent.it.run-jar");
        URL classes = runJar != null
                ? new File(runJar).toURI().toURL()
                : ChildMain.class.getProtectionDomain().getCodeSource().getLocation();
        for (int run = 1; run <= count; run++) {
            System.setProperty("bootui.agent.it.run", String.valueOf(run));
            RunLoader loader = new RunLoader(classes);
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> app = Class.forName("bootuiagentit.run.RunApp", true, loader);
            long token = (Long) app.getMethod("claim").invoke(null);
            if (run == 1) {
                awaitInstalled(bridge());
                // Every sensor RunApp claims: a pool worker started before the executors sensor installed keeps
                // running the untransformed runWorker, so the shared pool would never propagate.
                ThreadBehaviors.awaitSelfTests(List.of("executors", "threads", "inventory", "code-paths"));
            }
            app.getMethod("propagate").invoke(null);
            app.getMethod("disarm", long.class).invoke(null, token);
            Thread.currentThread().setContextClassLoader(ChildMain.class.getClassLoader());
        }
        System.out.println("HITS=" + counter(bridge(), "probeHits"));
        System.out.println("INSTALLER=" + installer(bridge()));
        System.out.println("EXECUTORS=" + executors(bridge()));
        System.out.println(
                "INVENTORY=" + ((Map<?, ?>) bridge().getMethod("status").invoke(null)).get("inventory"));
        System.out.println(
                "CODE_PATHS=" + ((Map<?, ?>) bridge().getMethod("status").invoke(null)).get("code-paths"));
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        new File(dump).delete();
        ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                .dumpHeap(dump, true);
        System.out.println("DUMPED=" + dump);
    }

    static void awaitInstalled(Class<?> bridge) throws Exception {
        for (int i = 0; i < 400; i++) {
            Object state = installer(bridge).get("state");
            if ("installed".equals(state) || "failed".equals(state)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the probe never installed: " + installer(bridge));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> installer(Class<?> bridge) throws Exception {
        Map<String, Object> status =
                (Map<String, Object>) bridge.getMethod("status").invoke(null);
        Map<String, Object> agent = (Map<String, Object>) status.get("agent");
        Object installer = agent == null ? null : agent.get("installer");
        return installer == null ? Map.of("state", "none") : (Map<String, Object>) installer;
    }

    @SuppressWarnings("unchecked")
    static Object executors(Class<?> bridge) throws Exception {
        return ((Map<String, Object>) bridge.getMethod("status").invoke(null)).get("executors");
    }

    @SuppressWarnings("unchecked")
    static long counter(Class<?> bridge, String name) throws Exception {
        Map<String, Object> status =
                (Map<String, Object>) bridge.getMethod("status").invoke(null);
        return (Long) ((Map<String, Object>) status.get("counters")).get(name);
    }

    static Class<?> bridge() {
        try {
            return Class.forName(BRIDGE, false, null);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    static String loader(String name) {
        try {
            ClassLoader loader = Class.forName(name, false, null).getClassLoader();
            return loader == null ? "bootstrap" : loader.getName();
        } catch (ClassNotFoundException ex) {
            return "absent";
        }
    }

    /** Child-first for the simulated run's package, as DevTools' restart class loader is for application classes. */
    static final class RunLoader extends URLClassLoader {

        RunLoader(URL classes) {
            super("run", new URL[] {classes}, ChildMain.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("bootuiagentit.run.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }
}
