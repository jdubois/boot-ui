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

    /** The bridge's sensor id of {@code security-sinks} in records (M5-6b). */
    static final int RECORD_SECURITY_SINKS = 8;

    /** The security-sinks sensor's id. */
    public static final String SECURITY_SINKS_ID = "security-sinks";

    /** The security-sinks sensor's record kinds: the sink request input reached ({@code RequestValues.SINK_*}). */
    static final int KIND_SINK_SQL = 1;

    static final int KIND_SINK_COMMAND = 2;
    static final int KIND_SINK_FILE = 3;
    static final int KIND_SINK_URL = 4;

    /**
     * The security-sinks sensor's JDK checks (M5-6b2, {@code SecuritySinks.KIND_*}): a deserialization without a filter,
     * a weak digest or cipher, a trust manager of the application, and a default hostname verifier or SSL socket factory
     * the application installed.
     */
    static final int KIND_CHECK_DESERIALIZATION = 5;

    static final int KIND_CHECK_WEAK_DIGEST = 6;
    static final int KIND_CHECK_WEAK_CIPHER = 7;
    static final int KIND_CHECK_TRUST_MANAGER = 8;
    static final int KIND_CHECK_HOSTNAME_VERIFIER = 9;
    static final int KIND_CHECK_SOCKET_FACTORY = 10;

    /** A JDK check's outcome bits ({@code SecuritySinks.ORIGIN_*}, {@code FLAG_ERROR}). */
    static final int CHECK_APPLICATION = 1;

    static final int CHECK_LIBRARY = 2;
    static final int CHECK_ERROR = 4;

    /** The marker the agent appends to a deserialization's classes past its bound. */
    static final String MORE_CLASSES = "(more)";

    /** What a JDK check row's kind says. */
    public static final String DESERIALIZATION = "deserialization without a filter";

    public static final String WEAK_DIGEST = "weak digest";
    public static final String WEAK_CIPHER = "weak cipher";
    public static final String TRUST_MANAGER = "trust manager";
    public static final String HOSTNAME_VERIFIER = "default hostname verifier";
    public static final String SOCKET_FACTORY = "default SSL socket factory";

    /** Whether a record is one of the security-sinks sensor's JDK checks rather than a request-value match. */
    static boolean check(int sensor, int kind) {
        return sensor == RECORD_SECURITY_SINKS
                && kind >= KIND_CHECK_DESERIALIZATION
                && kind <= KIND_CHECK_SOCKET_FACTORY;
    }

    /** A security-sinks record's outcome bits ({@code RequestValues.POSITION_*}, {@code FLAG_NUMERIC}). */
    static final int SINK_IN_LITERAL = 1;

    static final int SINK_OUTSIDE_LITERAL = 2;
    static final int SINK_POSITION_UNKNOWN = 3;
    static final int SINK_NUMERIC = 4;

    /** Where in an SQL text a value sat, as a security-sinks row's location says. */
    public static final String INSIDE_LITERAL = "inside a literal";

    public static final String OUTSIDE_LITERAL = "outside a literal";

    /** What a security-sinks row's kind says. */
    public static final String SQL_TEXT = "SQL text";

    public static final String COMMAND = "command";
    public static final String FILE_PATH = "file path";
    public static final String OUTBOUND_URL = "outbound URL";

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

    static final int OUTCOME_IO_ERROR = 2;

    /** Also the blocking sensor's call that threw, as BlockHound's error from inside it. */
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
            new Sensor(
                    SECURITY_SINKS_ID,
                    SECURITY_SINKS,
                    "Request input reaching SQL, commands, file paths, and URLs; deserialization without a filter, weak"
                            + " algorithms, and trust managers",
                    true,
                    RECORD_SECURITY_SINKS));

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
        if (recordId == RECORD_SECURITY_SINKS) {
            return switch (kind) {
                case KIND_SINK_SQL -> SQL_TEXT;
                case KIND_SINK_COMMAND -> COMMAND;
                case KIND_SINK_FILE -> FILE_PATH;
                case KIND_SINK_URL -> OUTBOUND_URL;
                case KIND_CHECK_DESERIALIZATION -> DESERIALIZATION;
                case KIND_CHECK_WEAK_DIGEST -> WEAK_DIGEST;
                case KIND_CHECK_WEAK_CIPHER -> WEAK_CIPHER;
                case KIND_CHECK_TRUST_MANAGER -> TRUST_MANAGER;
                case KIND_CHECK_HOSTNAME_VERIFIER -> HOSTNAME_VERIFIER;
                case KIND_CHECK_SOCKET_FACTORY -> SOCKET_FACTORY;
                default -> "sink";
            };
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
