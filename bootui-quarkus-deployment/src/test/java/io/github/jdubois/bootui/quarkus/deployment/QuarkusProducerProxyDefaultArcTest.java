package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.quarkusapp.QuarkusAppScanner;
import io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

// Keep the Quarkus 3.33 LTS test API, which predates QuarkusExtensionTest.
@SuppressWarnings("removal")
class QuarkusProducerProxyDefaultArcTest {
    @RegisterExtension
    static final QuarkusUnitTest application = new QuarkusUnitTest()
            .withApplicationRoot(archive -> archive.addClass(Producers.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=0\n"), "application.properties"));

    @Inject
    Clock clock;

    @Inject
    HttpClient client;

    @Inject
    AtomicInteger counter;

    @Inject
    QuarkusAppScanner scanner;

    @Test
    void consumesRealJdkClientProxiesWithoutAHighUnproxyableFinding() {
        assertThat(clock).isInstanceOf(ClientProxy.class);
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(client).isInstanceOf(ClientProxy.class);
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(counter).isInstanceOf(ClientProxy.class);
        // intValue delegates; final AtomicInteger.get does not, which is a separate proxy-use hazard.
        assertThat(counter.intValue()).isEqualTo(42);

        var metadata = QuarkusAppMetadataStore.load();
        assertThat(metadata.available()).isTrue();
        assertThat(metadata.unproxyableNormalScopedBeans()).isEmpty();
        assertThat(metadata.problems()).noneMatch(problem -> problem.ruleId().equals("QA-CDI-005"));
        var report = scanner.scan();
        assertThat(report.results()).noneMatch(result -> result.id().equals("QA-CDI-005"));
        assertThat(report.analysisErrors()).noneMatch(result -> result.id().equals("QA-CDI-005"));
    }

    @Singleton
    public static class Producers {
        @Produces
        @ApplicationScoped
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }

        @Produces
        @ApplicationScoped
        HttpClient client() {
            return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        }

        @Produces
        @ApplicationScoped
        AtomicInteger counter = new AtomicInteger(42);
    }
}
