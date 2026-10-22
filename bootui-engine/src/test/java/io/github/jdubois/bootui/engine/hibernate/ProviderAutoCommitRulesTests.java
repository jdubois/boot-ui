package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins every row of the HIB-CONFIG-008 / HIB-CONFIG-021 decision table for observed persistence units. */
class ProviderAutoCommitRulesTests {

    private static final HibernateApplicationFacts APP =
            new HibernateApplicationFacts(List.of(), false, false, false, false);
    private static final HibernatePoolObservation HIKARI = pool(false);

    private static HibernatePoolObservation pool(Boolean autoCommit) {
        return new HibernatePoolObservation("dataSource", "HikariCP", autoCommit);
    }

    private static HibernateFactorySettings settings(
            Boolean providerDisablesAutoCommit, HibernateFactorySettings.TransactionMode mode) {
        return new HibernateFactorySettings(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                providerDisablesAutoCommit,
                mode);
    }

    private static HibernateContext context(
            Boolean providerDisablesAutoCommit,
            HibernateFactorySettings.TransactionMode mode,
            HibernatePoolObservation pool) {
        return HibernateContext.observed(unit("orders", providerDisablesAutoCommit, mode, pool), APP);
    }

    private static HibernateContext context(Boolean providerDisablesAutoCommit, HibernatePoolObservation pool) {
        return context(providerDisablesAutoCommit, HibernateFactorySettings.TransactionMode.RESOURCE_LOCAL, pool);
    }

