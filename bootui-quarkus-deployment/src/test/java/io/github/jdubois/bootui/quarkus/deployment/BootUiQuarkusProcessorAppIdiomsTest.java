package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.vertx.http.security.AuthorizationPolicy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.junit.jupiter.api.Test;

/** Shared endpoint/security helpers; application declaration collection has its own native-metadata tests. */
class BootUiQuarkusProcessorAppIdiomsTest {

    @Test
    void normalLaunchProducesNoAppMetadataAndTouchesNoInputs() {
        new BootUiQuarkusProcessor()
                .registerAppIdioms(
                        new LaunchModeBuildItem(LaunchMode.NORMAL, Optional.empty(), false, Optional.empty(), false),
                        null,
                        null,
                        null,
                        Optional.empty(),
                        Optional.empty(),
                        null,
                        resource -> {
                            throw new AssertionError("No resource should be produced in NORMAL");
                        });
    }

    private static Index indexOf(Class<?>... classes) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> c : classes) {
            indexer.indexClass(c);
        }
        return indexer.complete();
    }

    @Test
    void hasRestApiEndpointRecognizesCustomJaxRsHttpMethodAnnotations() throws IOException {
        Index index = indexOf(Purge.class, PurgeResource.class);

        assertThat(BootUiQuarkusProcessor.hasRestApiEndpoint(index)).isTrue();
    }

    @Test
    void hasRestApiEndpointResolvesCustomVerbAnnotationsFromTheCombinedIndex() throws IOException {
        Index applicationIndex = indexOf(DependencyPurgeResource.class);
        Index annotationIndex = indexOf(DependencyPurge.class);

        assertThat(BootUiQuarkusProcessor.hasRestApiEndpoint(applicationIndex, annotationIndex))
                .isTrue();
    }

    @Test
    void hasRestApiEndpointExcludesOutboundRestClientInterfaces() throws IOException {
        Index index = indexOf(RegisterRestClient.class, RestClientOnly.class);

        assertThat(BootUiQuarkusProcessor.hasRestApiEndpoint(index)).isFalse();
    }

    @Test
    void hasJdbcDatasourceUsesTheAgroalCapability() {
        assertThat(BootUiQuarkusProcessor.hasJdbcDatasource(new Capabilities(Set.of(Capability.AGROAL))))
                .isTrue();
        assertThat(BootUiQuarkusProcessor.hasJdbcDatasource(new Capabilities(Set.of())))
                .isFalse();
    }

    @Test
    void securityAnnotationScanIncludesQuarkusPermissionAndPolicyAnnotations() throws IOException {
        Index index = indexOf(QuarkusAuthorizationResource.class);

        assertThat(BootUiQuarkusProcessor.quarkusAuthorizationAnnotationCount(index))
                .isEqualTo(2);
        assertThat(BootUiQuarkusProcessor.isSecuredEndpoint(method(index, "permissionProtected")))
                .isTrue();
        assertThat(BootUiQuarkusProcessor.isSecuredEndpoint(method(index, "policyProtected")))
                .isTrue();
        assertThat(BootUiQuarkusProcessor.isSecuredEndpoint(method(index, "unprotected")))
                .isFalse();
    }

    private static MethodInfo method(Index index, String name) {
        return index
                .getClassByName(DotName.createSimple(QuarkusAuthorizationResource.class.getName()))
                .methods()
                .stream()
                .filter(method -> name.equals(method.name()))
                .findFirst()
                .orElseThrow();
    }

    // ---- Fixtures ----

    static class QuarkusAuthorizationResource {
        @GET
        @PermissionsAllowed("read")
        String permissionProtected() {
            return "permission";
        }

        @GET
        @AuthorizationPolicy(name = "custom")
        String policyProtected() {
            return "policy";
        }

        @GET
        String unprotected() {
            return "open";
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("PURGE")
    @interface Purge {}

    @Path("/widgets")
    static class PurgeResource {
        @Purge
        void purge() {}
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("PURGE")
    @interface DependencyPurge {}

    @Path("/widgets")
    static class DependencyPurgeResource {
        @DependencyPurge
        void purge() {}
    }

    @RegisterRestClient
    @Path("/outbound")
    static class RestClientOnly {
        @jakarta.ws.rs.GET
        void read() {}
    }
}
