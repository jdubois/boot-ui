package bootuiagentit;

import bootuicaughtapp.Handlers;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.AgentRing;
import io.github.jdubois.bootui.agent.bridge.CaughtExceptions;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The caught-exceptions sensor's behaviors (PLAN-v2 M5-6a), in a forked JVM beside the agent, on
 * {@code bootuicaughtapp}, whose classes come from a jar, with a harness engine whose context is a thread-local request
 * id. {@code alone} claims the sensor alone, {@code all} with the inventory and code-paths sensors on the same methods
 * (the code paths' advice checking every frame), {@code blockhound} runs the behaviors on a thread BlockHound watches.
 * Prints one PASS or FAIL line per behavior, then the sensor's status.
 */
public final class CaughtExceptionsBehaviors {

    static final String REQUEST = "00000000000000ab";
    static final long REQUEST_BITS = 0xabL;
    static final String APP = "bootuicaughtapp/Handlers#";

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;
    static long token;

    private CaughtExceptionsBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.startsWith("mockito-")) {
            mockito(mode.substring("mockito-".length()));
            return;
        }
        // Loaded and run before the claim: its class is retransformed when the sensor installs.
        Handlers early = new Handlers();
        Object blockHound = "blockhound".equals(mode) ? installBlockHound() : null;
        List<String> sensors = "all".equals(mode)
                ? List.of("inventory", "code-paths", CaughtExceptions.SENSOR)
                : List.of(CaughtExceptions.SENSOR);
        token = claim(sensors, List.of("bootuicaughtapp.Handlers"));
        awaitSelfTest(CaughtExceptions.SENSOR);
        if ("all".equals(mode)) {
            awaitSelfTest("code-paths");
            awaitSelfTest("inventory");
        }
        Callable<Void> behaviors = () -> {
            behaviors(early);
            return null;
        };
        if (blockHound != null) {
            Throwable[] failure = new Throwable[1];
            Thread thread = new Thread(
                    () -> {
                        try {
                            behaviors.call();
                        } catch (Throwable ex) {
                            failure[0] = ex;
                        }
                    },
                    "nonblocking-behaviors");
            thread.start();
            thread.join();
            check("the behaviors ran on a thread BlockHound watches (" + failure[0] + ")", failure[0] == null);
        } else {
            behaviors.call();
        }
        aFreshClassLoaderGetsTheVisitAsItDefinesAClassWithTheSameSites();
        aClaimSwitchingTheSensorOffThenOnRunsItsSelfTestAgainBeforeRecording(sensors, early);
        releaseRestores(early);
        System.out.println("SENSOR=" + sensor(CaughtExceptions.SENSOR));
        System.out.println("SENSOR_inventory=" + sensor("inventory"));
        System.out.println("CAUGHT=" + CaughtExceptions.status());
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + AgentBridge.status());
    }

    static void behaviors(Handlers handlers) throws Exception {
        drain();
        withRequest(handlers::swallowed);
        List<String> swallowed = drain();
        check(
                "a swallowed exception is one caught record with its site, class, and request (" + swallowed + ")",
                swallowed.equals(List.of("CAUGHT " + APP + "swallowed()I#0#java/io/IOException java.io.IOException")));

        withRequest(() -> expect(IllegalStateException.class, handlers::rethrows));
        List<String> rethrown = drain();
        check(
                "a rethrown exception is caught, then thrown from its method's exit (" + rethrown + ")",
                rethrown.equals(List.of(
                        "CAUGHT " + APP + "rethrows()I#0#java/lang/IllegalStateException java.lang.IllegalStateException",
                        "THROWN-exit " + APP + "rethrows()I#0#java/lang/IllegalStateException")));

        withRequest(() -> expect(java.io.UncheckedIOException.class, handlers::wraps));
        List<String> wrapped = drain();
        check(
                "a wrapped rethrow is found through the cause (" + wrapped + ")",
                wrapped.equals(List.of(
                        "CAUGHT " + APP + "wraps()I#0#java/io/IOException java.io.IOException",
                        "THROWN-exit " + APP + "wraps()I#0#java/io/IOException")));

        withRequest(() -> expect(IllegalArgumentException.class, () -> {
            handlers.helper();
            return null;
        }));
        List<String> helper = drain();
        check(
                "a rethrow by a library-style helper is seen at the method's exit (" + helper + ")",
                helper.equals(List.of(
                        "CAUGHT " + APP + "helper()V#0#java/lang/IllegalArgumentException"
                                + " java.lang.IllegalArgumentException",
                        "THROWN-exit " + APP + "helper()V#0#java/lang/IllegalArgumentException")));

        withRequest(handlers::nested);
        List<String> nested = drain();
        check(
                "an exception rethrown and caught again in its method is found by the outer handler (" + nested + ")",
                nested.equals(List.of(
                        "CAUGHT " + APP + "nested()I#0#java/lang/IllegalStateException java.lang.IllegalStateException",
                        "THROWN-again " + APP + "nested()I#0#java/lang/IllegalStateException",
                        "CAUGHT " + APP + "nested()I#1#java/lang/IllegalStateException java.lang.IllegalStateException")));

        withRequest(handlers::finallyOnly);
        withRequest(() -> handlers.multi(1));
        List<String> others = drain();
        check(
                "a finally reports nothing, a multi-catch one site (" + others + ")",
                others.equals(List.of("CAUGHT " + APP
                        + "multi(I)I#0#java/lang/IllegalStateException|java/lang/UnsupportedOperationException"
                        + " java.lang.UnsupportedOperationException")));

        withRequest(() -> handlers.loop(CaughtExceptions.PER_SITE + 4));
        CaughtExceptions.flushThread();
        List<String> loop = drain();
        long caught = loop.stream().filter(line -> line.startsWith("CAUGHT")).count();
        check(
                "a site caught often publishes its first occurrences, then counts the rest (" + loop.size() + " "
                        + loop.get(loop.size() - 1) + ")",
                caught == CaughtExceptions.PER_SITE
                        && loop.get(loop.size() - 1).equals("UNTRACKED " + APP
                                + "loop(I)I#0#java/lang/IllegalStateException 4"));

        withRequest(() -> new Handlers().built());
        withRequest(handlers::lambda);
        List<String> shapes = drain();
        check(
                "a constructor's and a lambda's handlers are reported (" + shapes + ")",
                shapes.equals(List.of(
                        "CAUGHT " + APP + "<init>()V#0#java/lang/NumberFormatException"
                                + " java.lang.NumberFormatException",
                        "CAUGHT " + APP + "lambda$lambda$0()I#0#java/lang/IllegalStateException"
                                + " java.lang.IllegalStateException")));

        handlers.swallowed();
        handlers.resources();
        handlers.locked();
        handlers.defaults();
        check("without an owner nothing is recorded", drain().isEmpty());
        long wide = Handlers.wide(1L, 2.0d, " a ", 3);
        int multi = handlers.multi(0) + handlers.multi(1);
        check("transformed methods answer as before (" + wide + ", " + multi + ")", wide == 10L && multi == 11);
    }

    /** A later run's class loader defines the class with the visit applied as it loads, with the same site ids. */
    static void aFreshClassLoaderGetsTheVisitAsItDefinesAClassWithTheSameSites() throws Exception {
        drain();
        int sitesBefore = CaughtExceptions.siteCount();
        URL jar = new File(System.getProperty("caught.app.jar")).toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar}, null)) {
            Class<?> type = Class.forName("bootuicaughtapp.Handlers", true, loader);
            Object fresh = type.getConstructor().newInstance();
            CONTEXT.set(REQUEST);
            Object answer = type.getMethod("swallowed").invoke(fresh);
            CONTEXT.remove();
            List<String> records = drain();
            check(
                    "a class a fresh class loader defines gets the visit as it loads, with the same site ids ("
                            + records + ")",
                    Integer.valueOf(1).equals(answer)
                            && type.getClassLoader() == loader
                            && records.equals(List.of(
                                    "CAUGHT " + APP + "swallowed()I#0#java/io/IOException java.io.IOException"))
                            && CaughtExceptions.siteCount() == sitesBefore);
        }
    }

    /**
     * A claim without the sensor removes its visit (with no other application-methods sensor, the whole transformer);
     * the next claim asking for it again self-tests it again, while it records nothing, then records.
     */
    static void aClaimSwitchingTheSensorOffThenOnRunsItsSelfTestAgainBeforeRecording(
            List<String> sensors, Handlers handlers) throws Exception {
        List<String> without = new ArrayList<>(sensors);
        without.remove(CaughtExceptions.SENSOR);
        token = claim(without, List.of("bootuicaughtapp.Handlers"));
        if (!without.isEmpty()) {
            awaitSelfTest(without.get(0));
        }
        awaitIdle(CaughtExceptions.SENSOR);
        drain();
        withRequest(handlers::swallowed);
        List<String> off = drain();
        token = claim(sensors, List.of("bootuicaughtapp.Handlers"));
        Map<String, Object> again = SensorWait.awaitSettled(CaughtExceptions.SENSOR);
        withRequest(handlers::swallowed);
        List<String> on = drain();
        check(
                "a claim switching the sensor off then on runs its self-test again before recording (" + off + ", "
                        + again.get("selfTestPassed") + ", " + on + ")",
                off.isEmpty()
                        && Boolean.TRUE.equals(again.get("selfTestPassed"))
                        && on.equals(List.of(
                                "CAUGHT " + APP + "swallowed()I#0#java/io/IOException java.io.IOException")));
    }

    static void awaitIdle(String id) throws Exception {
        for (int i = 0; i < 400 && !Boolean.TRUE.equals(sensor(id).get("idle")); i++) {
            Thread.sleep(25);
        }
    }

    /** After a release the classes are restored: their handlers no longer call the bridge, even in its self-test. */
    static void releaseRestores(Handlers handlers) throws Exception {
        AgentBridge.release("caught-exceptions-behaviors", "dev");
        String state = null;
        for (int i = 0; i < 400; i++) {
            Map<String, Object> sensor = sensor(CaughtExceptions.SENSOR);
            state = String.valueOf(sensor.get("state"));
            if ("released".equals(state) && Boolean.TRUE.equals(sensor.get("idle"))) {
                break;
            }
            Thread.sleep(25);
        }
        CaughtExceptions.beginSelfTest();
        handlers.swallowed();
        long[] hits = CaughtExceptions.endSelfTest();
        check("release restores the classes (" + state + ", " + hits[0] + ")", "released".equals(state) && hits[0] == 0);
    }

    /**
     * Mockito's inline mock maker beside the sensor, in both transformer orders: {@code bootui-first} claims before the
     * first spy, {@code mockito-first} spies before the claim. A spy's real call is reported, and stubbing still works.
     */
    static void mockito(String order) throws Exception {
        Handlers spy = null;
        if ("mockito-first".equals(order)) {
            spy = org.mockito.Mockito.spy(new Handlers());
        }
        token = claim(List.of(CaughtExceptions.SENSOR), List.of());
        awaitSelfTest(CaughtExceptions.SENSOR);
        if (spy == null) {
            spy = org.mockito.Mockito.spy(new Handlers());
        }
        org.mockito.Mockito.doReturn(42).when(spy).multi(5);
        drain();
        Handlers called = spy;
        withRequest(called::swallowed);
        List<String> records = drain();
        int stubbed = spy.multi(5);
        org.mockito.Mockito.verify(spy).swallowed();
        System.out.println("MOCKITO=" + (stubbed == 42 ? "ok" : "unexpected " + stubbed));
        System.out.println("MOCK_CLASS=" + spy.getClass().getName());
        System.out.println("SPY=" + records);
        System.out.println("SENSOR=" + sensor(CaughtExceptions.SENSOR));
        System.out.println("CAUGHT=" + CaughtExceptions.status());
        System.out.println("STATUS=" + AgentBridge.status());
    }

    // ---- harness ---------------------------------------------------------------------------------------------------

    static void withRequest(Callable<?> work) throws Exception {
        CONTEXT.set(REQUEST);
        try {
            work.call();
        } finally {
            CONTEXT.remove();
        }
    }

    static Object expect(Class<? extends Throwable> type, Callable<?> work) {
        try {
            work.call();
            throw new AssertionError("expected " + type.getName());
        } catch (Throwable ex) {
            if (!type.isInstance(ex)) {
                throw new AssertionError("expected " + type.getName() + " but got " + ex, ex);
            }
            return null;
        }
    }

    static long claim(List<String> sensors, List<String> beans) {
        Object marker = new Object();
        capture = () -> {
            String request = CONTEXT.get();
            return request == null || marker == null
                    ? null
                    : new Object[] {request, null, null, null, "/orders", null, null, 1L, 1L};
        };
        reopen = argument -> () -> {};
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "caught-exceptions-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuicaughtapp"));
        request.put("sensors", sensors);
        request.put("beanClasses", beans);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        return (Long) result.get("token");
    }

    static void awaitSelfTest(String id) throws Exception {
        Map<String, Object> sensor = SensorWait.awaitSettled(id);
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
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

    /** Site ids to keys. */
    static Map<Integer, String> sites() {
        Map<Integer, String> sites = new HashMap<>();
        String[] all = CaughtExceptions.sites(0);
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null) {
                sites.put(i, all[i].split("\t")[0]);
            }
        }
        return sites;
    }

    /** The caught-exceptions records drained since the last drain, described; other sensors' records are skipped. */
    static List<String> drain() {
        List<long[]> records = new ArrayList<>();
        AgentRing.drain(token, record -> {
            if (record[AgentRing.SENSOR] == AgentRing.SENSOR_CAUGHT_EXCEPTIONS) {
                records.add(record.clone());
            }
        });
        Map<Integer, String> sites = sites();
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            long site = record[AgentRing.PAYLOAD + 2] >>> 32;
            long flags = record[AgentRing.PAYLOAD + 3];
            String key = sites.get((int) site);
            switch ((int) record[AgentRing.TYPE]) {
                case CaughtExceptions.TYPE_CAUGHT -> {
                    boolean owned = record[AgentRing.PAYLOAD] == REQUEST_BITS;
                    described.add((owned ? "CAUGHT " : "CAUGHT-unowned ") + key + " " + interned(record, flags >>> 32));
                }
                case CaughtExceptions.TYPE_THROWN -> described.add(
                        ((flags & 0xFF) == CaughtExceptions.THROWN_EXIT ? "THROWN-exit " : "THROWN-again ") + key);
                case CaughtExceptions.TYPE_UNTRACKED -> described.add(
                        "UNTRACKED " + key + " " + (int) record[AgentRing.PAYLOAD + 2]);
                default -> described.add("TYPE" + record[AgentRing.TYPE] + " " + key);
            }
        }
        return described;
    }

    static String interned(long[] record, long id) {
        String[] strings = AgentRing.interned(record[AgentRing.GENERATION], (int) id);
        return strings == null || strings.length == 0 ? "?" : strings[0];
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }

    /** BlockHound, watching threads named {@code nonblocking-}: a blocking call there throws. */
    static Object installBlockHound() throws Exception {
        Class<?> blockHound = Class.forName("reactor.blockhound.BlockHound");
        Object builder = blockHound.getMethod("builder").invoke(null);
        java.util.function.Function<java.util.function.Predicate<Thread>, java.util.function.Predicate<Thread>> watch =
                predicate -> predicate.or(thread -> thread.getName().startsWith("nonblocking-"));
        builder = builder.getClass()
                .getMethod("nonBlockingThreadPredicate", java.util.function.Function.class)
                .invoke(builder, watch);
        builder.getClass().getMethod("install").invoke(builder);
        // Proves it is watching: a sleep on a watched thread must throw.
        Throwable[] seen = new Throwable[1];
        Thread probe = new Thread(
                () -> {
                    try {
                        Thread.sleep(1);
                    } catch (Throwable ex) {
                        seen[0] = ex;
                    }
                },
                "nonblocking-probe");
        probe.start();
        probe.join();
        check("BlockHound watches the behaviors' thread (" + seen[0] + ")", seen[0] != null
                && seen[0].getClass().getName().contains("BlockingOperationError"));
        return builder;
    }
}
