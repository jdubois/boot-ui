package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = {
            BootUiWebfluxSampleApplication.class,
            WebFluxCustomPathIntegrationTest.ExecutionThreadProbeController.class
        },
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.webflux.base-path=/host",
            "bootui.path=/dev-console/",
            "bootui.api-path=/internal/bootui-api/",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-custom-path-test-overrides.properties",
            "bootui.panels.copilot.enabled=false",
            "bootui.panels.heap-dump.read-only=true",
            "bootui.heap-dump.capture-enabled=false",
            "bootui.claude-code.enabled=OFF",
            "bootui.conformance.api-token=conformance-raw-secret-value"
        })
class WebFluxCustomPathIntegrationTest extends AbstractBootUiApiConformanceTest {

    private static final String UI_PATH = "/host/dev-console";
    private static final String API_PATH = "/host/internal/bootui-api";
    private static final Pattern ASSET = Pattern.compile("(?:src|href)=\"\\./(assets/[^\"]+)\"");

    @LocalServerPort
    int port;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe("http://localhost:" + port);
    }

    @Override
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    protected String expectedPanelsResource() {
        return "/io/github/jdubois/bootui/conformance/expected-panels-webflux.json";
    }

    @Override
    protected Runtime runtime() {
        return Runtime.SPRING_WEBFLUX;
    }

    @Override
    protected String uiPath() {
        return UI_PATH;
    }

    @Override
    protected String apiPath() {
        return API_PATH;
    }

    /** This deployment serves the application behind {@code spring.webflux.base-path=/host}. */
    @Override
    protected String applicationPath(String relativePath) {
        return "/host" + relativePath;
    }

    @Override
    protected Set<String> unsupportedReadContracts() {
        // PR #726 moves these rebuilt reactive handlers behind the configured API path. Keep C1
        // stacked only on #732 and consume that sibling fix when it lands instead of duplicating it.
        return Set.of("rest-client-trace", "security", "spring-security");
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
        assertThat(probe.get(API_PATH + "/rest-client-trace").status()).isEqualTo(200);
        assertThat(probe.get(API_PATH + "/spring-security").status()).isEqualTo(200);
        assertThat(probe.get(API_PATH + "/security").status()).isEqualTo(200);
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
        Response stream = probe.getStreaming(API_PATH + "/log-tail/stream");

        assertThat(threads.status()).isEqualTo(200);
        assertThat(threads.json().path("page").path("limit").asInt()).isEqualTo(1);
        assertThat(threads.json().path("threads").size()).isLessThanOrEqualTo(1);
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentType()).contains("text/event-stream");

        Map<String, String> headers = stateChangingHeaders(probe);
        Response logger = probe.request(
                "POST", API_PATH + "/loggers/io.github.jdubois.bootui.webfluxsample", headers, "{\"level\":\"INFO\"}");
        Response download = probe.request("POST", API_PATH + "/threads/download", headers, "");
        Response mcp = probe.request(
                "POST",
                API_PATH + "/mcp",
                headers,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        Response restClientClear = probe.request("POST", API_PATH + "/rest-client-trace/clear", headers, "");
        Response securityScan = probe.request("POST", API_PATH + "/security/scan", headers, "");

        assertThat(logger.status()).isEqualTo(200);
        assertThat(download.status()).isEqualTo(200);
        assertThat(mcp.status()).isEqualTo(200);
        assertThat(restClientClear.status()).isEqualTo(200);
        assertThat(securityScan.status()).isEqualTo(200);
    }

    @Test
    void runsCustomPathReadAndActionHandlersOffTheNettyEventLoop() {
        BootUiHttpProbe probe = probe();
        Response read = probe.get(API_PATH + "/execution-thread");
        Response action =
                probe.request("POST", API_PATH + "/execution-thread", stateChangingHeaders(probe), "{\"probe\":true}");

        assertThat(read.status()).isEqualTo(200);
        assertThat(action.status()).isEqualTo(200);
        assertThat(read.body()).isNotBlank().doesNotContain("http-nio");
        assertThat(action.body()).isNotBlank().doesNotContain("http-nio");
    }

    private static Map<String, String> stateChangingHeaders(BootUiHttpProbe probe) {
        probe.get(API_PATH + "/overview");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        return headers;
    }

    @RestController
    @RequestMapping("${bootui.api-path:/bootui/api}/execution-thread")
    static class ExecutionThreadProbeController {

        @GetMapping
        String read() {
            return Thread.currentThread().getName();
        }

        @PostMapping
        String action(@RequestBody String request) {
            return Thread.currentThread().getName();
        }
    }
}
