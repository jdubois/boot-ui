package io.github.jdubois.bootui.quarkus.quarkusapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import io.quarkus.restclient.config.AbstractRestClientConfigBuilder;
import io.quarkus.restclient.config.RegisteredRestClient;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

class QuarkusAppSnapshotProviderImplTest {

    private static final String CLIENT = "com.example.RemoteClient";
    private static final QuarkusAppMetadata EMPTY =
            new QuarkusAppMetadata(true, 0, 0, 0, 0, 0, true, true, false, List.of(), List.of(), List.of(), List.of());

    @Test
    void productionDeclarationDoesNotCertifyAnUnobservedDeployment() {
        QuarkusAppSnapshot snapshot =
                snapshot("dev", Map.of("%prod.quarkus.hibernate-orm.schema-management.strategy", "drop-and-create"));
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("drop-and-create");
        assertThat(snapshot.evaluatedConfigurationRules()).doesNotContain("QA-PROD-002");
        assertThat(snapshot.problems()).anyMatch(p -> p.ruleId().equals("QA-PROD-002"));
    }

    @Test
    void unprofiledActiveProductionSettingsUseNativeConfigResolution() {
        QuarkusAppSnapshot snapshot = snapshot(
                "prod",
                Map.of(
                        "quarkus.hibernate-orm.schema-management.strategy", "create",
                        "%dev.quarkus.hibernate-orm.schema-management.strategy", "drop-and-create"));
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("create");
        assertThat(snapshot.evaluatedConfigurationRules()).contains("QA-PROD-002");
    }

    @Test
    void developmentDefaultIsNotProjectedIntoProduction() {
        QuarkusAppSnapshot snapshot = snapshot(
                "dev",
                Map.of(
                        "quarkus.hibernate-orm.schema-management.strategy", "drop-and-create",
                        "%dev.quarkus.datasource.jdbc.url", "jdbc:h2:mem:dev"));
        assertThat(values(snapshot, "QA-PROD-002")).isEmpty();
        assertThat(values(snapshot, "QA-PROD-003")).isEmpty();
        assertThat(snapshot.evaluatedConfigurationRules()).doesNotContain("QA-PROD-002", "QA-PROD-003");
    }

    @Test
    void deprecatedExplicitStrategyWinsOverItsReplacement() {
        QuarkusAppSnapshot snapshot = snapshot(
                "prod",
                Map.of(
                        "quarkus.hibernate-orm.schema-management.strategy", "drop-and-create",
                        "quarkus.hibernate-orm.database.generation", "validate"));
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("validate");
        assertThat(values(snapshot, "QA-CFG-004")).containsExactly("legacy");
    }

    @Test
    void namedUnitsAndQuotedDotsAreNotLost() {
        QuarkusAppSnapshot snapshot = snapshot(
                "dev",
                Map.of(
                        "%prod.quarkus.hibernate-orm.\"orders.eu\".schema-management.strategy", "create",
                        "%prod.quarkus.hibernate-orm.billing.database.generation", "update",
                        "%prod.quarkus.hibernate-orm.billing.log.sql", "true"));
        assertThat(values(snapshot, "QA-PROD-002")).containsExactlyInAnyOrder("create", "update");
        assertThat(values(snapshot, "QA-CFG-002")).containsExactly("true");
        assertThat(values(snapshot, "QA-CFG-004")).containsExactly("legacy");
    }

    @Test
    void specificProfileOverridesCombinedProfileDeclaration() {
        QuarkusAppSnapshot snapshot = snapshot(
                "dev",
                Map.of(
                        "%prod,dev.quarkus.hibernate-orm.schema-management.strategy", "drop-and-create",
                        "%prod.quarkus.hibernate-orm.schema-management.strategy", "validate"));
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("validate");
    }

    @Test
    void productionSourcePriorityIsPreserved() {
        ConfigSource lower = new PropertiesConfigSource(
                Map.of("%prod.quarkus.hibernate-orm.schema-management.strategy", "drop-and-create"), "lower", 100);
        ConfigSource higher = new PropertiesConfigSource(
                Map.of("%prod.quarkus.hibernate-orm.schema-management.strategy", "validate"), "higher", 500);
        QuarkusAppSnapshot snapshot =
                collect(builder("dev").withSources(lower, higher).build(), EMPTY);
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("validate");
    }

