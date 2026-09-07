package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.github.jdubois.bootui.quarkus.security.QuarkusSecuritySnapshotProviderImpl;
import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.security.spi.AdditionalSecuredMethodsBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.jboss.jandex.DotName;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SecurityAdditionalMethodsNativeTest {
    @RegisterExtension
    static final QuarkusUnitTest application = new QuarkusUnitTest()
            .withApplicationRoot(archive -> archive.addClass(Resource.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=0\n"), "application.properties"))
            .addBuildChainCustomizer(builder -> builder.addBuildStep(context -> {
                        var index =
                                context.consume(CombinedIndexBuildItem.class).getIndex();
                        var method =
                                index.getClassByName(DotName.createSimple(Resource.class.getName())).methods().stream()
                                        .filter(candidate -> candidate.name().equals("restricted"))
                                        .findFirst()
                                        .orElseThrow();
                        context.produce(new AdditionalSecuredMethodsBuildItem(List.of(method)));
                    })
                    .consumes(CombinedIndexBuildItem.class)
                    .produces(AdditionalSecuredMethodsBuildItem.class)
                    .build());

    @TestHTTPResource
    URI baseUri;

    @Inject
    Config config;

    @Inject
    QuarkusSecurityScanner scanner;

    @Test
    void actualQuarkusSecurityCheckDeniesWithoutTurningRawAnnotationsIntoPublicEvidence() throws Exception {
        var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(get(client, "/additional-security/restricted")).isIn(401, 403);
        assertThat(get(client, "/additional-security/ordinary")).isEqualTo(200);
        var snapshot = new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
        assertThat(snapshot.evidence().endpoints()).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/additional-security/restricted");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.UNKNOWN);
        });
        assertThat(snapshot.evidence().endpoints()).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/additional-security/ordinary");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.UNANNOTATED);
        });
        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .noneMatch(result ->
                        List.of("QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004").contains(result.id()));
        assertThat(report.analysisErrors()).isEmpty();
    }

    private int get(HttpClient client, String path) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(baseUri.resolve(path))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Path("/additional-security")
    public static class Resource {
        @GET
        @Path("/restricted")
        public String restricted() {
            return "must be denied by the native SecurityCheck";
        }

        @GET
        @Path("/ordinary")
        public String ordinary() {
            return "public";
        }
    }
}
