package io.github.jdubois.bootui.engine.sideeffects;

import java.util.List;

/**
 * Every Side Effects sensor of {@code docs/PLAN-v2.md} §5.16, in tab order, with its tab, what it records, and whether
 * this version ships it; and the record ids the bridge's {@code SideEffects} class uses.
 */
public final class SideEffectsCatalog {

    /** The sensors' ids, as {@code bootui.agent.sensors} names them. */
    public static final String PROCESSES_ID = "processes";

    public static final String FILES_ID = "files";
    public static final String ENVIRONMENT_ID = "environment";

    /** The bridge's sensor ids in records. */
    static final int RECORD_PROCESSES = 1;

    /** The bridge's sensor id of {@code network} in records (M5-5b). */
    static final int RECORD_NETWORK = 2;

    static final int RECORD_FILES = 3;
    static final int RECORD_ENVIRONMENT = 4;

    /** The bridge's sensor id of {@code blocking} in records ({@code Blocking}, M5-5c). */
    static final int RECORD_BLOCKING = 5;

    /** The bridge's record kinds. */
    static final int KIND_PROCESS_START = 1;

    static final int KIND_PROCESS_EXIT = 2;
    static final int KIND_CONNECT = 3;
    static final int KIND_CONNECT_FINISH = 4;
    static final int KIND_DATAGRAM = 5;
    static final int KIND_LOOKUP = 6;
    static final int KIND_FILE_READ = 7;
    static final int KIND_FILE_WRITE = 8;
    static final int KIND_FILE_DELETE = 9;
    static final int KIND_FILE_MOVE_FROM = 10;
    static final int KIND_FILE_MOVE_TO = 11;
    static final int KIND_FILE_COPY_FROM = 12;
    static final int KIND_FILE_COPY_TO = 13;
    static final int KIND_ENVIRONMENT_VARIABLE = 14;
    static final int KIND_SYSTEM_PROPERTY = 15;
    static final int KIND_SLEEP = 16;
    static final int KIND_WAIT = 17;
    static final int KIND_PARK = 18;
    static final int KIND_BLOCKING_NETWORK = 19;
    static final int KIND_BLOCKING_FILE = 20;

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
    static final int OUTCOME_DONE = 10;

    /** The blocking sensor's outcomes: a call that returned, or that was interrupted. */
    static final int OUTCOME_RETURNED = 11;

    static final int OUTCOME_INTERRUPTED = 12;

    /** What a blocking row's kind says. */
    public static final String SLEEP = "sleep";

    public static final String WAIT = "wait";
    public static final String PARK = "park";
    public static final String BLOCKING_NETWORK = "network";
    public static final String BLOCKING_FILE = "file";

    /** What a file row did, as rows name it. */
    public static final String READ = "read";

    public static final String WRITE = "write";
    public static final String DELETE = "delete";
    public static final String MOVE_FROM = "move from";
    public static final String MOVE_TO = "move to";
    public static final String COPY_FROM = "copy from";
    public static final String COPY_TO = "copy to";

    /** What an environment row read. */
    public static final String ENVIRONMENT_VARIABLE = "environment variable";

    public static final String SYSTEM_PROPERTY = "system property";

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
            new Sensor("files", FILES_AND_PROCESSES, "Files the application reads and writes", true, RECORD_FILES),
            new Sensor("processes", FILES_AND_PROCESSES, "Processes the application starts", true, RECORD_PROCESSES),
            new Sensor(
                    "environment",
                    ENVIRONMENT,
                    "Environment variables and system properties read",
                    true,
                    RECORD_ENVIRONMENT),
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
        if (recordId == RECORD_BLOCKING) {
            return switch (kind) {
                case KIND_SLEEP -> SLEEP;
                case KIND_WAIT -> WAIT;
                case KIND_PARK -> PARK;
                case KIND_BLOCKING_NETWORK -> BLOCKING_NETWORK;
                case KIND_BLOCKING_FILE -> BLOCKING_FILE;
                default -> "operation";
            };
        }
        return switch (kind) {
            case KIND_FILE_READ -> READ;
            case KIND_FILE_WRITE -> WRITE;
            case KIND_FILE_DELETE -> DELETE;
            case KIND_FILE_MOVE_FROM -> MOVE_FROM;
            case KIND_FILE_MOVE_TO -> MOVE_TO;
            case KIND_FILE_COPY_FROM -> COPY_FROM;
            case KIND_FILE_COPY_TO -> COPY_TO;
            case KIND_ENVIRONMENT_VARIABLE -> ENVIRONMENT_VARIABLE;
            case KIND_SYSTEM_PROPERTY -> SYSTEM_PROPERTY;
            default -> "operation";
        };
    }

    /** Whether a file row's kind writes: anything but a read or the source of a copy. */
    public static boolean writes(String kind) {
        return kind != null && !READ.equals(kind) && !COPY_FROM.equals(kind);
    }

    /** Whether a record of {@code recordId} and {@code kind} is a process's exit, not a new occurrence. */
    static boolean processExit(int recordId, int kind) {
        return recordId == RECORD_PROCESSES && kind == KIND_PROCESS_EXIT;
    }

    /** Whether an outcome is a failure. */
    static boolean failed(int outcome) {
        return outcome == OUTCOME_IO_ERROR
                || outcome == OUTCOME_ERROR
                || outcome == OUTCOME_UNKNOWN_HOST
                || outcome == OUTCOME_INTERRUPTED;
    }
}
