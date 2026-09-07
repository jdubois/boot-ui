package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.quarkus.vertx.http.security.AuthorizationPolicy;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

class SecurityDeclarationCaptureTest {
    @Test
    void applicationPathPrefixIsExplicitlyIncompleteWhileOrdinaryDeclarationsRemainKnown() throws Exception {
        for (Class<?> application : new Class<?>[] {RootApplication.class, PrefixedApplication.class}) {
            Indexer indexer = new Indexer();
            indexer.indexClass(application);
            indexer.indexClass(Server.class);
            indexer.indexClass(Purge.class);
            var index = indexer.complete();
            assertThat(SecurityDeclarationCapture.capture(index, index).incomplete())
                    .isEqualTo(application == PrefixedApplication.class);
        }
    }

    @Test
    void capturesServerOnlyAndMethodAnnotationsOverrideClassIncludingPermitAll() throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> type : new Class<?>[] {Server.class, Client.class, Unrelated.class, Purge.class}) {
            indexer.indexClass(type);
        }
        var index = indexer.complete();
        var capture = SecurityDeclarationCapture.capture(index, index);
        assertThat(capture.endpoints()).hasSize(4);
        assertThat(capture.endpoints()).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/advisor/open");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.PERMIT);
            assertThat(endpoint.document()).isTrue();
        });
        assertThat(capture.endpoints()).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/advisor/closed");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.RESTRICTED);
        });
        assertThat(capture.endpoints()).anySatisfy(endpoint -> {
            assertThat(endpoint.method()).isEqualTo("PURGE");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.PERMIT);
        });
        assertThat(capture.endpoints()).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/advisor/custom");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.UNKNOWN);
        });
    }

    @Path("/advisor")
    @DenyAll
    static class Server {
        @GET
        @Path("/open")
        @PermitAll
        @Produces("text/html")
        public String open() {
            return "document";
        }

        @GET
        @Path("/closed")
        public String closed() {
            return "closed";
        }

        @Purge
        @Path("/purge")
        @PermitAll
        public String purge() {
            return "purged";
        }

        @GET
        @Path("/custom")
        @AuthorizationPolicy(name = "business")
        public String custom() {
            return "custom";
        }
    }

    @RegisterRestClient
    @Path("/client")
    interface Client {
        @GET
        String get();
    }

    static class Unrelated {
        @GET
        public String get() {
            return "not a server resource";
        }
    }

    @HttpMethod("PURGE")
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Purge {}

    @ApplicationPath("/")
    static class RootApplication extends jakarta.ws.rs.core.Application {}

    @ApplicationPath("/api")
    static class PrefixedApplication extends jakarta.ws.rs.core.Application {}
}
