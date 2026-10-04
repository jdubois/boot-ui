package bootuiagentit;

import bootuicodepathsapp.Deep;
import bootuicodepathsapp.LateBean;
import bootuicodepathsapp.OrderController;
import bootuicodepathsapp.OrderService;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The code-paths sensor's behaviors (PLAN-v2 M5-4a), in a forked JVM beside the agent, claimed with the executors and
 * code-paths sensors, and with the inventory sensor too when the first argument is {@code both}, on
 * {@code bootuicodepathsapp}, whose classes come from a jar, with a harness engine whose context is a thread-local
 * request id and execution id. Prints one PASS or FAIL line per behavior, then the bridge's status.
 */
public final class CodePathsBehaviors {

    static final String REQUEST = "00000000000000ab";
    static final long REQUEST_BITS = 0xabL;
    static final String EXECUTION = "async-00000000000000cd";
    static final long EXECUTION_BITS = 0xcdL;
    static final String APP = "bootuicodepathsapp.";

    static final List<String> BEANS = List.of(
            APP + "OrderController",
            APP + "OrderService",
            APP + "Money",
            APP + "ShopProperties",
            APP + "Deep",
            APP + "PriceClient");

    /** The harness engine's context: {request, execution}. */
    static final ThreadLocal<String[]> CONTEXT = new ThreadLocal<>();

    static final List<String> RESULTS = new ArrayList<>();
    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;
    static boolean both;
    static long token;

    private CodePathsBehaviors() {}

    public static void main(String[] args) throws Exception {
        if ("tight".equals(args[0])) {
            tight();
            return;
        }
        both = "both".equals(args[0]);
        // Loaded and run before its refine names it a bean class.
        LateBean late = new LateBean();
        late.depthInside();

        token = claim(sensors(both), BEANS);
        awaitSelfTests(both);
        // The handoff needs the executors sensor installed before its pool's worker starts.
        awaitSelfTest("executors");

        callChainAndPhases();
        exceptionPath();
        handoff();
        noOwner();
        exclusions();
        depthCap();
        refineRetransformsALoadedBean(late);
        aFreshClassLoaderGetsAdviceAtLoad();
        if (both) {
            bothVisitsOnTheSameMethods();
            changingTheSetRetransforms();
        }
        releaseRestores();

        Map<String, Object> status = AgentBridge.status();
        System.out.println("CODE_PATHS=" + status.get(CodePaths.SENSOR));
        System.out.println("SENSOR=" + sensor(CodePaths.SENSOR));
        System.out.println("SENSOR_inventory=" + sensor("inventory"));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static List<String> sensors(boolean withInventory) {
        return withInventory ? List.of("executors", "inventory", "code-paths") : List.of("executors", "code-paths");
    }

    static void callChainAndPhases() {
        drain();
        OrderController controller = new OrderController();
        CONTEXT.set(new String[] {REQUEST, null});
        CodePaths.phase(CodePaths.PHASE_HANDLER);
        int answer = controller.place(3);
        CodePaths.phase(CodePaths.PHASE_UNKNOWN);
        CONTEXT.remove();
        List<long[]> blobs = drain();
        long[] blob = blobs.size() == 1 ? blobs.get(0) : null;
        check(
                "a bean call chain under a captured request yields one fragment with the right tree and phases ("
                        + describe(blobs) + ")",
                answer == 37
                        && blob != null
                        && blob[CodePaths.H_REQUEST] == REQUEST_BITS
                        && blob[CodePaths.H_EXECUTION] == 0L
                        && nodes(blob)
                                .equals(List.of(
                                        "-1:OrderController#place:1",
                                        "0:OrderService#price:1",
                                        "1:OrderService#rounding:1",
                                        "0:OrderService#tax:1"))
                        && allPhases(blob, CodePaths.PHASE_HANDLER)
                        && CodePaths.depth() == 0);
    }

    static void exceptionPath() {
        drain();
        CONTEXT.set(new String[] {REQUEST, null});
        int answer = new OrderController().failing();
        CONTEXT.remove();
        List<long[]> blobs = drain();
        check(
                "an exception path stays balanced (" + describe(blobs) + ")",
                answer == -1
                        && CodePaths.depth() == 0
                        && blobs.size() == 1
                        && nodes(blobs.get(0))
                                .equals(List.of("-1:OrderController#failing:1", "0:OrderService#fail:1")));
    }

    static void handoff() throws Exception {
        drain();
        // Created after the claim, so its workers run the executors sensor's transformed runWorker.
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            CONTEXT.set(new String[] {REQUEST, null});
            int answer = new OrderController().handoff(pool);
            CONTEXT.remove();
            List<long[]> blobs = drain();
            long[] worker = null;
            long[] submitter = null;
            for (long[] blob : blobs) {
                if (blob[CodePaths.H_EXECUTION] != 0L) {
                    worker = blob;
                } else {
                    submitter = blob;
                }
            }
            check(
                    "a handoff fragment carries the execution id (" + describe(blobs) + ")",
                    answer == 20
                            && blobs.size() == 2
                            && worker != null
                            && submitter != null
                            && worker[CodePaths.H_REQUEST] == REQUEST_BITS
                            && worker[CodePaths.H_EXECUTION] == EXECUTION_BITS
                            && (worker[CodePaths.H_FLAGS] & 15) == CodePaths.EXECUTION_ASYNC
                            && nodes(worker).equals(List.of("-1:OrderService#price:1", "0:OrderService#rounding:1"))
                            && nodes(submitter).equals(List.of("-1:OrderController#handoff:1")));
        } finally {
            pool.shutdown();
        }
    }

