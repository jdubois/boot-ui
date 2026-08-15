package io.github.jdubois.bootui.engine.servicemap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceMapIdentitiesTests {

    @Test
    void httpOriginKeepsOnlySchemeHostAndNonDefaultPort() {
        assertThat(ServiceMapIdentities.httpOrigin("https://api.example.com/orders/42?token=abc#top"))
                .isEqualTo("https://api.example.com");
        assertThat(ServiceMapIdentities.httpOrigin("http://localhost:8081/orders"))
                .isEqualTo("http://localhost:8081");
        assertThat(ServiceMapIdentities.httpOrigin("https://api.example.com:443/orders"))
                .isEqualTo("https://api.example.com");
        assertThat(ServiceMapIdentities.httpOrigin("http://api.example.com:80/orders"))
                .isEqualTo("http://api.example.com");
    }

    @Test
    void httpOriginNeverCarriesUserInfoPathQueryOrFragment() {
        String origin = ServiceMapIdentities.httpOrigin("https://alice:sup3rs3cret@api.example.com/v1/users?ssn=123#x");

        assertThat(origin).isEqualTo("https://api.example.com");
        assertThat(origin).doesNotContain("alice").doesNotContain("sup3rs3cret").doesNotContain("ssn");
    }

    @Test
    void httpOriginIsAbsentRatherThanGuessedForUnusableUris() {
        assertThat(ServiceMapIdentities.httpOrigin(null)).isNull();
        assertThat(ServiceMapIdentities.httpOrigin("   ")).isNull();
        assertThat(ServiceMapIdentities.httpOrigin("/relative/path")).isNull();
        assertThat(ServiceMapIdentities.httpOrigin("not a uri at all")).isNull();
    }

    @Test
    void httpOriginNormalizesCaseSoOneDependencyIsOneNode() {
        assertThat(ServiceMapIdentities.httpOrigin("HTTPS://API.Example.COM/x"))
                .isEqualTo(ServiceMapIdentities.httpOrigin("https://api.example.com/y"));
    }

    @Test
    void jdbcTargetDropsDriverParameterTails() {
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:postgresql://localhost:5432/shop?password=%2A%2A%2A%2A%2A%2A"))
                .isEqualTo("jdbc:postgresql://localhost:5432/shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:sqlserver://db:1433;user=sa;password=hunter2"))
                .isEqualTo("jdbc:sqlserver://db:1433");
        assertThat(ServiceMapIdentities.jdbcTarget(null)).isNull();
        assertThat(ServiceMapIdentities.jdbcTarget("  ")).isNull();
        assertThat(ServiceMapIdentities.jdbcTarget("?only=params")).isNull();
    }

    @Test
    void jdbcTargetAlwaysStripsCommonCredentialForms() {
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:postgresql://alice:secret@db:5432/shop"))
                .isEqualTo("jdbc:postgresql://db:5432/shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:mysql://alice:secret@db/shop?user=alice&password=secret"))
                .isEqualTo("jdbc:mysql://db/shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:sqlserver://db:1433;user=alice;password=secret"))
                .isEqualTo("jdbc:sqlserver://db:1433");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:oracle:thin:alice/secret@//db:1521/shop"))
                .isEqualTo("jdbc:oracle:thin:@//db:1521/shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:oracle:oci:alice/secret@shop"))
                .isEqualTo("jdbc:oracle:oci:@shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:oracle:thin:@//db:1521/shop"))
                .isEqualTo("jdbc:oracle:thin:@//db:1521/shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:db2://db:50000/shop:password=p4ss;user=alice;"))
                .isEqualTo("jdbc:db2://db:50000/shop");
        assertThat(ServiceMapIdentities.jdbcTarget("jdbc:db2:shop:user=alice;password=p4ss;"))
                .isEqualTo("jdbc:db2:shop");
    }

    @Test
    void rabbitDestinationDescribesBothHalvesOrNeither() {
        assertThat(ServiceMapIdentities.rabbitDestination("orders", "created")).isEqualTo("orders → created");
        assertThat(ServiceMapIdentities.rabbitDestination("orders", null)).isEqualTo("orders");
        assertThat(ServiceMapIdentities.rabbitDestination("orders", " ")).isEqualTo("orders →  ");
        assertThat(ServiceMapIdentities.rabbitDestination("", "created")).isEqualTo("(default exchange) → created");
        assertThat(ServiceMapIdentities.rabbitDestination(null, null)).isNull();
        assertThat(ServiceMapIdentities.rabbitIdentity("a → b", "c"))
                .isNotEqualTo(ServiceMapIdentities.rabbitIdentity("a", "b → c"));
        assertThat(ServiceMapIdentities.rabbitIdentity("(default exchange)", "created"))
                .isNotEqualTo(ServiceMapIdentities.rabbitIdentity("", "created"));
        assertThat(ServiceMapIdentities.rabbitIdentity("orders", "created"))
                .isNotEqualTo(ServiceMapIdentities.rabbitIdentity(" orders ", "created"));
        assertThat(ServiceMapIdentities.rabbitIdentity("", "created"))
                .isNotEqualTo(ServiceMapIdentities.rabbitIdentity(" ", "created"));
    }

    @Test
    void completeSanitizedIdentitiesRemainAvailableForGrouping() {
        String longHost = "a".repeat(400) + ".example.com";

        String origin = ServiceMapIdentities.httpOrigin("https://" + longHost + "/x");

        assertThat(origin).isEqualTo("https://" + longHost);
        assertThat(ServiceMapIdentities.truncate(origin))
                .hasSize(ServiceMapIdentities.MAX_IDENTITY_LENGTH)
                .endsWith("…");
    }

    @Test
    void flowIdIsNullForABlankOrAbsentTraceId() {
        assertThat(ServiceMapIdentities.flowId(null)).isNull();
        assertThat(ServiceMapIdentities.flowId("")).isNull();
        assertThat(ServiceMapIdentities.flowId("   ")).isNull();
    }

    @Test
    void flowIdIsStableAndNeverEqualToTheRawTraceId() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        String first = ServiceMapIdentities.flowId(traceId);
        String second = ServiceMapIdentities.flowId(traceId);

        assertThat(first).isNotNull().isNotEqualTo(traceId).doesNotContain(traceId);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void flowIdDiffersForDifferentTraceIdsAndIgnoresIncidentalWhitespace() {
        String flowA = ServiceMapIdentities.flowId("trace-a");
        String flowB = ServiceMapIdentities.flowId("trace-b");
        String flowATrimmed = ServiceMapIdentities.flowId("  trace-a  ");

        assertThat(flowA).isNotEqualTo(flowB);
        assertThat(flowATrimmed).isEqualTo(flowA);
    }
}
