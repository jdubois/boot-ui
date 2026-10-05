package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Probe shapes (PLAN-v2 §5.14, M5-8, D44): every kind, the exact-class allowlist, and proof that no method of a recorded
 * value runs: {@code toString}, {@code hashCode}, {@code equals}, {@code size}, getters, and iterators all count calls.
 */
class ProbeShapesTests {

    /** Calls of any method of the application values below. */
    static final AtomicInteger CALLS = new AtomicInteger();

    @BeforeEach
    void table() {
        AgentBridge.reset();
        AgentRing.newGeneration(1L, AgentRing.MIN_CAPACITY);
        CALLS.set(0);
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void nullIsNullWithNoType() {
        long shape = ProbeShapes.shape(null);

        assertThat(ProbeShapes.kind(shape)).isEqualTo(ProbeShapes.NULL);
        assertThat(ProbeShapes.typeId(shape)).isZero();
    }

    @Test
    void aStringRecordsItsLengthNeverItsContent() {
        long shape = ProbeShapes.shape("s3cr3t-password");

        assertThat(ProbeShapes.kind(shape)).isEqualTo(ProbeShapes.STRING);
        assertThat(ProbeShapes.summary(shape)).isEqualTo(15);
        assertThat(name(shape)).isEqualTo("java.lang.String");
        assertThat(AgentRing.internedNow()).doesNotContain("s3cr3t-password");
    }

    @Test
    void allowlistedCollectionsAndMapsRecordTheirSize() {
        assertSized(new ArrayList<>(List.of(1, 2, 3)), ProbeShapes.COLLECTION, 3);
        assertSized(new HashSet<>(Set.of(1, 2)), ProbeShapes.COLLECTION, 2);
        assertSized(new LinkedHashSet<>(Set.of(1)), ProbeShapes.COLLECTION, 1);
        assertSized(List.of(), ProbeShapes.COLLECTION, 0);
        assertSized(List.of(1), ProbeShapes.COLLECTION, 1);
        assertSized(List.of(1, 2, 3, 4), ProbeShapes.COLLECTION, 4);
        assertSized(Set.of(1, 2, 3), ProbeShapes.COLLECTION, 3);
        assertSized(Arrays.asList(1, 2), ProbeShapes.COLLECTION, 2);
        assertSized(Collections.emptyList(), ProbeShapes.COLLECTION, 0);
        assertSized(Collections.singletonList(1), ProbeShapes.COLLECTION, 1);
        assertSized(new HashMap<>(Map.of(1, 2)), ProbeShapes.MAP, 1);
        assertSized(new LinkedHashMap<>(), ProbeShapes.MAP, 0);
        assertSized(new TreeMap<>(Map.of(1, 2, 3, 4)), ProbeShapes.MAP, 2);
        assertSized(new IdentityHashMap<>(), ProbeShapes.MAP, 0);
        assertSized(new ConcurrentHashMap<>(Map.of(1, 2)), ProbeShapes.MAP, 1);
        assertSized(Map.of(), ProbeShapes.MAP, 0);
        assertSized(Map.of(1, 2), ProbeShapes.MAP, 1);
        assertSized(Map.of(1, 2, 3, 4, 5, 6), ProbeShapes.MAP, 3);
    }

    @Test
    void theAllowlistHoldsOnlyDistinctJdkClasses() {
        List<Class<?>> all = new ArrayList<>();
        all.addAll(Arrays.asList(ProbeShapes.COLLECTIONS));
        all.addAll(Arrays.asList(ProbeShapes.MAPS));
        for (Class<?> type : all) {
            assertThat(type.getName()).startsWith("java.util.");
            assertThat(type.getClassLoader()).isNull();
        }
        // Several instances may share one class (List.of() and List.of(a, b, c) are both ListN): taken from instances,
        // the allowlist holds whatever classes this JDK uses.
        assertThat(all)
                .extracting(Class::getName)
                .contains(
                        "java.util.ArrayList",
                        "java.util.ImmutableCollections$List12",
                        "java.util.ImmutableCollections$ListN",
                        "java.util.ImmutableCollections$Map1",
                        "java.util.ImmutableCollections$MapN",
                        "java.util.Arrays$ArrayList",
                        "java.util.EnumMap");
    }

    @Test
    void arraysOptionalsAndEnumsRecordTheirSummary() {
        long ints = ProbeShapes.shape(new int[7]);
        assertThat(ProbeShapes.kind(ints)).isEqualTo(ProbeShapes.ARRAY);
        assertThat(ProbeShapes.summary(ints)).isEqualTo(7);
        assertThat(name(ints)).isEqualTo("[I");
        assertThat(name(ProbeShapes.shape(new String[2]))).isEqualTo("[Ljava.lang.String;");

        assertThat(ProbeShapes.summary(ProbeShapes.shape(Optional.of("x")))).isEqualTo(1);
        assertThat(ProbeShapes.summary(ProbeShapes.shape(Optional.empty()))).isZero();
        assertThat(ProbeShapes.summary(ProbeShapes.shape(OptionalInt.of(1)))).isEqualTo(1);
        assertThat(ProbeShapes.summary(ProbeShapes.shape(OptionalLong.empty()))).isZero();
        assertThat(ProbeShapes.kind(ProbeShapes.shape(OptionalDouble.of(1)))).isEqualTo(ProbeShapes.OPTIONAL);

        long constant = ProbeShapes.shape(Level.HIGH);
        assertThat(ProbeShapes.kind(constant)).isEqualTo(ProbeShapes.ENUM);
        // A constant with a body is its own class: the declaring enum names it.
        assertThat(name(constant)).isEqualTo(Level.class.getName());
        assertThat(AgentRing.internedNow().get(ProbeShapes.summary(constant) - 1))
                .isEqualTo("HIGH");
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void numbersBooleansAndOtherObjectsRecordTheirClassOnly() {
        for (Object value : new Object[] {42, 3L, 2.5d, Boolean.TRUE, 'c', new java.math.BigDecimal("12.50")}) {
            long shape = ProbeShapes.shape(value);
            assertThat(ProbeShapes.kind(shape)).isEqualTo(ProbeShapes.TYPE);
            assertThat(ProbeShapes.summary(shape)).isZero();
            assertThat(name(shape)).isEqualTo(value.getClass().getName());
        }
        assertThat(AgentRing.internedNow()).doesNotContain("42", "12.50", "true");
    }

    @Test
    void noMethodOfAnApplicationValueEverRuns() {
        Object[] values = {
            new CountingCollection(),
            new CountingList(),
            new CountingArrayList(),
            new CountingBean(),
            new CountingMap(),
            Collections.unmodifiableList(new CountingList()),
            Collections.unmodifiableCollection(new CountingCollection()),
            Collections.synchronizedList(new CountingList()),
            new TreeSet<>(new CountingComparator()),
            new TreeMap<>(new CountingComparator()),
        };
        for (Object value : values) {
            ProbeShapes.shape(value);
        }

        assertThat(CALLS).hasValue(0);
        assertThat(ProbeShapes.kind(ProbeShapes.shape(new CountingCollection())))
                .isEqualTo(ProbeShapes.TYPE);
        // A subclass of an allowlisted class may override size(): its class only.
        assertThat(ProbeShapes.kind(ProbeShapes.shape(new CountingArrayList()))).isEqualTo(ProbeShapes.TYPE);
        assertThat(ProbeShapes.kind(ProbeShapes.shape(Collections.unmodifiableList(List.of()))))
                .isEqualTo(ProbeShapes.TYPE);
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void lockingOrWalkingJdkCollectionsAreRecordedByClassOnly() {
        for (Object value : new Object[] {
            new Vector<>(),
            new java.util.Hashtable<>(),
            new TreeSet<>(),
            new ConcurrentSkipListMap<>(),
            new ConcurrentLinkedQueue<>(),
            Collections.synchronizedMap(new HashMap<>()),
            new ArrayList<>(List.of(1, 2, 3)).subList(0, 1),
            new HashMap<>().keySet()
        }) {
            assertThat(ProbeShapes.kind(ProbeShapes.shape(value)))
                    .as(value.getClass().getName())
                    .isEqualTo(ProbeShapes.TYPE);
        }
    }

    @Test
    void summariesSaturateAndAFullTableLeavesTheTypeUnknown() {
        long packed = ProbeShapes.pack(ProbeShapes.STRING, Integer.MAX_VALUE, 5);

        assertThat(ProbeShapes.summary(packed)).isEqualTo(ProbeShapes.MAX_SUMMARY);
        assertThat(ProbeShapes.typeId(packed)).isEqualTo(5);
        assertThat(ProbeShapes.kind(packed)).isEqualTo(ProbeShapes.STRING);

        AgentBridge.reset();
        long unknown = ProbeShapes.shape("x");
        assertThat(ProbeShapes.kind(unknown)).isEqualTo(ProbeShapes.STRING);
        assertThat(ProbeShapes.typeId(unknown)).isZero();
    }

    private static void assertSized(Object value, int kind, int size) {
        long shape = ProbeShapes.shape(value);
        assertThat(ProbeShapes.kind(shape)).as(value.getClass().getName()).isEqualTo(kind);
        assertThat(ProbeShapes.summary(shape)).as(value.getClass().getName()).isEqualTo(size);
        assertThat(name(shape)).isEqualTo(value.getClass().getName());
    }

    private static String name(long shape) {
        int id = ProbeShapes.typeId(shape);
        return id == 0 ? null : AgentRing.internedNow().get(id - 1);
    }

    enum Level {
        LOW,
        HIGH {
            @Override
            public String toString() {
                CALLS.incrementAndGet();
                return "high";
            }
        }
    }

    static final class CountingCollection extends AbstractCollection<Object> {

        @Override
        public Iterator<Object> iterator() {
            CALLS.incrementAndGet();
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            CALLS.incrementAndGet();
            return 0;
        }

        @Override
        public String toString() {
            CALLS.incrementAndGet();
            return "";
        }

        @Override
        public int hashCode() {
            CALLS.incrementAndGet();
            return 0;
        }

        @Override
        public boolean equals(Object other) {
            CALLS.incrementAndGet();
            return false;
        }
    }

    static final class CountingList extends AbstractList<Object> {

        @Override
        public Object get(int index) {
            CALLS.incrementAndGet();
            return null;
        }

        @Override
        public int size() {
            CALLS.incrementAndGet();
            return 0;
        }
    }

    static final class CountingArrayList extends ArrayList<Object> {

        @Override
        public int size() {
            CALLS.incrementAndGet();
            return 0;
        }

        @Override
        public String toString() {
            CALLS.incrementAndGet();
            return "";
        }
    }

    static final class CountingMap extends java.util.AbstractMap<Object, Object> {

        @Override
        public Set<Entry<Object, Object>> entrySet() {
            CALLS.incrementAndGet();
            return Set.of();
        }

        @Override
        public int size() {
            CALLS.incrementAndGet();
            return 0;
        }
    }

    static final class CountingBean {

        public String getSecret() {
            CALLS.incrementAndGet();
            return "secret";
        }

        @Override
        public String toString() {
            CALLS.incrementAndGet();
            return getSecret();
        }

        @Override
        public int hashCode() {
            CALLS.incrementAndGet();
            return 1;
        }

        @Override
        public boolean equals(Object other) {
            CALLS.incrementAndGet();
            return false;
        }
    }

    static final class CountingComparator implements java.util.Comparator<Object> {

        @Override
        public int compare(Object a, Object b) {
            CALLS.incrementAndGet();
            return 0;
        }
    }
}
