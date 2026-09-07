package io.github.jdubois.bootui.spi;

import java.util.List;

/** Application-owned declarations resolved during augmentation, without framework types or live bean access. */
public record QuarkusAppMetadata(
        boolean available,
        int beanCount,
        int endpointCount,
        int configPropertyCount,
        int configMappingCount,
        int scheduledDeclarationCount,
        boolean hibernateOrmSupported,
        boolean jdbcDatasourceSupported,
        boolean restClientSupported,
        List<SharedField> sharedFields,
        List<String> synchronizedVirtualThreadMethods,
        List<RestClient> restClients,
        List<QuarkusAppEvidenceProblem> problems) {

    public QuarkusAppMetadata {
        sharedFields = List.copyOf(sharedFields);
        synchronizedVirtualThreadMethods = List.copyOf(synchronizedVirtualThreadMethods);
        restClients = List.copyOf(restClients);
        problems = List.copyOf(problems);
    }

    public static QuarkusAppMetadata unavailable() {
        return new QuarkusAppMetadata(
                false, 0, 0, 0, 0, 0, false, false, false, List.of(), List.of(), List.of(), List.of());
    }

    public record SharedField(String className, String fieldName, String scope, boolean resource) {}

    public record RestClient(String className, String configKey) {}
}
