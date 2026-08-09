package io.github.jdubois.bootui.engine.action;

/** Stable operation identifiers used by the single-flight busy contract. */
public final class ActionOperations {

    public static final String ARCHITECTURE_SCAN = "architecture.scan";
    public static final String CRAC_SCAN = "crac.scan";
    public static final String DATABASE_ADVISOR_SCAN = "database-advisor.scan";
    public static final String GRAALVM_SCAN = "graalvm.scan";
    public static final String HEAP_DUMP_ANALYZE = "heap-dump.analyze";
    public static final String HEAP_DUMP_CAPTURE = "heap-dump.capture";
    public static final String HEAP_DUMP_DELETE = "heap-dump.delete";
    public static final String HIBERNATE_SCAN = "hibernate.scan";
    public static final String MEMORY_SCAN = "memory.scan";
    public static final String PENTESTING_SCAN = "pentesting.scan";
    public static final String REST_API_SCAN = "rest-api.scan";
    public static final String SECURITY_SCAN = "security.scan";
    public static final String SPRING_SCAN = "spring.scan";
    public static final String VULNERABILITIES_SCAN = "vulnerabilities.scan";

    private ActionOperations() {}
}
