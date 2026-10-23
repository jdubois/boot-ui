package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import java.util.Optional;

/**
 * Shared skeleton of the rules that judge one database-server setting, as the scan connection's own session sees it.
 * A subclass names the setting and says whether a reported value is the one it flags; a setting the server did not
 * report, one this server version lacks, or a value that cannot be read is never a finding. It is reported through
 * the {@link Outcome} (a skip for a version that has no such setting, a coverage gap otherwise), so a clean result
 * never hides what could not be checked.
 */
abstract class AbstractServerSettingRule extends AbstractDriverRule {

    /** Added to every finding: a pool init statement or a driver option can give pooled sessions another value. */
    static final String EVIDENCE = "Evidence: effective, as seen by the scan connection.";

    private final boolean postgres;
    private final String setting;
    private final int sinceMajor;
    private final int sinceMinor;

    /**
     * @param postgres {@code true} for a PostgreSQL setting, {@code false} for a MySQL one
     * @param setting the server's name of the setting
     * @param sinceMajor the first major version that has the setting, or {@code 0} when every supported one does
     * @param sinceMinor the first minor version of {@code sinceMajor} that has it
     */
    AbstractServerSettingRule(
            DatabaseAdvisorRuleDefinition definition,
            boolean postgres,
            String setting,
            int sinceMajor,
            int sinceMinor) {
        super(definition, postgres ? "No PostgreSQL datasource was detected." : "No MySQL datasource was detected.");
        this.postgres = postgres;
        this.setting = setting;
        this.sinceMajor = sinceMajor;
        this.sinceMinor = sinceMinor;
    }

    /**
     * Whether {@code value}, the raw value the server reported, is the one this rule flags; {@code null} when it cannot
     * be read as a value of this setting.
     */
    abstract Boolean flagged(String value);

    /** The finding text for one flagged datasource, without the evidence label. */
    abstract String text(String dataSourceName, ServerSetting reported);

    /** Why this datasource is not the rule's subject, or {@code null} when it is. */
    Outcome scope(SchemaSnapshot schema) {
        Dialect required = postgres ? Dialect.POSTGRESQL : Dialect.MYSQL;
        return schema.dialect() == required ? null : Outcome.notApplicable();
    }

    /**
     * Judges one datasource. A rule calls it from its own {@code assess}, so that the rule class itself reaches
     * {@code findings.add} and the catalogue guard can see that it records identity-carrying findings.
     */
    final Outcome judge(SchemaSnapshot schema, DatabaseFindings findings) {
        Outcome scope = scope(schema);
        if (scope != null) {
            return scope;
        }
        Result<ServerSettings> settings = ServerSettings.read(
                schema,
                postgres ? VendorFindingKinds.POSTGRES_SERVER_SETTINGS : VendorFindingKinds.MYSQL_SERVER_SETTINGS);
        if (settings.unresolved() != null) {
            return settings.unresolved();
        }
        Optional<ServerSetting> reported = settings.value().get(setting);
        if (reported.isEmpty()) {
            return missing(schema);
        }
        Boolean flagged = flagged(reported.get().value());
        if (flagged == null) {
            return Outcome.gap("the server reported `" + setting + "` as a value that could not be read, so it is not"
                    + " evaluated.");
        }
        if (flagged) {
            findings.add(
                    text(schema.dataSourceName(), reported.get()) + " " + EVIDENCE,
                    identity(schema.dataSourceName(), setting));
        }
        return Outcome.assessed();
    }

    /** The canonical identity of a server-setting finding: the datasource and the setting, never the value. */
    static String identity(String dataSourceName, String setting) {
        return DatabaseIdentities.of(dataSourceName, "server=" + setting);
    }

    private Outcome missing(SchemaSnapshot schema) {
        if (sinceMajor > 0 && schema.version().known() && !schema.version().atLeast(sinceMajor, sinceMinor)) {
            return Outcome.skip("this server version has no `" + setting + "` (" + postgresOrMysql() + " " + sinceMajor
                    + "." + sinceMinor + " or later), so it is not evaluated.");
        }
        return Outcome.gap("the server did not report `" + setting + "`, so it is not evaluated.");
    }

    private String postgresOrMysql() {
        return postgres ? "PostgreSQL" : "MySQL";
    }
}
