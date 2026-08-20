package io.github.jdubois.bootui.autoconfigure.grpc;

import org.springframework.util.ClassUtils;

/**
 * Classpath detection for Spring Boot's gRPC support, kept deliberately free of any {@code io.grpc} import.
 *
 * <p>The gRPC panel's availability answer and its provider wiring must agree, and both are consulted from
 * always-loaded classes ({@code PanelsController}, the autoconfiguration). Referencing {@code io.grpc} types
 * from those classes would load an optional dependency in applications that do not use gRPC, so the detector
 * works purely on class names.</p>
 *
 * <p>Detection requires the gRPC API <em>and</em> Spring Boot's gRPC integration. {@code io.grpc} alone is not
 * enough: it arrives transitively in plenty of applications (OpenTelemetry OTLP exporters, cloud client
 * libraries) that have no gRPC server or managed channel at all, and showing them an empty gRPC registry
 * would be misleading.</p>
 */
public final class SpringGrpcPresence {

    /** The gRPC API type every server service implements. */
    public static final String BINDABLE_SERVICE = "io.grpc.BindableService";

    private static final String SERVER_PROPERTIES =
            "org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties";
    private static final String CLIENT_PROPERTIES =
            "org.springframework.boot.grpc.client.autoconfigure.GrpcClientProperties";

    private SpringGrpcPresence() {}

    /** Whether the gRPC API and Spring Boot's gRPC integration are both on the classpath. */
    public static boolean present(ClassLoader classLoader) {
        return grpcApiPresent(classLoader) && springIntegrationPresent(classLoader);
    }

    /** Why the gRPC panel is unavailable, or {@code null} when it is available. */
    public static String unavailableReason(ClassLoader classLoader) {
        if (!grpcApiPresent(classLoader)) {
            return "The gRPC API (io.grpc) is not on the classpath";
        }
        if (!springIntegrationPresent(classLoader)) {
            return "Spring Boot's gRPC server or client support is not on the classpath";
        }
        return null;
    }

    private static boolean grpcApiPresent(ClassLoader classLoader) {
        return ClassUtils.isPresent(BINDABLE_SERVICE, classLoader);
    }

    private static boolean springIntegrationPresent(ClassLoader classLoader) {
        return ClassUtils.isPresent(SERVER_PROPERTIES, classLoader)
                || ClassUtils.isPresent(CLIENT_PROPERTIES, classLoader);
    }
}
