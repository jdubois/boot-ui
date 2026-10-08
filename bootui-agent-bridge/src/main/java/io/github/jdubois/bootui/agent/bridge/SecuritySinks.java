package io.github.jdubois.bootui.agent.bridge;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The {@value SideEffects#SECURITY_SINKS} sensor's JDK checks (PLAN-v2 §5.16 and §5.17, M5-6b2), beside its
 * request-value matching ({@link RequestValues}): facts about how the application uses the JDK's security APIs, each
 * a record on the side-effect sensors' ring with sensor id {@value SideEffects#SENSOR_SECURITY_SINKS}, never a value.
 *
 * <ul>
 *   <li><b>Deserialization without a filter</b> ({@link #GROUP_DESERIALIZATION}): the outermost {@code
 *       ObjectInputStream.readObject()} on a thread whose stream has no {@code ObjectInputFilter}, with the names of the
 *       classes {@code resolveClass} resolved while it ran, at most {@value #MAX_CLASSES}.
 *   <li><b>Weak algorithms</b> ({@link #GROUP_ALGORITHMS}): {@code MessageDigest.getInstance} and {@code
 *       Cipher.getInstance} asked for MD5, MD2, SHA-1, DES, DESede, RC4, a block cipher in ECB mode, or a block cipher by
 *       its bare name, which defaults to ECB; requested by the application, or by a library, reported apart.
 *   <li><b>Trust managers and hostname verifiers</b> ({@link #GROUP_TRUST}): {@code SSLContext.init} given a trust
 *       manager of the claimed packages, and {@code HttpsURLConnection.setDefaultHostnameVerifier} and {@code
 *       setDefaultSSLSocketFactory} called by the application.
 * </ul>
 *
 * <p><b>Fast path.</b> Every hook reads {@link SideEffects#gate} once and returns unless the sensor records (or a
 * self-test runs); then its group's bit; then a check that allocates nothing: the algorithm's name, the stream's filter,
 * or a trust manager's package. Only what passes takes the slow path, which holds the thread's side-effect bit while it
 * walks the stack and records, so a {@code MessageDigest} the walk's class loading or jar verification asks for is never
 * recorded, nor anything another open side-effect hook does. The advised methods never hold that bit across their own
 * body: a {@code readObject} runs application code whose files and connects the other sensors still record.
 *
 * <p><b>Who asked.</b> A bounded {@code StackWalker} walk skips the agent's frames and the advised class's own, then
 * reflection and method-handle frames: the frame left is the immediate caller. A caller in the JDK, such as {@code
 * SecureRandom}'s {@code SHA1PRNG}, {@code UUID.nameUUIDFromBytes}, TLS, or jar verification, is the JDK's own use and is
 * only counted. Otherwise the first frame outside the JDK is the requester: the application's when it is in the claimed
 * packages, else a library's, with the first frame of the claimed packages above it, if any. A weak request's and a
 * deserialization's attribution is remembered per {@code (hook, immediate caller, algorithm or class)} in the
 * generation's sightings, so a call site walks its whole stack once.
 *
 * <p>The groups are switched by the agent after their hooks' self-test ({@link #groups(int, String[])}): a group whose
 * core hook failed is off alone, with its reason, and never takes request-value matching or another group with it.
 *
 * <p>JDK types only; every entry point catches everything.
 */
public final class SecuritySinks {

    /** The checks' groups, a bit each, as the agent switches them. */
    public static final int GROUP_DESERIALIZATION = 1;

    public static final int GROUP_ALGORITHMS = 2;
    public static final int GROUP_TRUST = 4;

    /** Every group. */
    public static final int GROUPS = GROUP_DESERIALIZATION | GROUP_ALGORITHMS | GROUP_TRUST;

    /** The groups' ids, by bit index, as status reports them. */
    public static final String[] GROUP_IDS = {"deserialization", "weak-algorithms", "trust-managers"};

    /** Record kinds on sensor {@value SideEffects#SENSOR_SECURITY_SINKS}; 1 to 4 are request-value matching's. */
    public static final int KIND_DESERIALIZATION = 5;

    public static final int KIND_WEAK_DIGEST = 6;
    public static final int KIND_WEAK_CIPHER = 7;
    public static final int KIND_TRUST_MANAGER = 8;
    public static final int KIND_HOSTNAME_VERIFIER = 9;
    public static final int KIND_SOCKET_FACTORY = 10;

    /** Outcome bits 0–1: who asked; bit 2: the deserialization threw. */
    public static final int ORIGIN_APPLICATION = 1;

    public static final int ORIGIN_LIBRARY = 2;
    public static final int FLAG_ERROR = 4;

    /** Distinct classes a deserialization names at most. */
    public static final int MAX_CLASSES = 16;

    /** Reflection and method-handle frames passed through to find the immediate caller, at most. */
    static final int MAX_CALLER_FRAMES = 16;

    /** Distinct targets and class lists interned per claim generation at most; past it, none is kept. */
    static final int INTERN_QUOTA = 1_000;

    /** The longest class name kept, and the longest list of classes. */
    static final int MAX_NAME = 200;

    static final int MAX_LIST = 1_024;

    /** The longest algorithm kept. */
    static final int MAX_ALGORITHM = 64;

    /** A nested {@code readObject}'s token: never a {@link System#nanoTime()} an outermost one returns. */
    static final long NESTED = 1L;

    /** The memo's verdict of a request the JDK made itself. */
    private static final int JDK_INTERNAL = -1;

    private static final String AGENT = "io.github.jdubois.bootui.agent.";

    private static final StackWalker WALKER = StackWalker.getInstance();

    /** The enabled groups: read by every hook after the gate. */
    static volatile int groups;

    private static final String[] GROUP_REASONS = new String[GROUP_IDS.length];

    private static final LongAdder WEAK = new LongAdder();
    private static final LongAdder JDK_REQUESTS = new LongAdder();
    private static final LongAdder UNFILTERED = new LongAdder();
    private static final LongAdder FILTERED = new LongAdder();
    private static final LongAdder NOT_NAMED = new LongAdder();
    private static final LongAdder TRUST_MANAGERS = new LongAdder();
    private static final LongAdder DEFAULTS = new LongAdder();
    private static final LongAdder LIBRARY_DEFAULTS = new LongAdder();
    private static final LongAdder WALKS = new LongAdder();
    private static final LongAdder MEMO_FULL = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder NOT_KEPT = new LongAdder();
    private static final LongAdder UNATTRIBUTED = new LongAdder();
    /** Each group's internal errors, by group index: a group past its own budget is off alone for the JVM's life. */
    private static final java.util.concurrent.atomic.AtomicLongArray ERRORS =
            new java.util.concurrent.atomic.AtomicLongArray(GROUP_IDS.length);

    /** The groups switched off for the JVM's life by their own error budget. */
    private static volatile int budgetOff;

    private static final String[] BUDGET_REASONS = new String[GROUP_IDS.length];
    private static final AtomicInteger INTERNED = new AtomicInteger();
    private static volatile long internGeneration = Long.MIN_VALUE;

    private SecuritySinks() {}

    /**
     * A thread's deserialization: how deep its {@code readObject} calls are nested, the outermost one's token, and the
     * classes resolved meanwhile. Held in the thread's {@link CodePaths.Frame}; names are cleared when it ends.
     */
    static final class Serial {
        int depth;
        long since;
        long generation = Long.MIN_VALUE;
        final String[] names = new String[MAX_CLASSES];
        int count;
        boolean more;
    }

    // ---- the agent's switches ----------------------------------------------------------------------------------

    /**
     * The agent switches the groups of {@code bits} on, the others off, once their hooks passed their self-test;
     * {@code reasons}, by group index, says why a group is off, or {@code null}. Never throws.
     */
    public static void groups(int bits, String[] reasons) {
        int off = budgetOff;
        for (int i = 0; i < GROUP_REASONS.length; i++) {
            // A group past its error budget stays off, with that reason, whatever the agent asks.
            GROUP_REASONS[i] = (off & (1 << i)) != 0
                    ? BUDGET_REASONS[i]
                    : reasons != null && i < reasons.length ? reasons[i] : null;
        }
        groups = bits & GROUPS & ~off;
    }

    /**
     * An internal error of a check of {@code group}: counted against that group's own budget of {@value
     * SideEffects#MAX_ERRORS}, which switches that group alone off for the JVM's life, never another group nor the
     * sensor, so request-value matching keeps running. Never throws.
     */
    static void failed(int group, Throwable ex) {
        try {
            if (ex instanceof VirtualMachineError) {
                return;
            }
            AgentBridge.error(ex);
            int index = Integer.numberOfTrailingZeros(group);
            if (index >= GROUP_IDS.length) {
                return;
            }
            if (ERRORS.incrementAndGet(index) == SideEffects.MAX_ERRORS) {
                String reason = "switched off after " + SideEffects.MAX_ERRORS + " internal errors, the last: " + ex;
                BUDGET_REASONS[index] = reason;
                GROUP_REASONS[index] = reason;
                budgetOff |= group;
                groups &= ~group;
                AgentBridge.message("the security-sinks checks " + GROUP_IDS[index] + " were " + reason);
            }
        } catch (Throwable ignored) {
            // Never throw from the error path.
        }
    }

    // ---- (c) weak algorithms --------------------------------------------------------------------------------------

    /** {@code MessageDigest.getInstance} entry, every overload: allocates nothing unless the algorithm is weak. */
    public static void digest(String algorithm) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return;
        }
        if (selfTest(SideEffects.HOOK_DIGEST) || (groups & GROUP_ALGORITHMS) == 0 || !weakDigest(algorithm)) {
            return;
        }
        weak(SideEffects.HOOK_DIGEST, KIND_WEAK_DIGEST, algorithm, "java.security.MessageDigest");
    }

    /**
     * {@code Cipher.getInstance(String)} and {@code getInstance(String, Provider)} entry; {@code getInstance(String,
     * String)}, which calls the latter, is not advised, so a request is seen once.
     */
    public static void cipher(String transformation) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return;
        }
        if (selfTest(SideEffects.HOOK_CIPHER) || (groups & GROUP_ALGORITHMS) == 0 || !weakCipher(transformation)) {
            return;
        }
        weak(SideEffects.HOOK_CIPHER, KIND_WEAK_CIPHER, transformation, "javax.crypto.Cipher");
    }

    /**
     * Whether {@code algorithm} names a weak digest, case-insensitively as the JCA does: MD5, MD2, SHA-1 (also {@code
     * SHA1} and {@code SHA}), or their object identifiers, with or without {@code OID.}. Allocates nothing.
     */
    static boolean weakDigest(String algorithm) {
        if (algorithm == null) {
            return false;
        }
        int from = 0;
        int length = algorithm.length();
        if (length > 4 && algorithm.regionMatches(true, 0, "OID.", 0, 4)) {
            from = 4;
        }
        int n = length - from;
        return is(algorithm, from, n, "MD5")
                || is(algorithm, from, n, "MD2")
                || is(algorithm, from, n, "SHA-1")
                || is(algorithm, from, n, "SHA1")
                || is(algorithm, from, n, "SHA")
                || is(algorithm, from, n, "1.2.840.113549.2.5")
                || is(algorithm, from, n, "1.2.840.113549.2.2")
                || is(algorithm, from, n, "1.3.14.3.2.26");
    }

    /**
     * Whether {@code transformation} asks for a weak cipher: DES, DESede, RC4 in any mode, or a block cipher (AES,
     * Blowfish, RC2) in ECB mode, which its bare name defaults to. {@code RSA/ECB/...} is not a block cipher's ECB. Tokens
     * are trimmed as the JCA trims them. Allocates nothing.
     */
    static boolean weakCipher(String transformation) {
        if (transformation == null) {
            return false;
        }
        int length = transformation.length();
        int start = skipSpaces(transformation, 0, length);
        int slash = transformation.indexOf('/', start);
        int end = trimEnd(transformation, start, slash < 0 ? length : slash);
        int n = end - start;
        if (is(transformation, start, n, "DES")
                || is(transformation, start, n, "DESede")
                || is(transformation, start, n, "TripleDES")
                || is(transformation, start, n, "RC4")
                || is(transformation, start, n, "ARCFOUR")) {
            return true;
        }
        boolean block = is(transformation, start, n, "AES")
                || is(transformation, start, n, "AES_128")
                || is(transformation, start, n, "AES_192")
                || is(transformation, start, n, "AES_256")
                || is(transformation, start, n, "Blowfish")
                || is(transformation, start, n, "RC2");
        if (!block) {
            return false;
        }
        if (slash < 0) {
            // A bare block cipher's mode defaults to ECB.
            return true;
        }
        int mode = skipSpaces(transformation, slash + 1, length);
        int next = transformation.indexOf('/', mode);
        int modeEnd = trimEnd(transformation, mode, next < 0 ? length : next);
        return is(transformation, mode, modeEnd - mode, "ECB");
    }

    private static boolean is(String text, int from, int length, String expected) {
        return length == expected.length() && text.regionMatches(true, from, expected, 0, length);
    }

    private static int skipSpaces(String text, int from, int end) {
        int i = from;
        while (i < end && text.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static int trimEnd(String text, int start, int end) {
        int i = end;
        while (i > start && text.charAt(i - 1) == ' ') {
            i--;
        }
        return i;
    }

    /** A weak algorithm was asked for: who asked, recorded unless the JDK did. */
    private static void weak(int hook, int kind, String algorithm, String advised) {
        CodePaths.Frame frame = null;
        boolean opened = false;
        try {
            Claim claim = recording();
            if (claim == null) {
                return;
            }
            frame = CodePaths.frame();
            if (!open(frame)) {
                return;
            }
            opened = true;
            WEAK.increment();
            String target = algorithmText(algorithm);
            long[] who = attribute(claim, hook, advised, target.hashCode());
            if (who == null) {
                return;
            }
            record(frame, claim, kind, intern(target, claim.generation), (int) who[1], 0, who[0]);
        } catch (Throwable ex) {
            failed(GROUP_ALGORITHMS, ex);
        } finally {
            if (opened) {
                frame.sideEffectOpen &= ~SideEffects.MASK_SECURITY_SINKS;
            }
        }
    }

    // ---- (b) deserialization without a filter -----------------------------------------------------------------------

    /**
     * {@code ObjectInputStream.readObject()} entry: a token for {@link #read}, 0 when nothing is tracked, {@link #NESTED}
     * inside an outermost call already tracked on the thread. An outermost call is tracked only when its stream has no
     * filter: the stream's own, which the JDK's filter factory set from the JVM-wide filter when it was built, is the one
     * every read checks. Allocates nothing past the thread's first tracked call.
     */
    public static long reading(java.io.ObjectInputStream stream) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return 0L;
        }
        try {
            if (selfTest(SideEffects.HOOK_READ_OBJECT) || (groups & GROUP_DESERIALIZATION) == 0) {
                return 0L;
            }
            // Nothing is allocated before the stream is known unfiltered and the sensor records on this thread.
            CodePaths.Frame existing = CodePaths.FRAME.get();
            Serial serial = existing == null ? null : existing.serial;
            long generation = SideEffects.generation;
            if (serial != null && serial.depth > 0) {
                if (serial.generation == generation
                        && System.nanoTime() - serial.since < SideEffects.STALE_DEPTH_NANOS) {
                    serial.depth++;
                    return NESTED;
                }
                // Left by an outermost exit that never ran, or by an earlier run.
                clear(serial);
            }
            if (stream == null || stream.getObjectInputFilter() != null) {
                FILTERED.increment();
                return 0L;
            }
            if (recording() == null) {
                return 0L;
            }
            CodePaths.Frame frame = existing != null ? existing : CodePaths.frame();
            if (!quiet(frame)) {
                return 0L;
            }
            long now = System.nanoTime();
            if (serial == null) {
                serial = new Serial();
                frame.serial = serial;
            }
            long token = now <= NESTED ? NESTED + 1L : now;
            serial.depth = 1;
            serial.since = token;
            serial.generation = generation;
            return token;
        } catch (Throwable ex) {
            failed(GROUP_DESERIALIZATION, ex);
            return 0L;
        }
    }

    /**
     * {@code ObjectInputStream.readObject()} exit, normal or not: a nested call's only steps out; the outermost call's
     * records what was read, whatever the depth, so a nested exit lost to a stack overflow never keeps the thread
     * tracked. Runs whatever the gate, so a switch never strands the thread's state.
     */
    public static void read(long token, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        boolean opened = false;
        try {
            frame = CodePaths.FRAME.get();
            Serial serial = frame == null ? null : frame.serial;
            if (serial == null || serial.depth == 0) {
                return;
            }
            if (token == NESTED) {
                if (serial.depth > 1) {
                    serial.depth--;
                }
                return;
            }
            if (token != serial.since) {
                return;
            }
            String[] names = Arrays.copyOf(serial.names, serial.count);
            boolean more = serial.more;
            clear(serial);
            Claim claim = recording();
            if (claim == null || (groups & GROUP_DESERIALIZATION) == 0 || !open(frame)) {
                return;
            }
            opened = true;
            UNFILTERED.increment();
            String top = names.length == 0 ? null : names[0];
            if (top == null) {
                NOT_NAMED.increment();
            }
            String others = others(names, more);
            long[] who = attribute(
                    claim, SideEffects.HOOK_READ_OBJECT, "java.io.ObjectInputStream", top == null ? 0 : top.hashCode());
            if (who == null) {
                return;
            }
            int outcome = (int) who[1] | (thrown != null ? FLAG_ERROR : 0);
            record(
                    frame,
                    claim,
                    KIND_DESERIALIZATION,
                    top == null ? 0 : intern(className(top), claim.generation),
                    outcome,
                    others == null ? 0 : intern(others, claim.generation),
                    who[0]);
        } catch (Throwable ex) {
            failed(GROUP_DESERIALIZATION, ex);
        } finally {
            if (opened) {
                frame.sideEffectOpen &= ~SideEffects.MASK_SECURITY_SINKS;
            }
        }
    }

    /**
     * {@code ObjectInputStream.resolveClass} returned {@code resolved}: named while an unfiltered outermost {@code
     * readObject} is tracked on the thread, at most {@value #MAX_CLASSES} distinct classes. Only classes that resolved
     * are named, so a name is a class of the class path, never text of the stream.
     */
    public static void resolved(Class<?> resolved) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return;
        }
        try {
            if (selfTest(SideEffects.HOOK_RESOLVE_CLASS) || resolved == null) {
                return;
            }
            CodePaths.Frame frame = CodePaths.FRAME.get();
            Serial serial = frame == null ? null : frame.serial;
            if (serial == null || serial.depth == 0) {
                return;
            }
            String name = resolved.getName();
            for (int i = 0; i < serial.count; i++) {
                if (serial.names[i].equals(name)) {
                    return;
                }
            }
            if (serial.count < MAX_CLASSES) {
                serial.names[serial.count++] = name;
            } else {
                serial.more = true;
            }
        } catch (Throwable ex) {
            failed(GROUP_DESERIALIZATION, ex);
        }
    }

    private static void clear(Serial serial) {
        serial.depth = 0;
        serial.since = 0L;
        Arrays.fill(serial.names, 0, serial.count, null);
        serial.count = 0;
        serial.more = false;
    }

    /** The classes read after the first, sorted, sanitized, and bounded; {@code null} when none. */
    static String others(String[] names, boolean more) {
        if (names.length <= 1 && !more) {
            return null;
        }
        String[] rest = new String[Math.max(0, names.length - 1)];
        for (int i = 1; i < names.length; i++) {
            rest[i - 1] = className(names[i]);
        }
        Arrays.sort(rest);
        StringBuilder text = new StringBuilder();
        for (String name : rest) {
            if (text.length() + name.length() + 2 > MAX_LIST) {
                more = true;
                break;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(name);
        }
        if (more) {
            text.append(text.length() > 0 ? ", " : "").append("(more)");
        }
        return text.toString();
    }

    // ---- (d) trust managers and hostname verifiers ------------------------------------------------------------------

    /**
     * {@code SSLContext.init} entry: recorded when one of the trust managers is a class of the claimed packages, its
     * nested and anonymous classes included.
     */
    public static void sslInit(Object[] managers) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return;
        }
        CodePaths.Frame frame = null;
        boolean opened = false;
        try {
            if (selfTest(SideEffects.HOOK_SSL_INIT) || (groups & GROUP_TRUST) == 0 || managers == null) {
                return;
            }
            Claim claim = recording();
            if (claim == null) {
                return;
            }
            String own = null;
            for (Object manager : managers) {
                if (manager != null) {
                    String name = manager.getClass().getName();
                    if (ThreadPropagation.inPackages(name, claim)) {
                        own = name;
                        break;
                    }
                }
            }
            if (own == null) {
                return;
            }
            frame = CodePaths.frame();
            if (!open(frame)) {
                return;
            }
            opened = true;
            long[] who = attribute(claim, -1, "javax.net.ssl.SSLContext", 0);
            if (who == null) {
                return;
            }
            TRUST_MANAGERS.increment();
            record(frame, claim, KIND_TRUST_MANAGER, intern(className(own), claim.generation), (int) who[1], 0, who[0]);
        } catch (Throwable ex) {
            failed(GROUP_TRUST, ex);
        } finally {
            if (opened) {
                frame.sideEffectOpen &= ~SideEffects.MASK_SECURITY_SINKS;
            }
        }
    }

    /** {@code HttpsURLConnection.setDefaultHostnameVerifier} entry. */
    public static void defaultVerifier(Object verifier) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return;
        }
        installed(SideEffects.HOOK_DEFAULT_VERIFIER, KIND_HOSTNAME_VERIFIER, verifier);
    }

    /** {@code HttpsURLConnection.setDefaultSSLSocketFactory} entry. */
    public static void defaultFactory(Object factory) {
        if ((SideEffects.gate & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return;
        }
        installed(SideEffects.HOOK_DEFAULT_FACTORY, KIND_SOCKET_FACTORY, factory);
    }

    /** A JVM-wide default installed: recorded when the application installed it; a library's is only counted. */
    private static void installed(int hook, int kind, Object value) {
        CodePaths.Frame frame = null;
        boolean opened = false;
        try {
            if (selfTest(hook) || (groups & GROUP_TRUST) == 0 || value == null) {
                // A null is refused by the JDK before anything is set.
                return;
            }
            Claim claim = recording();
            if (claim == null) {
                return;
            }
            frame = CodePaths.frame();
            if (!open(frame)) {
                return;
            }
            opened = true;
            long[] who = attribute(claim, -1, "javax.net.ssl.HttpsURLConnection", 0);
            if (who == null) {
                return;
            }
            if (who[1] != ORIGIN_APPLICATION) {
                LIBRARY_DEFAULTS.increment();
                return;
            }
            DEFAULTS.increment();
            String name = className(value.getClass().getName());
            record(frame, claim, kind, intern(name, claim.generation), ORIGIN_APPLICATION, 0, who[0]);
        } catch (Throwable ex) {
            failed(GROUP_TRUST, ex);
        } finally {
            if (opened) {
                frame.sideEffectOpen &= ~SideEffects.MASK_SECURITY_SINKS;
            }
        }
    }

    // ---- shared slow path -------------------------------------------------------------------------------------------

    /** On the self-test's thread: counts {@code hook} and returns true, so nothing is recorded. */
    private static boolean selfTest(int hook) {
        Thread self = SideEffects.selfTestThread;
        if (self != null && self == Thread.currentThread()) {
            SideEffects.SELF_TEST_HITS[hook].increment();
            return true;
        }
        return false;
    }

    /** The armed claim when the sensor records on this thread now: not BootUI's own work or thread; else null. */
    private static Claim recording() {
        if ((SideEffects.mask & SideEffects.MASK_SECURITY_SINKS) == 0) {
            return null;
        }
        Claim claim = AgentBridge.current();
        if (claim == null || !claim.armed || claim.generation != SideEffects.generation) {
            return null;
        }
        if (Reentrancy.sideEffectsSkipped() || Thread.currentThread().getName().startsWith("bootui-")) {
            SKIPPED.increment();
            return null;
        }
        return claim;
    }

    /** Whether no side-effect hook is open on the thread: one open silences this sensor, as every other. */
    private static boolean quiet(CodePaths.Frame frame) {
        int open = frame.sideEffectOpen;
        if (open != 0 && System.nanoTime() - frame.sideEffectSince >= SideEffects.STALE_DEPTH_NANOS) {
            SideEffects.staleDepth();
            frame.sideEffectOpen = 0;
            open = 0;
        }
        return (open & SideEffects.silencedBy(SideEffects.MASK_SECURITY_SINKS)) == 0;
    }

    /** Opens this sensor's bit on the thread while it records, when nothing else is open; false otherwise. */
    private static boolean open(CodePaths.Frame frame) {
        if (!quiet(frame)) {
            return false;
        }
        frame.sideEffectSince = System.nanoTime();
        frame.sideEffectOpen |= SideEffects.MASK_SECURITY_SINKS;
        return true;
    }

    /**
     * Who asked: {@code {frames, origin}}, the interned first frame outside the JDK (bits 32–63) and the first of the
     * claimed packages (bits 0–31), and {@link #ORIGIN_APPLICATION} or {@link #ORIGIN_LIBRARY}; {@code null} when the
     * immediate caller is the JDK. Remembered per {@code (hook, immediate caller, target)} when {@code hook} is not -1.
     */
    static long[] attribute(Claim claim, int hook, String advised, int targetHash) {
        WALKS.increment();
        StackWalker walker = SideEffects.classWalker();
        return (walker == null ? WALKER : walker).walk(new Attribution(claim, hook, advised, targetHash));
    }

    /** The walk behind {@link #attribute}: lazy, so a remembered caller stops it after a few frames. */
    static final class Attribution implements Function<Stream<StackWalker.StackFrame>, long[]> {

        private final Claim claim;
        private final int hook;
        private final String advised;
        private final int targetHash;

        Attribution(Claim claim, int hook, String advised, int targetHash) {
            this.claim = claim;
            this.hook = hook;
            this.advised = advised;
            this.targetHash = targetHash;
        }

        @Override
        public long[] apply(Stream<StackWalker.StackFrame> frames) {
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            StackWalker.StackFrame caller = null;
            int seen = 0;
            int skipped = 0;
            while (iterator.hasNext() && seen < SideEffects.MAX_FRAMES) {
                StackWalker.StackFrame frame = iterator.next();
                seen++;
                String name = frame.getClassName();
                // The agent's frames, the advised class's own (its overloads, its nested classes), and the reflection
                // and method-handle frames between it and its caller.
                if (name.startsWith(AGENT)
                        || name.equals(advised)
                        || name.startsWith(advised) && name.charAt(advised.length()) == '$') {
                    continue;
                }
                if (SideEffects.transparent(name)) {
                    if (++skipped > MAX_CALLER_FRAMES) {
                        break;
                    }
                    continue;
                }
                caller = frame;
                break;
            }
            if (caller == null) {
                // Past the reflection and method-handle frames walked, or the frames: who asked is not known.
                UNATTRIBUTED.increment();
                return null;
            }
            if (jdk(caller)) {
                JDK_REQUESTS.increment();
                return null;
            }
            String callerClass = caller.getClassName();
            long key = 0L;
            long[] found = null;
            if (hook >= 0) {
                int callerHash =
                        callerClass.hashCode() * 31 + caller.getMethodName().hashCode();
                int folded = (targetHash ^ (targetHash >>> 24)) & 0xFFFFFF;
                key = ((long) (hook + 1) << 56) | ((callerHash & 0xFFFFFFFFL) << 24) | folded;
                found = new long[2];
                int result = SIGHTINGS.find(SideEffects.generation, key, found);
                if (result == SideEffects.Sightings.FOUND) {
                    if (found[1] == ORIGIN_APPLICATION) {
                        return found;
                    }
                    // A library's request: its own frame is the caller's, remembered; the application frame above it
                    // depends on who called the library, so it is walked each time.
                    found[0] = (found[0] & 0xFFFFFFFF00000000L) | (applicationFrame(iterator, seen) & 0xFFFFFFFFL);
                    return found;
                }
                if (result == SideEffects.Sightings.FULL) {
                    MEMO_FULL.increment();
                    key = 0L;
                }
            }
            int outside = SideEffects.internFrame(callerClass, caller.getMethodName());
            boolean application = ThreadPropagation.inPackages(callerClass, claim);
            int own = application ? outside : applicationFrame(iterator, seen);
            long packed = ((long) outside << 32) | (own & 0xFFFFFFFFL);
            int origin = application ? ORIGIN_APPLICATION : ORIGIN_LIBRARY;
            if (key != 0L) {
                SIGHTINGS.put(SideEffects.generation, key, packed, origin);
            }
            return new long[] {packed, origin};
        }

        /** The first frame of the claimed packages left in {@code iterator}, interned; 0 when none. */
        private int applicationFrame(Iterator<StackWalker.StackFrame> iterator, int seen) {
            for (int i = seen; i < SideEffects.MAX_FRAMES && iterator.hasNext(); i++) {
                StackWalker.StackFrame frame = iterator.next();
                String name = frame.getClassName();
                if (ThreadPropagation.inPackages(name, claim)) {
                    return SideEffects.internFrame(name, frame.getMethodName());
                }
            }
            return 0;
        }
    }

    /** The checks' own memo, apart from the files and environment sensors'. */
    private static final SideEffects.Sightings SIGHTINGS = new SideEffects.Sightings();

    /**
     * Whether a frame is the JDK's. With the walker retaining classes, by its class alone: loaded by the boot or the
     * platform class loader, or declared in a named {@code java.} or {@code jdk.} module of the boot layer; so a
     * library in a {@code com.sun.} package, as Mojarra's {@code com.sun.faces}, JavaMail's {@code com.sun.mail}, or
     * {@code com.sun.xml}, is never the JDK. Without classes, by name: {@code java.}, {@code javax.crypto}, {@code
     * javax.net}, {@code javax.security}, {@code jdk.}, {@code sun.}, and the JDK's own {@code com.sun.} packages only.
     */
    static boolean jdk(StackWalker.StackFrame frame) {
        Class<?> type;
        try {
            type = frame.getDeclaringClass();
        } catch (UnsupportedOperationException ex) {
            return jdkByName(frame.getClassName());
        }
        ClassLoader loader = type.getClassLoader();
        if (loader == null || loader == PLATFORM) {
            return true;
        }
        Module module = type.getModule();
        String name = module.getName();
        return module.isNamed()
                && name != null
                && (name.startsWith("java.") || name.startsWith("jdk."))
                && module.getLayer() == ModuleLayer.boot();
    }

    /** The platform class loader, which loads the JDK's modules outside the boot loader. */
    private static final ClassLoader PLATFORM = platformLoader();

    private static ClassLoader platformLoader() {
        try {
            return ClassLoader.getPlatformClassLoader();
        } catch (Throwable ex) {
            return null;
        }
    }

    /** {@link #jdk(StackWalker.StackFrame)} by the class's name alone, when the walker keeps no class. */
    static boolean jdkByName(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.crypto.")
                || name.startsWith("javax.net.")
                || name.startsWith("javax.security.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.crypto.provider.")
                || name.startsWith("com.sun.security.")
                || name.startsWith("com.sun.net.ssl.")
                || name.startsWith("com.sun.jndi.")
                || name.startsWith("com.sun.jmx.")
                || name.startsWith("com.sun.management.")
                || name.startsWith("com.sun.org.apache.")
                || name.startsWith("com.sun.rowset.")
                || name.startsWith("com.sun.naming.")
                || name.startsWith("com.sun.proxy.");
    }

    private static void record(
            CodePaths.Frame frame, Claim claim, int kind, int target, int outcome, int detail, long frames) {
        SideEffects.RECORDED[hookOf(kind)].increment();
        SideEffects.Owner owner = SideEffects.owner(frame, claim);
        SideEffects.record(
                frame,
                owner,
                SideEffects.SENSOR_SECURITY_SINKS,
                kind,
                target,
                outcome,
                detail,
                CodePaths.stamp(),
                frames,
                0L);
    }

    /** The hook a record of {@code kind} comes from, for its hook's recorded counter. */
    private static int hookOf(int kind) {
        switch (kind) {
            case KIND_DESERIALIZATION:
                return SideEffects.HOOK_READ_OBJECT;
            case KIND_WEAK_DIGEST:
                return SideEffects.HOOK_DIGEST;
            case KIND_WEAK_CIPHER:
                return SideEffects.HOOK_CIPHER;
            case KIND_TRUST_MANAGER:
                return SideEffects.HOOK_SSL_INIT;
            case KIND_HOSTNAME_VERIFIER:
                return SideEffects.HOOK_DEFAULT_VERIFIER;
            default:
                return SideEffects.HOOK_DEFAULT_FACTORY;
        }
    }

    /** {@code text}'s id in the generation's strings, within the checks' quota; 0 past it, counted. */
    private static int intern(String text, long generation) {
        if (internGeneration != generation) {
            internGeneration = generation;
            INTERNED.set(0);
        }
        int known = SideEffects.internedId(text);
        if (known > 0) {
            return known;
        }
        if (INTERNED.incrementAndGet() > INTERN_QUOTA) {
            NOT_KEPT.increment();
            return 0;
        }
        return SideEffects.internRoom(text, SideEffects.ROOM_OTHER);
    }

    /** An algorithm as shown: letters, digits, and {@code / _ . -}, others {@code ?}, at most {@value #MAX_ALGORITHM}. */
    static String algorithmText(String algorithm) {
        int length = Math.min(algorithm.length(), MAX_ALGORITHM);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = algorithm.charAt(i);
            boolean kept = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '/'
                    || c == '_'
                    || c == '.'
                    || c == '-';
            text.append(kept ? c : '?');
        }
        return text.toString();
    }

    /**
     * A class's name as shown: a lambda's or hidden class's cut before {@code $$Lambda} or {@code /}, letters, digits,
     * and {@code _ . $ [ ; -} kept, others {@code ?}, at most {@value #MAX_NAME} characters.
     */
    static String className(String name) {
        int end = name.length();
        int lambda = name.indexOf("$$Lambda");
        if (lambda > 0) {
            end = lambda + "$$Lambda".length();
        }
        int hidden = name.indexOf('/');
        if (hidden > 0 && hidden < end) {
            end = hidden;
        }
        end = Math.min(end, MAX_NAME);
        StringBuilder text = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            char c = name.charAt(i);
            boolean kept = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '.'
                    || c == '$'
                    || c == '['
                    || c == ';'
                    || c == '-';
            text.append(kept ? c : '?');
        }
        return text.toString();
    }

    // ---- status and lifecycle ---------------------------------------------------------------------------------------

    static void putStatus(Map<String, Object> map) {
        Map<String, Object> state = new java.util.LinkedHashMap<String, Object>();
        int on = groups;
        for (int i = 0; i < GROUP_IDS.length; i++) {
            String reason = GROUP_REASONS[i];
            state.put(GROUP_IDS[i], (on & (1 << i)) != 0 ? "on" : reason == null ? "off" : "off: " + reason);
        }
        map.put("groups", state);
        map.put("weakAlgorithms", Long.valueOf(WEAK.sum()));
        map.put("jdkRequests", Long.valueOf(JDK_REQUESTS.sum()));
        map.put("unfilteredDeserializations", Long.valueOf(UNFILTERED.sum()));
        map.put("filteredDeserializations", Long.valueOf(FILTERED.sum()));
        map.put("classesNotNamed", Long.valueOf(NOT_NAMED.sum()));
        map.put("trustManagers", Long.valueOf(TRUST_MANAGERS.sum()));
        map.put("defaultsInstalled", Long.valueOf(DEFAULTS.sum()));
        map.put("libraryDefaults", Long.valueOf(LIBRARY_DEFAULTS.sum()));
        map.put("checkWalks", Long.valueOf(WALKS.sum()));
        map.put("checkMemoFull", Long.valueOf(MEMO_FULL.sum()));
        map.put("checksSkipped", Long.valueOf(SKIPPED.sum()));
        map.put("checksNotKept", Long.valueOf(NOT_KEPT.sum()));
        map.put("checksUnattributed", Long.valueOf(UNATTRIBUTED.sum()));
        Map<String, Object> errors = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < GROUP_IDS.length; i++) {
            errors.put(GROUP_IDS[i], Long.valueOf(ERRORS.get(i)));
        }
        map.put("checkErrors", errors);
    }

    /** Loads and links what the hooks use, on the agent's own thread, before the transformer installs. */
    static void warm() {
        weakDigest("warm");
        weakDigest("OID.1.3.14.3.2.26");
        weakCipher(" AES / ECB / NoPadding");
        algorithmText("warm");
        className("warm$$Lambda/0x1");
        others(new String[] {"a", "b"}, true);
        Serial serial = new Serial();
        clear(serial);
        new Attribution(null, -1, "warm", 0).getClass();
        SIGHTINGS.find(-2L, 1L, new long[2]);
        jdkByName("warm");
        java.io.ObjectInputStream.class.getName();
        putStatus(new java.util.LinkedHashMap<String, Object>());
    }

    /** Tests only: forgets the switches and counters. */
    static void reset() {
        groups = 0;
        Arrays.fill(GROUP_REASONS, null);
        for (LongAdder adder : new LongAdder[] {
            WEAK,
            JDK_REQUESTS,
            UNFILTERED,
            FILTERED,
            NOT_NAMED,
            TRUST_MANAGERS,
            DEFAULTS,
            LIBRARY_DEFAULTS,
            WALKS,
            MEMO_FULL,
            SKIPPED,
            NOT_KEPT,
            UNATTRIBUTED
        }) {
            adder.reset();
        }
        for (int i = 0; i < GROUP_IDS.length; i++) {
            ERRORS.set(i, 0L);
        }
        budgetOff = 0;
        Arrays.fill(BUDGET_REASONS, null);
        INTERNED.set(0);
        internGeneration = Long.MIN_VALUE;
        SIGHTINGS.clear();
    }
}
