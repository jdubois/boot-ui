package bootuiagentit;

import bootuicodepathsapp.OrderController;
import bootuicodepathsapp.OrderService;
import bootuicodepathsapp.Quotes;
import bootuicodepathsapp.Shapes;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.AgentRing;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import io.github.jdubois.bootui.agent.bridge.ProbeShapes;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Method probes' behaviors (PLAN-v2 M5-8), in a forked JVM beside the agent, claimed with the executors, inventory, and
 * code-paths sensors on {@code bootuicodepathsapp}, whose classes come from a jar, with the code-paths behaviors'
 * harness engine. Prints one PASS or FAIL line per behavior, then the bridge's status.
 */
public final class ProbeBehaviors {

    static final String APP = CodePathsBehaviors.APP;
    static final String PRICE = APP + "OrderService#price(I)I";

    private ProbeBehaviors() {}

    public static void main(String[] args) throws Exception {
        CodePathsBehaviors.both = true;
        CodePathsBehaviors.token = CodePathsBehaviors.claim(CodePathsBehaviors.sensors(true), beans());
        CodePathsBehaviors.awaitSelfTests(true);

        List<Behavior> behaviors = List.of(
                ProbeBehaviors::twentyInvocationsThenRemoved,
                ProbeBehaviors::theWindowEndsAProbeWithoutCalls,
                ProbeBehaviors::anExplicitStopEndsAProbe,
                ProbeBehaviors::exceptionsAreRecordedByType,
                ProbeBehaviors::overloadsNeedADescriptor,
                ProbeBehaviors::aClassLoadedLaterIsProbedAsItLoads,
                ProbeBehaviors::fiveAtOnceThenTheSixthIsRefused,
                ProbeBehaviors::aCallInFlightAcrossTheInstallAndTheRemoval,
                ProbeBehaviors::aShapesProbeRecordsShapesWithoutRunningApplicationCode,
                ProbeBehaviors::returnShapesNameApplicationCollectionsByClassAndJdkListsBySize,
                ProbeBehaviors::aNewRunEndsItsProbes,
                ProbeBehaviors::aRestartProbesOnlyTheNewRunsCopy,
                ProbeBehaviors::aReleaseEndsAndRemovesProbes);
        for (Behavior behavior : behaviors) {
            try {
                behavior.run();
            } catch (Exception ex) {
                CodePathsBehaviors.check("a behavior threw " + ex + " with probes " + MethodProbes.list(), false);
            }
            // A failed behavior's probes would hold slots the next ones need.
            for (Map<String, Object> probe : MethodProbes.list()) {
                Object state = probe.get("state");
                if ("starting".equals(state) || "active".equals(state) || "ending".equals(state)) {
                    MethodProbes.stop(CodePathsBehaviors.token, (Long) probe.get("id"));
                    await((Long) probe.get("id"), ProbeBehaviors::removed);
                }
            }
        }

        Map<String, Object> status = AgentBridge.status();
        CodePathsBehaviors.RESULTS.forEach(System.out::println);
        System.out.println("PROBES=" + status.get(MethodProbes.SENSOR));
        System.out.println("AGENT_PROBES=" + ((Map<?, ?>) status.get("agent")).get("methodProbes"));
        System.out.println("STATUS=" + status);
    }

    interface Behavior {
        void run() throws Exception;
    }

    static List<String> beans() {
        List<String> beans = new ArrayList<>(CodePathsBehaviors.BEANS);
        beans.add(APP + "Quotes");
        beans.add(APP + "Gate");
        return beans;
    }

