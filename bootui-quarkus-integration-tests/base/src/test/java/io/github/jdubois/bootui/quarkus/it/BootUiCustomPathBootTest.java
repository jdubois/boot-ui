package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiCustomPathBootTest.CustomPathProfile.class)
class BootUiCustomPathBootTest {

    private static final Pattern ASSET = Pattern.compile("(?:src|href)=\"\\./(assets/[^\"]+)\"");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        String serverRoot = baseUrl.getProtocol() + "://" + baseUrl.getHost() + ":" + baseUrl.getPort();
        return new BootUiHttpProbe(serverRoot);
    }

    @Test
    void shellAndApiComposeWithTheQuarkusRootPath() {
        BootUiHttpProbe probe = probe();
        Response shell = probe.get("/host/dev-console/");
        Response bareShell = probe.get("/host/dev-console");
        Response api = probe.get("/host/internal/bootui-api/overview");
        Matcher asset = ASSET.matcher(shell.body());

        assertThat(shell.status()).isEqualTo(200);
        assertThat(bareShell.status()).isEqualTo(200);
        assertThat(shell.body())
                .contains("<base href=\"/host/dev-console/\" />")
                .contains("content=\"/host/internal/bootui-api\" name=\"bootui-api-path\"");
        assertThat(asset.find()).isTrue();
        assertThat(probe.get("/host/dev-console/" + asset.group(1)).status()).isEqualTo(200);
        assertThat(api.status()).isEqualTo(200);
        assertThat(api.json().path("applicationName").isTextual()).isTrue();
    }

    @Test
    void reroutePreservesQueryParameters() {
        Response response = probe().get("/host/internal/bootui-api/mappings/flat?offset=0&limit=1");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("page").path("limit").asInt()).isEqualTo(1);
        assertThat(response.json().path("mappings").size()).isLessThanOrEqualTo(1);
    }

    @Test
    void oldInternalMountIsNotExposed() {
        assertThat(probe().get("/host/bootui/").status()).isEqualTo(404);
        assertThat(probe().get("/host/bootui").status()).isEqualTo(404);
        assertThat(probe().get("/host/bootui/api/overview").status()).isEqualTo(404);
    }

    @Test
    void securityAndStreamingEndpointsUseTheConfiguredApiPath() {
        BootUiHttpProbe probe = probe();
        Response rejected = probe.post(
                "/host/internal/bootui-api/overview",
                Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        Response stream = probe.getStreaming("/host/internal/bootui-api/activity/stream");
        Response logger = probe.request(
                "POST",
                "/host/internal/bootui-api/loggers/bootui.custom.path.test",
                Map.of("Content-Type", "application/json"),
                "{\"level\":\"INFO\"}");
        Response download = probe.post("/host/internal/bootui-api/threads/download", Map.of());

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.json().path("error").asText())
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentType()).contains("text/event-stream");
        assertThat(logger.status()).isEqualTo(200);
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.header("content-disposition")).contains("thread-dump.txt");
    }

    public static final class CustomPathProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.root-path", "/host",
                    "bootui.path", "/dev-console/",
                    "bootui.api-path", "/internal/bootui-api/");
        }
    }
}
