package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class QuarkusProducerProxyDefaultOptionalTest {
    @RegisterExtension
    static final QuarkusExtensionTest application = new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClass(Producers.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=0\n"), "application.properties"))
            .assertException(exception -> assertThat(exception)
                    .isInstanceOf(ExceptionInInitializerError.class)
                    .hasRootCauseInstanceOf(IncompatibleClassChangeError.class)
                    .hasStackTraceContaining("cannot inherit from final class java.util.Optional")
                    .hasStackTraceContaining("io.quarkus.arc.impl.ArcContainerImpl.<init>")
                    .hasStackTraceContaining("io.quarkus.runner.bootstrap.StartupActionImpl.run"));

    @Inject
    Instance<Optional<String>> value;

    @Test
    void defaultArcFailsDuringContainerStartupWhenLoadingAnOptionalClientProxy() {
        fail("The Optional client proxy must fail during container startup, before Instance.get");
    }

    @Singleton
    public static class Producers {
        @Produces
        @ApplicationScoped
        Optional<String> value() {
            return Optional.of("retained");
        }
    }
}