    static void twentyInvocationsThenRemoved() throws Exception {
        drain();
        // Loaded first: a probe on a class not loaded yet waits for it (aClassLoadedLaterIsProbedAsItLoads).
        OrderController controller = new OrderController();
        long id = start(PRICE, Map.of());
        Map<String, Object> active = await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        CodePathsBehaviors.CONTEXT.set(new String[] {CodePathsBehaviors.REQUEST, null});
        int answers = 0;
        for (int i = 0; i < 25; i++) {
            answers += controller.place(1);
        }
        CodePathsBehaviors.CONTEXT.remove();
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        List<long[]> hits = hits(id);
        long calls = adviceCalls();
        CodePathsBehaviors.drain();
        // Still instrumented by both sensors, and no longer by the probe.
        CodePathsBehaviors.CONTEXT.set(new String[] {CodePathsBehaviors.REQUEST, null});
        int after = controller.place(1);
        CodePathsBehaviors.CONTEXT.remove();
        List<long[]> fragments = CodePathsBehaviors.drain();
        String caller = hits.isEmpty() ? null : string(hits.get(0)[AgentRing.PAYLOAD + 3] >>> 32);
        CodePathsBehaviors.check(
                "a probe records its next 20 invocations, then removes itself, leaving both sensors' advice ("
                        + active.get("advised") + ", " + ended + ", " + hits.size() + " hits, caller " + caller
                        + ", " + CodePathsBehaviors.describe(fragments) + ")",
                Boolean.TRUE.equals(active.get("advised"))
                        && answers == 25 * 13
                        && after == 13
                        && MethodProbes.END_INVOCATIONS.equals(ended.get("endReason"))
                        && Integer.valueOf(20).equals(ended.get("invocations"))
                        && hits.size() == 20
                        && hits.stream().allMatch(hit -> hit[AgentRing.PAYLOAD + 2] == CodePathsBehaviors.REQUEST_BITS)
                        && hits.stream().allMatch(hit -> ((hit[AgentRing.PAYLOAD] >>> 2) & 1) == 0)
                        && caller != null
                        && caller.startsWith(APP + "OrderController#place:")
                        && adviceCalls() == calls
                        && fragments.size() == 1
                        && CodePathsBehaviors.nodes(fragments.get(0)).contains("0:OrderService#price:1")
                        && CodePathsBehaviors.tracking(PRICE) == CodeInventory.TRACKED);
    }

    static void theWindowEndsAProbeWithoutCalls() throws Exception {
        String tax = APP + "OrderService#tax(I)I";
        long id = start(tax, Map.of("windowMillis", 1500));
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        long calls = adviceCalls();
        new OrderService().tax(10);
        CodePathsBehaviors.check(
                "the window ends a probe without calls and removes it (" + ended + ")",
                MethodProbes.END_WINDOW.equals(ended.get("endReason"))
                        && Integer.valueOf(0).equals(ended.get("invocations"))
                        && adviceCalls() == calls
                        && hits(id).isEmpty());
    }

    static void anExplicitStopEndsAProbe() throws Exception {
        Quotes quotes = new Quotes();
        long id = start(APP + "Quotes#quote(I)I", Map.of());
        await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        for (int i = 0; i < 3; i++) {
            quotes.quote(i);
        }
        quotes.quote(5L);
        Map<String, Object> stopped = MethodProbes.stop(CodePathsBehaviors.token, id);
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        long calls = adviceCalls();
        int after = quotes.quote(2);
        CodePathsBehaviors.check(
                "an explicit stop ends a probe at once and removes it (" + stopped.get("status") + ", " + ended + ")",
                MethodProbes.END_STOPPED.equals(ended.get("endReason"))
                        && hits(id).size() == 3
                        && after == 6
                        && adviceCalls() == calls);
    }

