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

        for (int i = 0; i < 2; i++) {
            long opening = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
            SideEffects.fileOpened(
                    opening,
                    SideEffects.HOOK_FILE_INPUT_STREAM,
                    SideEffects.KIND_FILE_READ,
                    "/srv/app/reports/" + value + ".csv",
                    null);
        }

        assertThat(sinks(token)).hasSize(2);
        assertThat(String.join("|", interned())).doesNotContain(value);
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
