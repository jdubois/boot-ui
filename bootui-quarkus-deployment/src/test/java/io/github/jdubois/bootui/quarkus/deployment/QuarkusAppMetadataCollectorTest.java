package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.SharedField;
import io.quarkus.arc.processor.AnnotationsTransformer;
import io.quarkus.arc.processor.BeanDeploymentValidator;
import io.quarkus.arc.processor.BeanProcessor;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveResourceMethodEntriesBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.SetupEndpointsResultBuildItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.jboss.resteasy.reactive.common.model.ResourceClass;
import org.jboss.resteasy.reactive.server.model.ServerResourceMethod;
import org.jboss.resteasy.reactive.server.processor.ServerEndpointIndexer;
import org.junit.jupiter.api.Test;

class QuarkusAppMetadataCollectorTest {

    @Test
    void resolvedScopesIncludeStereotypesInheritanceAndTransformedBeansButNotProducers() throws IOException {
        Index application = index(InheritedBean.class, StereotypedBean.class, ImplicitBean.class, ProducerBean.class);
        Index combined = index(
                InheritedBean.class,
                ParentBean.class,
                StereotypedBean.class,
                AppStereotype.class,
                SingletonStereotype.class,
                ImplicitBean.class,
                ProducerBean.class,
                ProducedObject.class,
                Object.class);
        var context = arc(combined, false);

        QuarkusAppMetadata result = collect(application, combined, context, List.of(), List.of(), List.of());

        assertThat(result.beanCount()).isEqualTo(4);
        assertThat(result.sharedFields())
                .containsExactlyInAnyOrder(
                        field(InheritedBean.class, "inherited", "APPLICATION", false),
                        field(StereotypedBean.class, "state", "APPLICATION", false),
                        field(ImplicitBean.class, "state", "SINGLETON", false));
        assertThat(result.sharedFields()).noneMatch(value -> value.className().equals(ProducedObject.class.getName()));
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void respectsNativeRemovalAndExclusionRatherThanScanningEveryAnnotatedClass() throws IOException {
        Index application = index(RemovedBean.class, ExcludedBean.class, RetainedBean.class);
        var context = arc(application, true);

        QuarkusAppMetadata result = collect(application, application, context, List.of(), List.of(), List.of());

        assertThat(result.beanCount()).isEqualTo(1);
        assertThat(result.sharedFields()).containsExactly(field(RetainedBean.class, "state", "SINGLETON", false));
    }

    @Test
    void excludesPrivateStaticImmutableConcurrentAndResolvedImplicitInjectionFields() throws IOException {
        Index application = index(FieldBean.class, InjectedDependency.class);
        Index combined = index(
                FieldBean.class, FieldParent.class, InjectedDependency.class, CustomQualifier.class, Object.class);
        var context = arc(combined, false);

        QuarkusAppMetadata result = collect(application, combined, context, List.of(), List.of(), List.of());

        assertThat(result.sharedFields())
                .containsExactlyInAnyOrder(
                        field(FieldBean.class, "mutable", "APPLICATION", false),
                        field(FieldBean.class, "finalList", "APPLICATION", false),
                        field(FieldBean.class, "finalArray", "APPLICATION", false),
                        field(FieldBean.class, "inherited", "APPLICATION", false));
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void resourceFieldsRequireRegisteredEndpointsAndResolvedSharedScope() throws IOException {
        Index application =
                index(SharedResource.class, RequestResource.class, DependentResource.class, NonResource.class);
        var context = arc(application, false);
        List<ResteasyReactiveResourceMethodEntriesBuildItem.Entry> entries = List.of(
                endpoint(application, SharedResource.class, "call", false),
                endpoint(application, RequestResource.class, "call", false),
                endpoint(application, DependentResource.class, "call", false));

        QuarkusAppMetadata result = collect(
                application,
                application,
                context,
                entries,
                List.of(SharedResource.class, RequestResource.class, DependentResource.class),
                List.of());

        assertThat(result.endpointCount()).isEqualTo(3);
        assertThat(result.sharedFields())
                .containsExactlyInAnyOrder(
                        field(SharedResource.class, "state", "SINGLETON", true),
                        field(NonResource.class, "state", "APPLICATION", false));
    }

    @Test
    void virtualThreadEvidenceUsesUniqueRegisteredInvocationModesNotAnnotationsOrHelpers() throws IOException {
        Index application = index(VirtualResource.class, OutboundClient.class);
        var context = arc(application, false);
        var actual = endpoint(application, VirtualResource.class, "synchronizedCall", true);
        var worker = endpoint(application, VirtualResource.class, "workerCall", false);

        QuarkusAppMetadata result = collect(
                application,
                application,
                context,
                List.of(actual, actual, worker),
                List.of(VirtualResource.class),
                List.of());

        assertThat(result.endpointCount()).isEqualTo(2);
        assertThat(result.synchronizedVirtualThreadMethods())
                .containsExactly(VirtualResource.class.getName() + "#synchronizedCall()");
    }

    @Test
    void inheritedCustomVerbMethodsUseActualRegisteredResourceOwner() throws IOException {
        Index application = index(InheritedResource.class);
        Index combined = index(InheritedResource.class, ResourceParent.class, Object.class);
        var context = arc(combined, false);
        MethodInfo method =
                combined.getClassByName(ResourceParent.class.getName()).firstMethod("inheritedCall");
        var entry = new ResteasyReactiveResourceMethodEntriesBuildItem.Entry(
                null,
                method,
                application.getClassByName(InheritedResource.class.getName()),
                registeredMethod(combined, method, InheritedResource.class, true)
                        .setHttpMethod("PURGE"));

        QuarkusAppMetadata result =
                collect(application, combined, context, List.of(entry), List.of(InheritedResource.class), List.of());

        assertThat(result.endpointCount()).isEqualTo(1);
        assertThat(result.synchronizedVirtualThreadMethods())
                .containsExactly(InheritedResource.class.getName() + "#inheritedCall()");
    }

    @Test
    void inheritedRestAnnotationsDoNotDetermineImplementationSynchronization() throws IOException {
        Index application = index(SynchronizedChild.class, UnsynchronizedChild.class);
        Index combined = index(
                SynchronizedChild.class,
                UnsynchronizedChild.class,
                SynchronizedParent.class,
                UnsynchronizedParent.class,
                Object.class);
        var context = arc(combined, false);
        List<ResteasyReactiveResourceMethodEntriesBuildItem.Entry> entries = new ArrayList<>();
        for (Class<?> child : List.of(SynchronizedChild.class, UnsynchronizedChild.class)) {
            MethodInfo declaration =
                    combined.getClassByName(child.getSuperclass().getName()).firstMethod("call");
            entries.add(new ResteasyReactiveResourceMethodEntriesBuildItem.Entry(
                    null,
                    declaration,
                    application.getClassByName(child.getName()),
                    registeredMethod(combined, declaration, child, true)));
        }

        QuarkusAppMetadata result = collect(
                application,
                combined,
                context,
                entries,
                List.of(SynchronizedChild.class, UnsynchronizedChild.class),
                List.of());

        assertThat(result.synchronizedVirtualThreadMethods())
                .containsExactly(SynchronizedChild.class.getName() + "#call()");
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void missingResolvedImplementationIsUnknownRatherThanUsingTheAnnotationDeclaration() throws IOException {
        Index application = index(VirtualResource.class);
        var context = arc(application, false);
        var entry = endpoint(application, VirtualResource.class, "synchronizedCall", true);
        ((ServerResourceMethod) entry.getResourceMethod()).setActualDeclaringClassName("missing.Implementation");

        QuarkusAppMetadata result =
                collect(application, application, context, List.of(entry), List.of(VirtualResource.class), List.of());

        assertThat(result.synchronizedVirtualThreadMethods()).isEmpty();
        assertThat(result.problems()).extracting("ruleId").containsExactly("QA-PERF-002");
    }

    @Test
    void classicClientCapabilityIsUnknownCoverageRatherThanKnownAbsence() throws IOException {
        Index application = index(OutboundClient.class);
        var context = arc(application, false);
        QuarkusAppMetadata result = QuarkusAppMetadataCollector.collect(
                application,
                application,
                context.beans(),
                context.getInjectionPoints(),
                Optional.empty(),
                Optional.empty(),
                new Capabilities(Set.of(Capability.REST_CLIENT, Capability.RESTEASY_CLIENT)));

        assertThat(result.restClientSupported()).isFalse();
        assertThat(result.restClients()).isEmpty();
        assertThat(result.problems()).extracting("ruleId").containsExactly("QA-WEB-003");
    }

    @Test
    void ambiguousSubresourcesDoNotBecomeVirtualThreadEntrypoints() throws IOException {
        Index application = index(VirtualResource.class);
        var context = arc(application, false);

        QuarkusAppMetadata result = collect(
                application,
                application,
                context,
                List.of(endpoint(application, VirtualResource.class, "synchronizedCall", true)),
                List.of(),
                List.of(VirtualResource.class));

        assertThat(result.synchronizedVirtualThreadMethods()).isEmpty();
        assertThat(result.problems()).extracting("ruleId").containsExactly("QA-CDI-002", "QA-PERF-002");
    }

    @Test
    void clientIdentitiesComeFromSurvivingQualifiedBeansNotOutboundAnnotationCounts() throws IOException {
        Index application = index(OutboundClient.class, UnusedClient.class);
        Index combined = index(
                OutboundClient.class,
                UnusedClient.class,
                OutboundClient$$CDIWrapper.class,
                RestClient.class,
                Object.class);
        var context = arc(combined, false);

        QuarkusAppMetadata result = collect(application, combined, context, List.of(), List.of(), List.of());

        assertThat(result.restClientSupported()).isTrue();
        assertThat(result.restClients())
                .containsExactly(new QuarkusAppMetadata.RestClient(OutboundClient.class.getName(), "inventory"));
        assertThat(result.endpointCount()).isZero();
        assertThat(result.beanCount()).isZero();
    }

    @Test
    void customQualifiedClientsDoNotClaimManagedTimeoutConfiguration() throws IOException {
        Index application = index(OutboundClient.class, CustomClient.class);
        Index combined = index(OutboundClient.class, CustomClient.class, RestClient.class, Object.class);
        var context = arc(combined, false);
        QuarkusAppMetadata result = collect(application, combined, context, List.of(), List.of(), List.of());

        assertThat(result.restClients()).isEmpty();
        assertThat(result.problems()).extracting("ruleId").containsExactly("QA-WEB-003");
    }

    @Test
    void endpointLimitsYieldFixedCoverageProblems() throws IOException {
        Index application = index(VirtualResource.class);
        var context = arc(application, false);
        var entry = endpoint(application, VirtualResource.class, "synchronizedCall", true);

        QuarkusAppMetadata result = collect(
                application,
                application,
                context,
                Collections.nCopies(QuarkusAppMetadataStore.MAX_MEMBERS + 1, entry),
                List.of(VirtualResource.class),
                List.of());

        assertThat(result.endpointCount()).isZero();
        assertThat(result.problems()).allMatch(problem -> problem.message().equals(QuarkusAppMetadataStore.INCOMPLETE));
        assertThat(result.problems()).extracting("ruleId").contains("QA-CDI-002", "QA-PERF-002");
    }

    @Test
    void classLimitsStopBeforeTraversingOrAllocatingTheOversizedInventory() throws IOException {
        Index index = index(VirtualResource.class);
        IndexView oversized = (IndexView) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IndexView.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getKnownClasses")) {
                        return Collections.nCopies(
                                QuarkusAppMetadataStore.MAX_CLASSES + 1, index.getClassByName(VirtualResource.class));
                    }
                    throw new AssertionError("The oversized index must not be traversed");
                });
        QuarkusAppMetadata result = QuarkusAppMetadataCollector.collect(
                oversized, index, List.of(), List.of(), Optional.empty(), Optional.empty(), new Capabilities(Set.of()));

        assertThat(result.available()).isTrue();
        assertThat(result.beanCount()).isZero();
        assertThat(result.problems())
                .hasSize(5)
                .allMatch(problem -> problem.message().equals(QuarkusAppMetadataStore.INCOMPLETE));
    }

