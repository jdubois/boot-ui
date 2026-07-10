package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.Type;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

/**
 * Unit tests for the pure Jandex-index-processing helpers behind the Quarkus application advisor's
 * build-time idiom counts: {@link BootUiQuarkusProcessor#isReactive(Type)} (QA-RX-001),
 * {@link BootUiQuarkusProcessor#mutableFieldsOf(org.jboss.jandex.IndexView, DotName)} (QA-CDI-001/QA-CDI-003),
 * {@link BootUiQuarkusProcessor#classAnnotations(org.jboss.jandex.IndexView, DotName)}, and
 * {@link BootUiQuarkusProcessor#virtualThreadSitesOf(org.jboss.jandex.IndexView)} (QA-PERF-001/QA-PERF-002).
 *
 * <p>These build a real Jandex index from the small fixture classes below (compiled by Maven, indexed via
 * {@link Indexer#indexClass(Class)}) so the assertions exercise the exact bytecode-level annotation/flag
 * signals {@code registerAppIdioms} reads, rather than hand-rolled Jandex objects. The end-to-end wiring
 * (build step → runtime config → engine snapshot) is covered by
 * {@code BootUiQuarkusSpringResourceTest} in {@code bootui-quarkus-integration-tests}.
 */
class BootUiQuarkusProcessorAppIdiomsTest {

    private static final DotName APPLICATION_SCOPED =
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped");
    private static final DotName SINGLETON = DotName.createSimple("jakarta.inject.Singleton");
    private static final DotName RUN_ON_VIRTUAL_THREAD =
            DotName.createSimple("io.smallrye.common.annotation.RunOnVirtualThread");

