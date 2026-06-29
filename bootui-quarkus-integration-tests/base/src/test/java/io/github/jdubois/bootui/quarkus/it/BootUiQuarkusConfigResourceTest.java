package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Configuration and Profile Diff panels ({@code ConfigResource} /
 * {@code ProfileDiffResource} over the engine {@code ConfigService} backed by {@code QuarkusConfigProvider}).
 * The masking/paging/grouping logic is unit-tested in the engine; this test pins the Quarkus-specific
 * behavior end to end: SmallRye config is enumerated and masked, secret-looking keys never reach the
 * browser, the read-only configuration has no overrides, and {@code %test.}-profiled keys surface as a
 * Profile Diff source. The panel manifest reports config read-only (no override write path) and profile-diff
 * action-incapable, so it must stay writable-shaped.
 */
@QuarkusTest
class BootUiQuarkusConfigResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void configListMasksSecretsAndCarriesPagingShape() {
        Response response = probe().get("/bootui/api/config");
        assertThat(response.status()).as("GET /bootui/api/config status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/config content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("activeProfiles").isArray())
                .as("$.activeProfiles is array")
                .isTrue();
        assertThat(body.path("sources").isArray() && body.path("sources").size() > 0)
                .as("$.sources lists the SmallRye config sources")
                .isTrue();
        assertThat(body.path("propertySuggestions").isArray())
                .as("$.propertySuggestions is an array (empty on Quarkus, no metadata catalog)")
                .isTrue();
        assertThat(body.path("page").isObject()).as("$.page metadata object").isTrue();
        assertThat(body.path("overrideCount").asInt(-1))
                .as("no override source on Quarkus, so overrideCount is 0")
                .isZero();

        assertThat(value(body, "bootui.it.config.greeting"))
                .as("plain property value is visible")
                .isEqualTo("hello-config");
        assertThat(value(body, "bootui.it.config.api-token"))
                .as("secret-looking property must be masked before serialization")
                .isEqualTo("******");
        assertThat(masked(body, "bootui.it.config.api-token"))
                .as("masked secret flagged true")
                .isTrue();
    }

    @Test
    void profileDiffGroupsActiveProfileKeys() {
        Response response = probe().get("/bootui/api/profile-diff");
        assertThat(response.status()).as("GET /bootui/api/profile-diff status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/profile-diff content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        java.util.List<String> profiles = new java.util.ArrayList<>();
        body.path("activeProfiles").forEach(p -> profiles.add(p.asText()));
        assertThat(profiles).as("the test profile is active").contains("test");

        boolean found = false;
        for (JsonNode source : body.path("profileSources")) {
            if (!"test".equals(source.path("profile").asText())) {
                continue;
            }
            for (JsonNode prop : source.path("properties")) {
                if ("bootui.it.config.profile-only".equals(prop.path("name").asText())) {
                    assertThat(prop.path("value").asText()).isEqualTo("test-profile-value");
                    found = true;
                }
            }
        }
        assertThat(found)
                .as("%%test-profiled key surfaces as a Profile Diff source")
                .isTrue();
    }

    @Test
    void panelsManifestReportsConfigReadOnlyAndProfileDiffWritable() {
        JsonNode panels = probe().get("/bootui/api/panels").json().path("panels");

        JsonNode config = panel(panels, "config");
        assertThat(config.path("available").asBoolean()).as("config available").isTrue();
        assertThat(config.path("readOnly").asBoolean())
                .as("config read-only on Quarkus")
                .isTrue();
        assertThat(config.path("readOnlyReason").asText())
                .as("read-only reason names the missing overrides")
                .contains("overrides");

        JsonNode profileDiff = panel(panels, "profile-diff");
        assertThat(profileDiff.path("available").asBoolean())
                .as("profile-diff available")
                .isTrue();
        assertThat(profileDiff.path("readOnly").asBoolean())
                .as("profile-diff is read-only:false (not action-capable)")
                .isFalse();
    }

    private static JsonNode panel(JsonNode panels, String id) {
        for (JsonNode panel : panels) {
            if (id.equals(panel.path("id").asText())) {
                return panel;
            }
        }
        throw new AssertionError("panel not found: " + id);
    }

    private static String value(JsonNode body, String name) {
        return property(body, name).path("value").asText();
    }

    private static boolean masked(JsonNode body, String name) {
        return property(body, name).path("masked").asBoolean();
    }

    private static JsonNode property(JsonNode body, String name) {
        for (JsonNode prop : body.path("properties")) {
            if (name.equals(prop.path("name").asText())) {
                return prop;
            }
        }
        throw new AssertionError("property not found: " + name);
    }
}
