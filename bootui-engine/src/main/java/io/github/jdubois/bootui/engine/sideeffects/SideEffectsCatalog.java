package io.github.jdubois.bootui.engine.sideeffects;

import java.util.List;

/**
 * Every Side Effects sensor of {@code docs/PLAN-v2.md} §5.16, in tab order, with its tab, what it records, and whether
 * this version ships it; and the record ids the bridge's {@code SideEffects} class uses.
 */
public final class SideEffectsCatalog {

    /** The bridge's sensor id of {@code processes} in records. */
    static final int RECORD_PROCESSES = 1;

    /** The bridge's sensor id of {@code network} in records (M5-5b). */
    static final int RECORD_NETWORK = 2;

    /** The bridge's sensor id of {@code blocking} in records ({@code Blocking}, M5-5c). */
    static final int RECORD_BLOCKING = 3;

    /** The blocking sensor's record kinds, as the bridge's {@code Blocking} numbers them. */
    static final List<String> BLOCKING_KINDS = List.of("sleep", "wait", "park", "network", "file");

    /** The bridge's record kinds. */
    static final int KIND_PROCESS_START = 1;

    static final int KIND_PROCESS_EXIT = 2;
    static final int KIND_CONNECT = 3;
    static final int KIND_CONNECT_FINISH = 4;
    static final int KIND_DATAGRAM = 5;
    static final int KIND_LOOKUP = 6;

    /** The bridge's outcomes. */
    static final int OUTCOME_STARTED = 1;

    /** For the blocking sensor, an interrupted call. */
    static final int OUTCOME_IO_ERROR = 2;

    /** For the blocking sensor, a call that threw, as BlockHound's error from inside it. */
    static final int OUTCOME_ERROR = 3;

    static final int OUTCOME_EXITED = 4;
    static final int OUTCOME_CONNECTED = 5;
    static final int OUTCOME_PENDING = 6;
    static final int OUTCOME_SENT = 7;
    static final int OUTCOME_RESOLVED = 8;
    static final int OUTCOME_UNKNOWN_HOST = 9;

    /** The network sensor's id. */
    static final String NETWORK_ID = "network";

    /** What a network row's kind says. */
    static final String CONNECT = "connect";

    static final String DATAGRAM = "datagram";
    static final String LOOKUP = "lookup";

    public static final String NETWORK = "Network";
    public static final String FILES_AND_PROCESSES = "Files and processes";
    public static final String ENVIRONMENT = "Environment";
    public static final String THREADS_AND_LEAKS = "Threads and leaks";
    public static final String BLOCKING = "Blocking";
    public static final String SECURITY_SINKS = "Security sinks";

    /** The tabs, in order. */
    public static final List<String> GROUPS =
            List.of(NETWORK, FILES_AND_PROCESSES, ENVIRONMENT, THREADS_AND_LEAKS, BLOCKING, SECURITY_SINKS);

    /** Why a sensor of the catalog has no rows yet. */
    public static final String NOT_IN_THIS_VERSION = "Not available in this version.";

    /**
     * One sensor.
     *
     * @param id its {@code bootui.agent.sensors} id
     * @param group its tab
     * @param label what it records
     * @param available whether this version ships it
     * @param recordId its id in the bridge's records, 0 when this version does not ship it
     */
    public record Sensor(String id, String group, String label, boolean available, int recordId) {}

    /** Every sensor, in tab order. */
    public static final List<Sensor> SENSORS = List.of(
            new Sensor("network", NETWORK, "Hosts the application connects to", true, RECORD_NETWORK),
            new Sensor("files", FILES_AND_PROCESSES, "Files the application reads and writes", false, 0),
            new Sensor("processes", FILES_AND_PROCESSES, "Processes the application starts", true, RECORD_PROCESSES),
            new Sensor("environment", ENVIRONMENT, "Environment variables and system properties read", false, 0),
            new Sensor("thread-activity", THREADS_AND_LEAKS, "Threads and executors started per route", false, 0),
            new Sensor("thread-locals", THREADS_AND_LEAKS, "Thread locals left set after a request", false, 0),
            new Sensor("resources", THREADS_AND_LEAKS, "Streams and sockets left open", false, 0),
            new Sensor("blocking", BLOCKING, "Blocking calls started on an event loop", true, RECORD_BLOCKING),
            new Sensor("security-sinks", SECURITY_SINKS, "Request input reaching SQL, commands, and paths", false, 0));

    private SideEffectsCatalog() {}

    /** The sensor of {@code id}, or {@code null}. */
    public static Sensor sensor(String id) {
        for (Sensor sensor : SENSORS) {
            if (sensor.id().equals(id)) {
                return sensor;
            }
        }
        return null;
    }

    /** The sensor of record id {@code recordId}, or {@code null}. */
    static Sensor byRecordId(int recordId) {
        for (Sensor sensor : SENSORS) {
            if (sensor.recordId() != 0 && sensor.recordId() == recordId) {
                return sensor;
            }
        }
        return null;
    }

    /** What a record of {@code recordId} and {@code kind} did, as rows name it. */
    static String kind(int recordId, int kind) {
        if (recordId == RECORD_PROCESSES) {
            return "process";
        }
        if (recordId == RECORD_NETWORK) {
            return switch (kind) {
                case KIND_CONNECT, KIND_CONNECT_FINISH -> CONNECT;
                case KIND_DATAGRAM -> DATAGRAM;
                case KIND_LOOKUP -> LOOKUP;
                default -> "operation";
            };
        }
        if (recordId == RECORD_BLOCKING && kind >= 1 && kind <= BLOCKING_KINDS.size()) {
            return BLOCKING_KINDS.get(kind - 1);
        }
        return "operation";
    }

    /** Whether a record of {@code recordId} and {@code kind} is a process's exit, not a new occurrence. */
    static boolean processExit(int recordId, int kind) {
        return recordId == RECORD_PROCESSES && kind == KIND_PROCESS_EXIT;
    }

    /** Whether an outcome is a failure. */
    static boolean failed(int outcome) {
        return outcome == OUTCOME_IO_ERROR || outcome == OUTCOME_ERROR || outcome == OUTCOME_UNKNOWN_HOST;
    }
}
