package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import bootui.packaged.threadfactory.QuarkusThreadFactoryProbe;
import io.quarkus.test.QuarkusProdModeTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ThreadFactoryFastJarTest {
    @RegisterExtension
    static final QuarkusProdModeTest application = new QuarkusProdModeTest()
            .withApplicationRoot(jar -> jar.addClass(QuarkusThreadFactoryProbe.class))
            .setApplicationName("thread-factory-fast-jar")
            .overrideConfigKey("quarkus.package.main-class", QuarkusThreadFactoryProbe.class.getName())
            .setRun(true)
            .setExpectExit(true);

    @Test
    void scansApplicationJarResourcesUsingArchUnitsEmbeddedReader() {
        assertThat(application.getExitCode()).isZero();
        assertThat(application.getStartupConsoleOutput())
                .contains("THREAD_FACTORY_PACKAGED_OK", ".jar!/", "/quarkus-app/app/");
    }
}
