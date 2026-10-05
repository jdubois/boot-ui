package io.github.jdubois.bootui.agent.bridge;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The shape of one argument or return value a method probe records (PLAN-v2 §5.14, M5-8, D37, D44): its runtime class
 * and, for an allowlist of JDK types, a bounded summary, packed in one {@code long}, never the value itself.
 *
 * <p><b>No application code runs.</b> A shape reads only {@link Object#getClass()} (final and native),
 * {@link Class#getName()} and {@link Class#isArray()}, and then, by exact class, never by {@code instanceof}:
 * {@link String#length()} ({@code String} is final), {@link Array#getLength} (native), {@code isPresent()} of the final
 * {@code Optional} classes, {@link Enum#name()} and {@link Enum#getDeclaringClass()} (final in {@code java.lang.Enum};
 * {@code instanceof Enum} is a type check), and {@code size()} of the exact JDK collection and map classes listed in
 * {@link #COLLECTIONS} and {@link #MAPS}, each of which reads its own fields: no lock, no iteration, no comparator, no
 * element's method. Anything else, a subclass of an allowlisted class, an application collection, a Hibernate or Spring
 * proxy, an unmodifiable or synchronized wrapper (its {@code size()} delegates to an arbitrary collection or takes a
 * monitor), a {@code TreeSet} (its map may be a sub-map view whose {@code size()} runs the comparator), or a
 * concurrent skip list or linked queue (whose {@code size()} walks every node), is recorded by its class name only.
 * Boxed numbers, booleans, and characters are recorded by their class only: their value may be a secret. {@code toString},
 * {@code hashCode}, {@code equals}, and {@code compareTo} are never called on a recorded value.
 *
 * <p><b>Layout.</b> Bits 0–3: the kind; bits 4–31: the summary (a size or a length saturated at
 * {@value #MAX_SUMMARY}, {@code 1} or {@code 0} for an {@code Optional}'s presence, or the enum constant name's intern
 * id); bits 32–63: the intern id of the class name ({@link AgentRing#intern}), the enum's declaring class for an enum
 * constant, {@code 0} when the intern table is full or absent. What the engine shows of a summary depends on the live
 * exposure; the bridge only records.
 */
public final class ProbeShapes {

    /** No shape: the slot was not recorded. */
    public static final int ABSENT = 0;

    /** {@code null}. */
    public static final int NULL = 1;

    /** Any other object: its class name only. */
    public static final int TYPE = 2;

    /** A {@code String}: its length. */
    public static final int STRING = 3;

    /** An allowlisted JDK collection: its size. */
    public static final int COLLECTION = 4;

    /** An allowlisted JDK map: its size. */
    public static final int MAP = 5;

    /** An array: its length. */
    public static final int ARRAY = 6;

    /** An {@code Optional}, {@code OptionalInt}, {@code OptionalLong}, or {@code OptionalDouble}: present or empty. */
    public static final int OPTIONAL = 7;

    /** An enum constant: its name's intern id, its declaring class. */
    public static final int ENUM = 8;

    /** The largest summary: a size or a length at least this large is recorded as this. */
    public static final int MAX_SUMMARY = (1 << 28) - 1;

    /** Collection classes whose {@code size()} reads their own fields, matched by identity. */
    static final Class<?>[] COLLECTIONS = {
        ArrayList.class,
        LinkedList.class,
        ArrayDeque.class,
        PriorityQueue.class,
        HashSet.class,
        LinkedHashSet.class,
        CopyOnWriteArrayList.class,
        Arrays.asList(new Object[0]).getClass(),
        Collections.emptyList().getClass(),
        Collections.emptySet().getClass(),
        Collections.singletonList(Boolean.TRUE).getClass(),
        Collections.singleton(Boolean.TRUE).getClass(),
        List.of().getClass(),
        List.of(Boolean.TRUE).getClass(),
        List.of(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE).getClass(),
        Set.of().getClass(),
        Set.of(Boolean.TRUE).getClass(),
        Set.of(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3)).getClass()
    };

    /** Map classes whose {@code size()} reads their own fields, matched by identity. */
    static final Class<?>[] MAPS = {
        HashMap.class,
        LinkedHashMap.class,
        TreeMap.class,
        IdentityHashMap.class,
        EnumMap.class,
        ConcurrentHashMap.class,
        Collections.emptyMap().getClass(),
        Collections.singletonMap(Boolean.TRUE, Boolean.TRUE).getClass(),
        Map.of().getClass(),
        Map.of(Boolean.TRUE, Boolean.TRUE).getClass(),
        Map.of(Integer.valueOf(1), Boolean.TRUE, Integer.valueOf(2), Boolean.TRUE, Integer.valueOf(3), Boolean.TRUE)
                .getClass()
    };

    private ProbeShapes() {}

    /** The shape of {@code value}, interning its class name and, for an enum constant, its name. Never throws. */
    public static long shape(Object value) {
        try {
            if (value == null) {
                return NULL;
            }
            Class<?> type = value.getClass();
            int kind;
            long summary = 0L;
            if (type == String.class) {
                kind = STRING;
                summary = ((String) value).length();
            } else if (type.isArray()) {
                kind = ARRAY;
                summary = Array.getLength(value);
            } else if (listed(COLLECTIONS, type)) {
                kind = COLLECTION;
                summary = ((Collection<?>) value).size();
            } else if (listed(MAPS, type)) {
                kind = MAP;
                summary = ((Map<?, ?>) value).size();
            } else if (type == Optional.class) {
                kind = OPTIONAL;
                summary = ((Optional<?>) value).isPresent() ? 1L : 0L;
            } else if (type == OptionalInt.class) {
                kind = OPTIONAL;
                summary = ((OptionalInt) value).isPresent() ? 1L : 0L;
            } else if (type == OptionalLong.class) {
                kind = OPTIONAL;
                summary = ((OptionalLong) value).isPresent() ? 1L : 0L;
            } else if (type == OptionalDouble.class) {
                kind = OPTIONAL;
                summary = ((OptionalDouble) value).isPresent() ? 1L : 0L;
            } else if (value instanceof Enum) {
                Enum<?> constant = (Enum<?>) value;
                kind = ENUM;
                type = constant.getDeclaringClass();
                summary = Math.max(0, AgentRing.intern(constant.name()));
            } else {
                kind = TYPE;
            }
            return pack(kind, summary, AgentRing.intern(type.getName()));
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return ABSENT;
        }
    }

    /** One shape from its parts, the summary saturated. */
    static long pack(int kind, long summary, int typeId) {
        long bounded = summary < 0L ? 0L : Math.min(summary, MAX_SUMMARY);
        return ((long) Math.max(0, typeId) << 32) | (bounded << 4) | (kind & 0xFL);
    }

    /** The kind of a packed shape. */
    public static int kind(long shape) {
        return (int) (shape & 0xFL);
    }

    /** The summary of a packed shape. */
    public static int summary(long shape) {
        return (int) ((shape >>> 4) & MAX_SUMMARY);
    }

    /** The class name's intern id of a packed shape. */
    public static int typeId(long shape) {
        return (int) (shape >>> 32);
    }

    private static boolean listed(Class<?>[] classes, Class<?> type) {
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] == type) {
                return true;
            }
        }
        return false;
    }

    /** Loads and links what {@link #shape} calls, on the agent's thread, before a shapes probe is installed. */
    static void warm() {
        Object[] samples = {
            null,
            "",
            new int[0],
            new ArrayList<Object>(),
            new HashMap<Object, Object>(),
            Optional.empty(),
            OptionalInt.empty(),
            OptionalLong.empty(),
            OptionalDouble.empty(),
            Thread.State.NEW,
            Boolean.TRUE
        };
        for (int i = 0; i < samples.length; i++) {
            shape(samples[i]);
        }
    }
}
