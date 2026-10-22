package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * PostgreSQL without {@code pg_stat_statements}: the library is not in {@code shared_preload_libraries}, or the
 * extension is not created in the connected database. Both must be <em>known</em>: the library list is a server
 * setting and the extension a row of {@code pg_extension}, and a read that failed, was cut short or was hidden from
 * this role leaves the rule undecided rather than guess.
 */
final class PostgresStatStatementsRule extends AbstractDriverRule {

    static final String ID = "DB-SERVER-004";
    static final String LIBRARY = "pg_stat_statements";
    static final String SETTING = "shared_preload_libraries";

    PostgresStatStatementsRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "PostgreSQL pg_stat_statements is not available",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a PostgreSQL datasource where pg_stat_statements cannot be used: the library is not"
                                + " in shared_preload_libraries (the list is read as library names, so a name that only"
                                + " starts with pg_stat_statements does not count), or the extension is not created in"
                                + " the connected database (CREATE EXTENSION is per database). Without it the server"
                                + " keeps no per-statement statistics. Both facts must be readable by the scan"
                                + " connection: an unreadable list or extension read leaves the rule skipped. The"
                                + " values are the ones the scan connection's own session sees.",
                        "Add pg_stat_statements to shared_preload_libraries (this needs a server restart) and run"
                                + " CREATE EXTENSION pg_stat_statements in the application's database. With it, the"
                                + " PostgreSQL panel's Statement ranking section lists the statements that take the"
                                + " most time; without it the section is empty.",
                        "https://www.postgresql.org/docs/current/pgstatstatements.html"),
                "No PostgreSQL datasource was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        if (schema.dialect() != Dialect.POSTGRESQL) {
            return Outcome.notApplicable();
        }
        Result<Boolean> preloaded = preloaded(schema);
        if (preloaded.unresolved() != null) {
            return preloaded.unresolved();
        }
        Result<Boolean> created = created(schema);
        if (created.unresolved() != null) {
            return created.unresolved();
        }
        List<String> missing = new ArrayList<>();
        if (!preloaded.value()) {
            missing.add("is not in " + SETTING);
        }
        if (!created.value()) {
            missing.add("is not created in this database");
        }
        if (!missing.isEmpty()) {
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: " + LIBRARY + " " + String.join(" and ", missing)
                            + ", so the server keeps no per-statement statistics. "
                            + AbstractServerSettingRule.EVIDENCE,
                    AbstractServerSettingRule.identity(schema.dataSourceName(), LIBRARY));
        }
        return Outcome.assessed();
    }

    private static Result<Boolean> preloaded(SchemaSnapshot schema) {
        Result<ServerSettings> settings = ServerSettings.read(schema, VendorFindingKinds.POSTGRES_SERVER_SETTINGS);
        if (settings.unresolved() != null) {
            return Result.unresolved(settings.unresolved());
        }
        Optional<ServerSetting> libraries = settings.value().get(SETTING);
        if (libraries.isEmpty()) {
            return Result.unresolved(Outcome.gap("the server did not report `" + SETTING + "` (it may be hidden from"
                    + " this role), so whether " + LIBRARY + " is loaded is unknown."));
        }
        return Result.of(isListed(libraries.get().value(), LIBRARY));
    }

    private static Result<Boolean> created(SchemaSnapshot schema) {
        VendorAugmentation<PostgresInstalledExtension> extension =
                schema.vendorFindings().augmentation(VendorFindingKinds.POSTGRES_PG_STAT_STATEMENTS_EXTENSION);
        if (!extension.available()) {
            return Result.unresolved(Outcome.gap(extension.reason()));
        }
        // The statement can return at most one row; a bound that cut it short cannot make the absence certain.
        if (extension.truncated()) {
            return Result.unresolved(Outcome.gap(
                    "the " + LIBRARY + " extension read was truncated, so whether it is created is unknown."));
        }
        boolean present = extension.findings().stream().anyMatch(row -> LIBRARY.equals(row.name()));
        return Result.of(present);
    }

    /**
     * Whether {@code library} is one of the comma-separated entries of a {@code shared_preload_libraries} value. Each
     * entry is trimmed, stripped of optional single or double quotes, reduced to its file name (so a {@code $libdir/}
     * prefix or any other directory is dropped) and of a shared-library suffix the server accepts but does not
     * require, then compared for equality. Never a substring test: {@code pg_stat_statements_extra} is another
     * library, and an empty value lists nothing.
     */
    static boolean isListed(String value, String library) {
        if (value == null) {
            return false;
        }
        for (String entry : value.split(",", -1)) {
            if (library.equals(libraryName(entry))) {
                return true;
            }
        }
        return false;
    }

    private static String libraryName(String entry) {
        String name = entry.trim();
        if (name.length() >= 2
                && (name.charAt(0) == '"' || name.charAt(0) == '\'')
                && name.charAt(name.length() - 1) == name.charAt(0)) {
            name = name.substring(1, name.length() - 1).trim();
        }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".so", ".dylib", ".dll")) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }
}
