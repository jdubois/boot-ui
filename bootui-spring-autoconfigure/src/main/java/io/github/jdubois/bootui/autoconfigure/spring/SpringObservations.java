package io.github.jdubois.bootui.autoconfigure.spring;

import java.util.List;
import java.util.Map;

/**
 * Immutable Spring-local input facts with scan-local evaluation accounting. Missing entries are
 * UNKNOWN, never inferred defaults. Values are fixed classifications or bounded framework metadata,
 * not application property values.
 */
record SpringObservations(
        Map<Fact, Object> facts, List<String> incomplete, SpringEvaluation evaluation, boolean inventoryAvailable) {
    SpringObservations(Map<Fact, Object> facts, List<String> incomplete) {
        this(facts, incomplete, new SpringEvaluation(), true);
    }

    enum Fact {
        OVERRIDING,
        CIRCULAR,
        LAZY_DEFINITIONS,
        ASYNC_SELECTION,
        ASYNC_QUEUE_CAPACITY,
        SCHEDULER_POOL_SIZE,
        SCHEDULER_NON_POOL,
        SCHEDULED_TASK_COUNT,
        BOOT_WEB_SERVER,
        JACKSON3_CONFIGURATION,
        HTTP_CLIENT_DEFAULTS,
        HTTP_SERVICE_GROUPS,
        BOOT_ERROR_HANDLING,
        BOOT_CODEC_CONFIGURATION,
        CODEC_LIMIT,
        OSIV,
        JDBC_KIND,
        R2DBC_KIND,
        ENDPOINTS,
        TOMCAT_VIRTUAL_EXECUTOR
    }

    enum AsyncSelection {
        FRAMEWORK_FALLBACK,
        SELECTED,
        AMBIGUOUS
    }

    /**
     * A non-null registration is observed presence, null with complete coverage is confirmed
     * absence, and null with incomplete coverage is unknown. Presence survives incomplete discovery.
     */
    record OsivObservation(String registration, boolean complete) {}

    SpringObservations {
        facts = Map.copyOf(facts);
        incomplete = List.copyOf(incomplete);
    }

    static SpringObservations unknown() {
        return new SpringObservations(Map.of(), List.of(), new SpringEvaluation(), false);
    }

    <T> T get(Fact fact, Class<T> type) {
        Object value = facts.get(fact);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    boolean known(Fact fact) {
        return facts.containsKey(fact);
    }

    boolean yes(Fact fact) {
        return Boolean.TRUE.equals(get(fact, Boolean.class));
    }
}
