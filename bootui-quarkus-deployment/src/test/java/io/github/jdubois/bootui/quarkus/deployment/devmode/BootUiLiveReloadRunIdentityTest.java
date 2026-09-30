package io.github.jdubois.bootui.quarkus.deployment.devmode;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusDevModeTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A Quarkus live reload starts a new application run, so {@code /overview} reports a new {@code run} with a new id and
 * the next ordinal, while the BootUI instance, whose jars survive the reload, keeps its id ({@code docs/PLAN-v2.md}
 * §5.1).
 */
class BootUiLiveReloadRunIdentityTest {

    private static final Pattern RUN = Pattern.compile(
            "\"run\"\\s*:\\s*\\{[^}]*?\"instanceId\"\\s*:\\s*\"([0-9a-f]+)\"[^}]*?\"runId\"\\s*:\\s*\"([0-9a-f]+)\"[^}]*?"
                    + "\"ordinal\"\\s*:\\s*(\\d+)");

    /** A free port picked up front, since dev mode does not publish the port it bound for port 0. */
    private static final int PORT = freePort();

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClass(LiveReloadProbeResource.class)
                    .addAsResource(new StringAsset("quarkus.http.port=" + PORT + "\n"), "application.properties"));

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void aLiveReloadStartsANewRunOfTheSameInstance() throws Exception {
        assertThat(get("/live-reload-probe")).isEqualTo("before");
        Matcher before = run();

        TEST.modifySourceFile(LiveReloadProbeResource.class, source -> source.replace("\"before\"", "\"after\""));
        assertThat(get("/live-reload-probe")).as("the edit was reloaded").isEqualTo("after");
        Matcher after = run();

        assertThat(after.group(1)).as("the BootUI instance survives the reload").isEqualTo(before.group(1));
        assertThat(after.group(2)).as("the reload is a new run").isNotEqualTo(before.group(2));
        assertThat(Integer.parseInt(after.group(3)))
                .as("the run ordinal advances")
                .isGreaterThan(Integer.parseInt(before.group(3)));
    }

    private Matcher run() throws Exception {
        String overview = get("/bootui/api/overview");
        Matcher matcher = RUN.matcher(overview);
        assertThat(matcher.find()).as("/overview carries a run: " + overview).isTrue();
        return matcher;
    }

    private String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + path))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
