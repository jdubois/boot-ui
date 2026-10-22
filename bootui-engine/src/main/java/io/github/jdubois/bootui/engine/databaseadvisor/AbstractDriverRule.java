package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared skeleton of the JDBC driver rules. A subclass judges one datasource at a time and answers with an
 * {@link Outcome}; this class turns the outcomes into the single rule result, so a datasource whose evidence is
 * missing is always reported as skipped with its reason and never as a pass.
 *
 * <p>An outcome is one of: {@link Outcome#notApplicable() not applicable} (the datasource is simply not this rule's
 * subject, silently), {@link Outcome#skip(String) skip} (it is, but a documented condition excludes it, such as
 * batching being off), {@link Outcome#gap(String) gap} (evidence the rule needs could not be established), a
 * {@link Outcome#pass() pass} or a {@link Outcome#finding(String) finding}. Gaps matter in a mixed inventory: when
 * another datasource was assessed, they are journaled as coverage gaps so a clean result cannot hide them.</p>
 */
abstract class AbstractDriverRule extends AbstractDatabaseAdvisorRule {

    /** What to say when no datasource at all is this rule's subject. */
    private final String noDatasourceReason;

    AbstractDriverRule(DatabaseAdvisorRuleDefinition definition, String noDatasourceReason) {
        super(definition);
        this.noDatasourceReason = noDatasourceReason;
    }

    abstract Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema);

    @Override
    final DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> findings = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int assessed = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            Outcome outcome = assess(context, schema);
            String text = schema.dataSourceName() + ": " + outcome.text();
            switch (outcome.kind()) {
                case NOT_APPLICABLE -> {}
                case SKIP -> reasons.add(text);
                case GAP -> {
                    reasons.add(text);
                    gaps.add(text);
                }
                case PASS -> assessed++;
                case FINDING -> {
                    assessed++;
                    findings.add(outcome.text());
                }
            }
        }
        if (assessed == 0) {
            return skipped(reasons.isEmpty() ? "Not applicable: " + noDatasourceReason : String.join("; ", reasons));
        }
        // Only a mixed inventory journals gaps: with something assessed, a clean result must not hide them.
        gaps.forEach(gap -> unknown(context, gap));
        return VendorRuleSupport.assessed(this, context, assessed, findings);
    }

    /** The datasource's driver evidence, or a gap when the driver could not be identified at all. */
    static Outcome driverUnidentified(DriverEvidence driver) {
        return driver == null
                        || driver.driverName() == null
                        || driver.driverName().isBlank()
                ? Outcome.gap("the JDBC driver could not be identified, so its settings were not evaluated.")
                : null;
    }

    /** {@code null} when JDBC batching is demonstrably enabled; otherwise why this rule does not apply or is unsure. */
    static Outcome batchingGate(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        JdbcBatching batching = context.jdbcBatchingOf(schema.dataSourceName());
        if (batching == null || batching.status() == JdbcBatching.Status.UNKNOWN) {
            // A skip, not a gap: a datasource Hibernate does not use is not a hole in the coverage of the others.
            return Outcome.skip("JDBC batching status unknown: no Hibernate persistence unit could be attributed to"
                    + " this datasource's connection pool, or its batch size could not be read.");
        }
        if (batching.status() == JdbcBatching.Status.DISABLED) {
            return Outcome.skip("JDBC batching is not enabled for this datasource (see HIB-CONFIG-004).");
        }
        return null;
    }

    /** How one boolean driver setting stands on one datasource, or why that cannot be said. */
    record BooleanSetting(Boolean value, DriverSetting configured, Outcome unresolved) {

        static BooleanSetting of(DriverEvidence driver, String name) {
            if (driver.overrideConfigured()) {
                return new BooleanSetting(
                        null,
                        driver.settings().get(name),
                        Outcome.gap("a properties transform is configured and can replace any explicit value of `"
                                + name + "`, so it is not evaluated."));
            }
            DriverSetting setting = driver.settings().get(name);
            if (setting == null) {
                Outcome gap = absentIsNotEvidence(driver, name);
                return new BooleanSetting(null, null, gap);
            }
            if (setting.conflicting()) {
                return unresolved(
                        setting,
                        "the configuration of `" + name + "` cannot be settled: the URL and the pool disagree and the"
                                + " driver defines no winner, or the name is spelled or scoped in a way whose effect"
                                + " on this driver is not modeled.");
            }
            boolean urlWins = setting.source() == DriverSetting.Source.URL
                    && DriverSettingCatalog.precedence(driver.driver()) == DriverSettingCatalog.Precedence.URL_WINS;
            if (!urlWins && !(driver.urlSettingsKnown() && driver.poolSettingsKnown())) {
                return unresolved(
                        setting,
                        "`" + name + "` is configured in the " + setting.label() + ", but the "
                                + (driver.urlSettingsKnown() ? "pool's driver properties" : "JDBC URL")
                                + " could not be read and may override it.");
            }
            Boolean value = DriverSettingCatalog.asBoolean(driver.driver(), setting.value());
            return value == null
                    ? unresolved(
                            setting, "`" + name + "` is configured with a value the driver does not read as a boolean.")
                    : new BooleanSetting(value, setting, null);
        }

        private static BooleanSetting unresolved(DriverSetting setting, String reason) {
            return new BooleanSetting(null, setting, Outcome.gap(reason));
        }

        /** {@code null} when not finding {@code name} in the URL and the pool really means it is not configured there. */
        private static Outcome absentIsNotEvidence(DriverEvidence driver, String name) {
            if (!driver.urlSettingsKnown()) {
                return Outcome.gap("the JDBC URL could not be read, so whether `" + name + "` is set is unknown.");
            }
            if (!driver.poolSettingsKnown()) {
                return Outcome.gap("the pool's driver properties could not be read (only HikariCP and Agroal pools are"
                        + " read), so whether `" + name + "` is set is unknown.");
            }
            if (driver.expansionConfigured()) {
                return Outcome.gap("the driver reads further settings from a file the advisor does not see (a"
                        + " service or configuration-file setting is configured), so whether `" + name
                        + "` is set is unknown.");
            }
            return null;
        }

        boolean configuredTrue() {
            return Boolean.TRUE.equals(value);
        }
    }

    record Outcome(Kind kind, String text) {

        enum Kind {
            NOT_APPLICABLE,
            SKIP,
            GAP,
            PASS,
            FINDING
        }

        static Outcome notApplicable() {
            return new Outcome(Kind.NOT_APPLICABLE, "");
        }

        static Outcome skip(String reason) {
            return new Outcome(Kind.SKIP, reason);
        }

        static Outcome gap(String reason) {
            return new Outcome(Kind.GAP, reason);
        }

        static Outcome pass() {
            return new Outcome(Kind.PASS, "");
        }

        static Outcome finding(String detail) {
            return new Outcome(Kind.FINDING, detail);
        }
    }
}
