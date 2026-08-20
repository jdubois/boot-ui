package io.github.jdubois.bootui.autoconfigure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.GrpcController;
import io.github.jdubois.bootui.engine.grpc.GrpcReportService;
import io.github.jdubois.bootui.spi.GrpcMetadataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * An application without gRPC on the classpath must start normally, keep serving the panel contract, and
 * never class-load the {@code io.grpc}-touching provider.
 */
class GrpcBackendAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void startsWithoutGrpcAndReportsAnHonestUnavailableState() {
        runner.withClassLoader(new FilteredClassLoader("io.grpc")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(GrpcMetadataProvider.class);
            assertThat(context).hasSingleBean(GrpcReportService.class);
            assertThat(context).hasSingleBean(GrpcController.class);
            assertThat(context.getBean(GrpcReportService.class).report().available())
                    .isFalse();
            assertThat(context.getBean(GrpcReportService.class).report().unavailableReason())
                    .isNotBlank();
        });
    }

    @Test
    void registersTheMetadataProviderWhenGrpcIsOnTheClasspath() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GrpcMetadataProvider.class);
            assertThat(context).hasSingleBean(GrpcController.class);
        });
    }
}
