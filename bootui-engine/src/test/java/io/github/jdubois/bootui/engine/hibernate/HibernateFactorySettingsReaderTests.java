package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorChangesDto;
import io.github.jdubois.bootui.core.dto.HibernateDiagnosticDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings.SchemaAction;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HibernateFactorySettingsReaderTests {

    private static final String JAKARTA_ACTION = "jakarta.persistence.schema-generation.database.action";
    private static final String HIBERNATE_ACTION = "hibernate.hbm2ddl.auto";

    @Test
    void aReadableMapWithoutDatabaseActionUsesTheHibernateDefault() {
        assertThat(schemaAction(Map::of)).isEqualTo(SchemaAction.NONE);
        assertThat(schemaAction(() -> Map.of("hibernate.show_sql", true))).isEqualTo(SchemaAction.NONE);
        assertThat(schemaAction(() -> Map.of("jakarta.persistence.schema-generation.scripts.action", "create")))
                .isEqualTo(SchemaAction.NONE);
    }

    @Test
    void unavailablePropertiesDoNotEstablishTheDefault() {
        assertThat(schemaAction(() -> null)).isEqualTo(SchemaAction.UNKNOWN);
        assertThat(schemaAction(() -> "not a map")).isEqualTo(SchemaAction.UNKNOWN);
        assertThat(schemaAction(HibernateFactorySettingsReaderTests::unavailable))
                .isEqualTo(SchemaAction.UNKNOWN);
    }

    @Test
    void invalidActionsDoNotEstablishTheDefaultOrFallBackToAnotherAction() {
        assertThat(schemaAction(() -> Map.of(HIBERNATE_ACTION, 42))).isEqualTo(SchemaAction.UNKNOWN);
        assertThat(schemaAction(() -> Collections.singletonMap(HIBERNATE_ACTION, null)))
                .isEqualTo(SchemaAction.UNKNOWN);
        assertThat(schemaAction(() -> Map.of(JAKARTA_ACTION, "invalid", HIBERNATE_ACTION, "update")))
                .isEqualTo(SchemaAction.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "javax.persistence.schema-generation.database.action",
                "jakarta.persistence.schema-generation.database.action.orm",
                "javax.persistence.schema-generation.database.action.custom",
                "hibernate.hbm2ddl.auto.custom"
            })
    void unsupportedDatabaseActionKeysDoNotEstablishTheDefault(String key) {
        assertThat(schemaAction(() -> Map.of(key, "drop"))).isEqualTo(SchemaAction.UNKNOWN);
    }

    @Test
    void explicitJakartaAndHibernateActionsRetainTheirMeaningAndPrecedence() {
        assertThat(schemaAction(() -> Map.of(JAKARTA_ACTION, "create", HIBERNATE_ACTION, "update")))
                .isEqualTo(SchemaAction.CREATE_ONLY);
        assertThat(schemaAction(() -> Map.of(JAKARTA_ACTION, "none", HIBERNATE_ACTION, "update")))
                .isEqualTo(SchemaAction.NONE);
        assertThat(schemaAction(() -> Map.of(HIBERNATE_ACTION, "create-drop"))).isEqualTo(SchemaAction.DROP_AND_CREATE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aPreviousUpdateFindingIsResolvedOnlyWhenTheNewReadEstablishesAbsence(boolean readable) {
        AtomicReference<Supplier<?>> properties = new AtomicReference<>(() -> Map.of(HIBERNATE_ACTION, "update"));
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        List.of(
                                unit("orders", properties.get()),
                                unit("archive", () -> Map.of(JAKARTA_ACTION, "none"))),
                        HibernateApplicationFacts.unknown(List.of()),
                        List.of()),
                Clock.systemUTC(),
                List.of(new RiskyDdlAutoRule()));
        HibernateReport first = scanner.scan();
        assertThat(first.evidence().coverageComplete()).isTrue();
        assertThat(first.results()).extracting(HibernateRuleResultDto::id).containsExactly("HIB-CONFIG-002");

        properties.set(readable ? Map::of : HibernateFactorySettingsReaderTests::unavailable);
        HibernateReport second = scanner.scan();
        assertThat(second.evidence().usable()).isTrue();
        assertThat(second.evidence().coverageComplete()).isEqualTo(readable);
        assertThat(second.results()).isEmpty();
        AdvisorChangesDto changes = second.violationDetails().changes();
        assertThat(changes.previousScanId()).isEqualTo(first.violationDetails().scanId());
        assertThat(changes.complete()).isEqualTo(readable);
        assertThat(changes.resolvedCount()).isEqualTo(readable ? 1 : 0);
        assertThat(changes.unverifiedCount()).isEqualTo(readable ? 0 : 1);
        assertThat(second.diagnostics())
                .extracting(HibernateDiagnosticDto::source)
                .containsExactlyElementsOf(readable ? List.of() : List.of("HIB-CONFIG-002"));
    }

    private static HibernatePersistenceUnitObservation unit(String label, Supplier<?> properties) {
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(Order.class)),
                List.of(),
                null,
                read(properties),
                false);
    }

    private static SchemaAction schemaAction(Supplier<?> properties) {
        return read(properties).schemaAction();
    }

    private static HibernateFactorySettings read(Supplier<?> properties) {
        // Isolate the schema rule from diagnostics for other methods this synthetic factory does not implement.
        return HibernateFactorySettingsReader.read(new SettingsFactory(properties), "orders", new ArrayList<>());
    }

    private static Map<?, ?> unavailable() {
        throw new IllegalStateException("Factory properties unavailable");
    }

    public record SettingsFactory(Supplier<?> properties) {
        public Object getProperties() {
            return properties.get();
        }
    }

    @Entity
    static class Order {
        @Id
        Long id;
    }
}
