package io.github.jdubois.bootui.engine.postgres;

/** The stable section ids shared by the collectors, the rules, the UI, and the published checks catalogue. */
final class PostgresSectionIds {

    static final String VITAL_SIGNS = "vital-signs";
    static final String STATEMENTS = "statements";
    static final String INDEXES = "indexes";
    static final String TABLES = "tables";
    static final String VACUUM = "vacuum";
    static final String REPLICATION = "replication";
    static final String SETTINGS = "settings";

    private PostgresSectionIds() {}
}
