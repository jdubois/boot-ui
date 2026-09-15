package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MySqlObjectInstrumentationTests {

    @Test
    void usesExactThenSchemaThenGlobalRulesWithoutLikePatternsOrGlobalSpecificObjects() {
        var rules = new MySqlObjectInstrumentation(
                "shop",
                new MySqlQuery.Rows(
                        List.of(
                                rule("%", "%", "YES", "YES"),
                                rule("%", "global_specific", "NO", "NO"),
                                rule("shop", "%", "YES", "NO"),
                                rule("shop", "orders", "NO", "YES"),
                                rule("shop", "literal%", "NO", "NO")),
                        false,
                        null));
        assertThat(rules.forObject("orders").collection()).isEqualTo("DISABLED");
        assertThat(rules.forObject("orders").timing()).isEqualTo("DISABLED");
        assertThat(rules.forObject("other").collection()).isEqualTo("ENABLED");
        assertThat(rules.forObject("other").timing()).isEqualTo("DISABLED");
        assertThat(rules.forObject("literal_suffix").collection()).isEqualTo("ENABLED");
        assertThat(rules.forObject("literal%").collection()).isEqualTo("DISABLED");
        var global = new MySqlObjectInstrumentation(
                "another",
                new MySqlQuery.Rows(
                        List.of(rule("%", "%", "YES", "YES"), rule("%", "global_specific", "NO", "NO")), false, null));
        assertThat(global.forObject("global_specific").collection()).isEqualTo("ENABLED");
    }

    @Test
    void missingRulesDisableCollectionButUnreadOrTruncatedRulesRemainUnknown() {
        assertThat(new MySqlObjectInstrumentation("shop", new MySqlQuery.Rows(List.of(), false, null))
                        .forObject("orders")
                        .collection())
                .isEqualTo("DISABLED");
        assertThat(new MySqlObjectInstrumentation("shop", null)
                        .forObject("orders")
                        .collection())
                .isEqualTo("UNKNOWN");
        var limited = new MySqlObjectInstrumentation(
                "shop",
                new MySqlQuery.Rows(
                        List.of(rule("%", "%", "YES", "YES"), rule("shop", "exact", "YES", "YES")), true, null));
        assertThat(limited.forObject("orders").collection()).isEqualTo("UNKNOWN");
        assertThat(limited.forObject("exact").collection()).isEqualTo("ENABLED");
    }

    @Test
    void exactKeysAreCaseSensitiveAndConflictingEvidenceIsNotOverwritten() {
        var rules = new MySqlObjectInstrumentation(
                "Shop",
                new MySqlQuery.Rows(
                        List.of(
                                rule("Shop", "Orders", "YES", "YES"),
                                rule("Shop", "ambiguous", "YES", "YES"),
                                rule("Shop", "ambiguous", "NO", "NO")),
                        false,
                        null));
        assertThat(rules.forObject("Orders").collection()).isEqualTo("ENABLED");
        assertThat(rules.forObject("orders").collection()).isEqualTo("UNKNOWN");
        assertThat(rules.forObject("ambiguous").collection()).isEqualTo("UNKNOWN");
    }

    private static Map<String, String> rule(String schema, String object, String enabled, String timed) {
        return MySqlJdbcFixture.row("schema_name", schema, "object_name", object, "enabled", enabled, "timed", timed);
    }
}