    static void noOwner() {
        drain();
        long unowned = (Long) CodePaths.status().get("unowned");
        new OrderController().place(1);
        List<long[]> blobs = drain();
        long after = (Long) CodePaths.status().get("unowned");
        check(
                "no owner records nothing, capturing once per outermost call (" + (after - unowned) + ")",
                blobs.isEmpty() && after - unowned == 1 && CodePaths.depth() == 0);
    }

    static void exclusions() {
        drain();
        CONTEXT.set(new String[] {REQUEST, null});
        new OrderController().excluded();
        CONTEXT.remove();
        List<long[]> blobs = drain();
        check(
                "private, static, $-prefixed, Object, record accessor, constructor, configuration-properties, and"
                        + " non-bean methods are left alone (" + describe(blobs) + ")",
                blobs.size() == 1
                        && nodes(blobs.get(0))
                                .equals(List.of(
                                        "-1:OrderController#excluded:1",
                                        "0:OrderService#callExcluded:1",
                                        "1:Money#doubled:1")));
    }

    static void depthCap() {
        drain();
        CONTEXT.set(new String[] {REQUEST, null});
        int levels = new Deep().down(40);
        CONTEXT.remove();
        List<long[]> blobs = drain();
        long[] blob = blobs.size() == 1 ? blobs.get(0) : null;
        check(
                "calls past 32 levels stay in the level-32 node (" + describe(blobs) + ")",
                levels == 40
                        && blob != null
                        && blob[CodePaths.H_NODES] == CodePaths.MAX_DEPTH
                        && blob[CodePaths.H_DROPPED] == 41 - CodePaths.MAX_DEPTH);
    }

