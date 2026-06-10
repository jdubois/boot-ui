package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.HandlerMethodModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RestApiHandlerModelBuilderTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restapi.fixtures";
    private static final String EDGE = "io.github.jdubois.bootui.autoconfigure.restapi.edgecases";
    private static final String PHASE3_BAD = "io.github.jdubois.bootui.autoconfigure.restapi.phase3.bad";
    private static final String PHASE3_FIXES = "io.github.jdubois.bootui.autoconfigure.restapi.phase3.fixes";

    private RestApiHandlerModelBuilder model() {
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURES);
        return RestApiHandlerModelBuilder.build(classes);
    }

    private HandlerMethodModel handler(RestApiHandlerModelBuilder model, String name) {
        return model.handlers().stream()
                .filter(handler -> handler.methodName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No handler named " + name));
    }

    @Test
    void detectsControllersAndHandlers() {
        RestApiHandlerModelBuilder model = model();

        assertThat(model.controllers())
                .extracting(ControllerModel::simpleName)
                .contains("GoodUserController", "BadOrderController");
        assertThat(model.handlers())
                .extracting(HandlerMethodModel::methodName)
                .contains("listUsers", "getUser", "createUser", "deleteUser", "getOrders", "legacy");
    }

    @Test
    void extractsHttpMethodsAndEffectivePaths() {
        RestApiHandlerModelBuilder model = model();

        HandlerMethodModel listUsers = handler(model, "listUsers");
        assertThat(listUsers.httpMethods()).containsExactly("GET");
        assertThat(listUsers.explicitHttpMethod()).isTrue();
        assertThat(listUsers.effectivePaths()).containsExactly("/api/v1/users");
        assertThat(listUsers.returnsPageOrSlice()).isTrue();
        assertThat(listUsers.hasPageable()).isTrue();
        assertThat(listUsers.produces()).contains("application/json");

        HandlerMethodModel getUser = handler(model, "getUser");
        assertThat(getUser.effectivePaths()).containsExactly("/api/v1/users/{id}");
    }

    @Test
    void requestMappingWithoutMethodHasNoExplicitHttpMethod() {
        HandlerMethodModel legacy = handler(model(), "legacy");

        assertThat(legacy.httpMethods()).isEmpty();
        assertThat(legacy.explicitHttpMethod()).isFalse();
    }

    @Test
    void detectsValidatedRequestBodyVersusUnvalidated() {
        RestApiHandlerModelBuilder model = model();

        HandlerMethodModel createUser = handler(model, "createUser");
        assertThat(createUser.hasRequestBody()).isTrue();
        assertThat(createUser.requestBodyValidated()).isTrue();
        assertThat(createUser.requestBodyIsEntity()).isFalse();
        assertThat(createUser.controllerValidated()).isTrue();
        // createUser now signals creation via ResponseEntity.created(...) rather than @ResponseStatus.
        assertThat(createUser.returnsResponseEntity()).isTrue();
        assertThat(createUser.responseStatusValue()).isEmpty();

        HandlerMethodModel createOrder = handler(model, "createOrder");
        assertThat(createOrder.hasRequestBody()).isTrue();
        assertThat(createOrder.requestBodyValidated()).isFalse();
        assertThat(createOrder.requestBodyIsEntity()).isTrue();
        assertThat(createOrder.controllerValidated()).isFalse();
    }

    @Test
    void detectsEntityCollectionsAndScalarBodies() {
        RestApiHandlerModelBuilder model = model();

        HandlerMethodModel getOrders = handler(model, "getOrders");
        assertThat(getOrders.returnsCollection()).isTrue();
        assertThat(getOrders.bodyIsEntity()).isTrue();

        HandlerMethodModel count = handler(model, "count");
        assertThat(count.bodyIsScalar()).isTrue();
        assertThat(count.returnsCollection()).isFalse();

        HandlerMethodModel find = handler(model, "find");
        assertThat(find.declaresBroadThrows()).isTrue();
        assertThat(find.bodyExposesSetters()).isTrue();
    }

    @Test
    void detectsTrailingSlashAndResponseStatusDefaults() {
        RestApiHandlerModelBuilder model = model();

        HandlerMethodModel getOrder = handler(model, "getOrder");
        assertThat(getOrder.mappingPaths()).anyMatch(path -> path.endsWith("/"));

        HandlerMethodModel deleteUser = handler(model, "deleteUser");
        assertThat(deleteUser.returnsVoid()).isTrue();
        assertThat(deleteUser.responseStatusValue()).isEqualTo("NO_CONTENT");
    }

    @Test
    void detectsCentralizedExceptionHandlingReturningProblemDetail() {
        RestApiHandlerModelBuilder model = model();

        assertThat(model.hasExceptionHandling()).isTrue();
        Optional<RestApiModel.ExceptionHandlerModel> handler = model.exceptionHandlers().stream()
                .filter(candidate -> candidate.methodName().equals("handleBadRequest"))
                .findFirst();
        assertThat(handler).isPresent();
        assertThat(handler.orElseThrow().returnsProblemType()).isTrue();
    }

    @Test
    void derivesNewerHandlerFactsFromEdgeCases() {
        JavaClasses classes = new ClassFileImporter().importPackages(EDGE);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        HandlerMethodModel getWidget = handler(model, "getWidget");
        assertThat(getWidget.pathVariableNames()).containsExactly("widgetId");

        HandlerMethodModel scan = handler(model, "scan");
        assertThat(scan.hasUnboundedPrimitiveRequestParam()).isTrue();

        HandlerMethodModel createWidget = handler(model, "createWidget");
        assertThat(createWidget.hasRequestBody()).isTrue();
        assertThat(createWidget.effectiveConsumes()).isEmpty();

        HandlerMethodModel patchWidget = handler(model, "patchWidget");
        assertThat(patchWidget.effectiveConsumes()).contains("application/json");

        HandlerMethodModel removeWidget = handler(model, "removeWidget");
        assertThat(removeWidget.serializesBody()).isTrue();
    }

    @Test
    void buildIsNullSafeOnEmptyImport() {
        RestApiHandlerModelBuilder model =
                RestApiHandlerModelBuilder.build(new ClassFileImporter().importPackages(List.of("does.not.exist")));

        assertThat(model.controllers()).isEmpty();
        assertThat(model.handlers()).isEmpty();
        assertThat(model.exceptionHandlers()).isEmpty();
        assertThat(model.hasExceptionHandling()).isFalse();
    }

    @Test
    void detectsTypeLevelHttpMethodInheritance() {
        JavaClasses classes = new ClassFileImporter().importPackages(PHASE3_FIXES);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        // TypeLevelMethodController has @RequestMapping(method=GET) at class level.
        HandlerMethodModel getData = model.handlers().stream()
                .filter(h -> h.methodName().equals("getData"))
                .findFirst()
                .orElseThrow();

        assertThat(getData.httpMethods()).contains("GET");
        assertThat(getData.explicitHttpMethod()).isTrue();
    }

    @Test
    void detectsMethodHasResponseStatusVsClassLevelOnly() {
        JavaClasses classes = new ClassFileImporter().importPackages(PHASE3_FIXES);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        // ClassStatusController has @ResponseStatus at class level — method is NOT annotated.
        HandlerMethodModel get = model.handlers().stream()
                .filter(h -> h.controllerSimpleName().equals("ClassStatusController"))
                .findFirst()
                .orElseThrow();

        assertThat(get.hasResponseStatus()).isTrue(); // class-level counts
        assertThat(get.methodHasResponseStatus()).isFalse(); // method-level does not
    }

    @Test
    void detectsUnboundedMapRequestParam() {
        JavaClasses classes = new ClassFileImporter().importPackages(PHASE3_BAD);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        HandlerMethodModel searchWithMap = model.handlers().stream()
                .filter(h -> h.methodName().equals("searchWithMap"))
                .findFirst()
                .orElseThrow();

        assertThat(searchWithMap.hasUnboundedMapRequestParam()).isTrue();
    }

    @Test
    void detectsLegacyDateFieldInResponseDto() {
        JavaClasses classes = new ClassFileImporter().importPackages(PHASE3_BAD);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        HandlerMethodModel getDated = model.handlers().stream()
                .filter(h -> h.methodName().equals("getDated"))
                .findFirst()
                .orElseThrow();

        assertThat(getDated.bodyHasLegacyDateField()).isTrue();
    }

    @Test
    void detectsCatchesExceptionOrThrowable() {
        JavaClasses classes = new ClassFileImporter().importPackages(PHASE3_BAD);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        assertThat(model.exceptionHandlers())
                .filteredOn(ExceptionHandlerModel::catchesExceptionOrThrowable)
                .isNotEmpty();
    }

    @Test
    void detectsResponseStatusAnnotatedExceptionClasses() {
        JavaClasses classes = new ClassFileImporter().importPackages(PHASE3_BAD);
        RestApiHandlerModelBuilder model = RestApiHandlerModelBuilder.build(classes);

        assertThat(model.responseStatusExceptionClasses()).isNotEmpty();
        assertThat(model.responseStatusExceptionClasses()).anyMatch(name -> name.contains("BizException"));
    }
}
