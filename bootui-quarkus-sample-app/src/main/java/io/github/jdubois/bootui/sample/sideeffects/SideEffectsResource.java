package io.github.jdubois.bootui.sample.sideeffects;

import io.github.jdubois.bootui.sample.restclient.SampleApiClient;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Side Effects' seeds on Quarkus ({@code docs/PLAN-v2.md} §5.16), as on the Spring sample. M5-5a: {@code GET
 * /api/side-effects/java-version} runs on a worker thread and starts the JDK's {@code java -version}, so with the
 * BootUI agent it shows as a {@code java} process started by {@link JavaVersionReporter#version()}, and never its
 * arguments; the counterexample {@code GET /api/side-effects/runtime-version} starts none. M5-5b: {@code GET
 * /api/side-effects/sdk-call} connects to this application through an SDK's own socket ({@link LicenseSdkClient}), a
 * Network row <b>not captured by any panel</b>; the counterexample {@code GET /api/side-effects/rest-call} calls this
 * application through the recorded MicroProfile REST client.
 */
@Path("/api/side-effects")
@Produces(MediaType.APPLICATION_JSON)
public class SideEffectsResource {

    private final JavaVersionReporter reporter;
    private final LicenseSdkClient licenses;

    @RestClient
    SampleApiClient apiClient;

    public SideEffectsResource(JavaVersionReporter reporter, LicenseSdkClient licenses) {
        this.reporter = reporter;
        this.licenses = licenses;
    }

    @GET
    @Path("/java-version")
    public Map<String, String> javaVersion() {
        return Map.of("version", reporter.version());
    }

    @GET
    @Path("/runtime-version")
    public Map<String, String> runtimeVersion() {
        return Map.of("version", reporter.runtimeVersion());
    }

    @GET
    @Path("/sdk-call")
    public Map<String, String> sdkCall() {
        return Map.of("status", licenses.check(port()));
    }

    @GET
    @Path("/rest-call")
    public Map<String, String> restCall() {
        String body = apiClient.listProducts(1);
        return Map.of("body", body == null ? "" : body);
    }

    /** The port this application listens on: its test port under {@code @QuarkusTest}. */
    private static int port() {
        boolean test = io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST;
        return ConfigProvider.getConfig()
                .getOptionalValue(test ? "quarkus.http.test-port" : "quarkus.http.port", Integer.class)
                .orElse(test ? 8083 : 8082);
    }
}
