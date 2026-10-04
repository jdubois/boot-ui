package bootuiagentit;

import bootuihotswapapp.Shop;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * IntelliJ HotSwap while the inventory and code-paths sensors are claimed (PLAN-v2 M5-1), in a forked JVM beside the
 * agent: {@code bootuihotswapapp.Shop}, from a jar, is redefined with an edited body, as HotSwap does, then with an
 * added method, which the JVM refuses. With the argument {@code instrumentation}, this program redefines it through
 * {@link Instrumentation#redefineClasses} (Byte Buddy's agent, attached beside BootUI's, hands it over); with
 * {@code jdi}, a debugger redefines it through JDI's {@code VirtualMachine.redefineClasses} at a breakpoint on
 * {@link #redefine(String)}, as IntelliJ IDEA does. Prints one PASS or FAIL line per behavior, then the bridge's status.
 */
public final class HotSwapBehaviors {

    static final String REQUEST = "00000000000000ab";
    static final String SHOP = "bootuihotswapapp.Shop";
    static final String PRICE = SHOP + "#price()I";
    static final String BASE = SHOP + "#base()I";
    static final String LATER = SHOP + "#later()Ljava/lang/String;";

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();

    static String mode;
    /** In {@code jdi} mode, what the debugger's redefinition threw, which it sets at its breakpoint; else null. */
    static volatile String jdiRefusal;

    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;
    static long token;

    private HotSwapBehaviors() {}

    public static void main(String[] args) throws Exception {
        mode = args[0];
        token = claim();
        awaitSelfTest(CodePaths.SENSOR);
        awaitSelfTest("inventory");

        Shop shop = new Shop();
        int price = CodeInventory.idOf(PRICE);
        int base = CodeInventory.idOf(BASE);
        int later = CodeInventory.idOf(LATER);
        List<String> before = timed(shop);
        Object tracked = inventory().get("methodsTracked");
        int failedBefore = number(sensor("inventory"), "failed");
        check(
                "before the HotSwap, the bean's methods are timed and executed (" + before + ")",
                before.equals(List.of("-1:price:1", "0:base:1")) && executed(price) && !executed(later));

        redefine("v2");

        int answer = shop.price();
        check("the HotSwapped body runs (" + answer + ")", answer == 42);
        List<String> after = timed(shop);
        check(
                "the code-paths advice is applied again to the HotSwapped class (" + after + ")",
                after.equals(List.of("-1:price:1", "0:base:1")));
        shop.later();
        check("the inventory advice is applied again to the HotSwapped class", executed(later));
        check(
                "every method keeps its id",
                CodeInventory.idOf(PRICE) == price
                        && CodeInventory.idOf(BASE) == base
                        && CodeInventory.idOf(LATER) == later
                        && String.valueOf(tracked)
                                .equals(String.valueOf(inventory().get("methodsTracked"))));
        check(
                "a HotSwapped method keeps its executed flag and is neither late nor failed",
                executed(price)
                        && tracking(price) == CodeInventory.TRACKED
                        && !bit("late", price)
                        && failedClasses().isEmpty()
                        && number(sensor("inventory"), "failed") == failedBefore);

        // A schema change, as adding a method, which only an enhanced redefinition (DCEVM, JBR) accepts.
        String refused = redefine("v3");
        int afterRefused = shop.price();
        List<String> stillTimed = timed(shop);
        check(
                "a HotSwap the JVM refuses leaves the class running and instrumented (" + refused + ", " + stillTimed
                        + ")",
                refused != null
                        && afterRefused == 42
                        && stillTimed.equals(List.of("-1:price:1", "0:base:1"))
                        && CodeInventory.idOf(PRICE) == price);
        int extra = CodeInventory.idOf(SHOP + "#extra()I");
        check(
                "a method a refused HotSwap would have added is not tracked (" + tracking(extra) + ")",
                tracking(extra) != CodeInventory.TRACKED
                        && tracking(price) == CodeInventory.TRACKED
                        && failedClasses().isEmpty());

        // The next run (a restart keeping the class loader) counts the HotSwapped class afresh.
        long next = claim();
        token = next;
        boolean reset = !executed(price);
        shop.price();
        check(
                "a new run counts the HotSwapped method afresh under the same id",
                reset && executed(price) && CodeInventory.idOf(PRICE) == price);

        Map<String, Object> released = AgentBridge.release("hotswap-behaviors", "dev");
        awaitState(CodePaths.SENSOR, "released");
        List<String> none = timed(shop);
        int restored = shop.price();
        // Before JDK 20, a retransformation starts from the bytes the class was loaded with, not the HotSwapped ones
        // (JDK-7124710), so the release reverts the HotSwap there, unless an update backported the fix.
        boolean expected = restored == 42 || (Runtime.version().feature() < 20 && restored == 41);
        check(
                "a release restores the class without the advice, the HotSwapped body from JDK 20 on ("
                        + released.get("status") + ", " + none + ", " + restored + ")",
                expected && none.isEmpty());

        Map<String, Object> status = AgentBridge.status();
        System.out.println("SENSOR=" + sensor("inventory"));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    /**
     * Redefines {@code Shop} with the bytes of {@code version}: in {@code jdi} mode the debugger does it at its breakpoint
     * here. Returns the refusal, if any.
     */
    static String redefine(String version) throws Exception {
        if (!"instrumentation".equals(mode)) {
            String refusal = jdiRefusal;
            System.out.println("REDEFINE_" + version + "=" + (refusal == null ? "ok" : refusal));
            return refusal;
        }
        Path bytes = Path.of(System.getProperty("bootui.agent.it.hotswap"), "Shop-" + version + ".class");
        Instrumentation instrumentation = (Instrumentation) Class.forName("net.bytebuddy.agent.ByteBuddyAgent")
                .getMethod("getInstrumentation")
                .invoke(null);
        try {
            instrumentation.redefineClasses(new ClassDefinition(Shop.class, Files.readAllBytes(bytes)));
            System.out.println("REDEFINE_" + version + "=ok");
            return null;
        } catch (UnsupportedOperationException ex) {
            System.out.println("REDEFINE_" + version + "=" + ex);
            return ex.toString();
        }
    }

    /** {@code shop.price()} under a request: the fragment's nodes, as {@code parent:method:calls}. */
    static List<String> timed(Shop shop) {
        drain();
        CONTEXT.set(REQUEST);
        try {
            shop.price();
        } finally {
            CONTEXT.remove();
        }
        List<String> nodes = new ArrayList<>();
        for (long[] blob : drain()) {
            for (int node = 0; node < blob[CodePaths.H_NODES]; node++) {
                long method = node(blob, node, CodePaths.N_METHOD);
                String key = method < 0 ? "Other" : CodeInventory.methodKeys((int) method, 1)[0];
                String name = key.startsWith(SHOP + "#") ? key.substring(SHOP.length() + 1, key.indexOf('(')) : key;
                nodes.add(
                        node(blob, node, CodePaths.N_PARENT) + ":" + name + ":" + node(blob, node, CodePaths.N_CALLS));
            }
        }
        return nodes;
    }

    static long claim() {
        Object marker = new Object();
        capture = () -> {
            String request = CONTEXT.get();
            return request == null || marker == null
                    ? null
                    : new Object[] {request, null, null, null, "/shop", null, null, 1L, 1L};
        };
        reopen = argument -> () -> {};
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "hotswap-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuihotswapapp"));
        request.put("sensors", List.of("inventory", CodePaths.SENSOR));
        request.put("beanClasses", List.of(SHOP));
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        return (Long) result.get("token");
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
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError"));
    }

    static void awaitState(String id, String expected) throws Exception {
        for (int i = 0; i < 400 && !expected.equals(sensor(id).get("state")); i++) {
            Thread.sleep(25);
        }
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

    static Map<String, Object> inventory() {
        return CodeInventory.status();
    }

    static int number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : -1;
    }

    static boolean executed(int id) {
        return bit("executed", id);
    }

    static boolean bit(String bitset, int id) {
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        if (id < 0 || snapshot == null) {
            return false;
        }
        long[] bits = (long[]) snapshot.get(bitset);
        return (id >>> 6) < bits.length && (bits[id >>> 6] & (1L << (id & 63))) != 0;
    }

    static int tracking(int id) {
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        if (id < 0 || snapshot == null) {
            return -1;
        }
        byte[] states = (byte[]) snapshot.get("tracking");
        return id < states.length ? states[id] : -1;
    }

    static List<String> failedClasses() {
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        return snapshot == null ? List.of() : List.of((String[]) snapshot.get("failedClasses"));
    }

    static List<long[]> drain() {
        List<long[]> blobs = new ArrayList<>();
        CodePaths.drain(token, blobs::add);
        return blobs;
    }

    static long node(long[] blob, int node, int field) {
        return CodePathsBehaviors.node(blob, node, field);
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
