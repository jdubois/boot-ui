package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import org.junit.jupiter.api.Test;

/**
 * Proves the shared model builder + rules recognise JAX-RS resources (the basis for the Quarkus REST API
 * advisor), so the same engine ruleset lights up on Quarkus exactly as it does on Spring.
 */
class JaxRsRestApiModelTests {

    private static final String GOOD = "io.github.jdubois.bootui.engine.restapi.jaxrs";
    private static final String BAD = "io.github.jdubois.bootui.engine.restapi.jaxrs.bad";

    private RestApiHandlerModelBuilder build(String pkg) {
        JavaClasses classes = new ClassFileImporter().importPackages(pkg);
        return RestApiHandlerModelBuilder.build(classes);
    }

    private RestApiContext context(String pkg) {
        RestApiHandlerModelBuilder model = build(pkg);
        return new RestApiContext(
                java.util.List.of(pkg),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                false,
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.framework());
    }

    @Test
    void modelsJaxRsResourcesAsControllers() {
        RestApiHandlerModelBuilder model = build(GOOD);
        assertThat(model.framework()).isEqualTo(RestApiModel.Framework.JAX_RS);
        ControllerModel widgets = model.controllers().stream()
                .filter(c -> c.simpleName().equals("GoodWidgetResource"))
                .findFirst()
                .orElseThrow();
        assertThat(widgets.restController()).isTrue();
        assertThat(widgets.typeLevelPaths()).contains("/widgets");
        assertThat(model.handlers()).extracting(HandlerMethodModel::methodName).contains("list", "get", "create");
        assertThat(model.hasExceptionHandling()).isTrue();
    }

    @Test
    void detectsRequestBodyAndPathVariable() {
        HandlerMethodModel create = build(GOOD).handlers().stream()
                .filter(h -> h.methodName().equals("create"))
                .findFirst()
                .orElseThrow();
        assertThat(create.hasRequestBody()).isTrue();
        assertThat(create.httpMethods()).contains("POST");
        HandlerMethodModel get = build(GOOD).handlers().stream()
                .filter(h -> h.methodName().equals("get"))
                .findFirst()
                .orElseThrow();
        assertThat(get.pathVariableNames()).contains("id");
    }

    @Test
    void stateChangingGetRuleFiresOnJaxRs() {
        String status =
                new StateChangingHandlersNotOnGetRule().evaluate(context(BAD)).status();
        assertThat(status).isEqualTo(RestApiRuleSupport.VIOLATION);
    }

    @Test
    void voidHandlersModelTheImplicitJaxRsNoContentStatus() {
        // Per the Jakarta REST spec, a void resource method always answers 204 No Content — unlike
        // Spring MVC, which defaults an unannotated void handler to 200 OK. GoodWidgetResource#delete
        // has no status-setting annotation at all, yet the model must still resolve NO_CONTENT so
        // RAPI-RESP-002 does not mistake it for the Spring "silently defaults to 200 OK" footgun.
        HandlerMethodModel delete = build(GOOD).handlers().stream()
                .filter(h -> h.methodName().equals("delete"))
                .findFirst()
                .orElseThrow();
        assertThat(delete.returnsVoid()).isTrue();
        assertThat(delete.hasResponseStatus()).isTrue();
        assertThat(delete.methodHasResponseStatus()).isFalse();
        assertThat(delete.responseStatusValue()).isEqualTo("NO_CONTENT");
    }

    @Test
    void voidDeleteRuleDoesNotFireOnJaxRs() {
        String status = new VoidDeleteReturns204Rule().evaluate(context(GOOD)).status();
        assertThat(status).isEqualTo(RestApiRuleSupport.PASS);
    }

    @Test
    void voidReadRuleDoesNotFireOnJaxRs() {
        HandlerMethodModel probe = build(GOOD).handlers().stream()
                .filter(h -> h.methodName().equals("probe"))
                .findFirst()
                .orElseThrow();
        assertThat(probe.httpMethods()).contains("GET");
        assertThat(probe.returnsVoid()).isTrue();

        String status =
                new VoidReadEndpointsReturnContentRule().evaluate(context(GOOD)).status();
        assertThat(status).isEqualTo(RestApiRuleSupport.PASS);
    }

    @Test
    void broadThrowsRuleFiresOnJaxRs() {
        // A "throws Exception/Throwable" is a plain JVM method-signature fact, not a Spring-only one,
        // so RAPI-ERR-002 must fire on a JAX-RS resource method exactly as it does on Spring.
        HandlerMethodModel read = build(BAD).handlers().stream()
                .filter(h -> h.methodName().equals("read"))
                .findFirst()
                .orElseThrow();
        assertThat(read.declaresBroadThrows()).isTrue();

        String status = new NoBroadThrowsOnHandlersRule().evaluate(context(BAD)).status();
        assertThat(status).isEqualTo(RestApiRuleSupport.VIOLATION);
    }
}
