package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The request value holder (PLAN-v2 §5.16, M5-6b): its gate, its lifetime (begin, end, deadline, generation), the
 * owner rule ({@code SLOT_SCOPE} or a capture of the request's own work, never a handoff), its caps and matching
 * budget, and redaction; and that once a request ended, nothing the bridge holds reaches one of its values.
 */
class RequestValuesTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;
    private static final String OTHER = "00000000000000cd";
    private static final long OTHER_BITS = 0xcdL;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();

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
        CodePaths.FRAME.remove();
        AgentBridge.reset();
    }

    @Test
    void nothingIsHeldUntilTheSensorIsOnUnderAnArmedClaim() {
        assertThat(RequestValues.active()).isFalse();
        assertThat(RequestValues.begin(REQUEST, names("name"), values("alice"), null, null))
                .isEqualTo(-1);

        claim();
        assertThat(RequestValues.active()).as("the sensor is not on").isFalse();
        assertThat(RequestValues.begin(REQUEST, names("name"), values("alice"), null, null))
                .isEqualTo(-1);

        RequestValues.sensor(true, claimGeneration());
        assertThat(RequestValues.active()).isTrue();
        assertThat(RequestValues.begin(REQUEST, names("name"), values("alice"), null, null))
                .isEqualTo(1);
        assertThat(RequestValues.live()).isEqualTo(1);

        RequestValues.sensor(false, 0L);
        assertThat(RequestValues.live())
                .as("the sensor going off wipes the table")
                .isZero();
    }

    @Test
    void theSensorIsOnOnlyForTheClaimItWasEnabledFor() {
        on();
        assertThat(RequestValues.active()).isTrue();
        claim();
        assertThat(RequestValues.active())
                .as("a later claim that did not enable the sensor")
                .isFalse();
        assertThat(RequestValues.begin(REQUEST, names("name"), values("alice"), null, null))
                .isEqualTo(-1);
        RequestValues.sensor(true, claimGeneration());
        assertThat(RequestValues.active()).isTrue();
    }

    @Test
    void aValueMatchesOnTheRequestsOwnWorkUntilTheRequestEnds() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);

        int[] spans = new int[RequestValues.SPANS_LENGTH];
        String[] names = new String[RequestValues.MAX_VALUES];
        String sql = "select * from users where name = 'alice'";
        assertThat(RequestValues.match(sql, RequestValues.SINK_SQL, spans, names))
                .isEqualTo(1);
        assertThat(spans[RequestValues.S_COUNT]).isEqualTo(1);
        assertThat(spans[RequestValues.S_FLAGS]).isZero();
        assertThat(spans[RequestValues.S_FIRST]).isZero();
        assertThat(sql.substring(spans[RequestValues.S_FIRST + 1], spans[RequestValues.S_FIRST + 2]))
                .isEqualTo("alice");
        assertThat(names[0]).isEqualTo("name");
        assertThat(RequestValues.redact(sql, spans, names)).isEqualTo("select * from users where name = '{name}'");

        RequestValues.end(REQUEST);
        assertThat(RequestValues.live()).isZero();
        assertThat(RequestValues.match("where name = 'alice' or 1=1", RequestValues.SINK_SQL, spans, names))
                .isZero();
    }

    @Test
    void anotherRequestsThreadAndAPropagatedTaskGetNoValues() {
        on();
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        String sql = "where name = 'alice'";

        context.set(owner(OTHER, null));
        assertThat(RequestValues.match(sql, RequestValues.SINK_SQL, null, null))
                .as("another request")
                .isZero();
        context.set(owner(REQUEST, "async-00000000000000ef"));
        assertThat(RequestValues.match(sql, RequestValues.SINK_SQL, null, null))
                .as("a task the request handed to an executor")
                .isZero();
        context.set(owner(REQUEST, "task-00000000000000ef"));
        assertThat(RequestValues.match(sql, RequestValues.SINK_SQL, null, null))
                .as("a managed task of the request")
                .isZero();
        context.set(null);
        assertThat(RequestValues.match(sql, RequestValues.SINK_SQL, null, null))
                .as("unowned work")
                .isZero();
        context.set(owner(REQUEST, null));
        assertThat(RequestValues.match(sql, RequestValues.SINK_SQL, null, null)).isEqualTo(1);
    }

    @Test
    void onlyAScopeSlotAnswersAndAHandoffSlotNeverDoes() {
        onWithSlots();
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        String file = "/srv/reports/alice.pdf";
        // The capture names the request, but the slot is what the thread is doing.
        context.set(owner(REQUEST, null));

        SideEffects.handoff(owner(REQUEST, null), sideEffectsGeneration());
        try {
            assertThat(RequestValues.match(file, RequestValues.SINK_FILE, null, null))
                    .as("a propagated task's handoff slot, even naming the request")
                    .isZero();
        } finally {
            SideEffects.handoffDone();
        }

        context.set(null);
        SideEffects.scopeBegin(new long[] {REQUEST_BITS, 0L, 0L}, true);
        try {
            assertThat(RequestValues.match(file, RequestValues.SINK_FILE, null, null))
                    .as("the request's own scope slot")
                    .isEqualTo(1);
        } finally {
            SideEffects.scopeEnd();
        }

        SideEffects.scopeBegin(new long[] {OTHER_BITS, 0L, 0L}, true);
        try {
            assertThat(RequestValues.match(file, RequestValues.SINK_FILE + 16, null, null))
                    .as("another request's scope slot")
                    .isZero();
        } finally {
            SideEffects.scopeEnd();
        }

        SideEffects.scopeBegin(new long[] {0L, 0x12L, CodePaths.EXECUTION_ASYNC}, true);
        try {
            assertThat(RequestValues.match(file, RequestValues.SINK_FILE + 32, null, null))
                    .as("a scope of an execution")
                    .isZero();
        } finally {
            SideEffects.scopeEnd();
        }
    }

    @Test
    void aScopeWithoutAnOwnerFallsBackToTheCapture() {
        onWithSlots();
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        SideEffects.scopeBegin(null, true);
        try {
            context.set(owner(REQUEST, null));
            assertThat(RequestValues.match("alice", RequestValues.SINK_URL, null, null))
                    .isEqualTo(1);
        } finally {
            SideEffects.scopeEnd();
        }
    }

    @Test
    void aMissedEndIsSweptAfterTheDeadlineAndANewClaimWipesTheTable() {
        on();
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        RequestValues.expireAll();
        RequestValues.begin(OTHER, names("name"), values("bobby"), null, null);
        assertThat(RequestValues.live()).as("the expired entry was swept").isEqualTo(1);
        assertThat(RequestValues.status().get("expired")).isEqualTo(1L);

        claim();
        assertThat(RequestValues.live()).as("a newer generation").isZero();
        RequestValues.sensor(true, claimGeneration());
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        assertThat(RequestValues.live()).isEqualTo(1);

        AgentBridge.release("shop", "dev");
        assertThat(RequestValues.live()).as("released").isZero();
        assertThat(RequestValues.active()).isFalse();
    }

    @Test
    void valuesOutsideTheLengthsAndPastTheCountAreSkippedAndCounted() {
        on();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        names.add("short");
        values.add("abc");
        names.add("long");
        values.add("x".repeat(RequestValues.MAX_LENGTH + 1));
        names.add("edge");
        values.add("y".repeat(RequestValues.MAX_LENGTH));
        for (int i = 0; i < 40; i++) {
            names.add("p" + i);
            values.add("value-" + i);
        }
        int held =
                RequestValues.begin(REQUEST, names.toArray(new String[0]), values.toArray(new String[0]), null, null);

        assertThat(held).isEqualTo(RequestValues.MAX_VALUES);
        Map<String, Object> status = RequestValues.status();
        assertThat(status.get("valuesTooShort")).isEqualTo(1L);
        assertThat(status.get("valuesTooLong")).isEqualTo(1L);
        assertThat(status.get("valuesOverCount")).isEqualTo(9L);
    }

    @Test
    void namesThatAreNotSafeToShowAreReplacedByTheirPosition() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(
                REQUEST,
                new String[] {"user[name]", "<script>", null, "x".repeat(65)},
                values("alpha", "bravo", "charlie", "delta"),
                null,
                null);
        String[] names = new String[RequestValues.MAX_VALUES];

        int mask = RequestValues.match("alpha bravo charlie delta", RequestValues.SINK_COMMAND, null, names);

        assertThat(mask).isEqualTo(0b1111);
        assertThat(Arrays.copyOf(names, 4))
                .containsExactly(
                        "user[name]",
                        RequestValues.fallbackName("<script>"),
                        "param#0000",
                        RequestValues.fallbackName("x".repeat(65)));
        assertThat(names[1]).as("stable across requests, never the name itself").startsWith("param#");
        assertThat(String.join("", names[1], names[3])).doesNotContain("script").doesNotContain("xxxx");
    }

    @Test
    void theTableHoldsAtMostItsEntriesAndCountsTheRest() {
        on();
        for (int i = 0; i < RequestValues.ENTRIES + 3; i++) {
            RequestValues.begin(String.format("%016x", i + 1), names("name"), values("value" + i), null, null);
        }
        assertThat(RequestValues.live()).isEqualTo(RequestValues.ENTRIES);
        assertThat(RequestValues.status().get("tableFull")).isEqualTo(3L);
    }

    @Test
    void checksStopAtTheirBudgetAndRepeatedTextsAreNotCheckedAgain() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        int[] spans = new int[RequestValues.SPANS_LENGTH];
        String[] names = new String[RequestValues.MAX_VALUES];

        assertThat(RequestValues.match("select 1", RequestValues.SINK_SQL, spans, null))
                .isZero();
        assertThat(RequestValues.match("select 1", RequestValues.SINK_SQL, spans, null))
                .isZero();
        assertThat(spans[RequestValues.S_FLAGS])
                .as("a text that matched nothing is not checked again")
                .isEqualTo(RequestValues.F_REPEATED);
        assertThat(RequestValues.redact("select 1", spans, names))
                .as("a check that compared nothing keeps no text")
                .isNull();
        for (int i = 0; i < 2 * RequestValues.MAX_CHECKS; i++) {
            RequestValues.match("select 1", RequestValues.SINK_SQL, spans, null);
        }
        assertThat(RequestValues.status().get("stopped"))
                .as("a statement repeated in a loop spends no check")
                .isEqualTo(0L);
        assertThat(RequestValues.match("q 'alice'", RequestValues.SINK_SQL, spans, names))
                .isEqualTo(1);
        assertThat(RequestValues.match("q 'alice'", RequestValues.SINK_SQL, spans, names))
                .as("a text that matched is compared and redacted again")
                .isEqualTo(1);
        assertThat(RequestValues.redact("q 'alice'", spans, names)).isEqualTo("q '{name}'");

        for (int i = 0; i < RequestValues.MAX_CHECKS; i++) {
            RequestValues.match("text " + i, RequestValues.SINK_SQL, spans, null);
        }
        assertThat(RequestValues.match("again 'alice'", RequestValues.SINK_SQL, spans, null))
                .isZero();
        assertThat(spans[RequestValues.S_FLAGS]).isEqualTo(RequestValues.F_STOPPED);
        assertThat(RequestValues.status().get("stopped")).isEqualTo(1L);
    }

    @Test
    void theComparisonBudgetStopsMatchingForTheRequest() {
        on();
        context.set(owner(REQUEST, null));
        String[] values = new String[RequestValues.MAX_VALUES];
        String[] names = new String[RequestValues.MAX_VALUES];
        for (int i = 0; i < values.length; i++) {
            values[i] = String.format("w%03d", i);
            names[i] = "p" + i;
        }
        RequestValues.begin(REQUEST, names, values, null, null);
        int[] spans = new int[RequestValues.SPANS_LENGTH];
        // 16 KB times 32 values of 4 characters is 2 Mi comparisons: the third such check exceeds 4 Mi.
        String big = "z".repeat(RequestValues.MAX_SCAN);
        int checks = 0;
        while (checks < 20) {
            RequestValues.match(checks + big, RequestValues.SINK_SQL, spans, null);
            if ((spans[RequestValues.S_FLAGS] & RequestValues.F_STOPPED) != 0) {
                break;
            }
            checks++;
        }
        assertThat(checks).isEqualTo(2);
    }

    @Test
    void aLongTextIsScannedPartlyAndThenKeepsNoRedactedText() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        int[] spans = new int[RequestValues.SPANS_LENGTH];
        String[] names = new String[RequestValues.MAX_VALUES];
        String text = "'alice' " + "z".repeat(RequestValues.MAX_SCAN) + " 'alice-after-the-limit'";

        assertThat(RequestValues.match(text, RequestValues.SINK_SQL, spans, names))
                .isEqualTo(1);
        assertThat(spans[RequestValues.S_FLAGS]).isEqualTo(RequestValues.F_PARTIAL);
        assertThat(spans[RequestValues.S_COUNT]).as("only the scanned prefix").isEqualTo(1);
        assertThat(RequestValues.redact(text, spans, names)).as("fails closed").isNull();
    }

    @Test
    void moreThanEightSpansOverflowAndKeepNoRedactedText() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        int[] spans = new int[RequestValues.SPANS_LENGTH];
        String[] names = new String[RequestValues.MAX_VALUES];
        String text = "alice,".repeat(9);

        assertThat(RequestValues.match(text, RequestValues.SINK_COMMAND, spans, names))
                .isEqualTo(1);
        assertThat(spans[RequestValues.S_COUNT]).isEqualTo(RequestValues.MAX_SPANS);
        assertThat(spans[RequestValues.S_FLAGS]).isEqualTo(RequestValues.F_OVERFLOW);
        assertThat(RequestValues.redact(text, spans, names)).isNull();
    }

    @Test
    void redactionMergesOverlappingSpansAndReplacesEveryOccurrence() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("short", "long"), values("data", "database"), null, null);
        int[] spans = new int[RequestValues.SPANS_LENGTH];
        String[] names = new String[RequestValues.MAX_VALUES];
        String text = "/srv/database/data/data.csv";

        assertThat(RequestValues.match(text, RequestValues.SINK_FILE, spans, names))
                .isEqualTo(0b11);
        assertThat(RequestValues.redact(text, spans, names)).isEqualTo("/srv/{long}/{short}/{short}.csv");
    }

    @Test
    void lateValuesAreReadAtTheFirstCheckOnceAHandlerMappingSetThem() {
        on();
        context.set(owner(REQUEST, null));
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        String key = "org.springframework.web.reactive.HandlerMapping.uriTemplateVariables";
        RequestValues.begin(REQUEST, names("q"), values("query"), attributes, new String[] {key});
        String[] names = new String[RequestValues.MAX_VALUES];

        assertThat(RequestValues.match("/files/report", RequestValues.SINK_FILE, null, names))
                .as("not mapped yet")
                .isZero();
        attributes.put(key, Map.of("file", "report"));
        assertThat(RequestValues.match("/files/report", RequestValues.SINK_FILE + 1, null, names))
                .isEqualTo(0b10);
        assertThat(names[1]).isEqualTo("file");
    }

    @Test
    void aLockHeldByADeadHolderIsTakenOverAndItsLateUnlockReleasesNothing() throws Exception {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("q"), values("held-value"), null, null);
        Field requestsField = RequestValues.class.getDeclaredField("REQUESTS");
        requestsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLongArray requests =
                (java.util.concurrent.atomic.AtomicLongArray) requestsField.get(null);
        Field locksField = RequestValues.class.getDeclaredField("LOCKS");
        locksField.setAccessible(true);
        java.util.concurrent.atomic.AtomicIntegerArray locks =
                (java.util.concurrent.atomic.AtomicIntegerArray) locksField.get(null);
        int index = -1;
        for (int i = 0; i < RequestValues.ENTRIES; i++) {
            if (requests.get(i) != 0L) {
                index = i;
            }
        }
        // A holder that died inside: its token stays in the lock.
        int dead = -7;
        locks.set(index, dead);

        RequestValues.end(REQUEST);

        assertThat(RequestValues.status().get("lockTakeovers")).isEqualTo(1L);
        assertThat(RequestValues.status().get("live")).isEqualTo(0);
        assertThat(locks.get(index))
                .as("released by the thread that took it over")
                .isZero();
        assertThat(requests.get(index)).isZero();

        // The next request takes the entry; the dead holder's unlock, were it to run now, releases nothing.
        RequestValues.begin(REQUEST, names("q"), values("next-value"), null, null);
        locks.set(index, 99);
        java.lang.reflect.Method unlock = RequestValues.class.getDeclaredMethod("unlock", int.class, int.class);
        unlock.setAccessible(true);
        unlock.invoke(null, index, dead);
        assertThat(locks.get(index)).isEqualTo(99);
        locks.set(index, 0);
        RequestValues.end(REQUEST);
        RequestValues.end(REQUEST);
        assertThat(RequestValues.status().get("live"))
                .as("a second end never counts twice")
                .isEqualTo(0);
    }

    @Test
    void anOvertakenHolderThatResumesNeverFreesNorWritesIntoTheNextRequestsEntry() throws Exception {
        on();
        context.set(owner(REQUEST, null));
        String first = new String("first-" + System.nanoTime());
        String stale = new String("stale-" + System.nanoTime());
        String third = new String("third-" + System.nanoTime());
        RequestValues.begin(REQUEST, names("q"), new String[] {first}, null, null);
        java.util.concurrent.atomic.AtomicLongArray requests = field("REQUESTS");
        java.util.concurrent.atomic.AtomicIntegerArray locks = field("LOCKS");
        java.util.concurrent.atomic.AtomicReferenceArray<?> table = field("TABLE");
        int index = -1;
        for (int i = 0; i < RequestValues.ENTRIES; i++) {
            if (requests.get(i) != 0L) {
                index = i;
            }
        }
        Object overtaken = table.get(index);
        // Another request whose slot is the same, so it takes the slot the first one leaves.
        Method slot = RequestValues.class.getDeclaredMethod("slot", long.class);
        slot.setAccessible(true);
        long next = 0x100L;
        while ((int) slot.invoke(null, next) != (int) slot.invoke(null, Long.parseLong(REQUEST, 16))) {
            next++;
        }
        String nextRequest = String.format("%016x", next);

        // The first request's holder stalls inside; its end takes the lock over and detaches the entry.
        locks.set(index, -7);
        RequestValues.end(REQUEST);
        RequestValues.begin(nextRequest, names("q"), new String[] {third}, null, null);
        assertThat(requests.get(index)).isEqualTo(next);
        assertThat(table.get(index)).isNotSameAs(overtaken);

        // The stalled holder resumes: its wipe frees nothing, and what it adds goes into the detached object.
        Class<?> entryType = overtaken.getClass();
        Method wipe = RequestValues.class.getDeclaredMethod("wipe", int.class, entryType);
        wipe.setAccessible(true);
        Method add = RequestValues.class.getDeclaredMethod("add", entryType, String.class, String.class);
        add.setAccessible(true);
        assertThat((boolean) wipe.invoke(null, index, overtaken)).isFalse();
        add.invoke(null, overtaken, "late", stale);
        assertThat(requests.get(index)).isEqualTo(next);
        assertThat(RequestValues.status().get("live")).isEqualTo(1);

        RequestValues.end(nextRequest);
        assertThat(RequestValues.status().get("live")).isEqualTo(0);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> reached = new ArrayList<>();
        reach(RequestValues.class, seen, reached, 0);
        assertThat(reached).noneMatch(o -> o == first || o == stale || o == third || o == overtaken);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(String name) throws ReflectiveOperationException {
        Field field = RequestValues.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    @Test
    void signedAndDecimalNumbersAreNumbersAndNothingElseIs() {
        for (String number : List.of("4242", "-33.8688", "+7", "151.2093", ".5")) {
            assertThat(RequestValues.number(number, 0, number.length()))
                    .as(number)
                    .isTrue();
        }
        for (String text : List.of("-", ".", "1.2.3", "-x.1", "12a", "--1", "")) {
            assertThat(RequestValues.number(text, 0, text.length())).as(text).isFalse();
        }
    }

    @Test
    void aPartlyScannedTextIsCheckedAgainEachTimeNeverReportedAsRepeated() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("q"), values("hidden-far"), null, null);
        String text = "z".repeat(RequestValues.MAX_SCAN) + " hidden-far";
        int[] spans = new int[RequestValues.SPANS_LENGTH];
        for (int i = 0; i < 3; i++) {
            spans[RequestValues.S_FLAGS] = 0;
            assertThat(RequestValues.match(text, RequestValues.SINK_FILE, spans, null))
                    .isZero();
            assertThat(spans[RequestValues.S_FLAGS]).as("check %d", i).isEqualTo(RequestValues.F_PARTIAL);
        }
    }

    @Test
    void noValueIsReachableFromTheBridgeOnceTheRequestEnded() throws Exception {
        on();
        context.set(owner(REQUEST, null));
        // Built at run time, so no constant pool or interned literal holds them.
        String shortValue = new String(new char[] {'a', 'b', 'c', 'd'});
        String longValue = new String("seeded-" + System.nanoTime());
        String late = new String("late-" + System.nanoTime());
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put("vars", new LinkedHashMap<>(Map.of("v", late)));
        RequestValues.begin(
                REQUEST, names("s", "l"), new String[] {shortValue, longValue}, attributes, new String[] {"vars"});
        RequestValues.match("abcd " + longValue + " " + late, RequestValues.SINK_SQL, null, null);

        RequestValues.end(REQUEST);

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> reached = new ArrayList<>();
        reach(RequestValues.class, seen, reached, 0);
        assertThat(reached)
                .as("the bridge reaches no seeded value")
                .noneMatch(o -> o == shortValue || o == longValue || o == late || o == attributes);
    }

    @Test
    void theStatusNamesCountersNeverAValueOrAName() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("secretname"), values("hunter22"), null, null);
        RequestValues.match("x hunter22", RequestValues.SINK_SQL, null, null);

        String status = String.valueOf(RequestValues.status());
        assertThat(status).doesNotContain("hunter22").doesNotContain("secretname");
        assertThat(RequestValues.status().get("matched")).isEqualTo(1L);
    }

    /** Walks the static fields of {@code type} and everything they reach in arrays and bridge objects. */
    private static void reach(Class<?> type, IdentityHashMap<Object, Boolean> seen, List<Object> out, int depth)
            throws IllegalAccessException {
        for (Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && !field.getType().isPrimitive()) {
                field.setAccessible(true);
                walk(field.get(null), seen, out, depth);
            }
        }
    }

    private static void walk(Object value, IdentityHashMap<Object, Boolean> seen, List<Object> out, int depth)
            throws IllegalAccessException {
        if (value == null || depth > 6 || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        out.add(value);
        if (value instanceof Object[] array) {
            for (Object item : array) {
                walk(item, seen, out, depth + 1);
            }
            return;
        }
        if (value instanceof java.util.concurrent.atomic.AtomicReferenceArray<?> array) {
            for (int i = 0; i < array.length(); i++) {
                walk(array.get(i), seen, out, depth + 1);
            }
            return;
        }
        if (value.getClass().getName().startsWith("io.github.jdubois.bootui.agent.bridge.")) {
            for (Field field : value.getClass().getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !field.getType().isPrimitive()) {
                    field.setAccessible(true);
                    walk(field.get(value), seen, out, depth + 1);
                }
            }
        }
    }

    @Test
    void anApplicationMapIsNeverReadAsALateSource() {
        on();
        context.set(owner(REQUEST, null));
        Map<String, Object> attributes = new ConcurrentHashMap<>() {
            @Override
            public Object get(Object key) {
                throw new AssertionError("an application map was read");
            }
        };
        RequestValues.begin(REQUEST, names("q"), values("query"), attributes, new String[] {"vars"});

        assertThat(RequestValues.match("query", RequestValues.SINK_URL, null, null))
                .isEqualTo(1);
    }

    @Test
    void aShortSpansArrayMarksTheCheckOverflowedSoNoTextIsKept() {
        on();
        context.set(owner(REQUEST, null));
        RequestValues.begin(REQUEST, names("name"), values("alice"), null, null);
        int[] spans = new int[4];
        String[] names = new String[RequestValues.MAX_VALUES];

        assertThat(RequestValues.match("'alice'", RequestValues.SINK_SQL, spans, names))
                .isEqualTo(1);
        assertThat(spans[RequestValues.S_FLAGS] & RequestValues.F_OVERFLOW).isNotZero();
        assertThat(RequestValues.redact("'alice'", spans, names)).isNull();
        assertThat(RequestValues.redact("'alice'", new int[] {9, 0, 0, 0, 5}, names))
                .as("malformed spans never throw")
                .isNull();
    }

    private void on() {
        claim();
        RequestValues.sensor(true, claimGeneration());
    }

    private static long claimGeneration() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    /** A claim with the processes sensor enabled, so adapter scopes and handoffs push owner slots. */
    private void onWithSlots() {
        claim(List.of(SideEffects.PROCESSES));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        RequestValues.sensor(true, claimGeneration());
    }

    private void claim() {
        claim(List.of("executors"));
    }

    private void claim(List<String> sensors) {
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
    }

    private static long sideEffectsGeneration() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private static Object[] owner(String request, String execution) {
        return new Object[] {request, execution, null, null, null, null, null, 1L, 1L};
    }

    private static String[] names(String... names) {
        return names;
    }

    private static String[] values(String... values) {
        String[] copies = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            copies[i] = new String(values[i]);
        }
        return copies;
    }
}
