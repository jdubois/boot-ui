package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.bootui.errorcontract.QuarkusErrorFixtures.AbstractNotFoundMapper;
import com.example.bootui.errorcontract.QuarkusErrorFixtures.InheritingMapper;
import com.example.bootui.errorcontract.QuarkusErrorFixtures.NotFoundMapper;
import com.example.bootui.errorcontract.QuarkusErrorFixtures.PrioritisedMappers;
import com.example.bootui.errorcontract.QuarkusErrorFixtures.SampleNotFound;
import com.example.bootui.errorcontract.QuarkusErrorFixtures.SampleResource;
import com.example.bootui.errorcontract.QuarkusErrorFixtures.UnregisteredMapper;
import io.github.jdubois.bootui.quarkus.errorcontract.RawErrorHandler;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BootUiQuarkusProcessor#scanErrorHandlers(org.jboss.jandex.IndexView)}, the
 * build-time Jandex scan behind the REST API panel's error-contract catalogue.
 *
 * <p>They build a real Jandex index from the fixture classes below so the assertions exercise the exact
 * signals the build step reads from bytecode: {@code @Provider} registration, the mapped exception type
 * (including through a superclass and past the bridge method the compiler generates), the ordering
 * signal Quarkus actually consults, and how a runtime-built response is reported.</p>
 */
class BootUiQuarkusProcessorErrorContractTest {

    private static Index indexOf(Class<?>... classes) throws IOException {
        Indexer indexer = new Indexer();
        for (Class<?> type : classes) {
            indexer.indexClass(type);
        }
        return indexer.complete();
    }

    @Test
    void readsAProviderExceptionMapperIncludingItsPriorityAndDeclaredMediaTypes() throws IOException {
        List<RawErrorHandler> handlers =
                BootUiQuarkusProcessor.scanErrorHandlers(indexOf(NotFoundMapper.class, SampleNotFound.class));

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.source()).isEqualTo(ErrorHandlerDescriptor.JAKARTA_REST_EXCEPTION_MAPPER);
            assertThat(handler.componentClassName()).isEqualTo(NotFoundMapper.class.getName());
            assertThat(handler.methodName()).isEqualTo("toResponse");
            assertThat(handler.exceptionTypeNames()).containsExactly(SampleNotFound.class.getName());
            assertThat(handler.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_GLOBAL);
            assertThat(handler.declaredOrder()).isEqualTo(4000);
            assertThat(handler.dynamicStatus()).isTrue();
            assertThat(handler.produces()).containsExactly(MediaType.APPLICATION_JSON);
        });
    }

    @Test
    void ignoresAnExceptionMapperThatIsNotRegisteredAsAProvider() throws IOException {
        List<RawErrorHandler> handlers =
                BootUiQuarkusProcessor.scanErrorHandlers(indexOf(UnregisteredMapper.class, SampleNotFound.class));

        assertThat(handlers).isEmpty();
    }

    @Test
    void readsTheMappedExceptionTypeThroughAnAbstractSuperclass() throws IOException {
        List<RawErrorHandler> handlers = BootUiQuarkusProcessor.scanErrorHandlers(
                indexOf(InheritingMapper.class, AbstractNotFoundMapper.class, SampleNotFound.class));

        assertThat(handlers)
                .extracting(RawErrorHandler::componentClassName, RawErrorHandler::exceptionTypeNames)
                .containsExactly(tuple(InheritingMapper.class.getName(), List.of(SampleNotFound.class.getName())));
    }

    @Test
    void readsThePriorityDeclaredOnServerExceptionMapperRatherThanAPriorityAnnotation() throws IOException {
        List<RawErrorHandler> handlers =
                BootUiQuarkusProcessor.scanErrorHandlers(indexOf(PrioritisedMappers.class, SampleNotFound.class));

        assertThat(handlers)
                .extracting(RawErrorHandler::methodName, RawErrorHandler::declaredOrder)
                .containsExactlyInAnyOrder(tuple("declaredPriority", 1), tuple("defaultPriority", null));
    }

    @Test
    void excludesBootUisOwnExceptionMappersFromTheApplicationsContract() throws IOException {
        List<RawErrorHandler> handlers = BootUiQuarkusProcessor.scanErrorHandlers(indexOf(BootUiOwnMapper.class));

        assertThat(handlers).isEmpty();
    }

    /** Stands in for BootUI's own adapter classes, which are never part of the application's contract. */
    @Provider
    public static class BootUiOwnMapper implements ExceptionMapper<IllegalStateException> {

        @Override
        public jakarta.ws.rs.core.Response toResponse(IllegalStateException exception) {
            return null;
        }
    }

    @Test
    void reportsAResourceLocalServerExceptionMapperAndUnwrapsAnAsynchronousRuntimeResponse() throws IOException {
        List<RawErrorHandler> handlers =
                BootUiQuarkusProcessor.scanErrorHandlers(indexOf(SampleResource.class, SampleNotFound.class));

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.source()).isEqualTo(ErrorHandlerDescriptor.QUARKUS_SERVER_EXCEPTION_MAPPER);
            assertThat(handler.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_CONTROLLER);
            assertThat(handler.scopeTarget()).isEqualTo(SampleResource.class.getName());
            assertThat(handler.exceptionTypeNames()).containsExactly(SampleNotFound.class.getName());
            assertThat(handler.dynamicStatus()).isTrue();
            assertThat(handler.produces()).containsExactly(MediaType.APPLICATION_JSON);
        });
    }

    @Test
    void unwrapsKotlinSuspendServerExceptionMapperAndDetectsMethodLevelJaxRsResourceScope() throws IOException {
        List<RawErrorHandler> handlers = BootUiQuarkusProcessor.scanErrorHandlers(indexOf(
                com.example.bootui.errorcontract.QuarkusErrorFixtures.MethodOnlyJaxRsResource.class,
                SampleNotFound.class));

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.source()).isEqualTo(ErrorHandlerDescriptor.QUARKUS_SERVER_EXCEPTION_MAPPER);
            assertThat(handler.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_CONTROLLER);
            assertThat(handler.scopeTarget())
                    .isEqualTo(
                            com.example.bootui.errorcontract.QuarkusErrorFixtures.MethodOnlyJaxRsResource.class
                                    .getName());
            assertThat(handler.dynamicStatus()).isTrue();
            assertThat(handler.returnTypeName()).isEqualTo("org.jboss.resteasy.reactive.RestResponse");
            assertThat(handler.bodyTypeName()).isEqualTo("java.lang.String");
        });
    }
}
