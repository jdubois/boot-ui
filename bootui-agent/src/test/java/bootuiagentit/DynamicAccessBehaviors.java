package bootuiagentit;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The dynamic-access sensor's behaviors (PLAN-v2 M5-9b spike), against the published jar: caller-sensitive methods keep
 * their caller under advice, a session records what the application and its libraries reach with their calling frame
 * and never an argument value, JDK and BootUI callers are left out, and a session ends at its bounds and with the run.
 * Prints one PASS or FAIL line per behavior, the recorded entries, and the reachability-metadata fragment they make.
 */
public final class DynamicAccessBehaviors {

    static final String DYNAMIC_ACCESS = "io.github.jdubois.bootui.agent.bridge.DynamicAccess";
    private static int failures;

    private DynamicAccessBehaviors() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "behaviors" -> behaviors();
            case "disabled" -> disabled();
            case "bench" -> bench();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("FAILURES=" + failures);
    }

    @SuppressWarnings("unchecked")
    static void behaviors() throws Exception {
        ClassLoader isolated = isolatedLoader();
        Function<String, Object> app = application(isolated);
        Class<?> bridge = ChildMain.bridge();
        Class<?> dynamic = Class.forName(DYNAMIC_ACCESS, false, null);
        Object marker = new Object();
        Supplier<Object> capture = () -> marker;
        Function<Object, AutoCloseable> reopen = snapshot -> () -> {};
        long token = claim(bridge, capture, reopen);
        Map<String, Object> sensor = awaitSensor(bridge);
        System.out.println("SENSOR=" + sensor);
        check("the self-test passed", Boolean.TRUE.equals(sensor.get("selfTestPassed")));

        check(
                "forName(String) resolves through the caller's loader under advice",
                loaderOf(app.apply("direct")) == isolated);
        check(
                "forName(String) called through Method.invoke resolves through the caller's loader",
                loaderOf(app.apply("reflective")) == isolated);
        app.apply("direct");
        Map<String, Object> before = status(dynamic);
        check("no session records nothing", before.get("session") == null && before.get("last") == null);

        Map<String, Object> started = start(dynamic, token, 30, 100);
        check("a session starts " + started, "recording".equals(started.get("status")));
        check(
                "a second session is refused",
                "running".equals(start(dynamic, token, 30, 100).get("status")));
        check(
                "forName(String) still resolves through the caller's loader while recording",
                loaderOf(app.apply("direct")) == isolated);
        app.apply("direct");
        app.apply("direct");
        check("forName with a loader still works", loaderOf(app.apply("loader")) == isolated);
        check("forName with a module still works", app.apply("module") == String.class);
        check(
                "a reflective forName still resolves through the caller's loader while recording",
                loaderOf(app.apply("reflective")) == isolated);
        check(
                "a class only loaded reflectively resolves through the caller's loader",
                loaderOf(app.apply("reflective-only")) == isolated);
        check(
                "Constructor.newInstance still works",
                loaderOf(app.apply("construct").getClass()) == isolated);
        check("Method.invoke still works", "hello 16".equals(app.apply("call:s3cr3t-arg-value")));
        Object proxy = app.apply("proxy");
        check("Proxy.newProxyInstance still works", proxy instanceof Runnable);
        check("a missing class still throws", "missing".equals(app.apply("missing")));
        check(
                "a library's reflection still works",
                loaderOf(app.apply("library").getClass()) == isolated);
        check("deserialization still works", loaderOf(app.apply("deserialize").getClass()) == isolated);
        boolean previous =
                (Boolean) bridge.getMethod("bootUiWork", boolean.class).invoke(null, true);
        app.apply("marker");
        bridge.getMethod("bootUiWork", boolean.class).invoke(null, previous);

        Map<String, Object> stopped = stop(dynamic, token);
        Map<String, Object> session = (Map<String, Object>) stopped.get("session");
        System.out.println("SESSION=" + withoutEntries(session));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) session.get("entries");
        for (Map<String, Object> entry : entries) {
            System.out.println("ENTRY=" + entry);
        }
        String app0 = "bootuidynamicapp.Isolated";
        String frame = "bootuidynamicapp.Isolated#apply";
        check("a session stops and keeps its entries", "stopped".equals(session.get("endReason")));
        check(
                "forName is recorded with its application caller",
                has(entries, "forName", "bootuidynamicapp.Hidden", null, null, app0, frame));
        check(
                "Constructor.newInstance is recorded as <init> with its parameter types",
                has(entries, "newInstance", "bootuidynamicapp.Hidden", "<init>", "", app0, frame));
        check(
                "Method.invoke is recorded with its parameter types",
                has(entries, "invoke", "bootuidynamicapp.Hidden", "greet", "java.lang.String", app0, frame));
        check(
                "a reflective forName is recorded as the Method.invoke of Class.forName",
                has(entries, "invoke", "java.lang.Class", "forName", "java.lang.String", app0, frame));
        check(
                "a class only loaded reflectively is recorded with its application caller",
                has(entries, "forName", "bootuidynamicapp.OnlyReflective", null, null, app0, frame));
        check(
                "Proxy.newProxyInstance is recorded with its interface list",
                has(entries, "proxy", "java.lang.Runnable,bootuidynamicapp.Hidden$Marker", null, null, app0, frame));
        check(
                "a library's reflection names the library as caller and the application frame above it",
                has(entries, "forName", "bootuidynamicapp.Hidden", null, null, "bootuidynamiclib.Reflector", frame)
                        && has(
                                entries,
                                "newInstance",
                                "bootuidynamicapp.Hidden",
                                "<init>",
                                "",
                                "bootuidynamiclib.Reflector",
                                frame));
        check(
                "JDK callers (deserialization) are counted, never recorded",
                ((Long) session.get("jdkCallers")) > 0
                        && entries.stream()
                                .noneMatch(entry ->
                                        String.valueOf(entry.get("callerClass")).startsWith("java.")));
        check("a failed lookup is counted", ((Long) session.get("failedLookups")) > 0);
        check(
                "BootUI's own work is counted, never recorded",
                ((Long) session.get("bootUiWork")) > 0
                        && entries.stream()
                                .noneMatch(entry -> "forName".equals(entry.get("kind"))
                                        && "bootuidynamicapp.Hidden$Marker".equals(entry.get("type"))));
        check(
                "a generated proxy's own reflection is counted, never recorded",
                ((Long) session.get("generatedCallers")) > 0
                        && entries.stream()
                                .noneMatch(entry ->
                                        String.valueOf(entry.get("callerClass")).contains("$Proxy")));
        check(
                "another agent's reflection is counted as tooling, never recorded",
                entries.stream()
                        .noneMatch(entry ->
                                String.valueOf(entry.get("callerClass")).startsWith("net.bytebuddy.agent.")));
        check("repeated accesses fold into one entry", count(entries, "forName", "bootuidynamicapp.Hidden", app0) >= 3);
        String fragment = fragment(entries);
        System.out.println("FRAGMENT=" + fragment);
        check(
                "the session is off once stopped",
                Boolean.FALSE.equals(status(dynamic).get("on")));
        check(
                "a stopped session is returned only to its claim",
                "none".equals(stop(dynamic, token + 1_000).get("status")));

        start(dynamic, token, 30, 2);
        app.apply("spread");
        Map<String, Object> full = (Map<String, Object>) stop(dynamic, token).get("session");
        System.out.println("FULL=" + withoutEntries(full));
        check(
                "a session keeps at most its distinct entries and counts the rest",
                ((Integer) full.get("distinct")) == 2 && ((Long) full.get("dropped")) > 0);

        start(dynamic, token, 1, 100);
        Thread.sleep(1_200);
        app.apply("direct");
        Map<String, Object> timed = (Map<String, Object>) status(dynamic).get("last");
        System.out.println("TIMED=" + withoutEntries(timed));
        check(
                "a session ends at its time limit, in the advice, recording nothing after it",
                "time limit".equals(timed.get("endReason"))
                        && ((Integer) timed.get("distinct")) == 0
                        && !timed.containsKey("entries"));
        Map<String, Object> timedEntries =
                (Map<String, Object>) stop(dynamic, token).get("session");
        check(
                "an ended session's entries are returned to its claim only, through stop",
                timedEntries != null && ((List<?>) timedEntries.get("entries")).isEmpty());

        start(dynamic, token, 30, 100);
        bridge.getMethod("disarm", long.class).invoke(null, token);
        Map<String, Object> ended = (Map<String, Object>) status(dynamic).get("last");
        check("a session ends with the run", "run ended".equals(ended.get("endReason")));
        check(
                "a session cannot start for an ended run",
                "stale".equals(start(dynamic, token, 30, 100).get("status")));

        long next = claim(bridge, capture, reopen);
        bridge.getMethod("release", String.class, String.class).invoke(null, "dynamic", "dev");
        Map<String, Object> released = Map.of();
        for (int i = 0; i < 200; i++) {
            released = sensorStatus(bridge);
            if ("released".equals(released.get("state"))
                    || String.valueOf(released.get("state")).contains("failed")) {
                break;
            }
            Thread.sleep(25);
        }
        System.out.println("RELEASED=" + released.get("state"));
        check("a release restores every advised class", "released".equals(released.get("state")));
        check(
                "a released sensor records nothing and no session can start",
                Boolean.FALSE.equals(status(dynamic).get("on"))
                        && !"recording".equals(start(dynamic, next, 30, 100).get("status")));
        check("the advised methods still work once restored", loaderOf(app.apply("direct")) == isolated);
        Map<String, Object> bridgeStatus =
                (Map<String, Object>) bridge.getMethod("status").invoke(null);
        System.out.println("STATUS=" + bridgeStatus.get("counters"));
        check("no advice error", String.valueOf(bridgeStatus.get("counters")).contains("errors=0"));

        long startup = claim(bridge, capture, reopen, Map.of("startupSeconds", 30, "maxEntries", 100));
        awaitSensor(bridge);
        Map<String, Object> running = null;
        for (int i = 0; i < 200 && running == null; i++) {
            running = (Map<String, Object>) status(dynamic).get("session");
            Thread.sleep(25);
        }
        check("a claim can start a session as soon as the sensor is installed", running != null);
        check("the previous run's last session is forgotten", status(dynamic).get("last") == null);
        app.apply("direct");
        Map<String, Object> early = (Map<String, Object>) stop(dynamic, startup).get("session");
        check(
                "the startup session records",
                early != null
                        && has(
                                (List<Map<String, Object>>) early.get("entries"),
                                "forName",
                                "bootuidynamicapp.Hidden",
                                null,
                                null,
                                app0,
                                frame));
    }

    /** Without the JVM flag, a claim asking for the sensor installs nothing, and a session is refused with the reason. */
    static void disabled() throws Exception {
        Class<?> bridge = ChildMain.bridge();
        Class<?> dynamic = Class.forName(DYNAMIC_ACCESS, false, null);
        Object marker = new Object();
        Supplier<Object> capture = () -> marker;
        Function<Object, AutoCloseable> reopen = snapshot -> () -> {};
        long token = claim(bridge, capture, reopen);
        Thread.sleep(300);
        Map<String, Object> sensor = sensorStatus(bridge);
        System.out.println("SENSOR=" + sensor);
        Map<String, Object> refused = start(dynamic, token, 30, 100);
        System.out.println("START=" + refused);
        check("the sensor stays disabled without the flag", "disabled".equals(sensor.get("state")));
        check(
                "a session is refused with the flag as its remedy",
                "unavailable".equals(refused.get("status"))
                        && String.valueOf(refused.get("reason")).contains("bootui.agent.experimental.dynamic-access"));
    }

    /**
     * Nanoseconds per call of each advised operation, called from the application: without the agent (no advice), or,
     * in a JVM with the agent, claimed before any warm-up (so retransformation deoptimizes nothing the benchmark warmed),
     * with the advice installed and no session, with a session on, and after it stops. Best of five rounds after warm-up.
     */
    static void bench() throws Exception {
        ClassLoader isolated = isolatedLoader();
        Function<String, Object> app = application(isolated);
        Class<?> bridge = ChildMain.bridge();
        boolean attached =
                bridge != null && (Boolean) bridge.getMethod("attached").invoke(null);
        int rounds = Integer.getInteger("bootui.agent.it.bench.rounds", 5);
        // In both JVMs, as in any application, before measuring: the agent's own install-time reflection would
        // otherwise
        // be the only difference between their type profiles of the JDK's shared reflective call sites.
        app.apply("pollute");
        if (!attached) {
            phase(app, "no-advice", rounds, 1);
            return;
        }
        Class<?> dynamic = Class.forName(DYNAMIC_ACCESS, false, null);
        Object marker = new Object();
        Supplier<Object> capture = () -> marker;
        Function<Object, AutoCloseable> reopen = snapshot -> () -> {};
        long token = claim(bridge, capture, reopen);
        Map<String, Object> sensor = awaitSensor(bridge);
        System.out.println("INSTALL_MILLIS=" + sensor.get("installMillis") + " SELF_TEST_MILLIS="
                + sensor.get("selfTestMillis") + " STATE=" + sensor.get("state"));
        phase(app, "advised-off", rounds, 1);
        start(dynamic, token, 600, 100);
        phase(app, "session-on", rounds, 20);
        Map<String, Object> session = (Map<String, Object>) stop(dynamic, token).get("session");
        long recorded = (Long) session.get("recorded");
        System.out.println("SESSION_WALK_NANOS_PER_RECORD="
                + (recorded == 0 ? -1 : ((Long) session.get("walkNanos")) / recorded) + " RECORDED=" + recorded);
        phase(app, "after-stop", rounds, 1);
    }

    private static void phase(Function<String, Object> app, String phase, int rounds, int divisor) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("forName", 2_000_000 / divisor);
        sizes.put("invoke", 5_000_000 / divisor);
        sizes.put("newInstance", 5_000_000 / divisor);
        sizes.put("proxy", 1_000_000 / divisor);
        sizes.put("library", 2_000_000 / divisor);
        List<String> only = List.of(System.getProperty("bootui.agent.it.bench.ops", String.join(",", sizes.keySet()))
                .split(","));
        for (Map.Entry<String, Integer> op : sizes.entrySet()) {
            if (!only.contains(op.getKey())) {
                continue;
            }
            int n = op.getValue();
            app.apply("bench:" + op.getKey() + ":" + n);
            app.apply("bench:" + op.getKey() + ":" + n);
            double best = Double.MAX_VALUE;
            for (int round = 0; round < rounds; round++) {
                long nanos = (Long) app.apply("bench:" + op.getKey() + ":" + n);
                best = Math.min(best, (double) nanos / n);
            }
            System.out.printf("BENCH %s %s %.2f%n", phase, op.getKey(), best);
        }
    }

    // ---- the export a productized version would make ----------------------------------------------------------------

    /**
     * The entries as a GraalVM {@code reachability-metadata.json} fragment (unified format, schema 1.2.0): one
     * {@code reflection} element per accessed type and calling class, conditioned on the calling class being reached.
     */
    @SuppressWarnings("unchecked")
    static String fragment(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> elements = new TreeMap<>();
        for (Map<String, Object> entry : entries) {
            String kind = (String) entry.get("kind");
            String type = (String) entry.get("type");
            String caller = (String) entry.get("callerClass");
            String key = caller + "|" + kind.equals("proxy") + "|" + type;
            Map<String, Object> element = elements.computeIfAbsent(key, ignored -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("condition", "{\"typeReached\":" + quote(caller) + "}");
                created.put(
                        "type",
                        kind.equals("proxy") ? "{\"proxy\":" + array(List.of(type.split(","))) + "}" : quote(type));
                created.put("methods", new java.util.TreeSet<String>());
                return created;
            });
            if (entry.get("member") != null) {
                String parameters = (String) entry.get("parameterTypes");
                List<String> types =
                        parameters == null || parameters.isEmpty() ? List.of() : List.of(parameters.split(","));
                ((java.util.Set<String>) element.get("methods"))
                        .add("{\"name\":" + quote((String) entry.get("member")) + ",\"parameterTypes\":" + array(types)
                                + "}");
            }
        }
        List<String> reflection = new ArrayList<>();
        for (Map<String, Object> element : elements.values()) {
            StringBuilder text = new StringBuilder("{\"condition\":")
                    .append(element.get("condition"))
                    .append(",\"type\":")
                    .append(element.get("type"));
            java.util.Set<String> methods = (java.util.Set<String>) element.get("methods");
            if (!methods.isEmpty()) {
                text.append(",\"methods\":[").append(String.join(",", methods)).append(']');
            }
            reflection.add(text.append('}').toString());
        }
        return "{\"reflection\":[" + String.join(",", reflection) + "]}";
    }

    private static String array(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add(quote(value));
        }
        return "[" + String.join(",", quoted) + "]";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static ClassLoader isolatedLoader() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String path : System.getProperty("bootui.agent.it.dynamic-jars").split(File.pathSeparator)) {
            urls.add(new File(path).toURI().toURL());
        }
        return new URLClassLoader("isolated", urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    @SuppressWarnings("unchecked")
    static Function<String, Object> application(ClassLoader isolated) throws Exception {
        return (Function<String, Object>) isolated.loadClass("bootuidynamicapp.Isolated")
                .getDeclaredConstructor()
                .newInstance();
    }

    @SuppressWarnings("unchecked")
    static long claim(Class<?> bridge, Supplier<Object> capture, Function<Object, AutoCloseable> reopen)
            throws Exception {
        return claim(bridge, capture, reopen, null);
    }

    @SuppressWarnings("unchecked")
    static long claim(
            Class<?> bridge,
            Supplier<Object> capture,
            Function<Object, AutoCloseable> reopen,
            Map<String, Object> dynamicAccess)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "dynamic");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuidynamicapp"));
        request.put("sensors", List.of("dynamic-access"));
        if (dynamicAccess != null) {
            request.put("dynamicAccess", dynamicAccess);
        }
        Map<String, Object> result =
                (Map<String, Object>) bridge.getMethod("claim", Map.class, Supplier.class, Function.class)
                        .invoke(null, request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        return (Long) result.get("token");
    }

    static Map<String, Object> awaitSensor(Class<?> bridge) throws Exception {
        Map<String, Object> sensor = Map.of();
        for (int i = 0; i < 600; i++) {
            sensor = sensorStatus(bridge);
            Object state = sensor.get("state");
            if ("installed".equals(state) || String.valueOf(state).contains("failed")) {
                return sensor;
            }
            Thread.sleep(25);
        }
        return sensor;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sensorStatus(Class<?> bridge) throws Exception {
        Map<String, Object> agent = (Map<String, Object>)
                ((Map<String, Object>) bridge.getMethod("status").invoke(null)).get("agent");
        for (Object sensor : (List<Object>) agent.get("sensors")) {
            Map<String, Object> map = (Map<String, Object>) sensor;
            if ("dynamic-access".equals(map.get("id"))) {
                return map;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> start(Class<?> dynamic, long token, int seconds, int maxEntries) throws Exception {
        return (Map<String, Object>) dynamic.getMethod("start", long.class, Map.class)
                .invoke(null, token, Map.of("seconds", seconds, "maxEntries", maxEntries));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stop(Class<?> dynamic, long token) throws Exception {
        return (Map<String, Object>) dynamic.getMethod("stop", long.class).invoke(null, token);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> status(Class<?> dynamic) throws Exception {
        return (Map<String, Object>) dynamic.getMethod("status").invoke(null);
    }

    private static Map<String, Object> withoutEntries(Map<String, Object> session) {
        Map<String, Object> copy = new LinkedHashMap<>(session);
        Object entries = copy.remove("entries");
        if (entries instanceof List<?> list) {
            copy.put("entryCount", list.size());
        }
        return copy;
    }

    private static ClassLoader loaderOf(Object value) {
        return value instanceof Class<?> type ? type.getClassLoader() : null;
    }

    private static boolean has(
            List<Map<String, Object>> entries,
            String kind,
            String type,
            String member,
            String parameters,
            String caller,
            String frame) {
        return entries.stream()
                .anyMatch(entry -> kind.equals(entry.get("kind"))
                        && type.equals(entry.get("type"))
                        && java.util.Objects.equals(member, entry.get("member"))
                        && java.util.Objects.equals(parameters, entry.get("parameterTypes"))
                        && caller.equals(entry.get("callerClass"))
                        && frame.equals(entry.get("applicationFrame")));
    }

    private static long count(List<Map<String, Object>> entries, String kind, String type, String caller) {
        return entries.stream()
                .filter(entry -> kind.equals(entry.get("kind"))
                        && type.equals(entry.get("type"))
                        && caller.equals(entry.get("callerClass")))
                .mapToLong(entry -> (Long) entry.get("count"))
                .sum();
    }

    static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
    }
}
