package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.RequestValues;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The security-sinks sensor's request-value matching (PLAN-v2 §5.16, M5-6b1) in a forked JVM beside the agent, claimed
 * with {@code processes}, {@code files}, and {@code security-sinks}, through the real JDK hooks: a file opened and a
 * process started with a request's value record the redacted path pattern and the command's argument index, the files
 * and processes records name no value either, a propagated task and work after the response record nothing, and the
 * holder holds nothing once the request ended. Prints one PASS or FAIL line per behavior, then the bridge's status.
 * The seeded values are built at run time and never printed.
 */
public final class SecuritySinksBehaviors {

    static final String REQUEST = "00000000000000ef";
    static final long REQUEST_BITS = 0xefL;

    static final ThreadLocal<Object[]> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static long token;
    static long generation;
    static Path work;
    /** The claim's capture and reopen, kept strongly reachable: the bridge holds them weakly. */
    static Supplier<Object> capture;

    static Function<Object, AutoCloseable> reopen;

    private SecuritySinksBehaviors() {}

    public static void main(String[] args) throws Exception {
        work = Path.of("")
                .toAbsolutePath()
                .resolve("security-sinks-" + ProcessHandle.current().pid());
        Files.createDirectories(work.resolve("reports"));
        // Loaded and used before the claim, as an application's are: the hooks retransform them.
        Files.exists(work);
        token = claim(List.of(SideEffects.PROCESSES, SideEffects.FILES, SideEffects.SECURITY_SINKS));
        FilesEnvironmentBehaviors.awaitSelfTest(SideEffects.PROCESSES);
        FilesEnvironmentBehaviors.awaitSelfTest(SideEffects.FILES);
        String value = seed("q3-report");
        String argument = seed("tool-arg");
        aFileHoldingAValue(value);
        aProcessArgumentHoldingAValue(argument);
        anotherPathRecordsNoSink();
        aPropagatedTaskRecordsNothing(value);
        afterTheResponseNothingIsMatched(value);
        RESULTS.forEach(System.out::println);
        String everything = String.join("|", interned()) + RequestValues.status() + AgentBridge.status();
        System.out.println("VALUES_KEPT=" + (everything.contains(value) || everything.contains(argument)));
        System.out.println("STATUS=" + AgentBridge.status());
    }

    static void aFileHoldingAValue(String value) throws Exception {
        RECORDS.clear();
        Path report = work.resolve("reports").resolve(value + ".csv");
        Files.writeString(report, "a,b\n", StandardCharsets.UTF_8);
        inRequest(new String[] {"name"}, new String[] {value}, () -> {
            try (FileInputStream in = new FileInputStream(report.toString())) {
                in.read();
            }
        });
        long[] sink = await(SideEffects.SENSOR_SECURITY_SINKS, RequestValues.SINK_FILE);
        long[] file = find(SideEffects.SENSOR_FILES, SideEffects.KIND_FILE_READ);
        check(
                "a file opened with a request's value records the redacted pattern, in its files record too ("
                        + describe() + " " + RequestValues.status() + ")",
                sink != null
                        && file != null
                        && string(sink[SideEffects.R_TARGET]).endsWith("/reports/{name}.csv")
                        && string(sink[SideEffects.R_FLAGS] >>> 32).equals("name")
                        && sink[SideEffects.R_REQUEST] == REQUEST_BITS
                        && string(file[SideEffects.R_TARGET]).endsWith("/reports/{name}.csv")
                        && frame((int) sink[SideEffects.R_FRAMES]).startsWith("bootuiagentit.SecuritySinksBehaviors#"));
    }

    static void aProcessArgumentHoldingAValue(String argument) throws Exception {
        RECORDS.clear();
        inRequest(new String[] {"arg"}, new String[] {argument}, () -> {
            try {
                new ProcessBuilder("bootui-no-such-tool", "--input", argument).start();
            } catch (IOException expected) {
                // The tool does not exist: the start is attempted, and that is what the hook sees.
            }
        });
        long[] sink = await(SideEffects.SENSOR_SECURITY_SINKS, RequestValues.SINK_COMMAND);
        check(
                "a process started with a request's value records the command and the argument's index only ("
                        + describe() + ")",
                sink != null && "bootui-no-such-tool, argument 2".equals(string(sink[SideEffects.R_TARGET])));
    }