    static void refineRetransformsALoadedBean(LateBean late) throws Exception {
        CodePaths.beginSelfTest();
        int before;
        try {
            before = late.depthInside();
        } finally {
            CodePaths.endSelfTest();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("packages", List.of());
        request.put("beanClasses", List.of(APP + "LateBean"));
        Map<String, Object> result = AgentBridge.refine(token, request);
        int after = 0;
        for (int i = 0; i < 400 && after != 1; i++) {
            Thread.sleep(25);
            CodePaths.beginSelfTest();
            try {
                after = late.depthInside();
            } finally {
                CodePaths.endSelfTest();
            }
        }
        check(
                "a refine naming a loaded bean class retransforms it (" + before + " -> " + after + ", "
                        + result.get("status") + ")",
                before == 0 && after == 1);
    }

    /** A new run's claim names no bean class, as a DevTools restart's early claim: the union still applies. */
    static void aFreshClassLoaderGetsAdviceAtLoad() throws Exception {
        token = claim(sensors(both), List.of());
        awaitSelfTests(both);
        URL jar = OrderService.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader fresh = new URLClassLoader(
                new URL[] {jar}, CodePathsBehaviors.class.getClassLoader().getParent());
        Class<?> service = fresh.loadClass(OrderService.class.getName());
        Object instance = service.getConstructor().newInstance();
        CodePaths.beginSelfTest();
        Object depth;
        try {
            depth = service.getMethod("depthInside").invoke(instance);
        } finally {
            CodePaths.endSelfTest();
        }
        check(
                "a bean class a fresh class loader loads in a later run gets its advice as it loads (" + depth + ")",
                service != OrderService.class && Integer.valueOf(1).equals(depth));
    }

    static void bothVisitsOnTheSameMethods() throws Exception {
        drain();
        String price = APP + "OrderService#price(I)I";
        boolean before = executed(price);
        CONTEXT.set(new String[] {REQUEST, null});
        new OrderController().place(2);
        CONTEXT.remove();
        List<long[]> blobs = drain();
        check(
                "inventory and code-paths together: both visits on the same methods (" + before + ", " + describe(blobs)
                        + ")",
                !before
                        && executed(price)
                        && blobs.size() == 1
                        && nodes(blobs.get(0)).contains("0:OrderService#price:1"));
    }

    /** From both sensors to code-paths alone, then to inventory alone: each claim retransforms what changes. */
    static void changingTheSetRetransforms() throws Exception {
        token = claim(List.of("executors", "code-paths"), List.of());
        awaitState("inventory", "released");
        awaitSelfTests(false);
        long slowPath = (Long) CodeInventory.status().get("slowPathCalls");
        drain();
        CONTEXT.set(new String[] {REQUEST, null});
        // Not called yet in this inventory run: with its advice still there, it would take the slow path.
        new OrderController().failing();
        CONTEXT.remove();
        long slowPathAfter = (Long) CodeInventory.status().get("slowPathCalls");
        List<long[]> blobs = drain();
        check(
                "a claim dropping the inventory sensor removes its visit and keeps code-paths' ("
                        + (slowPathAfter - slowPath) + " inventory calls, " + describe(blobs) + ")",
                slowPathAfter == slowPath && blobs.size() == 1);

        token = claim(List.of("executors", "inventory"), List.of());
        awaitState("code-paths", "released");
        awaitState("inventory", "installed");
        CodePaths.beginSelfTest();
        int depth;
        try {
            depth = new OrderService().depthInside();
        } finally {
            CodePaths.endSelfTest();
        }
        String price = APP + "OrderService#price(I)I";
        new OrderController().place(1);
        check(
                "a claim dropping the code-paths sensor removes its visit and keeps the inventory's (" + depth + ")",
                depth == 0 && executed(price));
        token = claim(sensors(true), List.of());
        awaitSelfTests(true);
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("code-paths-behaviors", "dev");
        Object state = awaitState(CodePaths.SENSOR, "released");
        CodePaths.beginSelfTest();
        int depth;
        try {
            depth = new OrderService().depthInside();
        } finally {
            CodePaths.endSelfTest();
        }
        check("release restores the classes (" + state + ", " + depth + ")", "released".equals(state) && depth == 0);
    }

    /**
     * A bean class whose method fits the inventory's advice but not both sensors': its transformation with both fails,
     * so it is retransformed with the inventory's alone, keeping its inventory, and never gets the code paths' again.
     */
    static void tight() throws Exception {
        String tight = APP + "Tight";
        List<String> beans = new ArrayList<>(BEANS);
        beans.add(tight);
        token = claim(sensors(true), beans);
        awaitSelfTests(true);
        Class<?> type = Class.forName(tight);
        Object instance = type.getConstructor().newInstance();
        String key = tight + "#tight()I";
        // Tracked is set while the retry retransforms the class, before the JVM installs its new code: wait for the
        // retry job to finish too.
        boolean tracked = false;
        for (int i = 0; i < 400 && !tracked; i++) {
            Thread.sleep(25);
            tracked = tracking(key) == CodeInventory.TRACKED
                    && Boolean.TRUE.equals(sensor("inventory").get("idle"));
        }
        drain();
        CONTEXT.set(new String[] {REQUEST, null});
        Object answer = type.getMethod("tight").invoke(instance);
        CONTEXT.remove();
        List<long[]> blobs = drain();
        Map<String, Object> inventory = sensor("inventory");
        String failures = String.valueOf(inventory.get("failures"));
        check(
                "a class the code-paths visit pushes past the 64 KB limit keeps its inventory visit (" + tracking(key)
                        + ", " + executed(key) + ", " + describe(blobs) + ", " + failures + ")",
                Integer.valueOf(1).equals(answer)
                        && tracked
                        && executed(key)
                        && blobs.isEmpty()
                        && failures.contains("code paths left out of " + tight)
                        && !java.util.Arrays.asList((String[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                        .get("failedClasses"))
                                .contains(tight));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + AgentBridge.status());
    }

    static int tracking(String key) {
        int id = CodeInventory.idOf(key);
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        if (id < 0 || snapshot == null) {
            return -1;
        }
        byte[] tracking = (byte[]) snapshot.get("tracking");
        return id < tracking.length ? tracking[id] : -1;
    }

    // ---- harness
    // -----------------------------------------------------------------------------------------------------

    static long claim(List<String> sensors, List<String> beans) {
        Object marker = new Object();
        capture = () -> {
            String[] context = CONTEXT.get();
            return context == null || marker == null
                    ? null
                    : new Object[] {context[0], context[1], null, null, "/orders", null, null, 1L, 1L};
        };
        reopen = argument -> {
            Object[] snapshot = (Object[]) ((Object[]) argument)[0];
            String[] previous = CONTEXT.get();
            CONTEXT.set(new String[] {(String) snapshot[0], EXECUTION});
            return () -> CONTEXT.set(previous);
        };
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "code-paths-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuicodepathsapp"));
        request.put("sensors", sensors);
        request.put("beanClasses", beans);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        return (Long) result.get("token");
    }