    private static Index indexOf(Class<?>... classes) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> c : classes) {
            indexer.indexClass(c);
        }
        return indexer.complete();
    }

    // ---- isReactive (QA-RX-001) ----

    @Test
    void isReactiveRecognizesMutinyTypes() {
        assertThat(BootUiQuarkusProcessor.isReactive(classType("io.smallrye.mutiny.Uni")))
                .isTrue();
        assertThat(BootUiQuarkusProcessor.isReactive(classType("io.smallrye.mutiny.Multi")))
                .isTrue();
    }

    @Test
    void isReactiveRecognizesJdkAndReactiveStreamsTypes() {
        assertThat(BootUiQuarkusProcessor.isReactive(classType(CompletionStage.class.getName())))
                .as("CompletionStage return types are reactive dispatch too")
                .isTrue();
        assertThat(BootUiQuarkusProcessor.isReactive(classType(CompletableFuture.class.getName())))
                .isTrue();
        assertThat(BootUiQuarkusProcessor.isReactive(classType(Publisher.class.getName())))
                .isTrue();
    }

    @Test
    void isReactiveRejectsNonReactiveTypesAndNull() {
        assertThat(BootUiQuarkusProcessor.isReactive(classType("java.lang.String")))
                .isFalse();
        assertThat(BootUiQuarkusProcessor.isReactive(null)).isFalse();
    }

    private static Type classType(String fqcn) {
        return Type.create(DotName.createSimple(fqcn), Type.Kind.CLASS);
    }

    // ---- mutableFieldsOf (QA-CDI-001 / QA-CDI-003) ----

    @Test
    void mutableFieldsOfFlagsPublicAndPrivateMutableFieldsButExcludesStaticAndInjected() throws IOException {
        Index index = indexOf(MutableAppScopedBean.class);

        assertThat(BootUiQuarkusProcessor.mutableFieldsOf(index, APPLICATION_SCOPED))
                .containsExactlyInAnyOrder(
                        "MutableAppScopedBean.publicMutableField",
                        "MutableAppScopedBean.publicFinalMutableReference",
                        "MutableAppScopedBean.privateMutableField")
                .as("static fields, @Inject fields, and @ConfigProperty fields are never a shared-state risk")
                .doesNotContain(
                        "MutableAppScopedBean.staticField",
                        "MutableAppScopedBean.publicFinalValueField",
                        "MutableAppScopedBean.publicFinalStringField",
                        "MutableAppScopedBean.injectedField",
                        "MutableAppScopedBean.configPropertyField");
    }

    @Test
    void mutableFieldsOfCoversSingletonScopeForQaCdi003() throws IOException {
        Index index = indexOf(MutableSingletonBean.class);

        assertThat(BootUiQuarkusProcessor.mutableFieldsOf(index, SINGLETON))
                .containsExactly("MutableSingletonBean.publicMutableField");
    }

    @Test
    void mutableFieldsOfIgnoresClassesWithoutTheRequestedScopeAnnotation() throws IOException {
        Index index = indexOf(PlainBean.class);

        assertThat(BootUiQuarkusProcessor.mutableFieldsOf(index, APPLICATION_SCOPED))
                .isEmpty();
        assertThat(BootUiQuarkusProcessor.mutableFieldsOf(index, SINGLETON)).isEmpty();
    }

    // ---- classAnnotations / virtualThreadSitesOf (QA-PERF-001 / QA-PERF-002) ----

    @Test
    void classAnnotationsCountsOnlyClassLevelTargetsNotMethodLevel() throws IOException {
        Index index = indexOf(ClassLevelVirtualThreadBean.class, MethodLevelVirtualThreadBean.class);

        assertThat(BootUiQuarkusProcessor.classAnnotations(index, RUN_ON_VIRTUAL_THREAD))
                .as("the two method-level @RunOnVirtualThread sites on MethodLevelVirtualThreadBean must not count")
                .isEqualTo(1);
    }

    @Test
    void virtualThreadSitesOfCountsClassLevelAsOneSiteButScansAllItsMethodsForSynchronized() throws IOException {
        Index index = indexOf(ClassLevelVirtualThreadBean.class);

        BootUiQuarkusProcessor.VirtualThreadCounts counts = BootUiQuarkusProcessor.virtualThreadSitesOf(index);

        assertThat(counts.sites()).isEqualTo(1);
        assertThat(counts.synchronizedSites())
                .as("the class-level annotation makes synchronizedMethod run on a virtual thread too")
                .isEqualTo(1);
    }

    @Test
    void virtualThreadSitesOfCountsEachMethodLevelSiteIndividually() throws IOException {
        Index index = indexOf(MethodLevelVirtualThreadBean.class);

        BootUiQuarkusProcessor.VirtualThreadCounts counts = BootUiQuarkusProcessor.virtualThreadSitesOf(index);

        assertThat(counts.sites()).isEqualTo(2);
        assertThat(counts.synchronizedSites()).isEqualTo(1);
    }

    @Test
    void virtualThreadSitesOfCombinesClassAndMethodLevelSites() throws IOException {
        Index index = indexOf(ClassLevelVirtualThreadBean.class, MethodLevelVirtualThreadBean.class);

        BootUiQuarkusProcessor.VirtualThreadCounts counts = BootUiQuarkusProcessor.virtualThreadSitesOf(index);

        assertThat(counts.sites()).isEqualTo(3);
        assertThat(counts.synchronizedSites()).isEqualTo(2);
    }

    // ---- Fixtures ----

    @ApplicationScoped
    static class MutableAppScopedBean {
        public int publicMutableField;
        public final int publicFinalValueField = 1;
        public final String publicFinalStringField = "immutable";
        public final List<String> publicFinalMutableReference = new java.util.ArrayList<>();
        private int privateMutableField;
        private static int staticField;

        @Inject
        public String injectedField;

        @ConfigProperty(name = "some.prop")
        String configPropertyField;
    }

    @Singleton
    static class MutableSingletonBean {
        public String publicMutableField;
    }

    static class PlainBean {
        public String publicMutableField;
    }

    @io.smallrye.common.annotation.RunOnVirtualThread
    static class ClassLevelVirtualThreadBean {
        void plainMethod() {}

        synchronized void synchronizedMethod() {}
    }

    static class MethodLevelVirtualThreadBean {
        @io.smallrye.common.annotation.RunOnVirtualThread
        void annotatedMethod() {}

        @io.smallrye.common.annotation.RunOnVirtualThread
        synchronized void annotatedSynchronizedMethod() {}
    }
}