    private static HibernatePersistenceUnitObservation unit(
            String label,
            Boolean providerDisablesAutoCommit,
            HibernateFactorySettings.TransactionMode mode,
            HibernatePoolObservation pool) {
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(Order.class)),
                List.of(),
                "7.4.13.Final",
                settings(providerDisablesAutoCommit, mode),
                false,
                pool);
    }

    // --- HIB-CONFIG-008 -----------------------------------------------------

    @Test
    void recommendationFiresOnlyWhenThePoolDisablesAutoCommitAndTheProviderFlagIsOff() {
        HibernateContext context = context(false, HIKARI);

        HibernateRuleResultDto result = new ProviderDisablesAutocommitRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.INFO);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample ->
                        assertThat(sample).contains("HikariCP pool dataSource", "autoCommit=false", "eagerly"));
        assertThat(context.evidence().requiredUnknown()).isFalse();
        assertThat(context.evidence().usable()).isTrue();
    }

    @Test
    void recommendationPassesWhenThereIsNothingToRecommend() {
        assertThat(new ProviderDisablesAutocommitRule()
                        .evaluate(context(false, pool(true)))
                        .status())
                .as("the pool still hands out auto-commit connections")
                .isEqualTo(HibernateRuleSupport.PASS);
        assertThat(new ProviderDisablesAutocommitRule()
                        .evaluate(context(true, pool(false)))
                        .status())
                .as("the property is already set for a pool that disables auto-commit")
                .isEqualTo(HibernateRuleSupport.PASS);
        assertThat(new ProviderDisablesAutocommitRule()
                        .evaluate(context(true, pool(true)))
                        .status())
                .as("the contradiction belongs to HIB-CONFIG-021")
                .isEqualTo(HibernateRuleSupport.PASS);
    }

    @Test
    void recommendationIsSkippedWithAnAdvisorLimitGapWhenThePoolIsNotAttributedOrUnreadable() {
        for (HibernatePoolObservation pool : new HibernatePoolObservation[] {null, pool(null)}) {
            HibernateContext context = context(false, pool);

            HibernateRuleResultDto result = new ProviderDisablesAutocommitRule().evaluate(context);

            assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
            assertThat(context.evidence().requiredUnknown()).isTrue();
            assertThat(context.evidence().gaps()).containsOnlyKeys(HibernateEvidenceGap.POOL_GUARANTEES_BY_DESIGN);
            assertThat(context.evidence().usable()).isFalse();
        }
    }

    @Test
    void recommendationIsSkippedForJtaWithoutClaimingMissingEvidence() {
        HibernateContext context = context(false, HibernateFactorySettings.TransactionMode.JTA, HIKARI);

        HibernateRuleResultDto result = new ProviderDisablesAutocommitRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(result.sampleViolations()).singleElement().asString().contains("JTA");
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void recommendationIsSkippedWhenTheTransactionModeOrProviderFlagIsUnknown() {
        HibernateContext unknownMode = context(false, HibernateFactorySettings.TransactionMode.UNKNOWN, HIKARI);
        assertThat(new ProviderDisablesAutocommitRule().evaluate(unknownMode).status())
                .isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(unknownMode.evidence().gaps()).containsOnlyKeys(HibernateEvidenceGap.FACTORY_SETTING);

        HibernateContext unknownFlag = context(null, HIKARI);
        assertThat(new ProviderDisablesAutocommitRule().evaluate(unknownFlag).status())
                .isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(unknownFlag.evidence().gaps()).containsOnlyKeys(HibernateEvidenceGap.FACTORY_SETTING);
    }

    // --- HIB-CONFIG-021 -----------------------------------------------------

    @Test
    void contradictionFiresOnlyWhenTheProviderFlagIsOnAndThePoolHandsOutAutoCommitConnections() {
        HibernateContext context = context(true, pool(true));

        HibernateRuleResultDto result = new ProviderAutoCommitContradictionRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample)
                        .contains(
                                "provider_disables_autocommit=true",
                                "HikariCP pool dataSource",
                                "autoCommit=true",
                                "not atomic"));
        assertThat(context.evidence().usable()).isTrue();
    }

    @Test
    void contradictionPassesForEveryOtherKnownCombination() {
        for (boolean flag : new boolean[] {true, false}) {
            for (boolean poolAutoCommit : new boolean[] {true, false}) {
                if (flag && poolAutoCommit) continue;
                assertThat(new ProviderAutoCommitContradictionRule()
                                .evaluate(context(flag, pool(poolAutoCommit)))
                                .status())
                        .as("provider flag %s, pool autoCommit %s", flag, poolAutoCommit)
                        .isEqualTo(HibernateRuleSupport.PASS);
            }
        }
    }

    @Test
    void contradictionIsNeverPassedOrReportedOnUnknownOrUnprovenResourceLocalEvidence() {
        List<HibernateContext> contexts = List.of(
                context(true, null),
                context(true, pool(null)),
                context(null, pool(true)),
                context(true, HibernateFactorySettings.TransactionMode.JTA, pool(true)),
                context(true, HibernateFactorySettings.TransactionMode.UNKNOWN, pool(true)));
        for (HibernateContext context : contexts) {
            HibernateRuleResultDto result = new ProviderAutoCommitContradictionRule().evaluate(context);
            assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
            assertThat(context.evidence().usable()).isFalse();
        }
    }

    @Test
    void contradictionIsOnlyEvaluatedForALivePersistenceUnit() {
        HibernateContext declarations = new HibernateContext(List.of(), List.of(), key -> null, List.of());

        assertThat(new ProviderAutoCommitContradictionRule()
                        .evaluate(declarations)
                        .status())
                .isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    @Test
    void theNewRuleHasItsOwnIdentityAndIsRegisteredOnceAfterTheRecommendation() {
        List<String> ids = HibernateRuleRegistry.activeRules().stream()
                .map(rule -> rule.definition().id())
                .toList();

        assertThat(ids).containsOnlyOnce("HIB-CONFIG-008", "HIB-CONFIG-021");
        assertThat(ids.indexOf("HIB-CONFIG-021")).isEqualTo(ids.indexOf("HIB-CONFIG-008") + 1);
        assertThat(new ProviderAutoCommitContradictionRule().definition().severity())
                .isEqualTo("HIGH");
    }

    // --- scan level ---------------------------------------------------------

    @Test
    void aScanAttributesTheContradictionAndTheLimitToTheirOwnUnits() {
        HibernateReport report = HibernateScanner.observing(
                        () -> new HibernateAdvisorObservation(
                                List.of(
                                        unit(
                                                "billing",
                                                true,
                                                HibernateFactorySettings.TransactionMode.RESOURCE_LOCAL,
                                                pool(true)),
                                        unit(
                                                "orders",
                                                false,
                                                HibernateFactorySettings.TransactionMode.RESOURCE_LOCAL,
                                                null),
                                        unit(
                                                "audit",
                                                false,
                                                HibernateFactorySettings.TransactionMode.RESOURCE_LOCAL,
                                                pool(false))),
                                APP,
                                List.of()),
                        Clock.systemUTC())
                .scan();

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-CONFIG-021"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.severity()).isEqualTo(HibernateRuleSupport.HIGH);
                    assertThat(result.violationCount()).isEqualTo(1);
                    assertThat(result.sampleViolations())
                            .singleElement()
                            .asString()
                            .startsWith("[billing]");
                });
        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-CONFIG-008"))
                .singleElement()
                .satisfies(result -> assertThat(result.sampleViolations())
                        .singleElement()
                        .asString()
                        .startsWith("[audit]"));
        assertThat(report.diagnostics())
                .filteredOn(diagnostic -> diagnostic.unit().equals("orders")
                        && diagnostic.source().startsWith("HIB-CONFIG-0"))
                .extracting(diagnostic -> diagnostic.source() + ":" + diagnostic.level())
                .contains("HIB-CONFIG-008:INFO", "HIB-CONFIG-021:INFO");
    }

    @Entity
    static class Order {
        @Id
        Long id;
    }
}