    @Test
    void inactiveProductionExpressionsCannotResolveThroughDevelopmentValues() {
        QuarkusAppSnapshot snapshot = snapshot(
                "dev",
                Map.of(
                        "%prod.quarkus.hibernate-orm.schema-management.strategy", "${schema.mode}",
                        "schema.mode", "drop-and-create",
                        "%prod.quarkus.log.level", "DEBUG"));
        assertThat(values(snapshot, "QA-PROD-002")).isEmpty();
        assertThat(values(snapshot, "QA-CFG-003")).containsExactly("verbose");
        assertThat(snapshot.problems()).anyMatch(p -> p.ruleId().equals("QA-PROD-002"));
    }

    @Test
    void multipleActiveProfilesAreNotMistakenForTheProductionDeployment() {
        SmallRyeConfig config = builder("dev")
                .withProfiles(List.of("prod", "dev"))
                .withSources(new PropertiesConfigSource(
                        Map.of(
                                "%prod.quarkus.hibernate-orm.schema-management.strategy", "validate",
                                "%dev.quarkus.hibernate-orm.schema-management.strategy", "drop-and-create"),
                        "test",
                        1000))
                .build();
        QuarkusAppSnapshot snapshot = collect(config, EMPTY);
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("validate");
        assertThat(snapshot.evaluatedConfigurationRules()).doesNotContain("QA-PROD-002");
    }

    @Test
    void invalidNewStrategyIsNotAliasedToADestructiveAction() {
        QuarkusAppSnapshot snapshot =
                snapshot("prod", Map.of("quarkus.hibernate-orm.schema-management.strategy", "create-drop"));
        assertThat(values(snapshot, "QA-PROD-002")).isEmpty();
        assertThat(snapshot.evaluatedConfigurationRules()).doesNotContain("QA-PROD-002");
    }

    @Test
    void unrelatedAndAbsentLegacyPropertiesDoNotTriggerMigrationAdvice() {
        QuarkusAppSnapshot snapshot = snapshot(
                "prod",
                Map.of(
                        "vendor.database.generation", "update",
                        "quarkus.hibernate-orm.database.generation", "",
                        "quarkus.hibernate-orm.\"unit\".vendor.database.generation", "create"));
        assertThat(values(snapshot, "QA-CFG-004")).isEmpty();
    }

    @Test
    void databaseKindAloneNeverProvesVolatileStorage() {
        QuarkusAppSnapshot snapshot = snapshot(
                "prod",
                Map.of(
                        "quarkus.datasource.db-kind", "h2",
                        "quarkus.datasource.jdbc.url", "jdbc:h2:file:./durable"));
        assertThat(values(snapshot, "QA-PROD-003")).containsExactly("persistent-or-unclassified");
    }

    @Test
    void memoryUrlClassificationRecognizesStorageModeNotIncidentalSubstrings() {
        for (String url : List.of(
                "jdbc:h2:mem:test",
                "jdbc:hsqldb:mem:test",
                "jdbc:derby:memory:test",
                "jdbc:h2:tcp://localhost/mem:test",
                "jdbc:derby://localhost:1527/memory:test")) {
            assertThat(QuarkusAppSnapshotProviderImpl.inMemory(url)).as(url).isTrue();
        }
        for (String url : List.of(
                "jdbc:h2:file:./mem:data",
                "jdbc:postgresql://localhost/mem:database",
                "jdbc:h2:tcp://localhost/persistent",
                "jdbc:derby:./database",
                "jdbc:h2:tcp://mem:9000/persistent")) {
            assertThat(QuarkusAppSnapshotProviderImpl.inMemory(url)).as(url).isFalse();
        }
    }

    @Test
    void namedDatasourceMemoryEvidenceNeverExposesTheUrl() {
        QuarkusAppSnapshot snapshot = snapshot(
                "prod",
                Map.of("quarkus.datasource.\"orders\".jdbc.url", "jdbc:h2:mem:orders;PASSWORD=never-print-this"));
        assertThat(values(snapshot, "QA-PROD-003")).containsExactly("in-memory");
        assertThat(snapshot.toString()).doesNotContain("never-print-this", "jdbc:h2:");
    }

