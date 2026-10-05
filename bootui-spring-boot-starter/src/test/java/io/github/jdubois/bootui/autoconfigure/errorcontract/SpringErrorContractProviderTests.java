package io.github.jdubois.bootui.autoconfigure.errorcontract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.bootui.errorcontract.ErrorContractFixtures;
import com.example.bootui.errorcontract.ErrorContractFixtures.DynamicAdvice;
import com.example.bootui.errorcontract.ErrorContractFixtures.EmptyAdvice;
import com.example.bootui.errorcontract.ErrorContractFixtures.GlobalAdvice;
import com.example.bootui.errorcontract.ErrorContractFixtures.LocalController;
import com.example.bootui.errorcontract.ErrorContractFixtures.ParameterTypedAdvice;
import com.example.bootui.errorcontract.ErrorContractFixtures.RuntimeOrderedAdvice;
import com.example.bootui.errorcontract.ErrorContractFixtures.ScopedAdvice;
import com.example.bootui.errorcontract.ErrorContractFixtures.TrackingFactoryBean;
import com.example.bootui.errorcontract.ErrorContractFixtures.ViewRenderingAdvice;
import io.github.jdubois.bootui.sample.errorcontract.HostApplicationAdvice;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Tests for {@link SpringErrorContractProvider}, the metadata-only reader that serves both Spring MVC and
 * Spring WebFlux.
 *
 * <p>The provider decides what the catalogue is allowed to claim, so these tests pin the guarantees the
 * panel depends on: a narrowed {@code @ControllerAdvice} is reported as scoped rather than global,
 * asynchronous wrappers are unwrapped so the declared body type is classified rather than the wrapper, a
 * {@code ResponseEntity} return is honestly reported as a runtime-built status, and BootUI never reports
 * itself as part of the host application's contract.</p>
 */
class SpringErrorContractProviderTests {

    @Test
    void unavailableWithoutABeanFactoryAndReportsNoHandlers() {
        SpringErrorContractProvider provider = new SpringErrorContractProvider(null);

        assertThat(provider.available()).isFalse();
        assertThat(provider.handlers()).isEmpty();
    }

    @Test
    void readsGlobalAdviceWithDeclaredOrderStatusAndExceptionTypes() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(GlobalAdvice.class);

