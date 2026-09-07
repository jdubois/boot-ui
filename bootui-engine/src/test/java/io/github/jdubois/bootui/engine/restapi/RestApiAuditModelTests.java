package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.engine.restapi.auditmodel.RestApiAuditModelFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RestApiAuditModelTests {
    private static RestApiHandlerModelBuilder model;

    @BeforeAll
    static void importModel() {
        model = RestApiHandlerModelBuilder.build(
                new ClassFileImporter().importPackages(RestApiAuditModelFixtures.class.getPackageName()));
    }

    private HandlerMethodModel handler(String name) {
        return model.handlers().stream()
                .filter(h -> h.methodName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private ExceptionHandlerModel exception(String name) {
        return model.exceptionHandlers().stream()
                .filter(h -> h.methodName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void resolvesSingleValueStatusEnvelopesWithoutGivingHttpEntityAStatus() {
        for (String name : List.of("asyncEnvelope", "futureEnvelope", "asyncBody", "mutinyEnvelope")) {
            assertThat(handler(name).returnsResponseEntity()).as(name).isTrue();
            assertThat(handler(name).bodyTypeName())
                    .as(name)
                    .isEqualTo(RestApiAuditModelFixtures.Payload.class.getName());
        }
        assertThat(handler("responseEnvelope").returnsResponseEntity()).isTrue();
        assertThat(handler("headersAndBody").returnsResponseEntity()).isFalse();
        assertThat(handler("nestedHttpEntity").returnsResponseEntity()).isFalse();
        assertThat(handler("optionalEnvelope").returnsResponseEntity()).isFalse();
        assertThat(handler("unsupportedMultipleEnvelopes").returnsResponseEntity())
                .isFalse();
        assertThat(handler("asyncHttpEntity").returnsResponseEntity()).isFalse();
        assertThat(handler("asyncHttpEntity").returnsBodyEnvelope()).isTrue();
        assertThat(handler("asyncHttpEntity").bodyIsUntyped()).isTrue();
        assertThat(handler("bodyStream").returnsStream()).isTrue();
        assertThat(handler("mutinyBodyStream").returnsStream()).isTrue();
        assertThat(handler("entityArray").returnsStream()).isFalse();
    }

    @Test
    void resolvesNoBodyBeforeCollectionElements() {
        for (String name : List.of("asyncEmpty", "asyncEmptyEnvelope", "headersOnly", "mutinyEmpty")) {
            assertThat(handler(name).returnsVoid()).as(name).isTrue();
        }
        assertThat(handler("nullableItems").returnsVoid()).isFalse();
        assertThat(handler("optionalVoid").returnsVoid()).isFalse();
        assertThat(handler("headersPayload").returnsVoid()).isFalse();
        assertThat(handler("asyncHeadersPayload").returnsVoid()).isFalse();
        assertThat(handler("mutinyEmpty").responseStatusValue()).isEqualTo("NO_CONTENT");
        assertThat(handler("asyncEmptyEnvelope").hasResponseStatus()).isFalse();
        assertThat(handler("mutinyEmptyEnvelope").hasResponseStatus()).isFalse();
    }

    @Test
    void resolvesEntityArraysAndReactiveRequestPayloadsButNotBinaryAsCollections() {
        assertThat(handler("entityArray").returnsCollection()).isTrue();
        assertThat(handler("entityArray").bodyIsEntity()).isTrue();
        assertThat(handler("binary").returnsCollection()).isFalse();
        assertThat(handler("reactiveInput").requestBodyIsEntity()).isTrue();
        assertThat(handler("reactiveStreamInput").requestBodyIsEntity()).isTrue();
        assertThat(handler("reactiveInput").requestBodyIsSimple()).isFalse();
        assertThat(handler("binaryInput").requestBodyIsSimple()).isTrue();
    }

    @Test
    void optionalBooleanAndNonblankDefaultsAvoidThePrimitiveBindingWarning() {
        assertThat(handler("optionalNumber").hasUnboundedPrimitiveRequestParam())
                .isTrue();
        assertThat(handler("optionalBoolean").hasUnboundedPrimitiveRequestParam())
                .isFalse();
        assertThat(handler("emptyDefault").hasUnboundedPrimitiveRequestParam()).isTrue();
        assertThat(handler("blankDefault").hasUnboundedPrimitiveRequestParam()).isTrue();
        assertThat(handler("numericDefault").hasUnboundedPrimitiveRequestParam())
                .isFalse();
    }

    @Test
    void onlyUnnamedMapsAggregateAllQueryParameters() {
        assertThat(handler("aggregateMap").hasUnboundedMapRequestParam()).isTrue();
        assertThat(handler("namedMap").hasUnboundedMapRequestParam()).isFalse();
    }

    @Test
    void retainsOnlyRequiredExplicitPathBindings() {
        assertThat(handler("requiredPath").pathVariableNames()).containsExactly("id");
        assertThat(handler("requiredPath").effectivePaths()).containsExactly("/audit/required", "/audit/required/{id}");
        assertThat(handler("optionalPath").pathVariableNames()).isEmpty();
        assertThat(handler("optionalTypePath").pathVariableNames()).isEmpty();
    }

    @Test
    void preservesRouteIdentityAndTemplateInterior() {
        assertThat(handler("trailingSlash").effectivePaths()).containsExactly("/audit/slash/");
        assertThat(handler("withoutSlash").effectivePaths()).containsExactly("/audit/slash");
        assertThat(handler("regexInterior").effectivePaths()).containsExactly("/audit/regex/{id:[a-z/]+}");
        assertThat(handler("doubleSlash").effectivePaths()).containsExactly("/audit/double//slash");
        assertThat(handler("union").httpMethods()).containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    void doesNotInventSubresourceRootsOrJaxRsDispatchConditions() {
        assertThat(handler("unrooted").effectivePaths()).isEmpty();
        assertThat(handler("versionHeader").headers()).isEmpty();
        assertThat(handler("versionQuery").params()).isEmpty();
        assertThat(handler("versionHeader").versionBindings()).containsExactly("header:Api-Version");
        assertThat(handler("versionQuery").versionBindings()).containsExactly("query:version");
        assertThat(handler("versionBindings").versionBindings()).containsExactly("query:version", "header:Api-Version");
    }

    @Test
    void mixedImportsRetainPerDeclarationFrameworkIdentity() {
        assertThat(model.framework()).isEqualTo(RestApiModel.Framework.SPRING);
        assertThat(handler("mutinyEnvelope").jaxRs()).isTrue();
        assertThat(handler("asyncEnvelope").jaxRs()).isFalse();
        assertThat(exception("explicitMapper").jaxRs()).isTrue();
        assertThat(exception("aliasedException").jaxRs()).isFalse();
    }

    @Test
    void recognizesImperativeResponseArgumentsAndTagForms() {
        for (String name : List.of("servletResponse", "outputStream", "writer")) {
            assertThat(handler(name).hasResponseParam()).as(name).isTrue();
        }
        assertThat(handler("operationTags").hasTag()).isTrue();
        assertThat(handler("repeatedTags").hasTag()).isTrue();
    }

    @Test
    void readsAliasesThrowableInferenceAndExceptionMediaWithoutControllerMappingInheritance() {
        assertThat(exception("aliasedException").handledExceptionTypes())
                .containsExactly("java.lang.IllegalArgumentException");
        assertThat(exception("aliasedException").returnsResponseEntity()).isTrue();
        assertThat(exception("aliasedException").produces()).isEmpty();
        assertThat(exception("throwableParameter").handledExceptionTypes()).containsExactly("java.lang.Throwable");
        assertThat(exception("throwableParameter").catchesExceptionOrThrowable())
                .isTrue();
        assertThat(exception("throwableParameter").returnsResponseEntity()).isFalse();
        assertThat(exception("throwableParameter").rendersBody()).isTrue();
        assertThat(exception("declaredMedia").produces()).containsExactly("text/plain");
    }

    @Test
    void recognizesInheritedMappersAndExplicitQuarkusExceptionTypes() {
        assertThat(exception("toResponse").handledExceptionTypes())
                .containsExactly("java.lang.IllegalArgumentException");
        assertThat(exception("explicitMapper").handledExceptionTypes())
                .containsExactly("java.lang.IllegalArgumentException", "java.lang.IllegalStateException");
        assertThat(exception("explicitMapper").returnsResponseEntity()).isTrue();
    }

    @Test
    void recognizesReactiveFrameworkAdvice() {
        RestApiHandlerModelBuilder advice = RestApiHandlerModelBuilder.build(
                new ClassFileImporter().importClasses(RestApiAuditModelFixtures.ReactiveAdvice.class));
        assertThat(advice.hasExceptionHandling()).isTrue();
        assertThat(advice.incomplete()).isFalse();
    }

    @Test
    void preservesHealthyElementsAndMarksUnresolvableEvidenceIncomplete() {
        JavaClass unreadable = mock(JavaClass.class);
        when(unreadable.isAnnotatedWith(anyString())).thenThrow(new IllegalStateException("sensitive value"));
        JavaClasses imported = new ClassFileImporter().importClasses(RestApiAuditModelFixtures.SpringShapes.class);
        List<JavaClass> types = new ArrayList<>();
        imported.forEach(types::add);
        types.add(unreadable);
        JavaClasses partial = mock(JavaClasses.class);
        when(partial.iterator()).thenReturn(types.iterator());

        RestApiHandlerModelBuilder result = RestApiHandlerModelBuilder.build(partial);
        assertThat(result.incomplete()).isTrue();
        assertThat(result.handlers()).isNotEmpty();
        assertThat(model.incomplete()).isFalse();
    }

    @Test
    void unreadableParameterDropsTheHandlerInsteadOfInventingNegativeBindingFacts() {
        JavaClass controller = spy(new ClassFileImporter().importClass(RestApiAuditModelFixtures.SpringShapes.class));
        JavaMethod unreadable = spy(controller.getMethod("optionalNumber", int.class));
        doThrow(new IllegalStateException("sensitive parameter metadata"))
                .when(unreadable)
                .getParameters();
        doReturn(Set.of(unreadable)).when(controller).getMethods();
        JavaClasses imported = mock(JavaClasses.class);
        when(imported.iterator()).thenReturn(List.of(controller).iterator());

        RestApiHandlerModelBuilder result = RestApiHandlerModelBuilder.build(imported);
        assertThat(result.incomplete()).isTrue();
        assertThat(result.controllers()).hasSize(1);
        assertThat(result.handlers()).isEmpty();
    }
}
