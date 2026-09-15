package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.MySqlReportContract;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real augmented named Agroal + Connector/J HTTP proof, alongside the application's ordinary H2 pool. */
@QuarkusTest
@TestProfile(BootUiQuarkusMySqlLiveTest.LiveProfile.class)
@QuarkusTestResource(value = MySqlLiveResource.class, restrictToAnnotatedClass = true)
class BootUiQuarkusMySqlLiveTest {

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");
    private static final String API = "/host/internal/bootui-api";

    public static final class LiveProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Declare the named bean during augmentation, independently of container runtime credentials.
            return Map.of(
                    "quarkus.datasource.mysql.db-kind",
                    "mysql",
                    "quarkus.datasource.mysql.devservices.enabled",
                    "false",
                    "quarkus.http.root-path",
                    "/host",
                    "bootui.path",
                    "/dev-console",
                    "bootui.api-path",
                    "/internal/bootui-api");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Inject
    @io.quarkus.agroal.DataSource("mysql")
    AgroalDataSource mysql;

    @Inject
    QuarkusExposurePolicy exposure;

    @Test
    void explicitReadPublishesNamedMysqlEvidenceAcrossRestAndMcpAndRestoresTheApplicationConnection() throws Exception {
        String origin = baseUrl.getProtocol() + "://" + baseUrl.getAuthority();
        BootUiHttpProbe probe = new BootUiHttpProbe(origin);
        long acquisitions = mysql.getMetrics().acquireCount();
        var initial = probe.get(API + "/mysql");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("status").asText()).isEqualTo("NOT_READ");
        assertThat(initial.json().path("readAt").isNull()).isTrue();

        JsonNode panel = null;
        for (JsonNode candidate : probe.get(API + "/panels").json().path("panels")) {
            if ("mysql".equals(candidate.path("id").asText())) {
                panel = candidate;
            }
        }
        assertThat(panel).isNotNull();
        assertThat(panel.path("available").asBoolean()).isTrue();
        var tools = rpc(probe, "\"method\":\"tools/list\"");
        assertThat(tools.path("result").path("tools").toString()).contains("\"get_mysql_report\"", "\"mysql_read\"");
        assertThat(probe.get(API + "/mysql").json()).isEqualTo(initial.json());
        for (String alias : List.of("/bootui/api/mysql", "/host/bootui/api/mysql")) {
            assertThat(probe.get(alias).status())
                    .as("no default API alias after custom-mount composition")
                    .isEqualTo(404);
        }
        assertThat(mysql.getMetrics().acquireCount())
                .as("cached GET, panel manifest and MCP tool discovery must not borrow a connection")
                .isEqualTo(acquisitions);
        MySqlReportContract.verify(probe, origin, API);

        var read = probe.post(API + "/mysql/read", JSON);
        assertThat(read.status()).isEqualTo(200);
        JsonNode report = read.json();
        assertThat(report.path("status").asText()).as(report.toString()).isIn("READ", "PARTIAL");
        assertThat(report.path("dataSourcesRead").asInt()).isEqualTo(1);
        JsonNode source = null;
        for (JsonNode candidate : report.path("dataSources")) {
            if ("mysql".equals(candidate.path("name").asText())) {
                source = candidate;
            }
        }
        assertThat(source)
                .as("the named datasource, not a positional or duplicated SQL Trace wrapper")
                .isNotNull();
        assertThat(source.path("schemaName").asText()).isEqualTo("bootui_mysql");
        assertThat(source.path("serverFlavor").asText()).containsIgnoringCase("mysql");
        assertThat(source.path("serverVersion").asText()).startsWith("8.4.");
        assertThat(source.path("vitalSigns")).isNotEmpty();
        assertThat(source.path("tables")).isNotEmpty();
        assertThat(source.path("tables").toString()).contains("fixture_products");
        HashSet<String> sections = new HashSet<>();
        source.path("sections")
                .forEach(section -> sections.add(section.path("id").asText()));
        assertThat(sections)
                .contains("sessions", "statements", "indexes", "tables", "innodb", "replication", "settings");
        assertThat(report.toString()).doesNotContain("disposable-fixture-only", "jdbc:mysql:", "LOCK_DATA");
        assertThat(probe.get(API + "/mysql").json()).isEqualTo(report);

        JsonNode cachedTool = rpc(probe, "\"method\":\"tools/call\",\"params\":{\"name\":\"get_mysql_report\"}")
                .path("result");
        assertThat(cachedTool.path("isError").asBoolean()).isFalse();
        assertThat(new ObjectMapper()
                        .readTree(cachedTool.path("content").get(0).path("text").asText()))
                .isEqualTo(report);
        JsonNode actionTool = rpc(probe, "\"method\":\"tools/call\",\"params\":{\"name\":\"mysql_read\"}")
                .path("result");
        assertThat(actionTool.path("isError").asBoolean()).isFalse();
        assertThat(new ObjectMapper()
                        .readTree(actionTool.path("content").get(0).path("text").asText()))
                .isEqualTo(probe.get(API + "/mysql").json());

