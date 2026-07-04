package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
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
        return context(pkg, false);
    }

    private RestApiContext context(String pkg, boolean openApiAnnotationsPresent) {
        RestApiHandlerModelBuilder model = build(pkg);
        return new RestApiContext(
                java.util.List.of(pkg),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                openApiAnnotationsPresent,
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

    @Test
    void unwrapsMutinyUniAndRestResponseToTheRealBodyType() {
        // Fix #1: Uni<T>/RestResponse<T> must unwrap to T, exactly like Spring WebFlux's Mono<T> already
        // does — otherwise bodyTypeName resolves to the wrapper class itself, silently blinding the
        // DTO/pagination/naming rules on idiomatic Quarkus reactive handlers.
        String widgetDto = "io.github.jdubois.bootui.engine.restapi.jaxrs.WidgetDto";
        RestApiHandlerModelBuilder model = build(GOOD);

        HandlerMethodModel uniHandler = model.handlers().stream()
                .filter(h -> h.methodName().equals("getOne"))
                .findFirst()
                .orElseThrow();
        assertThat(uniHandler.bodyTypeName()).isEqualTo(widgetDto);
        assertThat(uniHandler.returnsCollection()).isFalse();

        HandlerMethodModel typedHandler = model.handlers().stream()
                .filter(h -> h.methodName().equals("getTyped"))
                .findFirst()
                .orElseThrow();
        assertThat(typedHandler.bodyTypeName()).isEqualTo(widgetDto);
        assertThat(typedHandler.returnsResponseEntity()).isTrue();
    }

    @Test
    void unwrapsMutinyMultiAsACollectionOfTheRealBodyType() {
        // Multi<T> is Mutiny's async-stream analogue of reactor.core.publisher.Flux<T> and must be
        // recognised as a collection wrapper the same way, so returnsCollection()/bodyTypeName() resolve
        // correctly instead of reporting the handler as returning a plain "Multi".
        HandlerMethodModel multiHandler = build(GOOD).handlers().stream()
                .filter(h -> h.methodName().equals("getAll"))
                .findFirst()
                .orElseThrow();
        assertThat(multiHandler.returnsCollection()).isTrue();
        assertThat(multiHandler.bodyTypeName()).isEqualTo("io.github.jdubois.bootui.engine.restapi.jaxrs.WidgetDto");
    }

    @Test
    void collectionReadsArePaginatedRuleNowSeesThroughTheMultiWrapper() {
        // Before fix #1, Multi<WidgetDto> resolved as an unrecognised, non-collection body type, so this
        // unpaginated collection GET was silently invisible to RAPI-PAGE-001 on Quarkus reactive resources.
        RestApiRuleResultDto result = new CollectionReadsArePaginatedRule().evaluate(context(GOOD));
        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(violation -> violation.contains("getAll"));
    }

    @Test
    void serverExceptionMapperMethodIsModeledAsAnExceptionHandler() {
        // Fix #2: a RESTEasy Reactive @ServerExceptionMapper method (no @Provider required) must populate
        // a full ExceptionHandlerModel, not just the boolean hasExceptionHandling flag, so RAPI-ERR-004/005
        // can actually evaluate its quality instead of vacuously passing.
        RestApiHandlerModelBuilder model = build(GOOD + ".serverexceptionmapper");
        assertThat(model.hasExceptionHandling()).isTrue();
        ExceptionHandlerModel handler = model.exceptionHandlers().stream()
                .filter(h -> h.methodName().equals("mapIllegalState"))
                .findFirst()
                .orElseThrow();
        assertThat(handler.declaringClassName())
                .isEqualTo("io.github.jdubois.bootui.engine.restapi.jaxrs.serverexceptionmapper"
                        + ".WidgetServerExceptionMapper");
        assertThat(handler.catchesExceptionOrThrowable()).isFalse();
        assertThat(handler.returnsResponseEntity()).isTrue();
    }

    @Test
    void centralizedExceptionHandlingRulePassesForAServerExceptionMapperOnlyApp() {
        // Before fix #2, only the classic @Provider ExceptionMapper<X> style was recognised, so a
        // well-designed Quarkus app using only @ServerExceptionMapper false-flagged RAPI-ERR-001.
        RestApiContext context = context(GOOD + ".serverexceptionmapper");
        assertThat(context.controllers()).isNotEmpty();
        String status = new CentralizedExceptionHandlingRule().evaluate(context).status();
        assertThat(status).isEqualTo(RestApiRuleSupport.PASS);
    }

    @Test
    void microProfileOpenApiOperationAndTagAnnotationsAreRecognized() {
        // Fix #5: MicroProfile OpenAPI's own @Operation/@Tag (org.eclipse.microprofile.openapi.annotations)
        // are framework-neutral annotations that quarkus-smallrye-openapi honors identically to Swagger's;
        // a Quarkus app using only these must not be treated as undocumented.
        RestApiHandlerModelBuilder model = build(GOOD + ".mpopenapi");
        HandlerMethodModel list = model.handlers().stream()
                .filter(h -> h.methodName().equals("list"))
                .findFirst()
                .orElseThrow();
        assertThat(list.hasOperationAnnotation()).isTrue();
        // @Tag is applied at the class level on the fixture; HandlerMethodModel#hasTag() is method-level
        // only (symmetric with the Spring path), so class-level tagging is asserted on ControllerModel.
        ControllerModel controller = model.controllers().stream()
                .filter(c -> c.className().equals(list.controllerClassName()))
                .findFirst()
                .orElseThrow();
        assertThat(controller.hasTag()).isTrue();

        RestApiContext context = context(GOOD + ".mpopenapi", true);
        assertThat(new EndpointsAreDocumentedRule().evaluate(context).status()).isEqualTo(RestApiRuleSupport.PASS);
        assertThat(new ControllersAreTaggedRule().evaluate(context).status()).isEqualTo(RestApiRuleSupport.PASS);
    }

    @Test
    void headerAndQueryParamVersionSignalsAreRecognizedOnJaxRs() {
        // Fix #6: JAX-RS's @HeaderParam/@QueryParam are exact analogues of Spring's
        // @RequestHeader/@RequestParam for version-signal detection; before the fix, toJaxRsHandler left
        // params/headers empty so these could never be recognised as a versioning strategy.
        RestApiHandlerModelBuilder model = build(GOOD + ".versioning");

        HandlerMethodModel headerHandler = model.handlers().stream()
                .filter(h -> h.methodName().equals("getByHeader"))
                .findFirst()
                .orElseThrow();
        assertThat(headerHandler.headers()).contains("Api-Version");
        assertThat(RestApiRuleHelp.hasVersionSignal(headerHandler)).isTrue();

        HandlerMethodModel queryHandler = model.handlers().stream()
                .filter(h -> h.methodName().equals("getByQuery"))
                .findFirst()
                .orElseThrow();
        assertThat(queryHandler.params()).contains("version");
        assertThat(RestApiRuleHelp.hasVersionSignal(queryHandler)).isTrue();
    }

    @Test
    void catchAllPatternRuleFlagsAnAllMatchingRegexButNotAConstrainedOne() {
        // Fix #7: a JAX-RS {token:regex} template whose regex is all-matching (.*/.+) is the JAX-RS
        // analogue of Spring's /** or {*path} catch-all and must be flagged, while a genuinely constrained
        // template like {id:[0-9]+} must still pass.
        RestApiRuleResultDto result = new CatchAllPatternRule().evaluate(context(GOOD + ".catchall"));
        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(violation -> violation.contains("CatchAllRegexResource"));
        assertThat(result.sampleViolations()).noneMatch(violation -> violation.contains("ConstrainedIdResource"));
    }
}
