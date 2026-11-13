package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

// Keep the Quarkus 3.33 LTS test API, which predates QuarkusExtensionTest.
@SuppressWarnings("removal")
class BootUiPlatformCompatibilityBootstrapTest {

    @RegisterExtension
    static final QuarkusUnitTest application = new QuarkusUnitTest()
            .withApplicationRoot(archive -> archive.addClass(Resource.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=0\n"), "application.properties"))
            .addBuildChainCustomizer(builder -> builder.addBuildStep(context -> {
                        assertThat(context.consumeMulti(FeatureBuildItem.class))
                                .as("the standalone compatibility processor participates in real augmentation")
                                .anyMatch(feature -> feature.getName().equals("bootui"));
                    })
                    .consumes(FeatureBuildItem.class)
                    .produces(ValidationErrorBuildItem.class)
                    .build());

    @TestHTTPResource
    URI baseUri;

    @Test
    void aSupportedPlatformPassesTheDiscoveredBuildStepAndBootstraps() throws Exception {
        var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/compatibility-probe"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("started");
    }

    @Path("/compatibility-probe")
    public static class Resource {
        @GET
        public String get() {
            return "started";
        }
    }
}