        try (var connection = mysql.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.isReadOnly()).isFalse();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM fixture_products")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1))
                        .as("collector has not modified synthetic application data")
                        .isEqualTo(2);
            }
        }
        assertPolicyParityWithoutAcquisition(probe);
        long beforeInvalidation = mysql.getMetrics().acquireCount();
        String previousExposure = System.getProperty("bootui.expose-values");
        try {
            System.setProperty("bootui.expose-values", "METADATA_ONLY");
            var invalidated = probe.get(API + "/mysql");
            assertThat(invalidated.json().path("status").asText()).isEqualTo("NOT_READ");
            assertThat(invalidated.json().path("dataSources")).isEmpty();
            assertThat(mysql.getMetrics().acquireCount()).isEqualTo(beforeInvalidation);
        } finally {
            restoreProperty("bootui.expose-values", previousExposure);
        }
        assertAgroalDiscardsThePhysicalConnectionAfterRestorationFailure();
    }

    private void assertAgroalDiscardsThePhysicalConnectionAfterRestorationFailure() throws Exception {
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<String> originalId = new AtomicReference<>();
        DataSource faulting = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(mysql, method, args);
                    }
                    Connection connection = mysql.getConnection();
                    originalId.set(scalar(connection, "SELECT CONNECTION_ID()"));
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET SESSION max_execution_time=1234");
                    }
                    return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (connectionProxy, connectionMethod, connectionArgs) -> {
                                if (connectionMethod.getName().equals("abort")) {
                                    aborted.set(true);
                                }
                                Object result = invoke(connection, connectionMethod, connectionArgs);
                                if (connectionMethod.getName().equals("unwrap")
                                        && result instanceof Connection physical) {
                                    return Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {Connection.class},
                                            (physicalProxy, physicalMethod, physicalArgs) -> {
                                                if (physicalMethod.getName().equals("abort")) {
                                                    aborted.set(true);
                                                }
                                                return invoke(physical, physicalMethod, physicalArgs);
                                            });
                                }
                                if (!connectionMethod.getName().equals("createStatement")) {
                                    return result;
                                }
                                return Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {Statement.class},
                                        (statementProxy, statementMethod, statementArgs) -> {
                                            if (statementMethod.getName().equals("execute")
                                                    && "SET SESSION max_execution_time=1234".equals(statementArgs[0])) {
                                                throw new SQLException(
                                                        "Synthetic session restoration failure", "HY000");
                                            }
                                            return invoke(result, statementMethod, statementArgs);
                                        });
                            });
                });
        MySqlInsightService service = MySqlInsightService.using(
                () -> new DatabaseAdvisorDataSourceDiscovery(
                        List.of(new NamedDataSource("mysql-abort-test", faulting)), List.of()),
                exposure,
                Clock.systemUTC(),
                MySqlRowLimits.defaults());
        var report = service.read();
        assertThat(aborted)
                .as("restoration failure must abort rather than recycle contaminated state")
                .isTrue();
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("aborted and discarded"));
        // The fixture's one-connection Agroal pool makes this a physical eviction proof, not a lucky borrow
        // of another idle connection. No reflective pool eviction is used by the implementation.
        try (Connection next = mysql.getConnection()) {
            assertThat(scalar(next, "SELECT CONNECTION_ID()")).isNotEqualTo(originalId.get());
            assertThat(scalar(next, "SELECT @@session.transaction_read_only")).isEqualTo("0");
            assertThat(next.getAutoCommit()).isTrue();
            assertThat(scalar(next, "SELECT 1")).isEqualTo("1");
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private void assertPolicyParityWithoutAcquisition(BootUiHttpProbe probe) {
        long before = mysql.getMetrics().acquireCount();
        for (String key : java.util.List.of("bootui.panels.mysql.read-only", "bootui.read-only")) {
            String previous = System.getProperty(key);
            String reason = key.equals("bootui.read-only")
                    ? "BootUI is read-only via bootui.read-only=true"
                    : "Panel is read-only via bootui.panels.mysql.read-only=true";
            try {
                System.setProperty(key, "true");
                assertThat(probe.get(API + "/mysql").status()).isEqualTo(200);
                var blocked = probe.post(API + "/mysql/read", JSON);
                assertThat(blocked.status()).isEqualTo(403);
                assertThat(blocked.json().path("reason").asText()).isEqualTo(reason);
                JsonNode tool = rpc(probe, "\"method\":\"tools/call\",\"params\":{\"name\":\"mysql_read\"}")
                        .path("result");
                assertThat(tool.path("isError").asBoolean()).isTrue();
                assertThat(tool.path("content").get(0).path("text").asText()).isEqualTo(reason);
                JsonNode cached = rpc(probe, "\"method\":\"tools/call\",\"params\":{\"name\":\"get_mysql_report\"}")
                        .path("result");
                assertThat(cached.path("isError").asBoolean()).isFalse();
            } finally {
                restoreProperty(key, previous);
            }
        }
        String key = "bootui.panels.mysql.enabled";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "false");
            assertThat(probe.get(API + "/mysql").status()).isEqualTo(403);
            assertThat(probe.post(API + "/mysql/read", JSON).status()).isEqualTo(403);
            for (String name : java.util.List.of("get_mysql_report", "mysql_read")) {
                JsonNode tool = rpc(probe, "\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\"}")
                        .path("result");
                assertThat(tool.path("isError").asBoolean()).isTrue();
                assertThat(tool.path("content").get(0).path("text").asText())
                        .isEqualTo("Panel is disabled via bootui.panels.mysql.enabled=false");
            }
        } finally {
            restoreProperty(key, previous);
        }
        var crossSite = probe.post(
                API + "/mysql/read", Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        assertThat(crossSite.status()).isEqualTo(403);
        assertThat(crossSite.json().path("error").asText())
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
        assertThat(mysql.getMetrics().acquireCount())
                .as("all policy rejections precede datasource acquisition")
                .isEqualTo(before);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    private static JsonNode rpc(BootUiHttpProbe probe, String request) {
        var response = probe.request("POST", API + "/mcp", JSON, "{\"jsonrpc\":\"2.0\",\"id\":1," + request + "}");
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }
}