    static void awaitSelfTests(boolean withInventory) throws Exception {
        List<String> ids = withInventory ? List.of(CodePaths.SENSOR, "inventory") : List.of(CodePaths.SENSOR);
        for (String id : ids) {
            awaitSelfTest(id);
        }
    }

    static void awaitSelfTest(String id) throws Exception {
        Map<String, Object> sensor = Map.of();
        for (int i = 0; i < 400; i++) {
            sensor = sensor(id);
            if (Boolean.TRUE.equals(sensor.get("selfTestPassed")) || sensor.get("selfTestError") != null) {
                break;
            }
            Thread.sleep(25);
        }
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
    }

    static Object awaitState(String id, String expected) throws Exception {
        Object state = null;
        for (int i = 0; i < 400; i++) {
            Map<String, Object> sensor = sensor(id);
            state = sensor.get("state");
            if (expected.equals(state) && Boolean.TRUE.equals(sensor.get("idle"))) {
                return state;
            }
            Thread.sleep(25);
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sensor(String id) {
        Map<String, Object> agent = (Map<String, Object>) AgentBridge.status().get("agent");
        for (Object item : (List<Object>) agent.get("sensors")) {
            Map<String, Object> sensor = (Map<String, Object>) item;
            if (id.equals(sensor.get("id"))) {
                return sensor;
            }
        }
        return Map.of();
    }

    static boolean executed(String key) {
        int id = CodeInventory.idOf(key);
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        if (id < 0 || snapshot == null) {
            return false;
        }
        long[] bits = (long[]) snapshot.get("executed");
        return (id >>> 6) < bits.length && (bits[id >>> 6] & (1L << (id & 63))) != 0;
    }

    static List<long[]> drain() {
        List<long[]> blobs = new ArrayList<>();
        CodePaths.drain(token, blobs::add);
        return blobs;
    }

    static long node(long[] blob, int node, int field) {
        return blob[CodePaths.HEADER + node * CodePaths.NODE + field];
    }

    /** {@code parent:Class#method:calls} per node, in node order. */
    static List<String> nodes(long[] blob) {
        List<String> list = new ArrayList<>();
        for (int node = 0; node < blob[CodePaths.H_NODES]; node++) {
            long method = node(blob, node, CodePaths.N_METHOD);
            String key = method < 0 ? "Other" : CodeInventory.methodKeys((int) method, 1)[0];
            String name = key.startsWith(APP) ? key.substring(APP.length(), key.indexOf('(')) : key;
            list.add(node(blob, node, CodePaths.N_PARENT) + ":" + name + ":" + node(blob, node, CodePaths.N_CALLS));
        }
        return list;
    }

    static boolean allPhases(long[] blob, int phase) {
        for (int node = 0; node < blob[CodePaths.H_NODES]; node++) {
            if (node(blob, node, CodePaths.N_PHASE) != phase) {
                return false;
            }
        }
        return true;
    }

    static String describe(List<long[]> blobs) {
        List<String> described = new ArrayList<>();
        for (long[] blob : blobs) {
            described.add("exec=" + Long.toHexString(blob[CodePaths.H_EXECUTION]) + " " + nodes(blob));
        }
        return described.toString();
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
