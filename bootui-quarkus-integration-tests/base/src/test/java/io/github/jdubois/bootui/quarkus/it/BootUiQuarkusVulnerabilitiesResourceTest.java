package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Real-boot ground-truth checks for the Quarkus Vulnerabilities panel ({@code VulnerabilitiesResource} over
 * the build-time {@code QuarkusDependencyProvider} inventory + the {@code OsvVulnerabilityScanner}). The
 * shared conformance contract deliberately excludes {@code vulnerabilities} from its auto-asserted GETs, so
 * this IT is the panel's GET safety net as well as its scan proof.
 *
 * <p>Methods are ordered because the resource caches the last scan report across requests (an
 * {@code @ApplicationScoped} bean with a {@code volatile} field): the inventory GET must observe the
 * pre-scan {@code NOT_SCANNED} state first, then the user-initiated POST enriches it via the loopback OSV
 * stub ({@link OsvStubTestResource} — never the real OSV.dev), then the GET must return the cached scan.</p>
 */
@QuarkusTest
@WithTestResource(value = OsvStubTestResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class BootUiQuarkusVulnerabilitiesResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    private static List<String> packageNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.path("dependencies")
                .forEach(dependency -> names.add(dependency.path("packageName").asText()));
        return names;
    }

    @Test
    @Order(1)
    void getListsLocalInventoryWithoutScanning() {
        Response response = probe().get("/bootui/api/vulnerabilities");
        assertThat(response.status())
                .as("GET /bootui/api/vulnerabilities status")
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("GET content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("scan").path("status").asText())
                .as("a render must never scan — it reports the un-scanned inventory")
                .isEqualTo("NOT_SCANNED");
        assertThat(body.path("scanningEnabled").asBoolean())
                .as("osv-enabled defaults to true")
                .isTrue();
        assertThat(body.path("vulnerable").asInt())
                .as("no OSV call on render means nothing is marked vulnerable")
                .isZero();
        assertThat(body.path("scan").path("vulnerabilitiesFound").asInt()).isZero();

        assertThat(body.path("dependencies").isArray()
                        && body.path("dependencies").size() > 0)
                .as("the build-time application model yields a non-empty inventory")
                .isTrue();
        assertThat(packageNames(body))
                .as("the resolved runtime classpath contains Quarkus artifacts")
                .anyMatch(name -> name.startsWith("io.quarkus:"));
        body.path("dependencies").forEach(dependency -> {
            assertThat(dependency.path("source").asText())
                    .as("each dependency is labelled with its discovery source")
                    .isEqualTo("Quarkus application model");
            assertThat(dependency.path("vulnerabilities").size())
                    .as("no vulnerabilities before a scan")
                    .isZero();
            assertThat(dependency.path("highestSeverity").asText()).isEqualTo("NONE");
        });
    }

    @Test
    @Order(2)
    void postScanEnrichesInventoryFromOsv() {
        Response response = probe().request("POST", "/bootui/api/vulnerabilities/scan", java.util.Map.of(), "");
        assertThat(response.status()).as("POST /scan status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();

        JsonNode body = response.json();
        assertThat(body.path("scan").path("status").asText())
                .as("a successful scan against the stub is SCANNED (or PARTIAL if limits truncate)")
                .isIn("SCANNED", "PARTIAL");
        assertThat(body.path("scan").path("scanner").asText()).isEqualTo("OSV.dev");
        assertThat(body.path("scan").path("vulnerabilitiesFound").asInt())
                .as("the stub reports exactly one advisory")
                .isGreaterThanOrEqualTo(1);
        assertThat(body.path("vulnerable").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode critical = findFirstVulnerableDependency(body);
        assertThat(critical)
                .as("at least one dependency carries the stubbed advisory")
                .isNotNull();
        assertThat(critical.path("highestSeverity").asText()).isEqualTo("CRITICAL");
        JsonNode advisory = critical.path("vulnerabilities").get(0);
        assertThat(advisory.path("id").asText()).isEqualTo(OsvStubTestResource.ADVISORY_ID);
        assertThat(advisory.path("severity").asText()).isEqualTo("CRITICAL");
        assertThat(advisory.path("score").asDouble()).isEqualTo(9.8d);

        assertThat(severityCount(body, "CRITICAL"))
                .as("severity rollup counts the critical advisory")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    void getReturnsTheCachedScanAfterScanning() {
        Response response = probe().get("/bootui/api/vulnerabilities");
        assertThat(response.status()).isEqualTo(200);

        JsonNode body = response.json();
        assertThat(body.path("scan").path("status").asText())
                .as("GET after a scan returns the cached enriched report, not a fresh NOT_SCANNED inventory")
                .isIn("SCANNED", "PARTIAL");
        assertThat(body.path("scan").path("vulnerabilitiesFound").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void dismissingAVulnerabilityHidesItFromActiveCountsAndRestoringBringsItBack() {
        Map<String, String> jsonHeaders = Map.of("Content-Type", "application/json");
        JsonNode before = probe().get("/bootui/api/vulnerabilities").json();
        JsonNode vulnerableDependency = findFirstVulnerableDependency(before);
        assertThat(vulnerableDependency)
                .as("a prior scan (Order 2) must have already found the stubbed advisory")
                .isNotNull();
        String packageName = vulnerableDependency.path("packageName").asText();
        String dismissalKey = OsvStubTestResource.ADVISORY_ID + "::" + packageName;

        // Vulnerabilities has no dedicated dismiss endpoint -- it reuses the same shared
        // /bootui/api/dismissed-rules/{ruleId} resource every other advisor writes to.
        Response dismiss = probe().post("/bootui/api/dismissed-rules/" + dismissalKey, jsonHeaders);
        assertThat(dismiss.status()).as("POST dismiss status").isEqualTo(200);

        JsonNode afterDismiss = probe().get("/bootui/api/vulnerabilities").json();
        JsonNode dependencyAfterDismiss = findDependencyByPackageName(afterDismiss, packageName);
        assertThat(dependencyAfterDismiss)
                .as("the dismissed dependency stays in the inventory")
                .isNotNull();
        assertThat(dependencyAfterDismiss.path("vulnerabilityCount").asInt())
                .as("a dismissed vulnerability no longer counts toward the active count")
                .isZero();
        assertThat(dependencyAfterDismiss.path("highestSeverity").asText())
                .as("with its only vulnerability dismissed, the dependency reports no active severity")
                .isEqualTo("NONE");
        assertThat(dependencyAfterDismiss
                        .path("vulnerabilities")
                        .get(0)
                        .path("dismissed")
                        .asBoolean())
                .as("the vulnerability itself is still present, marked dismissed, so it can be restored")
                .isTrue();
        assertThat(afterDismiss.path("vulnerable").asInt())
                .as("the report-level vulnerable count excludes dismissed findings")
                .isZero();

        Response restore = probe().request("DELETE", "/bootui/api/dismissed-rules/" + dismissalKey, jsonHeaders, null);
        assertThat(restore.status()).as("DELETE restore status").isEqualTo(200);

        JsonNode afterRestore = probe().get("/bootui/api/vulnerabilities").json();
        JsonNode dependencyAfterRestore = findDependencyByPackageName(afterRestore, packageName);
        assertThat(dependencyAfterRestore.path("vulnerabilityCount").asInt())
                .as("restoring the vulnerability brings it back into the active count")
                .isEqualTo(1);
        assertThat(dependencyAfterRestore
                        .path("vulnerabilities")
                        .get(0)
                        .path("dismissed")
                        .asBoolean())
                .isFalse();
        assertThat(afterRestore.path("vulnerable").asInt()).isGreaterThanOrEqualTo(1);
    }

    private static JsonNode findFirstVulnerableDependency(JsonNode body) {
        for (JsonNode dependency : body.path("dependencies")) {
            if (dependency.path("vulnerabilities").size() > 0) {
                return dependency;
            }
        }
        return null;
    }

    private static JsonNode findDependencyByPackageName(JsonNode body, String packageName) {
        for (JsonNode dependency : body.path("dependencies")) {
            if (packageName.equals(dependency.path("packageName").asText())) {
                return dependency;
            }
        }
        return null;
    }

    private static int severityCount(JsonNode body, String severity) {
        for (JsonNode count : body.path("severityCounts")) {
            if (severity.equals(count.path("severity").asText())) {
                return count.path("count").asInt();
            }
        }
        return 0;
    }
}
