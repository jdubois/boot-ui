package io.github.jdubois.bootui.engine.sideeffects;

/**
 * One record of the BootUI agent's side-effect sensors ({@code docs/PLAN-v2.md} §5.16), as the bridge's
 * {@code SideEffects} class lays it out in {@value #LENGTH} longs: decoded, with its strings still as ids.
 *
 * @param sensor the sensor's record id
 * @param kind the record kind
 * @param generation the claim generation it was made under
 * @param firstMillis when its first occurrence happened
 * @param lastMillis when its last occurrence happened
 * @param request the request id's 64 bits, 0 when none
 * @param execution the execution id's 64 bits, 0 when none
 * @param stamp the code-paths stamp of the innermost instrumented call, 0 or negative when none
 * @param target the target's string id
 * @param outcome the outcome
 * @param threadKind the thread kind
 * @param executionKind the execution's kind: 1 an agent-propagated task of a request, 2 a managed task of a request, 3 an
 *     execution no request owns ({@link #EXECUTION_OWN}), 0 none
 * @param threadName the thread name's string id, for a record without request or execution
 * @param exitStatus a process's exit status
 * @param count its occurrences
 * @param nanos their total duration
 * @param maxNanos the longest
 * @param outsideFrame the string id of the first frame outside the JDK, 0 when none
 * @param applicationFrame the string id of the first frame in the claimed packages, 0 when none
 */
record SideEffectRecord(
        int sensor,
        int kind,
        long generation,
        long firstMillis,
        long lastMillis,
        long request,
        long execution,
        long stamp,
        int target,
        int outcome,
        int threadKind,
        int executionKind,
        int threadName,
        int exitStatus,
        long count,
        long nanos,
        long maxNanos,
        int outsideFrame,
        int applicationFrame) {

    /** Longs per record. */
    static final int LENGTH = 14;

    /** The execution kind of an execution no request owns ({@code SideEffects.EXECUTION_OWN}). */
    static final int EXECUTION_OWN = 3;

    /** {@code record}, or {@code null} when it is not a side-effect record. */
    static SideEffectRecord decode(long[] record) {
        if (record == null || record.length < LENGTH || record[10] <= 0) {
            return null;
        }
        long flags = record[9];
        long frames = record[13];
        return new SideEffectRecord(
                (int) record[0],
                (int) record[1],
                record[2],
                record[3],
                record[4],
                record[5],
                record[6],
                record[7],
                (int) record[8],
                (int) (flags & 0xFF),
                (int) ((flags >>> 8) & 0xF),
                (int) ((flags >>> 12) & 0xF),
                (int) ((flags >>> 16) & 0xFFFF),
                (int) (flags >>> 32),
                record[10],
                Math.max(0L, record[11]),
                Math.max(0L, record[12]),
                (int) (frames >>> 32),
                (int) frames);
    }

    /** The request id as BootUI writes it, 16 hexadecimal digits, or {@code null}. */
    String requestId() {
        return request == 0L ? null : String.format("%016x", request);
    }

    /** The id of the execution no request owns this happened in, 16 hexadecimal digits, or {@code null}. */
    String executionId() {
        return execution == 0L || executionKind != EXECUTION_OWN ? null : String.format("%016x", execution);
    }
}