        assertThat(handlers)
                .extracting(
                        ErrorHandlerDescriptor::source,
                        ErrorHandlerDescriptor::methodName,
                        ErrorHandlerDescriptor::scope,
                        ErrorHandlerDescriptor::declaredOrder,
                        ErrorHandlerDescriptor::declaredStatus,
                        ErrorHandlerDescriptor::dynamicStatus)
                .containsExactly(tuple(
                        ErrorHandlerDescriptor.SPRING_CONTROLLER_ADVICE,
                        "handleNotFound",
                        ErrorHandlerDescriptor.SCOPE_GLOBAL,
                        10,
                        "404",
                        false));
        ErrorHandlerDescriptor handler = handlers.get(0);
        assertThat(handler.componentClassName()).isEqualTo(GlobalAdvice.class.getName());
        assertThat(handler.exceptionTypeNames()).containsExactly(IllegalStateException.class.getName());
        assertThat(handler.bodyTypeName()).isEqualTo(ProblemDetail.class.getName());
        assertThat(handler.scopeTarget()).isNull();
        assertThat(handler.produces()).containsExactly("application/problem+json");
    }

    @Test
    void fallsBackToThrowableParametersWhenTheAnnotationDeclaresNoTypes() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(ParameterTypedAdvice.class);

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.exceptionTypeNames()).containsExactly(IllegalArgumentException.class.getName());
            assertThat(handler.produces()).isEmpty();
        });
    }

    @Test
    void reportsNarrowedAdviceAsScopedWithItsSelectorsAsEvidence() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(ScopedAdvice.class);

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_SCOPED);
            assertThat(handler.scopeTarget()).contains("assignableTypes=" + LocalController.class.getName());
            assertThat(handler.declaredOrder()).isNull();
        });
    }

    @Test
    void reportsControllerLocalHandlersAsControllerScopedWithTheControllerAsTarget() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(LocalController.class);

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.source()).isEqualTo(ErrorHandlerDescriptor.SPRING_CONTROLLER);
            assertThat(handler.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_CONTROLLER);
            assertThat(handler.scopeTarget()).isEqualTo(LocalController.class.getName());
            assertThat(handler.declaredStatus()).isNull();
        });
    }

    @Test
    void marksResponseEntityAsDynamicStatusAndUnwrapsAsynchronousWrappers() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(DynamicAdvice.class);

        assertThat(handlers)
                .extracting(
                        ErrorHandlerDescriptor::methodName,
                        ErrorHandlerDescriptor::dynamicStatus,
                        ErrorHandlerDescriptor::bodyTypeName)
                .containsExactly(
                        tuple("handleAsync", true, ErrorContractFixtures.ErrorBody.class.getName()),
                        tuple("handleDynamic", true, ErrorContractFixtures.ErrorBody.class.getName()),
                        tuple("handleVoid", false, "void"));
    }

    @Test
    void reportsNoBodyTypeForAnAdviceThatRendersAViewRatherThanWritingTheResponse() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(ViewRenderingAdvice.class);

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.returnTypeName()).isEqualTo("java.lang.String");
            assertThat(handler.bodyTypeName()).isNull();
            assertThat(handler.dynamicStatus()).isFalse();
        });
    }

    @Test
    void reportsAdviceThatOrdersItselfAtRuntimeAsUnrankable() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(RuntimeOrderedAdvice.class);

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.declaredOrder()).isNull();
            assertThat(handler.dynamicPrecedence()).isTrue();
        });
    }

    @Test
    void treatsADeclaredOrderAsTheRankingEvidenceEvenWhenTheAdviceIsAlsoOrdered() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(GlobalAdvice.class);

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.declaredOrder()).isEqualTo(10);
            assertThat(handler.dynamicPrecedence()).isFalse();
        });
    }

    @Test
    void excludesBootUisOwnComponentsFromTheApplicationsContract() {
        assertThat(handlersOf(SelfAdvice.class)).isEmpty();
    }

    @Test
    void stillReportsAHostApplicationThatLivesUnderBootUisOwnTopLevelPackage() {
        // Regression: excluding the bare "io.github.jdubois.bootui" prefix hid every handler declared by
        // BootUI's own sample applications, which live under io.github.jdubois.bootui.sample.
        assertThat(handlersOf(HostApplicationAdvice.class))
                .extracting(ErrorHandlerDescriptor::componentClassName)
                .containsExactly(HostApplicationAdvice.class.getName());
    }

    @Test
    void ignoresAdviceBeansThatDeclareNoHandlerMethods() {
        assertThat(handlersOf(EmptyAdvice.class)).isEmpty();
    }

    @Test
    void sortsHandlersByComponentThenMethodSoTheCatalogueIsStable() {
        List<ErrorHandlerDescriptor> handlers = handlersOf(LocalController.class, GlobalAdvice.class);

        assertThat(handlers)
                .extracting(ErrorHandlerDescriptor::componentClassName)
                .containsExactly(GlobalAdvice.class.getName(), LocalController.class.getName());
    }

    @Test
    void discoversHandlersWithoutInitializingApplicationFactoryBeans() {
        // getBeanNamesForAnnotation is documented to initialize FactoryBeans so it can inspect the objects
        // they produce. A declaration-only panel read must never run application code, so discovery walks
        // bean definitions and resolves types without allowing factory initialization.
        try (AnnotationConfigApplicationContext context = contextWith(GlobalAdvice.class, TrackingFactoryBean.class)) {
            TrackingFactoryBean.INSTANTIATIONS.set(0);
            TrackingFactoryBean.OBJECTS_BUILT.set(0);

            List<ErrorHandlerDescriptor> handlers =
                    new SpringErrorContractProvider(context.getBeanFactory()).handlers();

            assertThat(handlers)
                    .extracting(ErrorHandlerDescriptor::componentClassName)
                    .containsExactly(GlobalAdvice.class.getName());
            assertThat(TrackingFactoryBean.INSTANTIATIONS).hasValue(0);
            assertThat(TrackingFactoryBean.OBJECTS_BUILT).hasValue(0);
        }
    }

    @Test
    void memoizesDiscoveryBehindAStableListInstance() {
        try (AnnotationConfigApplicationContext context = contextWith(GlobalAdvice.class)) {
            SpringErrorContractProvider provider = new SpringErrorContractProvider(context.getBeanFactory());

            assertThat(provider.handlers()).isSameAs(provider.handlers());
        }
    }

    private static List<ErrorHandlerDescriptor> handlersOf(Class<?>... beanClasses) {
        try (AnnotationConfigApplicationContext context = contextWith(beanClasses)) {
            return new SpringErrorContractProvider(context.getBeanFactory()).handlers();
        }
    }

    private static AnnotationConfigApplicationContext contextWith(Class<?>... beanClasses) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(beanClasses);
        context.refresh();
        return context;
    }

    /** Declared inside BootUI's own package on purpose: it must never appear in the catalogue. */
    @ControllerAdvice
    static class SelfAdvice {

        @ExceptionHandler(IllegalStateException.class)
        ProblemDetail handle() {
            return ProblemDetail.forStatus(HttpStatus.CONFLICT);
        }
    }
}