    @Test
    void retainsConfigurationAnnotationsAsNonScoredDeclarationsWithoutRequiringBeans() throws IOException {
        Index application = index(ConfigurationDeclarations.class, MappingDeclaration.class);
        var context = arc(application, false);

        QuarkusAppMetadata result = collect(application, application, context, List.of(), List.of(), List.of());

        assertThat(result.beanCount()).isZero();
        assertThat(result.configPropertyCount()).isEqualTo(2);
        assertThat(result.configMappingCount()).isEqualTo(1);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void finalCustomTemporalTypesAreNotAssumedImmutableByPackagePrefix() throws IOException {
        Index application = index(UnknownTemporalBean.class);
        var context = arc(application, false);

        QuarkusAppMetadata result = collect(application, application, context, List.of(), List.of(), List.of());

        assertThat(result.sharedFields())
                .containsExactly(field(UnknownTemporalBean.class, "temporal", "SINGLETON", false));
    }

    @Test
    void inheritanceTraversalIsBoundedAndMissingMetadataDoesNotClaimClean() throws IOException {
        Index application = index(InheritedBean.class);
        ClassInfo bean = application.getClassByName(InheritedBean.class.getName());
        assertThatThrownBy(() -> QuarkusAppMetadataCollector.collectFields(
                        bean,
                        application,
                        "APPLICATION",
                        false,
                        Set.of(),
                        new ArrayList<>(),
                        new QuarkusAppMetadataCollector.Budget(0)))
                .isInstanceOf(RuntimeException.class);

        Index combined = index(InheritedBean.class, ParentBean.class, SingletonStereotype.class, Object.class);
        var context = arc(combined, false);
        QuarkusAppMetadata result = collect(application, application, context, List.of(), List.of(), List.of());
        assertThat(result.problems()).extracting("ruleId").contains("QA-CDI-001");
    }

    private static QuarkusAppMetadata collect(
            Index application,
            Index combined,
            BeanDeploymentValidator.ValidationContext context,
            List<ResteasyReactiveResourceMethodEntriesBuildItem.Entry> entries,
            List<Class<?>> roots,
            List<Class<?>> subresources) {
        return QuarkusAppMetadataCollector.collect(
                application,
                combined,
                context.beans(),
                context.getInjectionPoints(),
                Optional.of(new ResteasyReactiveResourceMethodEntriesBuildItem(entries)),
                Optional.of(new SetupEndpointsResultBuildItem(
                        roots.stream()
                                .map(type -> new ResourceClass().setClassName(type.getName()))
                                .toList(),
                        subresources.stream()
                                .map(type -> new ResourceClass().setClassName(type.getName()))
                                .toList(),
                        null,
                        null)),
                new Capabilities(Set.of(Capability.REST_CLIENT_REACTIVE)));
    }

    private static ResteasyReactiveResourceMethodEntriesBuildItem.Entry endpoint(
            Index index, Class<?> type, String name, boolean virtual) {
        ClassInfo owner = index.getClassByName(type.getName());
        return new ResteasyReactiveResourceMethodEntriesBuildItem.Entry(
                null, owner.firstMethod(name), owner, registeredMethod(index, owner.firstMethod(name), type, virtual));
    }

    private static ServerResourceMethod registeredMethod(
            Index index, MethodInfo declaration, Class<?> owner, boolean virtual) {
        MethodInfo implementation = ServerEndpointIndexer.findEndpointImplementation(
                declaration, index.getClassByName(owner.getName()), index);
        ServerResourceMethod method = new ServerResourceMethod();
        method.setHttpMethod("GET").setRunOnVirtualThread(virtual);
        method.setActualDeclaringClassName(
                implementation.declaringClass().name().toString());
        return method;
    }

    private static BeanDeploymentValidator.ValidationContext arc(Index index, boolean removeUnused) throws IOException {
        var resolvedIndex = CompositeIndex.create(
                index, index(ApplicationScoped.class, RequestScoped.class, Dependent.class, Singleton.class));
        BeanProcessor processor = BeanProcessor.builder()
                .setImmutableBeanArchiveIndex(resolvedIndex)
                .setComputingBeanArchiveIndex(resolvedIndex)
                .setApplicationIndex(index)
                .setRemoveUnusedBeans(removeUnused)
                .addRemovalExclusion(bean -> bean.getBeanClass().toString().equals(RetainedBean.class.getName()))
                .addExcludeType(info -> info.name().toString().equals(ExcludedBean.class.getName()))
                .addAnnotationTransformation(new AnnotationsTransformer() {
                    @Override
                    public void transform(TransformationContext context) {
                        if (context.getTarget().kind() == AnnotationTarget.Kind.CLASS
                                && context.getTarget()
                                        .asClass()
                                        .name()
                                        .toString()
                                        .equals(ImplicitBean.class.getName())) {
                            context.transform().add(Singleton.class).done();
                        } else if (context.getTarget().kind() == AnnotationTarget.Kind.FIELD
                                && context.getTarget()
                                        .asField()
                                        .hasAnnotation(DotName.createSimple(CustomQualifier.class))) {
                            context.transform().add(Inject.class).done();
                        }
                    }
                })
                .build();
        processor.registerCustomContexts();
        processor.registerScopes();
        var registration = processor.registerBeans();
        processor.registerSyntheticInjectionPoints(registration);
        processor.getBeanDeployment().initBeanByTypeMap();
        processor.registerSyntheticObservers();
        processor.initialize(ignored -> {}, List.of());
        var context = processor.validate(ignored -> {});
        processor.processValidationErrors(context);
        return context;
    }

    private static Index index(Class<?>... types) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> type : types) {
            indexer.indexClass(type);
        }
        return indexer.complete();
    }

    private static SharedField field(Class<?> owner, String name, String scope, boolean resource) {
        return new SharedField(owner.getName(), name, scope, resource);
    }

    @ApplicationScoped
    static class ParentBean {
        public int inherited;
    }

    @SingletonStereotype
    static class InheritedBean extends ParentBean {}

    @Stereotype
    @Singleton
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface SingletonStereotype {}

    @Stereotype
    @ApplicationScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface AppStereotype {}

    @AppStereotype
    static class StereotypedBean {
        public int state;
    }

    static class ImplicitBean {
        public int state;
    }

    @Singleton
    static class ProducerBean {
        @Produces
        @ApplicationScoped
        ProducedObject create() {
            return new ProducedObject();
        }
    }

    static class ProducedObject {
        public int state;
    }

    @Singleton
    static class RemovedBean {
        public int state;
    }

    @Singleton
    static class ExcludedBean {
        public int state;
    }

    @Singleton
    static class RetainedBean {
        public int state;
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    @interface CustomQualifier {}

    @Singleton
    @CustomQualifier
    static class InjectedDependency {}

    static class FieldParent {
        public int inherited;

        @CustomQualifier
        public InjectedDependency implicitInjection;
    }

    @ApplicationScoped
    static class FieldBean extends FieldParent {
        public int mutable;
        private int hidden;
        public static int global;
        public final int primitive = 1;
        public final String text = "";
        public final java.time.Instant time = java.time.Instant.EPOCH;
        public final AtomicInteger atomic = new AtomicInteger();
        public final ConcurrentHashMap<String, String> concurrent = new ConcurrentHashMap<>();
        public final List<String> finalList = new ArrayList<>();
        public final int[] finalArray = new int[1];
    }

    @Singleton
    @Path("/shared")
    static class SharedResource {
        public int state;

        @QueryParam("query")
        public String injectedParameter;

        @GET
        public String call() {
            return "";
        }
    }

    @RequestScoped
    @Path("/request")
    static class RequestResource {
        public int state;

        @GET
        public String call() {
            return "";
        }
    }

    @Dependent
    @Path("/dependent")
    static class DependentResource {
        public int state;

        @GET
        public String call() {
            return "";
        }
    }

    @ApplicationScoped
    static class NonResource {
        public int state;
    }

    @Singleton
    @Path("/virtual")
    @io.smallrye.common.annotation.RunOnVirtualThread
    static class VirtualResource {
        @GET
        @io.smallrye.common.annotation.RunOnVirtualThread
        public synchronized String synchronizedCall() {
            return "";
        }

        public synchronized String workerCall() {
            return "";
        }

        public synchronized void helper() {}
    }

    static class ResourceParent {
        public synchronized String inheritedCall() {
            return "";
        }
    }

    @Singleton
    static class InheritedResource extends ResourceParent {}

    static class SynchronizedParent {
        @GET
        public synchronized String call() {
            return "";
        }
    }

    @Singleton
    static class UnsynchronizedChild extends SynchronizedParent {
        @Override
        public String call() {
            return "";
        }
    }

    static class UnsynchronizedParent {
        @GET
        public String call() {
            return "";
        }
    }

    @Singleton
    static class SynchronizedChild extends UnsynchronizedParent {
        @Override
        public synchronized String call() {
            return "";
        }
    }

    @RegisterRestClient(configKey = "inventory")
    interface OutboundClient {
        @GET
        String call();
    }

    @RegisterRestClient
    interface UnusedClient {
        @GET
        String call();
    }

    @Singleton
    @RestClient
    static class OutboundClient$$CDIWrapper implements OutboundClient {
        public String call() {
            throw new AssertionError("Application code must not be invoked");
        }
    }

    @Singleton
    @RestClient
    static class CustomClient implements OutboundClient {
        public String call() {
            throw new AssertionError("Application code must not be invoked");
        }
    }

    static class ConfigurationDeclarations {
        @ConfigProperty(name = "sample.field")
        String field;

        void parameter(@ConfigProperty(name = "sample.parameter") String value) {}
    }

    @io.smallrye.config.ConfigMapping(prefix = "sample")
    interface MappingDeclaration {
        String text();
    }

    @Singleton
    static class UnknownTemporalBean {
        public final java.time.temporal.Temporal temporal = null;
    }
}