    static void exceptionsAreRecordedByType() throws Exception {
        long id = start(APP + "OrderService#fail()V", Map.of("maxInvocations", 2));
        await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        OrderController controller = new OrderController();
        int first = controller.failing();
        int second = controller.failing();
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        List<long[]> hits = hits(id);
        String type = hits.isEmpty() ? null : string(hits.get(0)[AgentRing.PAYLOAD + 3] & 0xFFFFFFFFL);
        CodePathsBehaviors.check(
                "an exception is recorded by its type, and the method still throws (" + type + ", " + ended + ")",
                first == -1
                        && second == -1
                        && hits.size() == 2
                        && ((hits.get(0)[AgentRing.PAYLOAD] >>> 2) & 1) == MethodProbes.OUTCOME_THREW
                        && "java.lang.IllegalStateException".equals(type)
                        && MethodProbes.END_INVOCATIONS.equals(ended.get("endReason")));
    }

    static void overloadsNeedADescriptor() throws Exception {
        Map<String, Object> answer =
                MethodProbes.start(CodePathsBehaviors.token, Map.of("method", APP + "Quotes#quote"));
        Map<String, Object> probe = probe(answer);
        Map<String, Object> failed =
                probe == null ? Map.of() : await((Long) probe.get("id"), p -> "failed".equals(p.get("state")));
        long unique = start(APP + "Quotes#m6", Map.of("maxInvocations", 1));
        Map<String, Object> resolved = await(unique, p -> Boolean.TRUE.equals(p.get("advised")));
        new Quotes().m6();
        Map<String, Object> ended = await(unique, ProbeBehaviors::removed);
        CodePathsBehaviors.check(
                "an overloaded method needs its descriptor; a unique one is resolved (" + failed.get("failure") + ", "
                        + resolved.get("descriptor") + ")",
                String.valueOf(failed.get("failure")).contains("overloaded")
                        && "()I".equals(resolved.get("descriptor"))
                        && hits(unique).size() == 1
                        && "ended".equals(ended.get("state")));
    }

    static void aClassLoadedLaterIsProbedAsItLoads() throws Exception {
        // Another class loader's copy, as a previous run's: the agent knows the method, and never probes that copy.
        URL jar = OrderService.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader other = new URLClassLoader(
                new URL[] {jar}, ProbeBehaviors.class.getClassLoader().getParent());
        Class<?> otherLater = other.loadClass(APP + "Later");
        Object otherInstance = otherLater.getConstructor().newInstance();
        long id = start(APP + "Later#run()I", Map.of("maxInvocations", 1));
        Map<String, Object> waiting = await(id, probe -> "active".equals(probe.get("state")));
        otherLater.getMethod("run").invoke(otherInstance);
        Class<?> later = Class.forName(APP + "Later");
        Object answer = later.getMethod("run").invoke(later.getConstructor().newInstance());
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        CodePathsBehaviors.check(
                "a class this run loads after its probe started is probed as it loads, another loader's copy never ("
                        + waiting.get("advised") + ", " + ended + ")",
                Boolean.FALSE.equals(waiting.get("advised"))
                        && Integer.valueOf(7).equals(answer)
                        && Boolean.TRUE.equals(ended.get("advised"))
                        && hits(id).size() == 1);
    }