    @Test
    void compressionDistinguishesKnownDefaultAndExplicitOverride() {
        assertThat(values(snapshot("prod", Map.of()), "QA-WEB-001")).containsExactly("default-disabled");
        assertThat(values(snapshot("prod", Map.of("quarkus.http.enable-compression", "false")), "QA-WEB-001"))
                .containsExactly("disabled");
        assertThat(values(snapshot("prod", Map.of("quarkus.http.enable-compression", "true")), "QA-WEB-001"))
                .containsExactly("enabled");
    }

    @Test
    void shutdownUsesNativeQuarkusDurationsAndDistinguishesAbsence() {
        assertThat(values(snapshot("prod", Map.of()), "QA-WEB-004")).containsExactly("absent");
        assertThat(values(snapshot("prod", Map.of("quarkus.shutdown.timeout", "0")), "QA-WEB-002"))
                .containsExactly("zero");
        assertThat(values(snapshot("prod", Map.of("quarkus.shutdown.timeout", "10s")), "QA-WEB-004"))
                .containsExactly("positive");
    }

    @Test
    void malformedOneGroupPreservesOtherEvidenceAndSanitizesErrors() {
        QuarkusAppSnapshot snapshot = snapshot(
                "prod",
                Map.of(
                        "quarkus.shutdown.timeout", "secret-unparseable-duration",
                        "quarkus.hibernate-orm.schema-management.strategy", "drop"));
        assertThat(values(snapshot, "QA-PROD-002")).containsExactly("drop");
        assertThat(snapshot.evaluatedConfigurationRules()).doesNotContain("QA-WEB-002", "QA-WEB-004");
        assertThat(snapshot.toString()).doesNotContain("secret-unparseable-duration");
    }

    @Test
    void finiteClientTimeoutsHaveNoArbitraryFiveMinuteCeiling() {
        for (String timeout : List.of("15000", "30000", "300000", "300001", "3600000")) {
            QuarkusAppSnapshot snapshot = clientSnapshot(Map.of("quarkus.rest-client.remote.read-timeout", timeout));
            assertThat(values(snapshot, "QA-WEB-003")).as(timeout).isEmpty();
            assertThat(snapshot.evaluatedConfigurationRules()).contains("QA-WEB-003");
        }
    }

    @Test
    void clientSpecificNativeAliasesOverrideGlobalZero() {
        QuarkusAppSnapshot snapshot = clientSnapshot(Map.of(
                "quarkus.rest-client.connect-timeout", "0",
                "remote/mp-rest/connectTimeout", "500",
                "quarkus.rest-client.unregistered.read-timeout", "0"));
        assertThat(values(snapshot, "QA-WEB-003")).isEmpty();
    }

    @Test
    void nativeClientFqcnConfigKeyAndMpKeysAreRecognized() {
        for (String key : List.of(
                "quarkus.rest-client.\"" + CLIENT + "\".read-timeout",
                "quarkus.rest-client.remote.read-timeout",
                CLIENT + "/mp-rest/readTimeout",
                "remote/mp-rest/readTimeout")) {
            assertThat(values(clientSnapshot(Map.of(key, "0")), "QA-WEB-003"))
                    .as(key)
                    .containsExactly("read-zero");
        }
    }

    @Test
    void clientAliasResolutionHonorsSourcePriorityRatherThanTextOrdering() {
        SmallRyeConfigBuilder builder = clientBuilder();
        builder.withSources(
                new PropertiesConfigSource(
                        Map.of("quarkus.rest-client.\"" + CLIENT + "\".read-timeout", "0"), "lower", 100),
                new PropertiesConfigSource(Map.of("remote/mp-rest/readTimeout", "500"), "higher", 1000));
        assertThat(values(collect(builder.build(), clientMetadata()), "QA-WEB-003"))
                .isEmpty();
    }

    @Test
    void globalZeroNamesOnlyTheDisabledTimer() {
        assertThat(values(clientSnapshot(Map.of("quarkus.rest-client.connect-timeout", "0")), "QA-WEB-003"))
                .containsExactly("connect-zero");
    }

    @Test
    void absentClientCapabilityNeedsNoOptionalClientLookup() {
        QuarkusAppSnapshot snapshot =
                snapshot("prod", Map.of("quarkus.rest-client.unrelated.read-timeout", "unparseable"));
        assertThat(values(snapshot, "QA-WEB-003")).isEmpty();
        assertThat(snapshot.evaluatedConfigurationRules()).contains("QA-WEB-003");
    }

