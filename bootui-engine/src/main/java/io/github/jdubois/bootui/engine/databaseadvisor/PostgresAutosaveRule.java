package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.Locale;
import java.util.Set;

/**
 * pgjdbc {@code autosave=always} sets a savepoint before each query so a failed statement can be rolled back without
 * aborting the transaction; the default is {@code never}. Judged only from configured evidence.
 */
final class PostgresAutosaveRule extends AbstractDriverRule {

    private static final String ALWAYS = "always";

    /** The values pgjdbc accepts for {@code autosave}, which it reads case-insensitively and untrimmed. */
    private static final Set<String> VALUES = Set.of("never", "conservative", ALWAYS);

    PostgresAutosaveRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-006",
                        "PostgreSQL driver sets a savepoint before every query",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a datasource using the PostgreSQL JDBC driver (pgjdbc) whose driver property"
                                + " autosave, whose default is never, is always in the JDBC URL or the pool's driver"
                                + " properties. In that mode the driver sets a savepoint before each query and rolls"
                                + " back to it on failure, which adds work to every statement of a transaction. Only"
                                + " configured evidence is read.",
                        "Keep autosave=always only when the application relies on continuing a transaction after a"
                                + " failed statement. Otherwise remove it, or evaluate autosave=conservative, which"
                                + " pgjdbc documents as setting the savepoint but rolling back only in rare cases such"
                                + " as a cached statement that changed its return type.",
                        "https://jdbc.postgresql.org/documentation/use/"),
                "No PostgreSQL datasource using the PostgreSQL JDBC driver (pgjdbc) was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = PgJdbc.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        SettingReading reading = SettingReading.of(driver, DriverSettingCatalog.AUTOSAVE);
        if (reading.unresolved() != null) {
            return reading.unresolved();
        }
        if (reading.configured() == null) {
            return Outcome.assessed();
        }
        String value = reading.configured().value().toLowerCase(Locale.ROOT);
        if (!VALUES.contains(value)) {
            return reading.unreadable(DriverSettingCatalog.AUTOSAVE, "one of never, conservative or always")
                    .unresolved();
        }
        if (ALWAYS.equals(value)) {
            findings.add(
                    "Datasource `" + schema.dataSourceName() + "`: `" + DriverSettingCatalog.AUTOSAVE + "` is `"
                            + ALWAYS + "`, configured in the "
                            + reading.configured().label()
                            + ", so the driver sets a savepoint before each query (driver default `never`)."
                            + " Evidence: configured.",
                    settingIdentity(schema.dataSourceName(), DriverSettingCatalog.AUTOSAVE));
        }
        return Outcome.assessed();
    }
}
