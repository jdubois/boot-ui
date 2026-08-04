package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "server.servlet.context-path=/host",
            "bootui.path=/dev-console/",
            "bootui.api-path=/internal/bootui-api/",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-custom-path-test-overrides.properties"
        })
class BootUiCustomPathIntegrationTests {

    private static final String UI_PATH = "/host/dev-console";
    private static final String API_PATH = "/host/internal/bootui-api";
    private static final Pattern ASSET = Pattern.compile("(?:src|href)=\"\\./(assets/[^\"]+)\"");

    @LocalServerPort
    int port;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe("http://localhost:" + port);
    }

    @Test
    void servesTheShellAssetsAndApiUnderConfiguredPaths() {
        BootUiHttpProbe probe = probe();
        Response shell = probe.get(UI_PATH + "/");
        Response bareShell = probe.get(UI_PATH);
        Matcher asset = ASSET.matcher(shell.body());

        assertThat(shell.status()).isEqualTo(200);
        assertThat(bareShell.status()).isEqualTo(200);
        assertThat(shell.body())
                .contains("<base href=\"" + UI_PATH + "/\"")
                .contains("content=\"" + API_PATH + "\" name=\"bootui-api-path\"");
        assertThat(asset.find()).isTrue();
        assertThat(probe.get(UI_PATH + "/" + asset.group(1)).status()).isEqualTo(200);
        assertThat(probe.get(API_PATH + "/overview").status()).isEqualTo(200);
    }

    @Test
    void doesNotExposeThePackagedInternalMount() {
        BootUiHttpProbe probe = probe();

        assertThat(probe.get("/host/bootui/").status()).isEqualTo(404);
        assertThat(probe.get("/host/bootui").status()).isEqualTo(404);
        assertThat(probe.get("/host/bootui/api/overview").status()).isEqualTo(404);
    }

    @Test
    void preservesQueriesAndSupportsStreamingDownloadsAndWrites() {
        BootUiHttpProbe probe = probe();
        Response threads = probe.get(API_PATH + "/threads?offset=0&limit=1");
        Response stream = probe.getStreaming(API_PATH + "/activity/stream");

        assertThat(threads.status()).isEqualTo(200);
        assertThat(threads.json().path("page").path("limit").asInt()).isEqualTo(1);
        assertThat(threads.json().path("threads").size()).isLessThanOrEqualTo(1);
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentType()).contains("text/event-stream");

        Map<String, String> headers = stateChangingHeaders(probe, "application/json");
        Response logger = probe.request(
                "POST", API_PATH + "/loggers/io.github.jdubois.bootui.sample", headers, "{\"level\":\"INFO\"}");
        Response download = probe.request("POST", API_PATH + "/threads/download", headers, "");
        Response mcp = probe.request(
                "POST",
                API_PATH + "/mcp",
                headers,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");

        assertThat(logger.status()).isEqualTo(200);
        assertThat(download.status()).isEqualTo(200);
        assertThat(mcp.status()).isEqualTo(200);
    }

    @Test
    void exposesTheOtlpReceiverAtTheConfiguredApiPath() {
        BootUiHttpProbe probe = probe();
        Map<String, String> headers = stateChangingHeaders(probe, "application/x-protobuf");

        Response response = probe.request("POST", API_PATH + "/otlp/v1/traces", headers, "\n");

        assertThat(response.status()).isEqualTo(400);
    }

    private static Map<String, String> stateChangingHeaders(BootUiHttpProbe probe, String contentType) {
        probe.get(API_PATH + "/overview");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        return headers;
    }
}
