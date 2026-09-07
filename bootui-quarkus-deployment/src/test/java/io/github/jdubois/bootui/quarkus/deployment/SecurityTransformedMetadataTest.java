package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.github.jdubois.bootui.quarkus.security.QuarkusSecuritySnapshotProviderImpl;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.processor.AnnotationsTransformer;
import io.quarkus.arc.processor.BeanProcessor;
import io.quarkus.arc.processor.InterceptorBindingRegistrar;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.spi.AdditionalSecuredMethodsBuildItem;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.annotation.Priority;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Singleton;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.junit.jupiter.api.Test;

class SecurityTransformedMetadataTest {
    @Test
    void ordinaryUntransformedProcessorMetadataRemainsKnown() throws Exception {
        var fixture = bootstrap(OrdinaryResource.class, null);
        var values = capture(fixture, List.of(), LaunchMode.TEST);
        assertThat(values.get("bootui.internal.sec.incomplete")).isEqualTo("false");
        assertThat(values.get("bootui.internal.sec.endpoint.0.access")).isEqualTo("UNANNOTATED");
        assertThat(scanner(values).scan().results())
                .anyMatch(result -> result.id().equals("QS-AUTHZ-004"));
    }

    @Test
    void unrelatedNativeTransformationDoesNotMakeOrdinaryEndpointsUnknown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transformer = AnnotationsTransformer.appliedToMethod()
                .whenMethod(method -> method.name().equals("get"))
                .transform(context -> {
                    calls.incrementAndGet();
                    context.transform().add(Deprecated.class).done();
                });
        var fixture = bootstrap(OrdinaryResource.class, transformer);
        assertThat(calls.get()).isPositive();
        int before = calls.get();
        var values = capture(fixture, List.of(), LaunchMode.TEST);
        assertThat(calls).hasValue(before);
        assertThat(values.get("bootui.internal.sec.incomplete")).isEqualTo("false");
        assertThat(values.get("bootui.internal.sec.endpoint.0.access")).isEqualTo("UNANNOTATED");
    }

    @Test
    void materializedArcBindingDivergenceIsUnknownWithoutReplayingTheTransformer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transformer = AnnotationsTransformer.appliedToMethod()
                .whenMethod(method -> method.name().equals("get"))
                .transform(context -> {
                    calls.incrementAndGet();
                    context.transform().add(DenyAll.class).done();
                });
        var fixture = bootstrap(OrdinaryResource.class, transformer);
        MethodInfo method = method(fixture.index(), OrdinaryResource.class);
        assertThat(method.declaredAnnotation(DotName.createSimple(DenyAll.class.getName())))
                .isNull();
        var bindings = fixture.validation()
                .getContext()
                .beans()
                .withBeanClass(OrdinaryResource.class)
                .firstResult()
                .orElseThrow()
                .getInterceptedMethodsBindings();
        assertThat(bindings.get(method))
                .anyMatch(annotation -> annotation.name().toString().equals(DenyAll.class.getName()));

        int before = calls.get();
        assertThat(before).isPositive();
        var values = capture(fixture, List.of(), LaunchMode.TEST);
        assertThat(calls).hasValue(before);
        assertUnknownWithoutRawFindings(values);
        assertThat(calls).hasValue(before);
    }

    @Test
    void materializedRemovalCannotLeaveRawRestrictionAsProof() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var transformer = AnnotationsTransformer.appliedToMethod()
                .whenMethod(method -> method.name().equals("get"))
                .transform(context -> {
                    calls.incrementAndGet();
                    context.transform()
                            .remove(annotation ->
                                    annotation.name().equals(DotName.createSimple(DenyAll.class.getName())))
                            .done();
                });
        var fixture = bootstrap(RestrictedResource.class, transformer);
        MethodInfo method = method(fixture.index(), RestrictedResource.class);
        assertThat(method.declaredAnnotation(DotName.createSimple(DenyAll.class.getName())))
                .isNotNull();
        assertThat(fixture.validation()
                        .getContext()
                        .beans()
                        .withBeanClass(RestrictedResource.class)
                        .firstResult()
                        .orElseThrow()
                        .getInterceptedMethodsBindings())
                .doesNotContainKey(method);
        int before = calls.get();
        var values = capture(fixture, List.of(), LaunchMode.TEST);
        assertThat(calls).hasValue(before);
        assertUnknownWithoutRawFindings(values);
        assertThat(values.get("bootui.internal.sec.secured-endpoints")).isEqualTo("0");
    }

    @Test
    void additionalNativeSecuredMethodsAreExplicitlyUnknownInsteadOfRawPublicDeclarations() throws Exception {
        var fixture = bootstrap(OrdinaryResource.class, null);
        var declaration =
                new AdditionalSecuredMethodsBuildItem(List.of(method(fixture.index(), OrdinaryResource.class)));
        assertUnknownWithoutRawFindings(capture(fixture, List.of(declaration), LaunchMode.TEST));
    }

    @Test
    void ordinaryMaterializedRestrictionRemainsSupported() throws Exception {
        var fixture = bootstrap(RestrictedResource.class, null);
        var values = capture(fixture, List.of(), LaunchMode.TEST);
        assertThat(values.get("bootui.internal.sec.incomplete")).isEqualTo("false");
        assertThat(values.get("bootui.internal.sec.endpoint.0.access")).isEqualTo("RESTRICTED");
    }

    @Test
    void ordinaryMethodOverrideDoesNotConfuseCombinedArcBindingsWithEffectiveAuthorization() throws Exception {
        var fixture = bootstrap(OverriddenResource.class, null);
        var bindings = fixture.validation()
                .getContext()
                .beans()
                .withBeanClass(OverriddenResource.class)
                .firstResult()
                .orElseThrow()
                .getInterceptedMethodsBindings()
                .get(method(fixture.index(), OverriddenResource.class));
        assertThat(bindings).anyMatch(annotation -> annotation.name().toString().equals(DenyAll.class.getName()));
        assertThat(bindings).anyMatch(annotation -> annotation.name().toString().equals(PermitAll.class.getName()));
        var values = capture(fixture, List.of(), LaunchMode.TEST);
        assertThat(values.get("bootui.internal.sec.incomplete")).isEqualTo("false");
        assertThat(values.get("bootui.internal.sec.endpoint.0.access")).isEqualTo("PERMIT");
    }

    @Test
    void normalModeRemainsDarkWithoutReadingValidationMetadata() throws Exception {
        assertThat(capture(new Fixture(index(OrdinaryResource.class), null), List.of(), LaunchMode.NORMAL))
                .isEmpty();
    }

    private static void assertUnknownWithoutRawFindings(Map<String, String> values) {
        assertThat(values.get("bootui.internal.sec.incomplete")).isEqualTo("true");
        assertThat(values.get("bootui.internal.sec.endpoint.0.access")).isEqualTo("UNKNOWN");
        var report = scanner(values).scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .noneMatch(result ->
                        List.of("QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004").contains(result.id()));
        assertThat(report.results()).anyMatch(result -> result.id().equals("QS-AUTH-002"));
        assertThat(report.analysisErrors()).isEmpty();
    }

    private static Fixture bootstrap(Class<?> resource, AnnotationsTransformer transformer) throws Exception {
        Index index = index(resource);
        var builder = BeanProcessor.builder()
                .setApplicationIndex(index)
                .setImmutableBeanArchiveIndex(index)
                .setComputingBeanArchiveIndex(index)
                .setRemoveUnusedBeans(false)
                .setOutput(ignored -> {})
                .addInterceptorBindingRegistrar(new InterceptorBindingRegistrar() {
                    @Override
                    public List<InterceptorBinding> getAdditionalBindings() {
                        return List.of(InterceptorBinding.of(DenyAll.class), InterceptorBinding.of(PermitAll.class));
                    }
                });
        if (transformer != null) {
            builder.addAnnotationTransformer(transformer);
        }
        var processor = builder.build();
        processor.process();
        var validation = processor.validate(ignored -> {});
        processor.processValidationErrors(validation);
        return new Fixture(index, new ValidationPhaseBuildItem(validation, processor));
    }

    private static Index index(Class<?> resource) throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> type : List.of(
                resource,
                BindingFixtureInterceptor.class,
                PermitBindingFixtureInterceptor.class,
                DenyAll.class,
                PermitAll.class,
                Singleton.class,
                Interceptor.class,
                AroundInvoke.class,
                Priority.class,
                Object.class)) {
            indexer.indexClass(type);
        }
        return indexer.complete();
    }

    private static MethodInfo method(Index index, Class<?> type) {
        return index.getClassByName(DotName.createSimple(type.getName())).methods().stream()
                .filter(candidate -> candidate.name().equals("get"))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> capture(
            Fixture fixture, List<AdditionalSecuredMethodsBuildItem> additional, LaunchMode mode) {
        Map<String, String> values = new LinkedHashMap<>();
        new BootUiQuarkusProcessor()
                .registerSecurityAnnotations(
                        new LaunchModeBuildItem(
                                mode, Optional.empty(), false, Optional.empty(), mode == LaunchMode.TEST),
                        new ApplicationIndexBuildItem(fixture.index()),
                        new CombinedIndexBuildItem(fixture.index(), fixture.index()),
                        fixture.validation(),
                        additional,
                        ignored -> {},
                        item -> values.put(item.getKey(), item.getValue()));
        return values;
    }

    private static QuarkusSecurityScanner scanner(Map<String, String> defaults) {
        Map<String, String> values = new LinkedHashMap<>(defaults);
        for (String capability : List.of("oidc", "jwt", "jdbc", "properties", "kafka", "openapi", "health")) {
            values.put("bootui.internal.sec." + capability + "-present", "false");
        }
        values.put("bootui.internal.sec.security-present", "true");
        values.put("quarkus.http.auth.basic", "true");
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(values, "application.properties", 1000))
                .build();
        var provider = new QuarkusSecuritySnapshotProviderImpl(config);
        return QuarkusSecurityScanner.usingSnapshot(provider::snapshot, Clock.systemUTC());
    }

    record Fixture(Index index, ValidationPhaseBuildItem validation) {}

    @Singleton
    @Path("/ordinary-security")
    public static class OrdinaryResource {
        @GET
        public String get() {
            return "ordinary";
        }
    }

    @Singleton
    @Path("/restricted-security")
    public static class RestrictedResource {
        @GET
        @DenyAll
        public String get() {
            return "restricted";
        }
    }

    @Singleton
    @Path("/overridden-security")
    @DenyAll
    public static class OverriddenResource {
        @GET
        @PermitAll
        public String get() {
            return "method overrides class";
        }
    }

    // This fixture materializes a binding, not a Quarkus SecurityCheck or proof of actual request authorization.
    @Interceptor
    @DenyAll
    @Priority(1)
    public static class BindingFixtureInterceptor {
        @AroundInvoke
        Object invoke(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Interceptor
    @PermitAll
    @Priority(2)
    public static class PermitBindingFixtureInterceptor {
        @AroundInvoke
        Object invoke(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }
}
