package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.ThreadLocals;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;

/**
 * The {@code thread-locals} sensor's scanner (PLAN-v2 §5.16, M5-5f): reads the calling thread's {@code threadLocals}
 * and {@code inheritableThreadLocals} maps through method handles on {@code java.lang}'s private fields, which the
 * sensor opened to the agent's own module alone ({@link ThreadLocalsSensor#grant}). For each entry it reads the key, the
 * entry's referent, and whether the value is {@code null}, never the value itself; a key's identity is its {@code
 * threadLocalHashCode}, unique per instance for 2^32 creations. Its handles are built when the class initializes,
 * after the grant, and linked by the self-test, so a request thread never links one. No lambda, no string
 * concatenation, no monitor: it runs inside the executors sensor's hooks.
 */
final class ThreadLocalScanner extends ThreadLocals.Scanner {

    private static final MethodHandle THREAD_LOCALS;
    private static final MethodHandle INHERITABLE_THREAD_LOCALS;
    private static final MethodHandle TABLE;
    private static final MethodHandle VALUE;
    private static final MethodHandle HASH;

    static {
        try {
            MethodHandles.Lookup threads = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup());
            Class<?> map = Class.forName("java.lang.ThreadLocal$ThreadLocalMap", false, null);
            Class<?> entry = Class.forName("java.lang.ThreadLocal$ThreadLocalMap$Entry", false, null);
            MethodType threadToObject = MethodType.methodType(Object.class, Thread.class);
            MethodType objectToObject = MethodType.methodType(Object.class, Object.class);
            THREAD_LOCALS =
                    threads.findGetter(Thread.class, "threadLocals", map).asType(threadToObject);
            INHERITABLE_THREAD_LOCALS = threads.findGetter(Thread.class, "inheritableThreadLocals", map)
                    .asType(threadToObject);
            MethodHandles.Lookup maps = MethodHandles.privateLookupIn(map, MethodHandles.lookup());
            TABLE = maps.findGetter(map, "table", entry.arrayType())
                    .asType(MethodType.methodType(Object[].class, Object.class));
            MethodHandles.Lookup entries = MethodHandles.privateLookupIn(entry, MethodHandles.lookup());
            VALUE = entries.findGetter(entry, "value", Object.class).asType(objectToObject);
            MethodHandles.Lookup locals = MethodHandles.privateLookupIn(ThreadLocal.class, MethodHandles.lookup());
            HASH = locals.findGetter(ThreadLocal.class, "threadLocalHashCode", int.class)
                    .asType(MethodType.methodType(int.class, Object.class));
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Override
    public int snapshot(int[] hashes) {
        try {
            Thread thread = Thread.currentThread();
            int count = snapshot((Object) THREAD_LOCALS.invokeExact(thread), hashes, 0);
            if (count < 0) {
                return count;
            }
            return snapshot((Object) INHERITABLE_THREAD_LOCALS.invokeExact(thread), hashes, count);
        } catch (Throwable ex) {
            throw rethrown(ex);
        }
    }

    private static int snapshot(Object map, int[] hashes, int from) throws Throwable {
        if (map == null) {
            return from;
        }
        Object[] table = (Object[]) TABLE.invokeExact(map);
        if (table == null) {
            return from;
        }
        if (table.length > ThreadLocals.MAX_TABLE) {
            return ThreadLocals.TOO_LARGE;
        }
        int count = from;
        for (int i = 0; i < table.length; i++) {
            Object entry = table[i];
            if (entry == null) {
                continue;
            }
            Object key = ((Reference<?>) entry).get();
            if (key == null || (Object) VALUE.invokeExact(entry) == null) {
                continue;
            }
            if (count == hashes.length) {
                return ThreadLocals.OVERFLOW;
            }
            hashes[count++] = (int) HASH.invokeExact(key);
        }
        return count;
    }

    @Override
    public int leftovers(int[] open, int openCount, Object[] keys, int[] hashes, boolean[] inheritable) {
        try {
            Thread thread = Thread.currentThread();
            int found = leftovers(
                    (Object) THREAD_LOCALS.invokeExact(thread), false, open, openCount, keys, hashes, inheritable, 0);
            if (found < 0) {
                return found;
            }
            return leftovers(
                    (Object) INHERITABLE_THREAD_LOCALS.invokeExact(thread),
                    true,
                    open,
                    openCount,
                    keys,
                    hashes,
                    inheritable,
                    found);
        } catch (Throwable ex) {
            throw rethrown(ex);
        }
    }

    private static int leftovers(
            Object map,
            boolean fromInheritable,
            int[] open,
            int openCount,
            Object[] keys,
            int[] hashes,
            boolean[] inheritable,
            int from)
            throws Throwable {
        if (map == null) {
            return from;
        }
        Object[] table = (Object[]) TABLE.invokeExact(map);
        if (table == null) {
            return from;
        }
        if (table.length > ThreadLocals.MAX_TABLE) {
            return ThreadLocals.TOO_LARGE;
        }
        int found = from;
        for (int i = 0; i < table.length; i++) {
            Object entry = table[i];
            if (entry == null) {
                continue;
            }
            Object key = ((Reference<?>) entry).get();
            if (key == null || (Object) VALUE.invokeExact(entry) == null) {
                continue;
            }
            int hash = (int) HASH.invokeExact(key);
            if (ThreadLocals.contains(open, openCount, hash)) {
                continue;
            }
            if (found < keys.length) {
                keys[found] = key;
                hashes[found] = hash;
                inheritable[found] = fromInheritable;
            }
            found++;
        }
        return found;
    }

    /** A thread local's identity hash code, for the self-test and the resolver. */
    static int hash(Object threadLocal) {
        try {
            return (int) HASH.invokeExact(threadLocal);
        } catch (Throwable ex) {
            throw rethrown(ex);
        }
    }

    private static RuntimeException rethrown(Throwable ex) {
        if (ex instanceof RuntimeException) {
            return (RuntimeException) ex;
        }
        if (ex instanceof Error) {
            throw (Error) ex;
        }
        return new IllegalStateException(ex);
    }
}