    static void fiveAtOnceThenTheSixthIsRefused() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(start(APP + "Quotes#m" + i + "()I", Map.of()));
        }
        boolean allActive = true;
        for (Long id : ids) {
            allActive &= Boolean.TRUE.equals(await(id, probe -> Boolean.TRUE.equals(probe.get("advised")))
                    .get("advised"));
        }
        Map<String, Object> sixth =
                MethodProbes.start(CodePathsBehaviors.token, Map.of("method", APP + "Quotes#m6()I"));
        Quotes quotes = new Quotes();
        int sum = quotes.m1() + quotes.m2() + quotes.m3() + quotes.m4() + quotes.m5();
        boolean recorded = true;
        for (Long id : ids) {
            recorded &= hits(id).size() == 1;
            MethodProbes.stop(CodePathsBehaviors.token, id);
        }
        for (Long id : ids) {
            await(id, ProbeBehaviors::removed);
        }
        CodePathsBehaviors.check(
                "five probes run at once and a sixth is refused (" + sixth + ")",
                allActive
                        && MethodProbes.REFUSED.equals(sixth.get("status"))
                        && String.valueOf(sixth.get("reason")).contains("five probes")
                        && sum == 15
                        && recorded);
    }

    /**
     * A call held open while the probe installs runs the code it entered, unprobed, so it records nothing; a call held
     * open while the probe is removed was counted when it entered, so it records once when it returns. Code Paths and
     * the inventory still see the method afterwards.
     */
    static void aCallInFlightAcrossTheInstallAndTheRemoval() throws Exception {
        String pass = APP + "Gate#pass(Ljava/util/concurrent/CountDownLatch;Ljava/util/concurrent/CountDownLatch;)I";
        bootuicodepathsapp.Gate gate = new bootuicodepathsapp.Gate();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread before = new Thread(() -> gate.pass(entered, release));
        before.start();
        entered.await();
        long id = start(pass, Map.of());
        await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        release.countDown();
        before.join();
        int acrossInstall = hits(id).size();

        java.util.concurrent.CountDownLatch enteredDuring = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseDuring = new java.util.concurrent.CountDownLatch(1);
        Thread during = new Thread(() -> gate.pass(enteredDuring, releaseDuring));
        during.start();
        enteredDuring.await();
        MethodProbes.stop(CodePathsBehaviors.token, id);
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        releaseDuring.countDown();
        during.join();
        int acrossRemoval = hits(id).size() - acrossInstall;

        long calls = adviceCalls();
        CodePathsBehaviors.drain();
        java.util.concurrent.CountDownLatch open = new java.util.concurrent.CountDownLatch(0);
        CodePathsBehaviors.CONTEXT.set(new String[] {CodePathsBehaviors.REQUEST, null});
        int after = gate.pass(new java.util.concurrent.CountDownLatch(1), open);
        CodePathsBehaviors.CONTEXT.remove();
        List<long[]> fragments = CodePathsBehaviors.drain();
        CodePathsBehaviors.check(
                "a call in flight across the install records nothing, one across the removal records once, and both"
                        + " sensors still see the method (" + acrossInstall + ", " + acrossRemoval + ", " + ended + ", "
                        + CodePathsBehaviors.describe(fragments) + ")",
                acrossInstall == 0
                        && acrossRemoval == 1
                        && "removed".equals(ended.get("removal"))
                        && after == 1
                        && adviceCalls() == calls
                        && fragments.size() == 1
                        && CodePathsBehaviors.nodes(fragments.get(0)).contains("-1:Gate#pass:1")
                        && CodePathsBehaviors.tracking(pass) == CodeInventory.TRACKED
                        && CodePathsBehaviors.executed(pass));
    }

    static void aNewRunEndsItsProbes() throws Exception {
        long id = start(PRICE, Map.of());
        await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        CodePathsBehaviors.token = CodePathsBehaviors.claim(CodePathsBehaviors.sensors(true), beans());
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        CodePathsBehaviors.awaitSelfTests(true);
        long calls = adviceCalls();
        int answer = new OrderController().place(2);
        CodePathsBehaviors.check(
                "a new claim generation ends the previous run's probes and removes them (" + answer + ", "
                        + (adviceCalls() - calls) + " calls, tracking " + CodePathsBehaviors.tracking(PRICE) + ", "
                        + ended + ")",
                MethodProbes.END_RUN.equals(ended.get("endReason"))
                        && answer == 24
                        && adviceCalls() == calls
                        && CodePathsBehaviors.tracking(PRICE) == CodeInventory.TRACKED);
    }

    /**
     * A DevTools-like restart: a fresh class loader defines the application again and claims with it as the thread's
     * context class loader. A probe then advises that copy only, and the class's inventory stays tracked.
     */
    static void aRestartProbesOnlyTheNewRunsCopy() throws Exception {
        URL jar = OrderService.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader fresh = new URLClassLoader(
                new URL[] {jar}, ProbeBehaviors.class.getClassLoader().getParent());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(fresh);
        try {
            CodePathsBehaviors.token = CodePathsBehaviors.claim(CodePathsBehaviors.sensors(true), beans());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        CodePathsBehaviors.awaitSelfTests(true);
        Class<?> service = fresh.loadClass(OrderService.class.getName());
        Object instance = service.getConstructor().newInstance();
        service.getMethod("price", int.class).invoke(instance, 1);
        long id = start(PRICE, Map.of("maxInvocations", 2));
        Map<String, Object> active = await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        new OrderService().price(1);
        Object answer = service.getMethod("price", int.class).invoke(instance, 2);
        List<long[]> hits = hits(id);
        // The next restart: that copy is a previous run's now, so its probe's transformer is only deregistered.
        URLClassLoader next = new URLClassLoader(
                new URL[] {jar}, ProbeBehaviors.class.getClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(next);
        try {
            CodePathsBehaviors.token = CodePathsBehaviors.claim(CodePathsBehaviors.sensors(true), beans());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        CodePathsBehaviors.awaitSelfTests(true);
        service.getMethod("price", int.class).invoke(instance, 3);
        CodePathsBehaviors.check(
                "after a restart, a probe advises only the new run's copy, its inventory stays tracked, and the next"
                        + " restart only deregisters it (" + active.get("state") + ", " + hits.size() + " hits, "
                        + ended
                        + ")",
                Integer.valueOf(20).equals(answer)
                        && hits.size() == 1
                        && MethodProbes.END_RUN.equals(ended.get("endReason"))
                        && String.valueOf(ended.get("removal")).startsWith("deregistered")
                        && hits(id).size() == 1
                        && CodePathsBehaviors.tracking(PRICE) == CodeInventory.TRACKED);
    }

    static void aReleaseEndsAndRemovesProbes() throws Exception {
        long id = start(APP + "Quotes#m1()I", Map.of());
        await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        AgentBridge.release("code-paths-behaviors", "dev");
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        CodePathsBehaviors.awaitState(CodePaths.SENSOR, "released");
        long calls = adviceCalls();
        int answer = new Quotes().m1();
        CodePathsBehaviors.check(
                "a release ends and removes probes (" + ended + ")",
                MethodProbes.END_RUN.equals(ended.get("endReason")) && answer == 1 && adviceCalls() == calls);
    }

    static void aShapesProbeRecordsShapesWithoutRunningApplicationCode() throws Exception {
        Shapes shapes = new Shapes();
        Shapes.Basket basket = new Shapes.Basket();
        Shapes.Card card = new Shapes.Card();
        long id = start(APP + "Shapes#quote", Map.of("shapes", Boolean.TRUE, "maxInvocations", 2));
        Map<String, Object> active = await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        Shapes.CALLS.set(0);
        int answers = 0;
        for (int i = 0; i < 3; i++) {
            answers += shapes.quote(
                    "sku-12",
                    List.of(1, 2, 3),
                    basket,
                    card,
                    Optional.of("c"),
                    Shapes.Level.HIGH,
                    new int[4],
                    7,
                    null,
                    5L);
        }
        Map<String, Object> ended = await(id, ProbeBehaviors::removed);
        List<long[]> hits = records(id, MethodProbes.PROBE_HIT);
        List<long[]> shapeRecords = records(id, MethodProbes.PROBE_SHAPES);
        long[] arguments = new long[12];
        long returned = 0L;
        for (long[] record : shapeRecords) {
            long payload = record[AgentRing.PAYLOAD];
            if (((payload >>> 3) & 31) != 0) {
                continue;
            }
            int part = (int) (payload & 7);
            if (part == MethodProbes.RETURN_PART) {
                returned = record[AgentRing.PAYLOAD + 1];
            } else {
                for (int i = 0; i < 3; i++) {
                    arguments[part * 3 + i] = record[AgentRing.PAYLOAD + 1 + i];
                }
            }
        }
        String described = describe(arguments) + " -> " + describe(new long[] {returned});
        CodePathsBehaviors.check(
                "a shapes probe records argument and return shapes, joined by index, and runs no application method ("
                        + active.get("shapes") + ", " + hits.size() + " hits, " + shapeRecords.size()
                        + " shape records, " + described + ", calls " + Shapes.CALLS.get() + ")",
                Boolean.TRUE.equals(active.get("shapes"))
                        && answers == 3 * 10
                        && Shapes.CALLS.get() == 0
                        && hits.size() == 2
                        && ((hits.get(1)[AgentRing.PAYLOAD] >>> 3) & 31) == 1
                        // Three argument records, nine of ten arguments, and the return record per invocation.
                        && shapeRecords.size() == 8
                        && Integer.valueOf(0).equals(ended.get("shapesDropped"))
                        && is(arguments[0], ProbeShapes.STRING, 6, "java.lang.String")
                        && is(arguments[1], ProbeShapes.COLLECTION, 3, "java.util.ImmutableCollections$ListN")
                        && is(arguments[2], ProbeShapes.TYPE, 0, APP + "Shapes$Basket")
                        && is(arguments[3], ProbeShapes.TYPE, 0, APP + "Shapes$Card")
                        && is(arguments[4], ProbeShapes.OPTIONAL, 1, "java.util.Optional")
                        && ProbeShapes.kind(arguments[5]) == ProbeShapes.ENUM
                        && (APP + "Shapes$Level").equals(string(ProbeShapes.typeId(arguments[5])))
                        && "HIGH".equals(string(ProbeShapes.summary(arguments[5])))
                        && is(arguments[6], ProbeShapes.ARRAY, 4, "[I")
                        && is(arguments[7], ProbeShapes.TYPE, 0, "java.lang.Integer")
                        && ProbeShapes.kind(arguments[8]) == ProbeShapes.NULL
                        && arguments[9] == 0L
                        && is(returned, ProbeShapes.TYPE, 0, "java.lang.Integer")
                        && !interned("sku-12")
                        && !interned("4111111111111111"));
    }

    static void returnShapesNameApplicationCollectionsByClassAndJdkListsBySize() throws Exception {
        Shapes shapes = new Shapes();
        Shapes.Card card = new Shapes.Card();
        long basket = start(APP + "Shapes#basket", Map.of("shapes", Boolean.TRUE, "maxInvocations", 1));
        long names = start(APP + "Shapes#names", Map.of("shapes", Boolean.TRUE, "maxInvocations", 1));
        long touch = start(APP + "Shapes#touch", Map.of("shapes", Boolean.TRUE, "maxInvocations", 1));
        for (long id : new long[] {basket, names, touch}) {
            await(id, probe -> Boolean.TRUE.equals(probe.get("advised")));
        }
        Shapes.CALLS.set(0);
        Shapes.Basket answer = shapes.basket("b");
        int size = shapes.names(5).size();
        shapes.touch(card);
        int calls = Shapes.CALLS.get();
        for (long id : new long[] {basket, names, touch}) {
            await(id, ProbeBehaviors::removed);
        }
        long basketShape = returnShape(basket);
        long namesShape = returnShape(names);
        List<long[]> touched = records(touch, MethodProbes.PROBE_SHAPES);
        CodePathsBehaviors.check(
                "return shapes name an application collection by its class and a JDK list by its size, and a void method"
                        + " has none (" + describe(new long[] {basketShape, namesShape}) + ", " + touched.size()
                        + " records for touch, calls " + calls + ")",
                answer != null
                        && size == 5
                        && calls == 0
                        && is(basketShape, ProbeShapes.TYPE, 0, APP + "Shapes$Basket")
                        && is(namesShape, ProbeShapes.COLLECTION, 5, "java.util.ArrayList")
                        && touched.size() == 1
                        && (touched.get(0)[AgentRing.PAYLOAD] & 7) == 0
                        && is(touched.get(0)[AgentRing.PAYLOAD + 1], ProbeShapes.TYPE, 0, APP + "Shapes$Card")
                        && records(touch, MethodProbes.PROBE_HIT).size() == 1);
    }

    static long returnShape(long id) {
        for (long[] record : records(id, MethodProbes.PROBE_SHAPES)) {
            if ((record[AgentRing.PAYLOAD] & 7) == MethodProbes.RETURN_PART) {
                return record[AgentRing.PAYLOAD + 1];
            }
        }
        return 0L;
    }

    static boolean is(long shape, int kind, int summary, String type) {
        return ProbeShapes.kind(shape) == kind
                && ProbeShapes.summary(shape) == summary
                && type.equals(string(ProbeShapes.typeId(shape)));
    }

    static String describe(long[] shapes) {
        StringBuilder text = new StringBuilder();
        for (long shape : shapes) {
            text.append(text.length() == 0 ? "" : " ")
                    .append(ProbeShapes.kind(shape))
                    .append('/')
                    .append(ProbeShapes.summary(shape))
                    .append('/')
                    .append(string(ProbeShapes.typeId(shape)));
        }
        return text.toString();
    }

    /** Whether the intern table holds {@code text}: a value must never reach it. */
    static boolean interned(String text) {
        String[] strings = AgentRing.interned(hitsGeneration, 1);
        if (strings != null) {
            for (String string : strings) {
                if (text.equals(string)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The records of probe {@code id} of {@code type} drained so far. */
    static List<long[]> records(long id, int type) {
        List<long[]> list = new ArrayList<>();
        for (long[] record : hits(id)) {
            if (record[AgentRing.TYPE] == type) {
                list.add(record);
            }
        }
        return list;
    }

    // ---- harness -----------------------------------------------------------------------------------------------

    static long start(String method, Map<String, Object> options) {
        Map<String, Object> request = new LinkedHashMap<>(options);
        request.put("method", method);
        Map<String, Object> answer = MethodProbes.start(CodePathsBehaviors.token, request);
        Map<String, Object> probe = probe(answer);
        if (probe == null || !MethodProbes.STARTED.equals(answer.get("status"))) {
            throw new IllegalStateException("could not start a probe on " + method + ": " + answer);
        }
        return (Long) probe.get("id");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> probe(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("probe");
    }

    static Map<String, Object> await(long id, Predicate<Map<String, Object>> condition) throws Exception {
        Map<String, Object> last = Map.of();
        for (int i = 0; i < 600; i++) {
            for (Map<String, Object> probe : MethodProbes.list()) {
                if (Long.valueOf(id).equals(probe.get("id"))) {
                    last = probe;
                }
            }
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(25);
        }
        return last;
    }

    static boolean removed(Map<String, Object> probe) {
        return "ended".equals(probe.get("state")) && probe.get("removal") != null;
    }

    static long adviceCalls() {
        return (Long) MethodProbes.status().get("adviceCalls");
    }

    static final Map<Long, List<long[]>> HITS = new LinkedHashMap<>();
    static long hitsGeneration = -1L;

    /** The hits of probe {@code id} drained so far, this one included. */
    static List<long[]> hits(long id) {
        drain();
        return HITS.getOrDefault(id, List.of());
    }

    static void drain() {
        AgentRing.drain(CodePathsBehaviors.token, record -> {
            if (record[AgentRing.SENSOR] == AgentRing.SENSOR_METHOD_PROBES) {
                hitsGeneration = record[AgentRing.GENERATION];
                HITS.computeIfAbsent(record[AgentRing.PAYLOAD] >>> 8, key -> new ArrayList<>())
                        .add(record.clone());
            }
        });
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = AgentRing.interned(hitsGeneration, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
