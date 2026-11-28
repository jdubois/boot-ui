package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class QuarkusProducerProxyDefaultRejectionTest {
    @RegisterExtension
    static final QuarkusExtensionTest application = new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClasses(Producers.class, SealedValue.class, Value.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=0\n"), "application.properties"))
            .assertException(exception ->
                    assertThat(exception).hasStackTraceContaining("must not have a return type that is sealed"));

    @Inject
    Instance<SealedValue> value;

    @Test
    void defaultArcRejectsASealedProducerEvenForProgrammaticLookup() {
        fail("Default ArC must reject the sealed producer during augmentation, not defer the error to lookup");
    }

    @Singleton
    public static class Producers {
        @Produces
        @ApplicationScoped
        SealedValue value() {
            throw new AssertionError("Rejected producer must not be invoked");
        }
    }

    public static sealed class SealedValue permits Value {}

    public static final class Value extends SealedValue {}
}