    static void anotherPathRecordsNoSink() throws Exception {
        RECORDS.clear();
        Path other = work.resolve("reports").resolve("static.csv");
        Files.writeString(other, "a\n", StandardCharsets.UTF_8);
        inRequest(new String[] {"name"}, new String[] {seed("unrelated-value")}, () -> {
            new FileInputStream(other.toString()).close();
        });
        await(SideEffects.SENSOR_FILES, SideEffects.KIND_FILE_READ);
        check(
                "a path not holding the value records no sink (" + describe() + ")",
                find(SideEffects.SENSOR_SECURITY_SINKS, RequestValues.SINK_FILE) == null);
    }

    static void aPropagatedTaskRecordsNothing(String value) throws Exception {
        RECORDS.clear();
        Path report = work.resolve("reports").resolve(value + ".csv");
        CONTEXT.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, new String[] {"name"}, new String[] {value}, null, null);
        CONTEXT.set(owner(REQUEST, "async-00000000000000aa"));
        try {
            new FileInputStream(report.toString()).close();
        } finally {
            CONTEXT.remove();
            RequestValues.end(REQUEST);
        }
        await(SideEffects.SENSOR_FILES, SideEffects.KIND_FILE_READ);
        check(
                "a task the request handed off records no sink (" + describe() + ")",
                find(SideEffects.SENSOR_SECURITY_SINKS, RequestValues.SINK_FILE) == null);
    }

    static void afterTheResponseNothingIsMatched(String value) throws Exception {
        RECORDS.clear();
        Path report = work.resolve("reports").resolve(value + ".csv");
        CONTEXT.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, new String[] {"name"}, new String[] {value}, null, null);
        RequestValues.end(REQUEST);
        try {
            new FileInputStream(report.toString()).close();
        } finally {
            CONTEXT.remove();
        }
        await(SideEffects.SENSOR_FILES, SideEffects.KIND_FILE_READ);
        check(
                "after the response, nothing is held or matched ("
                        + RequestValues.status().get("live") + ")",
                find(SideEffects.SENSOR_SECURITY_SINKS, RequestValues.SINK_FILE) == null
                        && Integer.valueOf(0).equals(RequestValues.status().get("live")));
    }

    interface Work {
        void run() throws Exception;
    }

    static void inRequest(String[] names, String[] values, Work work) throws Exception {
        CONTEXT.set(owner(REQUEST, null));
        int held = RequestValues.begin(REQUEST, names, values, null, null);
        if (held < 0) {
            RESULTS.add("  FAIL the holder took the request's values (" + RequestValues.status() + ")");
        }
        try {
            work.run();
        } finally {
            RequestValues.end(REQUEST);
            CONTEXT.remove();
        }
    }

    static long claim(List<String> sensors) {
        capture = CONTEXT::get;
        reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "security-sinks-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        return (Long) result.get("token");
    }

    static Object[] owner(String request, String execution) {
        return new Object[] {request, execution, null, null, "/reports", null, null, 1L, 1L};
    }

    /** A value built at run time, so no constant or interned literal holds it. */
    static String seed(String prefix) {
        return new String((prefix + "-" + Long.toHexString(System.nanoTime())).toCharArray());
    }

    static long[] await(int sensor, int kind) throws Exception {
        for (int i = 0; i < 200; i++) {
            drain();
            long[] found = find(sensor, kind);
            if (found != null) {
                return found;
            }
            Thread.sleep(25);
        }
        return null;
    }

    static long[] find(int sensor, int kind) {
        for (long[] record : RECORDS) {
            if (record[SideEffects.R_SENSOR] == sensor && record[SideEffects.R_KIND] == kind) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.flushThread();
        SideEffects.drain(token, record -> RECORDS.add(record.clone()));
    }

    static List<String> interned() {
        String[] strings = SideEffects.interned(generation, 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    static String string(long id) {
        if (id <= 0) {
            return "";
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? "" : strings[0];
    }

    static String frame(int id) {
        return string(id);
    }

    static String describe() {
        List<String> described = new ArrayList<>();
        for (long[] record : RECORDS) {
            described.add("sensor=" + record[SideEffects.R_SENSOR] + " kind=" + record[SideEffects.R_KIND] + " target="
                    + string(record[SideEffects.R_TARGET]));
        }
        return described.toString();
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
