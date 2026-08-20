package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.quarkus.grpc.QuarkusGrpcMetadataProvider;
import io.github.jdubois.bootui.spi.GrpcMetadataProvider;
import io.grpc.BindableService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/**
 * gRPC-panel wiring for the Quarkus adapter: produces the {@link GrpcMetadataProvider} the engine
 * {@code GrpcReportService} reads, backed by the application's {@code @GrpcService} beans and its
 * {@code quarkus.grpc.*} configuration.
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when the {@code GRPC} capability is absent.</strong> The extension runtime jar is
 * Jandex-indexed, and Arc treats a {@code @Produces} method as bean-defining, so this producer would otherwise
 * be discovered in every application and Arc would try to resolve its {@link BindableService} parameter type
 * even where {@code quarkus-grpc} is absent, linking an {@code io.grpc} type that must stay absent (R2). This
 * mirrors {@link BootUiCacheProducer} exactly.</p>
 *
 * <p>When the capability is absent there is no {@code GrpcMetadataProvider} bean, so the always-produced
 * {@code GrpcReportService} (see {@link BootUiEngineProducer}) resolves an unsatisfied {@code Instance} to a
 * {@code null} provider and renders the panel unavailable.</p>
 */
public class BootUiGrpcProducer {

    @Produces
    @Singleton
    public GrpcMetadataProvider grpcMetadataProvider(Instance<BindableService> services, Config config) {
        return new QuarkusGrpcMetadataProvider(services, config);
    }
}
