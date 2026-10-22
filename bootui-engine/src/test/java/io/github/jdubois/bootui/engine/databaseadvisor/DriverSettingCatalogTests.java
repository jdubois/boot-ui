package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.databaseadvisor.DriverSettingCatalog.Driver;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DriverSettingCatalogTests {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "PostgreSQL JDBC Driver|PGJDBC",
                "postgresql jdbc driver|PGJDBC",
                "PostgreSQL JDBC Driver NG|UNKNOWN",
                "MySQL Connector/J|MYSQL_CONNECTOR_J",
                "MySQL Connector Java|MYSQL_CONNECTOR_J",
                "MariaDB Connector/J|MARIADB_CONNECTOR_J",
                "Acme MySQL Driver|UNKNOWN",
                "H2 JDBC Driver|UNKNOWN",
            })
    void identifiesTheDriverFamilyFromItsSelfReportedName(String name, Driver expected) {
        assertThat(Driver.of(name)).isEqualTo(expected);
    }

    @Test
    void anUnreadableDriverNameIsUnknown() {
        assertThat(Driver.of(null)).isEqualTo(Driver.UNKNOWN);
    }

    @Test
    void pgjdbcDocumentsUrlPrecedenceAndNoOtherDriverIsAssumedToHaveOne() {
        assertThat(DriverSettingCatalog.precedence(Driver.PGJDBC)).isEqualTo(DriverSettingCatalog.Precedence.URL_WINS);
        assertThat(DriverSettingCatalog.precedence(Driver.MYSQL_CONNECTOR_J))
                .isEqualTo(DriverSettingCatalog.Precedence.UNDEFINED);
        assertThat(DriverSettingCatalog.precedence(Driver.MARIADB_CONNECTOR_J))
                .isEqualTo(DriverSettingCatalog.Precedence.UNDEFINED);
        assertThat(DriverSettingCatalog.precedence(Driver.UNKNOWN))
                .isEqualTo(DriverSettingCatalog.Precedence.UNDEFINED);
    }

    @Test
    void pgjdbcReadsBooleansLikeBooleanParseBoolean() {
        assertThat(DriverSettingCatalog.asBoolean(Driver.PGJDBC, "TRUE")).isTrue();
        assertThat(DriverSettingCatalog.asBoolean(Driver.PGJDBC, " true ")).isFalse();
        assertThat(DriverSettingCatalog.asBoolean(Driver.MYSQL_CONNECTOR_J, " true "))
                .isNull();
        assertThat(DriverSettingCatalog.asBoolean(Driver.PGJDBC, "yes")).isFalse();
        assertThat(DriverSettingCatalog.asBoolean(Driver.PGJDBC, "1")).isFalse();
        assertThat(DriverSettingCatalog.asBoolean(Driver.PGJDBC, "")).isFalse();
    }

    @Test
    void connectorJAcceptsTrueFalseYesNoAndNothingElse() {
        assertThat(DriverSettingCatalog.asBoolean(Driver.MYSQL_CONNECTOR_J, "yes"))
                .isTrue();
        assertThat(DriverSettingCatalog.asBoolean(Driver.MYSQL_CONNECTOR_J, "No"))
                .isFalse();
        assertThat(DriverSettingCatalog.asBoolean(Driver.MYSQL_CONNECTOR_J, "TRUE"))
                .isTrue();
        assertThat(DriverSettingCatalog.asBoolean(Driver.MYSQL_CONNECTOR_J, "1"))
                .isNull();
        assertThat(DriverSettingCatalog.asBoolean(Driver.MYSQL_CONNECTOR_J, "")).isNull();
        assertThat(DriverSettingCatalog.asBoolean(Driver.UNKNOWN, "yes")).isNull();
    }

    @Test
    void allowListedKeepsOnlyKnownNamesUnderTheSpellingThePoolUsed() {
        Properties properties = new Properties();
        properties.setProperty("user", "app");
        properties.setProperty("password", "hunter2");
        properties.setProperty("REWRITEBATCHEDSTATEMENTS", " true ");
        properties.put("useAffectedRows", Boolean.TRUE);
        properties.put(7, "ignored");
        Map<String, String> retained = DriverSettingCatalog.allowListed(properties);
        assertThat(retained)
                .containsOnly(Map.entry("REWRITEBATCHEDSTATEMENTS", " true "), Map.entry("useAffectedRows", "true"));
        assertThat(retained.toString()).doesNotContain("hunter2", "app");
    }

    @Test
    void allowListedCapsValuesAndToleratesNullsAndHostileValues() {
        Map<Object, Object> properties = new HashMap<>();
        properties.put("reWriteBatchedInserts", "x".repeat(500));
        properties.put("rewriteBatchedStatements", null);
        properties.put("useAffectedRows", new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        });
        Map<String, String> retained = DriverSettingCatalog.allowListed(properties);
        assertThat(retained).containsOnlyKeys("reWriteBatchedInserts");
        assertThat(retained.get("reWriteBatchedInserts")).hasSize(DriverSettingCatalog.MAX_VALUE_LENGTH);
        assertThat(DriverSettingCatalog.allowListed(null)).isEmpty();
    }

    @Test
    void mergePrecedenceFollowsTheDriver() {
        Map<String, String> url = Map.of("reWriteBatchedInserts", "true");
        Map<String, String> pool = Map.of("reWriteBatchedInserts", "false");

        DriverSetting pgjdbc =
                DriverEvidenceReader.merge(Driver.PGJDBC, url, pool).get("reWriteBatchedInserts");
        assertThat(pgjdbc).isEqualTo(new DriverSetting("true", DriverSetting.Source.URL, DriverSetting.State.KNOWN));

        DriverSetting connectorJ =
                DriverEvidenceReader.merge(Driver.MYSQL_CONNECTOR_J, url, pool).get("reWriteBatchedInserts");
        assertThat(connectorJ.conflicting()).isTrue();

        DriverSetting unknownDriver =
                DriverEvidenceReader.merge(Driver.UNKNOWN, url, pool).get("reWriteBatchedInserts");
        assertThat(unknownDriver.conflicting()).isTrue();
    }

    @Test
    void neitherDriverHonorsADifferentCaseSoItIsUnsettledForBoth() {
        DriverEvidence pgjdbc = DriverEvidenceReader.read(
                "PostgreSQL JDBC Driver", "42", "jdbc:postgresql://h/db?rewritebatchedinserts=true", Map.of());
        assertThat(pgjdbc.settings().get("reWriteBatchedInserts").conflicting()).isTrue();

        DriverEvidence pgjdbcPool = DriverEvidenceReader.read(
                "PostgreSQL JDBC Driver", "42", "jdbc:postgresql://h/db", Map.of("REWRITEBATCHEDINSERTS", "true"));
        assertThat(pgjdbcPool.settings().get("reWriteBatchedInserts").conflicting())
                .isTrue();

        // Connector/J 9.7 declares these properties case-sensitive in PropertyKey, so it ignores other spellings too.
        DriverEvidence connectorJ =
                DriverEvidenceReader.read("MySQL Connector/J", "9", "jdbc:mysql://h/db?USEAFFECTEDROWS=true", Map.of());
        assertThat(connectorJ.settings().get("useAffectedRows").conflicting()).isTrue();

        DriverEvidence canonical =
                DriverEvidenceReader.read("MySQL Connector/J", "9", "jdbc:mysql://h/db?useAffectedRows=true", Map.of());
        assertThat(canonical.settings().get("useAffectedRows").conflicting()).isFalse();
    }

    @Test
    void aHostLevelConnectorJPropertyIsUnsettledAndAPoolSpelledTwoWaysWithTwoValuesIsToo() {
        DriverEvidence host = DriverEvidenceReader.read(
                "MySQL Connector/J", "9", "jdbc:mysql://address=(host=h)(useAffectedRows=true)/db", Map.of());
        assertThat(host.settings().get("useAffectedRows").conflicting()).isTrue();

        DriverEvidence twoSpellings = DriverEvidenceReader.read(
                "MySQL Connector/J",
                "9",
                "jdbc:mysql://h/db",
                Map.of("useAffectedRows", "true", "USEAFFECTEDROWS", "false"));
        assertThat(twoSpellings.settings().get("useAffectedRows").conflicting()).isTrue();
    }

    @Test
    void aPropertiesTransformIsAnOverrideMarkerOnlyForConnectorJ() {
        assertThat(DriverEvidenceReader.read(
                                "MySQL Connector/J",
                                "9",
                                "jdbc:mysql://h/db?propertiesTransform=acme.Transform",
                                Map.of())
                        .overrideConfigured())
                .isTrue();
        assertThat(DriverEvidenceReader.read(
                                "MySQL Connector/J", "9", "jdbc:mysql://h/db?useConfigs=maxPerformance", Map.of())
                        .overrideConfigured())
                .isFalse();
        assertThat(DriverEvidenceReader.read(
                                "PostgreSQL JDBC Driver",
                                "42",
                                "jdbc:postgresql://h/db?propertiesTransform=x",
                                Map.of())
                        .overrideConfigured())
                .isFalse();
    }

    @Test
    void unreadablePoolPropertiesAreNotTheSameAsNoneConfigured() {
        assertThat(DriverEvidenceReader.read("MySQL Connector/J", "9", "jdbc:mysql://h/db", null)
                        .poolSettingsKnown())
                .isFalse();
        assertThat(DriverEvidenceReader.read("MySQL Connector/J", "9", "jdbc:mysql://h/db", Map.of())
                        .poolSettingsKnown())
                .isTrue();
    }

    @Test
    void anExpansionMarkerIsConfiguredOnlyForTheDriverThatReadsIt() {
        assertThat(DriverEvidenceReader.read(
                                "PostgreSQL JDBC Driver", "42", "jdbc:postgresql://h/db?service=x", Map.of())
                        .expansionConfigured())
                .isTrue();
        assertThat(DriverEvidenceReader.read(
                                "MySQL Connector/J", "9", "jdbc:mysql://h/db?useConfigs=maxPerformance", Map.of())
                        .expansionConfigured())
                .isTrue();
        assertThat(DriverEvidenceReader.read(
                                "PostgreSQL JDBC Driver", "42", "jdbc:postgresql://h/db?useConfigs=x", Map.of())
                        .expansionConfigured())
                .isFalse();
    }

    @Test
    void agreeingValuesAreKnownOnEveryDriverAndASingleSourceKeepsItsOrigin() {
        Map<String, String> both = Map.of("useAffectedRows", "TRUE");
        assertThat(DriverEvidenceReader.merge(Driver.MYSQL_CONNECTOR_J, Map.of("useAffectedRows", "true"), both)
                        .get("useAffectedRows")
                        .state())
                .isEqualTo(DriverSetting.State.KNOWN);
        assertThat(DriverEvidenceReader.merge(Driver.MYSQL_CONNECTOR_J, Map.of(), both)
                        .get("useAffectedRows")
                        .source())
                .isEqualTo(DriverSetting.Source.POOL);
        assertThat(DriverEvidenceReader.merge(Driver.MYSQL_CONNECTOR_J, both, Map.of())
                        .get("useAffectedRows")
                        .source())
                .isEqualTo(DriverSetting.Source.URL);
    }

    @Test
    void theEvidenceReaderRetainsNoCredentialFromEitherSource() {
        Properties pool = new Properties();
        pool.setProperty("password", "hunter2");
        pool.setProperty("reWriteBatchedInserts", "true");
        DriverEvidence evidence = DriverEvidenceReader.read(
                "PostgreSQL JDBC Driver",
                "42.7",
                "jdbc:postgresql://u:p@h/db?user=app&password=hunter2&reWriteBatchedInserts=true",
                Map.copyOf(DriverSettingCatalog.allowListed(pool)));
        assertThat(evidence.settings()).containsOnlyKeys("reWriteBatchedInserts");
        assertThat(evidence.toString()).doesNotContain("hunter2", "app", "u:p");
        assertThat(evidence.urlSettingsKnown()).isTrue();
    }

    @Test
    void aMissingUrlIsNotEvidenceThatNothingIsConfigured() {
        assertThat(DriverEvidenceReader.read("MySQL Connector/J", "9", null, Map.of())
                        .urlSettingsKnown())
                .isFalse();
        assertThat(DriverEvidenceReader.read("MySQL Connector/J", "9", "  ", Map.of())
                        .urlSettingsKnown())
                .isFalse();
    }
}
