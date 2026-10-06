package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The security-sinks sensor's request-value matching at the bridge-side sinks (PLAN-v2 §5.16, M5-6b): a process
 * start's arguments and a file operation's path, published as records on the side-effect sensors' ring with the
 * command's file name and the argument's index, or the redacted path's pattern, and the parameter's name; never a
 * value, never an argument, in any record or interned string. An engine-side sink publishes the same way.
 */
class RequestValuesSinksTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();

    private static final SideEffects.Places PLACES = new SideEffects.Places(
            null,
            "/srv/app",
            new String[] {"/srv/app"},
            new String[] {"/tmp"},
            new String[] {"/home/alice"},
            new String[] {"/opt/jdk"},
            new String[0],
            false);

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        SideEffects.places(null);
        CodePaths.FRAME.remove();
        AgentBridge.reset();
    }

    @Test
    void aCommandArgumentHoldingAValueRecordsTheCommandAndTheArgumentsIndexOnly() {
        long token = claim(List.of(SideEffects.PROCESSES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));
        String value = seeded("report-q3");
        RequestValues.begin(REQUEST, new String[] {"file"}, new String[] {value}, null, null);

        long started = SideEffects.processStarting();
        SideEffects.processStarted(
                started, List.of("/usr/bin/convert", "/srv/in/" + value + ".pdf", "out.png"), null, new IOException());

        List<long[]> sinks = sinks(token);
        assertThat(sinks).hasSize(1);
        long[] record = sinks.get(0);
        assertThat(record[SideEffects.R_KIND]).isEqualTo(RequestValues.SINK_COMMAND);
        assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("convert, argument 1");
        assertThat(string(record[SideEffects.R_FLAGS] >>> 32)).isEqualTo("file");
        assertThat(record[SideEffects.R_NANOS]).as("the raw text's keyed hash").isPositive();
        assertThat(String.join("|", interned())).doesNotContain(value).doesNotContain("/srv/in");
    }

    @Test
    void aFilePathHoldingAValueRecordsTheRedactedPathsPattern() {
        long token = claim(List.of(SideEffects.FILES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_FILES);
        SideEffects.places(PLACES);
        context.set(owner(REQUEST));
        String value = seeded("../../etc/passwd");
        RequestValues.begin(REQUEST, new String[] {"name"}, new String[] {value}, null, null);

        long opening = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
        SideEffects.fileOpened(
                opening,
                SideEffects.HOOK_FILE_INPUT_STREAM,
                SideEffects.KIND_FILE_READ,
                "/srv/app/reports/" + value,
                null);

        List<long[]> sinks = sinks(token);
        assertThat(sinks).hasSize(1);
        assertThat(sinks.get(0)[SideEffects.R_KIND]).isEqualTo(RequestValues.SINK_FILE);
        assertThat(string(sinks.get(0)[SideEffects.R_TARGET])).isEqualTo("./reports/{name}");
        assertThat(String.join("|", interned())).doesNotContain("passwd");
    }

    @Test
    void anExecutableHoldingAValueIsNamedByNeitherRecord() {
        long token = claim(List.of(SideEffects.PROCESSES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));
        String value = seeded("evil-tool");
        RequestValues.begin(REQUEST, new String[] {"tool"}, new String[] {value}, null, null);

        SideEffects.processStarted(SideEffects.processStarting(), List.of(value, "--help"), null, new IOException());

        assertThat(String.join("|", interned())).doesNotContain(value).contains(RequestValues.FROM_REQUEST_INPUT);
        assertThat(sinks(token)).hasSize(1);
    }

    @Test
    void aPathOpenedTwiceByTheRequestIsRedactedBothTimesInEveryRecord() {
        long token = claim(List.of(SideEffects.FILES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_FILES);
        SideEffects.places(PLACES);
        context.set(owner(REQUEST));
        String value = seeded("quarterly");
        RequestValues.begin(REQUEST, new String[] {"name"}, new String[] {value}, null, null);

        // Past the request's check budget: a path opened again and again, as in a loop, spends none of it.
        for (int i = 0; i < RequestValues.MAX_CHECKS + 44; i++) {
            long opening = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
            SideEffects.fileOpened(
                    opening,
                    SideEffects.HOOK_FILE_INPUT_STREAM,
                    SideEffects.KIND_FILE_READ,
                    "/srv/app/reports/" + value + ".csv",
                    null);
        }

        assertThat(sinks(token)).as("published once for the request").hasSize(1);
        assertThat(String.join("|", interned()))
                .doesNotContain(value)
                .doesNotContain(RequestValues.NOT_CHECKED)
                .contains("./reports/{name}.csv");
        assertThat(RequestValues.status().get("stopped")).isEqualTo(0L);
    }

    @Test
    void aPathOrAnExecutableTheRequestsMatchingCouldNotCheckIsNeverNamed() {
        long token = claim(List.of(SideEffects.FILES, SideEffects.PROCESSES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_FILES | SideEffects.MASK_PROCESSES | SideEffects.MASK_SECURITY_SINKS);
        SideEffects.places(PLACES);
        context.set(owner(REQUEST));
        String value = seeded("quarterly");
        RequestValues.begin(REQUEST, new String[] {"name"}, new String[] {value}, null, null);
        // The request's check budget spent on texts holding nothing.
        for (int i = 0; i <= RequestValues.MAX_CHECKS; i++) {
            RequestValues.match("select " + i, RequestValues.SINK_SQL, null, null);
        }

        long opening = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
        SideEffects.fileOpened(
                opening,
                SideEffects.HOOK_FILE_INPUT_STREAM,
                SideEffects.KIND_FILE_READ,
                "/srv/app/reports/" + value + ".csv",
                null);
        SideEffects.processStarted(SideEffects.processStarting(), List.of(value, "--help"), null, new IOException());

        List<String> interned = interned();
        assertThat(String.join("|", interned)).doesNotContain(value).contains(RequestValues.NOT_CHECKED);
        assertThat(sinks(token)).isEmpty();
    }

    @Test
    void aDigitsOnlyValueIsFlaggedSoTheEngineWaitsForConfirmation() {
        long token = claim(List.of(SideEffects.FILES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_FILES);
        SideEffects.places(PLACES);
        context.set(owner(REQUEST));
        RequestValues.begin(REQUEST, new String[] {"id"}, new String[] {seeded("20261005")}, null, null);

        long opening = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
        SideEffects.fileOpened(
                opening, SideEffects.HOOK_FILE_INPUT_STREAM, SideEffects.KIND_FILE_READ, "/srv/app/20261005.csv", null);

        long[] record = sinks(token).get(0);
        assertThat(record[SideEffects.R_FLAGS] & 0xFF).isEqualTo(RequestValues.FLAG_NUMERIC);
    }

    @Test
    void anEngineSideMatchPublishesItsRedactedTargetUnderTheCallersRequest() {
        long token = claim(List.of(SideEffects.SECURITY_SINKS));
        context.set(owner(REQUEST));
        RequestValues.begin(REQUEST, new String[] {"name"}, new String[] {seeded("alice")}, null, null);

        RequestValues.sinkMatched(
                RequestValues.SINK_SQL,
                "name",
                RequestValues.POSITION_IN_LITERAL,
                "select * from users where name = '{name}'",
                42L,
                43L,
                0L);

        List<long[]> sinks = sinks(token);
        assertThat(sinks).hasSize(1);
        assertThat(sinks.get(0)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(sinks.get(0)[SideEffects.R_FLAGS] & 0xFF).isEqualTo(RequestValues.POSITION_IN_LITERAL);
        assertThat(string(sinks.get(0)[SideEffects.R_TARGET])).isEqualTo("select * from users where name = '{name}'");
    }

    @Test
    void nothingIsPublishedWithoutTheSensorForAnotherRequestOrWithoutValues() {
        long token = claim(List.of(SideEffects.PROCESSES));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));
        assertThat(RequestValues.begin(REQUEST, new String[] {"file"}, new String[] {"report"}, null, null))
                .as("a claim without the security-sinks sensor")
                .isEqualTo(-1);
        SideEffects.processStarted(
                SideEffects.processStarting(), List.of("convert", "report"), null, new IOException());
        assertThat(sinks(token)).isEmpty();

        token = claim(List.of(SideEffects.PROCESSES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        RequestValues.begin(REQUEST, new String[] {"file"}, new String[] {"report"}, null, null);
        context.set(owner("00000000000000cd"));
        SideEffects.processStarted(
                SideEffects.processStarting(), List.of("convert", "report"), null, new IOException());
        assertThat(sinks(token)).as("another request's thread").isEmpty();
    }

    @Test
    void noSeededValueIsReachableFromTheBridgeAfterTheResponseTheInternTableAndRingIncluded() throws Exception {
        claim(List.of(SideEffects.FILES, SideEffects.PROCESSES, SideEffects.SECURITY_SINKS));
        SideEffects.enable(SideEffects.MASK_FILES | SideEffects.MASK_PROCESSES | SideEffects.MASK_SECURITY_SINKS);
        SideEffects.places(PLACES);
        context.set(owner(REQUEST));
        // Letters found nowhere else in the bridge, built at run time, so no constant holds them.
        String four = seeded("zqxv");
        String file = seeded("quarterlyzz");
        RequestValues.begin(REQUEST, new String[] {"code", "file"}, new String[] {four, file}, null, null);
        long opening = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
        SideEffects.fileOpened(
                opening,
                SideEffects.HOOK_FILE_INPUT_STREAM,
                SideEffects.KIND_FILE_READ,
                "/srv/app/reports/" + file + "-" + four + ".csv",
                null);
        SideEffects.processStarted(
                SideEffects.processStarting(),
                List.of("/usr/bin/convert", "/srv/in/" + file + ".pdf", four),
                null,
                new IOException());
        RequestValues.match("select * from t where c = '" + four + "'", RequestValues.SINK_SQL, null, null);
        RequestValues.end(REQUEST);
        context.set(null);

        // The records stay on the ring, undrained, and their strings in the intern table.
        List<String> strings = new ArrayList<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        for (Class<?> type :
                List.of(RequestValues.class, SideEffects.class, AgentRing.class, AgentBridge.class, CodePaths.class)) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !field.getType().isPrimitive()) {
                    field.setAccessible(true);
                    walk(field.get(null), seen, strings, 0);
                }
            }
        }
        assertThat(strings)
                .as("the walk reached the intern table")
                .anyMatch(text -> text.contains("{file}"))
                .anyMatch(text -> text.contains("convert, argument 1"));
        assertThat(strings).noneMatch(text -> text.contains(four) || text.contains(file));
    }

    /** Walks arrays, maps, collections, atomic and plain references, and bridge objects, collecting every string. */
    private static void walk(
            Object value, java.util.IdentityHashMap<Object, Boolean> seen, List<String> strings, int depth)
            throws IllegalAccessException {
        if (value == null || depth > 12 || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (value instanceof String text) {
            strings.add(text);
        } else if (value instanceof Object[] array) {
            for (Object item : array) {
                walk(item, seen, strings, depth + 1);
            }
        } else if (value instanceof AtomicReference<?> reference) {
            walk(reference.get(), seen, strings, depth + 1);
        } else if (value instanceof java.util.concurrent.atomic.AtomicReferenceArray<?> array) {
            for (int i = 0; i < array.length(); i++) {
                walk(array.get(i), seen, strings, depth + 1);
            }
        } else if (value instanceof java.lang.ref.Reference<?> reference) {
            walk(reference.get(), seen, strings, depth + 1);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                walk(entry.getKey(), seen, strings, depth + 1);
                walk(entry.getValue(), seen, strings, depth + 1);
            }
        } else if (value instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                walk(item, seen, strings, depth + 1);
            }
        } else if (value.getClass().getName().startsWith("io.github.jdubois.bootui.agent.bridge.")) {
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            && !field.getType().isPrimitive()) {
                        field.setAccessible(true);
                        walk(field.get(value), seen, strings, depth + 1);
                    }
                }
            }
        }
    }

    private long claim(List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        Supplier<Object> capture = context::get;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        if (sensors.contains(SideEffects.SECURITY_SINKS)) {
            // As the agent does once the sensor's transformer is installed and self-tested: matching follows its bit.
            SideEffects.enable(SideEffects.MASK_SECURITY_SINKS);
        }
        return (Long) result.get("token");
    }

    private static Object[] owner(String request) {
        return new Object[] {request, null, null, null, null, null, null, 1L, 1L};
    }

    private static String seeded(String value) {
        return new String(value.toCharArray());
    }

    private static List<long[]> sinks(long token) {
        List<long[]> records = new ArrayList<>();
        SideEffects.drain(token, record -> {
            if (record[SideEffects.R_SENSOR] == SideEffects.SENSOR_SECURITY_SINKS) {
                records.add(record.clone());
            }
        });
        return records;
    }

    private static long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private static List<String> interned() {
        String[] strings = SideEffects.interned(generation(), 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    private static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation(), (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
