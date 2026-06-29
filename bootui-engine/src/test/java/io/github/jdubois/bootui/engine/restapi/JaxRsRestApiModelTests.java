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
        RestApiHandlerModelBuilder model = build(BAD);
        RestApiContext context = new RestApiContext(
                java.util.List.of(BAD),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                false,
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.framework());
        String status =
                new StateChangingHandlersNotOnGetRule().evaluate(context).status();
        assertThat(status).isEqualTo(RestApiRuleSupport.VIOLATION);
    }
}
