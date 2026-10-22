package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcUrlSettingsTests {

    private static final Set<String> NAMES = DriverSettingCatalog.NAMES;

    @Test
    void readsAllowListedSettingsFromAPostgresUrl() {
        assertThat(JdbcUrlSettings.parse(
                        "jdbc:postgresql://db.example:5432/orders?reWriteBatchedInserts=true&ApplicationName=orders",
                        NAMES))
                .containsExactly(Map.entry("reWriteBatchedInserts", "true"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://h1:3306,h2:3306/db?rewriteBatchedStatements=true",
                "jdbc:mysql:replication://primary:3306,replica:3306/db?rewriteBatchedStatements=true",
                "jdbc:mysql:loadbalance://h1,h2/db?rewriteBatchedStatements=true",
                "jdbc:mariadb://h1:3306/db?rewriteBatchedStatements=true",
                "jdbc:mysql://address=(host=h1)(port=3306)/db?rewriteBatchedStatements=true",
                "JDBC:MYSQL://h1/db?rewriteBatchedStatements=true",
            })
    void readsTheVendorSpecificUrlShapes(String url) {
        assertThat(JdbcUrlSettings.parse(url, NAMES)).containsExactly(Map.entry("rewriteBatchedStatements", "true"));
    }

    @Test
    void neverRetainsCredentialsOrAnyOtherParameter() {
        Map<String, String> settings = JdbcUrlSettings.parse(
                "jdbc:postgresql://user:s3cret@h/db?user=app&password=hunter2&sslpassword=x&reWriteBatchedInserts=true",
                NAMES);
        assertThat(settings).containsOnlyKeys("reWriteBatchedInserts");
        assertThat(settings.toString()).doesNotContain("app", "hunter2", "s3cret", "sslpassword");
    }

    @Test
    void ignoresCredentialsEvenWhenTheCallerAllowsNoNames() {
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?user=app&password=hunter2", Set.of()))
                .isEmpty();
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?user=app&password=hunter2", NAMES))
                .isEmpty();
    }

    @Test
    void matchesNamesIgnoringCaseAndReportsTheCanonicalSpelling() {
        assertThat(JdbcUrlSettings.parse("jdbc:mysql://h/db?REWRITEBATCHEDSTATEMENTS=true&UseAffectedRows=true", NAMES))
                .containsOnly(Map.entry("rewriteBatchedStatements", "true"), Map.entry("useAffectedRows", "true"));
    }

    @Test
    void theLastOccurrenceWins() {
        assertThat(JdbcUrlSettings.parse(
                        "jdbc:postgresql://h/db?reWriteBatchedInserts=true&reWriteBatchedInserts=false", NAMES))
                .containsExactly(Map.entry("reWriteBatchedInserts", "false"));
    }

    @Test
    void decodesPercentEscapedValuesButFlagsAnEscapedNameBecauseNeitherDriverDecodesNames() {
        JdbcUrlSettings.Parsed parsed =
                JdbcUrlSettings.parseDetailed("jdbc:postgresql://h/db?reWriteBatched%49nserts=%74rue", NAMES);

        assertThat(parsed.values()).containsExactly(Map.entry("reWriteBatchedInserts", "true"));
        assertThat(parsed.irregularSpelling()).containsExactly("reWriteBatchedInserts");
        assertThat(JdbcUrlSettings.parseDetailed("jdbc:postgresql://h/db?reWriteBatchedInserts=%74rue", NAMES)
                        .irregularSpelling())
                .isEmpty();
    }

    @Test
    void keepsTheRawTextOfAMalformedEscape() {
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?reWriteBatchedInserts=%zz", NAMES))
                .containsExactly(Map.entry("reWriteBatchedInserts", "%zz"));
    }

    @Test
    void treatsAParameterWithoutAValueAsAnEmptyValue() {
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?reWriteBatchedInserts", NAMES))
                .containsExactly(Map.entry("reWriteBatchedInserts", ""));
    }

    @Test
    void capsValues() {
        String longValue = "x".repeat(200);
        Map<String, String> settings =
                JdbcUrlSettings.parse("jdbc:postgresql://h/db?reWriteBatchedInserts=" + longValue, NAMES);
        assertThat(settings.get("reWriteBatchedInserts")).hasSize(DriverSettingCatalog.MAX_VALUE_LENGTH);
    }

    @Test
    void neitherTrimsAValueNorTreatsAHashAsAFragmentBecauseTheDriversDoNot() {
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?reWriteBatchedInserts=true%20", NAMES))
                .containsExactly(Map.entry("reWriteBatchedInserts", "true "));
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?reWriteBatchedInserts=true#fragment", NAMES))
                .containsExactly(Map.entry("reWriteBatchedInserts", "true#fragment"));
    }

    @Test
    void reportsANonCanonicalSpellingSoADriverThatReadsExactNamesIsNotMisled() {
        JdbcUrlSettings.Parsed parsed =
                JdbcUrlSettings.parseDetailed("jdbc:postgresql://h/db?rewritebatchedinserts=true", NAMES);

        assertThat(parsed.values()).containsExactly(Map.entry("reWriteBatchedInserts", "true"));
        assertThat(parsed.irregularSpelling()).containsExactly("reWriteBatchedInserts");
        assertThat(JdbcUrlSettings.parseDetailed("jdbc:postgresql://h/db?reWriteBatchedInserts=true", NAMES)
                        .irregularSpelling())
                .isEmpty();
    }

    @Test
    void reportsConnectorJHostLevelPropertiesInsteadOfMissingThem() {
        JdbcUrlSettings.Parsed parsed = JdbcUrlSettings.parseDetailed(
                "jdbc:mysql://address=(host=h1)(port=3306)(useAffectedRows=true),address=(host=h2)(port=3306)/db",
                NAMES);

        assertThat(parsed.values()).isEmpty();
        assertThat(parsed.hostScoped()).containsExactly(Map.entry("useAffectedRows", "true"));
    }

    @Test
    void reportsTheKeyValueHostFormOfConnectorJToo() {
        JdbcUrlSettings.Parsed parsed = JdbcUrlSettings.parseDetailed(
                "jdbc:mysql://(host=h,port=3306,useAffectedRows=true,rewriteBatchedStatements=yes)/db", NAMES);

        assertThat(parsed.values()).isEmpty();
        assertThat(parsed.hostScoped())
                .containsOnly(Map.entry("useAffectedRows", "true"), Map.entry("rewriteBatchedStatements", "yes"));
    }

    @Test
    void anExpansionMarkerIsRetainedButNeverACredential() {
        assertThat(JdbcUrlSettings.parse("jdbc:postgresql://h/db?service=prod&password=x", NAMES))
                .containsExactly(Map.entry("service", "prod"));
        assertThat(JdbcUrlSettings.parse("jdbc:mysql://h/db?useConfigs=maxPerformance&password=x", NAMES))
                .containsExactly(Map.entry("useConfigs", "maxPerformance"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "   ",
                "not a url",
                "postgresql://h/db?reWriteBatchedInserts=true",
                "jdbc:postgresql://h/db",
                "jdbc:postgresql://h/db?",
                "jdbc:postgresql://h/db?&&==&=",
                "jdbc:postgresql://h/db?reWriteBatchedInserts=true=false",
            })
    void aMalformedOrSettingFreeUrlNeverThrows(String url) {
        Map<String, String> settings = JdbcUrlSettings.parse(url, NAMES);
        assertThat(settings).isNotNull();
        assertThat(settings.keySet()).isSubsetOf(NAMES);
    }

    @Test
    void anInvalidUrlYieldsNothing() {
        assertThat(JdbcUrlSettings.parse("not a url", NAMES)).isEmpty();
        assertThat(JdbcUrlSettings.parse("postgresql://h/db?reWriteBatchedInserts=true", NAMES))
                .isEmpty();
        assertThat(JdbcUrlSettings.parse(null, NAMES)).isEmpty();
    }
}