    @Test
    void absentOptionalOrmAndJdbcCapabilitiesDoNotTurnUnusedPropertiesIntoBehavior() {
        QuarkusAppMetadata withoutDatabase = new QuarkusAppMetadata(
                true, 0, 0, 0, 0, 0, false, false, false, List.of(), List.of(), List.of(), List.of());
        SmallRyeConfig config = builder("prod")
                .withSources(new PropertiesConfigSource(
                        Map.of(
                                "quarkus.hibernate-orm.schema-management.strategy", "drop-and-create",
                                "quarkus.hibernate-orm.log.sql", "true",
                                "quarkus.datasource.jdbc.url", "jdbc:h2:mem:unused"),
                        "test",
                        1000))
                .build();
        QuarkusAppSnapshot snapshot = collect(config, withoutDatabase);
        assertThat(values(snapshot, "QA-PROD-002")).isEmpty();
        assertThat(values(snapshot, "QA-PROD-003")).isEmpty();
        assertThat(values(snapshot, "QA-CFG-002")).isEmpty();
        assertThat(snapshot.evaluatedConfigurationRules()).contains("QA-PROD-002", "QA-PROD-003", "QA-CFG-002");
    }

    @Test
    void missingMetadataIsUnknownRatherThanAnEmptyRegistrationList() {
        QuarkusAppSnapshot snapshot = collect(builder("prod").build(), QuarkusAppMetadata.unavailable());
        assertThat(snapshot.evaluatedConfigurationRules()).doesNotContain("QA-WEB-003");
        assertThat(snapshot.problems()).anyMatch(p -> p.ruleId().equals("QA-WEB-003"));
    }

    @Test
    void propertyNameOverflowIsExplicitWithoutDiscardingDirectEvidence() {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i <= QuarkusAppSnapshotProviderImpl.MAX_PROPERTY_NAMES; i++) {
            properties.put("unrelated.property." + i, "value");
        }
        properties.put("quarkus.shutdown.timeout", "0");
        QuarkusAppSnapshot snapshot = snapshot("prod", properties);
        assertThat(snapshot.problems()).anyMatch(p -> p.message().contains("inspection limit"));
        assertThat(values(snapshot, "QA-WEB-002")).containsExactly("zero");
        assertThat(snapshot.evaluatedConfigurationRules()).isEmpty();
    }

    private static List<String> values(QuarkusAppSnapshot snapshot, String rule) {
        return snapshot.settings().stream()
                .filter(s -> s.ruleId().equals(rule))
                .map(QuarkusAppSnapshot.Setting::value)
                .toList();
    }

    private static QuarkusAppSnapshot snapshot(String profile, Map<String, String> properties) {
        return collect(
                builder(profile)
                        .withSources(new PropertiesConfigSource(properties, "test", 1000))
                        .build(),
                EMPTY);
    }

    private static SmallRyeConfigBuilder builder(String profile) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withProfile(profile)
                .withConverter(Duration.class, 200, new DurationConverter());
    }

    private static QuarkusAppSnapshot collect(SmallRyeConfig config, QuarkusAppMetadata metadata) {
        return new QuarkusAppSnapshotProviderImpl(config, () -> metadata).snapshot();
    }

    private static QuarkusAppMetadata clientMetadata() {
        return new QuarkusAppMetadata(
                true,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                true,
                List.of(),
                List.of(),
                List.of(new QuarkusAppMetadata.RestClient(CLIENT, "remote")),
                List.of());
    }

    private static SmallRyeConfigBuilder clientBuilder() {
        return new AbstractRestClientConfigBuilder() {
            @Override
            public List<RegisteredRestClient> getRestClients() {
                return List.of(new RegisteredRestClient(CLIENT, "RemoteClient", "remote"));
            }
        }.configBuilder(builder("prod"));
    }

    private static QuarkusAppSnapshot clientSnapshot(Map<String, String> properties) {
        return collect(
                clientBuilder()
                        .withSources(new PropertiesConfigSource(properties, "test", 1000))
                        .build(),
                clientMetadata());
    }
}
