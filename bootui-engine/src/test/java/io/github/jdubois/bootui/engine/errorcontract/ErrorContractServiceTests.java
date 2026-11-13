package io.github.jdubois.bootui.engine.errorcontract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.ErrorContractEntryDto;
import io.github.jdubois.bootui.core.dto.ErrorContractLinkDto;
import io.github.jdubois.bootui.core.dto.ErrorContractReport;
import io.github.jdubois.bootui.spi.ErrorContractProvider;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavior of the framework-neutral error-contract engine: classification, precedence, ordering, bounding,
 * query/paging, availability, and the deliberately conservative Exceptions cross-link.
 */
class ErrorContractServiceTests {

    private static final String ADVICE = ErrorHandlerDescriptor.SPRING_CONTROLLER_ADVICE;
    private static final String CONTROLLER = ErrorHandlerDescriptor.SPRING_CONTROLLER;
    private static final String MAPPER = ErrorHandlerDescriptor.JAKARTA_REST_EXCEPTION_MAPPER;
    private static final String GLOBAL = ErrorHandlerDescriptor.SCOPE_GLOBAL;

    @Test
    void reportsExplicitlyUnavailableWhenNoBackendIsWired() {
        ErrorContractReport report = new ErrorContractService(null).report(null, null, null);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isNotBlank();
        assertThat(report.entries()).isEmpty();
        assertThat(report.total()).isZero();
        assertThat(report.page()).isNotNull();
    }

    @Test
    void reportsTheProviderReasonWhenTheBackendIsPresentButUnavailable() {
        ErrorContractProvider unavailable = new StubProvider(List.of(), false, "Capture is not wired here.");

        ErrorContractReport report = new ErrorContractService(unavailable).report(null, null, null);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("Capture is not wired here.");
    }

    @Test
    void anApplicationWithoutHandlersIsAvailableAndEmpty() {
        ErrorContractReport report = service(List.of()).report(null, null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.unavailableReason()).isNull();
        assertThat(report.total()).isZero();
        assertThat(report.entries()).isEmpty();
    }

    @Test
    void oneDeclarationPerHandledExceptionTypeIsCatalogued() {
        ErrorContractReport report = service(List.of(descriptor(
                        ADVICE,
                        "com.acme.GlobalAdvice",
                        "handle",
                        List.of("com.acme.NotFound", "com.acme.Conflict"),
                        ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .report(null, null, null);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.handlerCount()).isEqualTo(1);
        assertThat(report.componentCount()).isEqualTo(1);
        assertThat(report.exceptionTypeCount()).isEqualTo(2);
        assertThat(report.entries())
                .extracting(ErrorContractEntryDto::exceptionSimpleName)
                .containsExactly("Conflict", "NotFound");
    }

    @Test
    void aControllerLocalHandlerOutranksAnApplicationWideOne() {
        ErrorContractReport report = service(List.of(
                        descriptor(
                                ADVICE,
                                "com.acme.GlobalAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL),
                        local("com.acme.OrderController", "handleNotFound", "com.acme.NotFound")))
                .report(null, null, null);

        assertThat(report.entries())
                .extracting(ErrorContractEntryDto::component, ErrorContractEntryDto::precedence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("com.acme.OrderController", 1),
                        org.assertj.core.groups.Tuple.tuple("com.acme.GlobalAdvice", 2));
        assertThat(report.entries())
                .allSatisfy(entry -> assertThat(entry.precedenceSource()).isEqualTo("DEFAULT"));
    }

    @Test
    void aDeclaredOrderResolvesPrecedenceBetweenTwoApplicationWideHandlers() {
        ErrorHandlerDescriptor first = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.FirstAdvice",
                "handle",
                List.of("com.acme.NotFound"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                1,
                false,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of());
        ErrorHandlerDescriptor second = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.SecondAdvice",
                "handle",
                List.of("com.acme.NotFound"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                5,
                false,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of());

        ErrorContractReport report = service(List.of(second, first)).report(null, null, null);

        assertThat(report.entries())
                .extracting(ErrorContractEntryDto::component, ErrorContractEntryDto::precedence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("com.acme.FirstAdvice", 1),
                        org.assertj.core.groups.Tuple.tuple("com.acme.SecondAdvice", 2));
        assertThat(report.entries())
                .allSatisfy(entry -> assertThat(entry.precedenceSource()).isEqualTo("DECLARED"));
    }

    @Test
    void indistinguishableHandlersShareARankAndStayUnresolved() {
        ErrorContractReport report = service(List.of(
                        descriptor(
                                ADVICE,
                                "com.acme.OneAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL),
                        descriptor(
                                ADVICE,
                                "com.acme.TwoAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .report(null, null, null);

        assertThat(report.entries())
                .extracting(ErrorContractEntryDto::precedence)
                .containsExactly(1, 1);
        assertThat(report.entries())
                .allSatisfy(entry -> assertThat(entry.precedenceSource()).isEqualTo("UNRESOLVED"));
    }

    @Test
    void bodyCategoriesAreDerivedFromTheDeclarationOnly() {
        assertThat(bodyCategoryOf("org.springframework.http.ProblemDetail", null, false))
                .isEqualTo("PROBLEM_DETAIL");
        assertThat(bodyCategoryOf("org.springframework.web.ErrorResponse", null, false))
                .isEqualTo("PROBLEM_DETAIL");
        assertThat(bodyCategoryOf("org.springframework.web.ErrorResponseException", null, false))
                .isEqualTo("PROBLEM_DETAIL");
        assertThat(bodyCategoryOf("org.springframework.web.server.ResponseStatusException", null, false))
                .isEqualTo("PROBLEM_DETAIL");
        assertThat(bodyCategoryOf("com.acme.ErrorBody", null, false)).isEqualTo("CUSTOM_OBJECT");
        assertThat(bodyCategoryOf("java.lang.String", null, false)).isEqualTo("STRING");
        assertThat(bodyCategoryOf(null, "void", false)).isEqualTo("EMPTY");
        assertThat(bodyCategoryOf("kotlin.Unit", "kotlin.Unit", false)).isEqualTo("EMPTY");
        assertThat(bodyCategoryOf("java.util.Map", null, false)).isEqualTo("DYNAMIC");
        assertThat(bodyCategoryOf("com.fasterxml.jackson.databind.JsonNode", null, false))
                .isEqualTo("DYNAMIC");
        assertThat(bodyCategoryOf("com.fasterxml.jackson.databind.node.ObjectNode", null, false))
                .isEqualTo("DYNAMIC");
        assertThat(bodyCategoryOf("tools.jackson.databind.JsonNode", null, false))
                .isEqualTo("DYNAMIC");
        assertThat(bodyCategoryOf("tools.jackson.databind.node.ObjectNode", null, false))
                .isEqualTo("DYNAMIC");
        assertThat(bodyCategoryOf(null, "jakarta.ws.rs.core.Response", true)).isEqualTo("DYNAMIC");
        assertThat(bodyCategoryOf(null, "com.acme.Unknown", false)).isEqualTo("UNRESOLVED");
    }

    @Test
    void problemDetailAndErrorResponseTypesDefaultToDynamicStatusSourceWhenUnannotated() {
        ErrorHandlerDescriptor problemDetail = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.Advice",
                "handleProblem",
                List.of("com.acme.BadInput"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                false,
                null,
                false,
                "org.springframework.http.ProblemDetail",
                "org.springframework.http.ProblemDetail",
                List.of());
        ErrorHandlerDescriptor errorResponse = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.Advice",
                "handleErrorResponse",
                List.of("com.acme.Conflict"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                false,
                null,
                false,
                "org.springframework.web.ErrorResponseException",
                "org.springframework.web.ErrorResponseException",
                List.of());

        ErrorHandlerDescriptor httpEntityProblem = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.Advice",
                "handleHttpEntityProblem",
                List.of("com.acme.HeaderOnly"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                false,
                null,
                false,
                "org.springframework.http.HttpEntity",
                "org.springframework.http.ProblemDetail",
                List.of());

        List<ErrorContractEntryDto> entries = service(List.of(problemDetail, errorResponse, httpEntityProblem))
                .report(null, null, null)
                .entries();

        assertThat(entries)
                .extracting(
                        ErrorContractEntryDto::method,
                        ErrorContractEntryDto::statusSource,
                        ErrorContractEntryDto::bodyCategory)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("handleProblem", "DYNAMIC", "PROBLEM_DETAIL"),
                        org.assertj.core.groups.Tuple.tuple("handleErrorResponse", "DYNAMIC", "PROBLEM_DETAIL"),
                        org.assertj.core.groups.Tuple.tuple("handleHttpEntityProblem", "UNRESOLVED", "PROBLEM_DETAIL"));
    }

    @Test
    void aDeclaredStatusIsReportedAsAnnotationSourcedAndARuntimeStatusStaysDynamic() {
        ErrorHandlerDescriptor annotated = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.Advice",
                "handle",
                List.of("com.acme.NotFound"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                false,
                "404",
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of("application/problem+json"));
        ErrorHandlerDescriptor dynamic = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.Advice",
                "handleConflict",
                List.of("com.acme.Conflict"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                false,
                null,
                true,
                "org.springframework.http.ResponseEntity",
                "com.acme.ErrorBody",
                List.of());

        List<ErrorContractEntryDto> entries =
                service(List.of(annotated, dynamic)).report(null, null, null).entries();

        assertThat(entries)
                .filteredOn(entry -> entry.method().equals("handle"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo("404");
                    assertThat(entry.statusSource()).isEqualTo("ANNOTATION");
                    assertThat(entry.produces()).containsExactly("application/problem+json");
                });
        assertThat(entries)
                .filteredOn(entry -> entry.method().equals("handleConflict"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isNull();
                    assertThat(entry.statusSource()).isEqualTo("DYNAMIC");
                });
    }

    @Test
    void theQueryAndPagingRunOnTheServer() {
        List<ErrorHandlerDescriptor> handlers = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            handlers.add(descriptor(
                    ADVICE,
                    "com.acme.Advice",
                    "handle" + index,
                    List.of("com.acme.Failure" + index),
                    ErrorHandlerDescriptor.SCOPE_GLOBAL));
        }
        ErrorContractService service = service(handlers);

        ErrorContractReport firstPage = service.report(null, 0, 5);
        assertThat(firstPage.entries()).hasSize(5);
        assertThat(firstPage.page().total()).isEqualTo(12);
        assertThat(firstPage.page().matched()).isEqualTo(12);

        ErrorContractReport filtered = service.report("Failure7", null, null);
        assertThat(filtered.entries()).hasSize(1);
        assertThat(filtered.page().matched()).isEqualTo(1);
        assertThat(filtered.page().total()).isEqualTo(12);
    }

    @Test
    void theCatalogueIsBoundedAndSaysSoWhenItTruncates() {
        List<ErrorHandlerDescriptor> handlers = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            handlers.add(descriptor(
                    ADVICE,
                    "com.acme.Advice",
                    "handle" + index,
                    List.of("com.acme.Failure" + index),
                    ErrorHandlerDescriptor.SCOPE_GLOBAL));
        }
        ErrorContractService service = new ErrorContractService(new StubProvider(handlers, true, null), 4);

        ErrorContractReport report = service.report(null, null, null);

        assertThat(report.truncated()).isTrue();
        assertThat(report.maxEntries()).isEqualTo(4);
        assertThat(report.total()).isEqualTo(4);
        assertThat(report.entries()).hasSize(4);
    }

    // --- Cross-link ---------------------------------------------------------------------------

    @Test
    void aSingleApplicationWideHandlerIsLinked() {
        ErrorContractLinkDto link = service(List.of(descriptor(
                        ADVICE,
                        "com.acme.GlobalAdvice",
                        "handle",
                        List.of("com.acme.NotFound"),
                        ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .resolve("com.acme.NotFound", "com.acme.OrderController#get");

        assertThat(link).isNotNull();
        assertThat(link.component()).isEqualTo("com.acme.GlobalAdvice");
        assertThat(link.componentSimpleName()).isEqualTo("GlobalAdvice");
        assertThat(link.method()).isEqualTo("handle");
        assertThat(link.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_GLOBAL);
    }

    @Test
    void anUnhandledExceptionTypeIsNotLinked() {
        ErrorContractLinkDto link = service(List.of(descriptor(
                        ADVICE,
                        "com.acme.GlobalAdvice",
                        "handle",
                        List.of("com.acme.NotFound"),
                        ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .resolve("com.acme.SomethingElse", "com.acme.OrderController#get");

        assertThat(link).isNull();
    }

    @Test
    void aSupertypeHandlerIsNotAttributed() {
        ErrorContractLinkDto link = service(List.of(descriptor(
                        ADVICE,
                        "com.acme.GlobalAdvice",
                        "handle",
                        List.of("java.lang.RuntimeException"),
                        ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .resolve("com.acme.NotFound", "com.acme.OrderController#get");

        assertThat(link).isNull();
    }

    @Test
    void aSelectorScopedCandidateMakesTheWholeResolutionAmbiguous() {
        ErrorHandlerDescriptor scoped = descriptor(
                ADVICE,
                "com.acme.ScopedAdvice",
                "handle",
                List.of("com.acme.NotFound"),
                ErrorHandlerDescriptor.SCOPE_SCOPED);

        ErrorContractLinkDto link = service(List.of(
                        scoped,
                        descriptor(
                                ADVICE,
                                "com.acme.GlobalAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .resolve("com.acme.NotFound", "com.acme.OrderController#get");

        assertThat(link).isNull();
    }

    @Test
    void aControllerLocalHandlerIsLinkedOnlyWhenTheEvidenceNamesItsController() {
        ErrorContractService service =
                service(List.of(local("com.acme.OrderController", "handle", "com.acme.NotFound")));

        assertThat(service.resolve("com.acme.NotFound", "com.acme.OrderController#get"))
                .isNotNull();
        assertThat(service.resolve("com.acme.NotFound", null)).isNull();
        assertThat(service.resolve("com.acme.NotFound", "com.acme.OtherController#get"))
                .isNull();
    }

    @Test
    void aControllerLocalHandlerIsLinkedFromTheSimpleNamedEvidenceBothAdaptersActuallyRecord() {
        // Regression: both adapters record evidence as "SimpleName#method"
        // (BootUiExceptionHandlerResolver, QuarkusResourceHandlers), so matching only fully-qualified
        // evidence silently never linked a controller-local handler in a running application.
        ErrorContractService service =
                service(List.of(local("com.acme.OrderController", "handle", "com.acme.NotFound")));

        assertThat(service.resolve("com.acme.NotFound", "OrderController#get")).isNotNull();
        assertThat(service.resolve("com.acme.NotFound", "OrderController")).isNotNull();
        assertThat(service.resolve("com.acme.NotFound", "AdminOrderController#get"))
                .isNull();
    }

    @Test
    void aControllerLocalHandlerInAnotherControllerFallsBackToTheApplicationWideOne() {
        ErrorContractLinkDto link = service(List.of(
                        local("com.acme.OrderController", "handle", "com.acme.NotFound"),
                        descriptor(
                                ADVICE,
                                "com.acme.GlobalAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .resolve("com.acme.NotFound", "com.acme.BillingController#charge");

        assertThat(link).isNotNull();
        assertThat(link.component()).isEqualTo("com.acme.GlobalAdvice");
    }

    @Test
    void twoIndistinguishableApplicationWideHandlersAreNotLinked() {
        ErrorContractLinkDto link = service(List.of(
                        descriptor(
                                ADVICE,
                                "com.acme.OneAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL),
                        descriptor(
                                ADVICE,
                                "com.acme.TwoAdvice",
                                "handle",
                                List.of("com.acme.NotFound"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .resolve("com.acme.NotFound", "com.acme.OrderController#get");

        assertThat(link).isNull();
    }

    @Test
    void nothingIsLinkedWhenTheBackendIsUnavailable() {
        assertThat(new ErrorContractService(null).resolve("com.acme.NotFound", "com.acme.OrderController#get"))
                .isNull();
    }

    @Test
    void aQuarkusMapperProducesTheSameNeutralShapeAsSpringAdvice() {
        ErrorContractEntryDto quarkus = service(List.of(new ErrorHandlerDescriptor(
                        MAPPER,
                        "com.acme.NotFoundMapper",
                        "toResponse",
                        List.of("com.acme.NotFound"),
                        ErrorHandlerDescriptor.SCOPE_GLOBAL,
                        null,
                        null,
                        false,
                        null,
                        true,
                        "jakarta.ws.rs.core.Response",
                        null,
                        List.of("application/json"))))
                .report(null, null, null)
                .entries()
                .get(0);

        assertThat(quarkus.source()).isEqualTo(MAPPER);
        assertThat(quarkus.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_GLOBAL);
        assertThat(quarkus.statusSource()).isEqualTo("DYNAMIC");
        assertThat(quarkus.bodyCategory()).isEqualTo("DYNAMIC");
        assertThat(quarkus.precedence()).isEqualTo(1);
    }

    @Test
    void excludesTheFrameworksOwnHandlersSoAnApplicationWithoutDeclarationsIsEmpty() {
        // Every Spring Boot app inherits BasicErrorController and every Quarkus app inherits the built-in
        // RESTEasy Reactive mappers. The panel promises an empty catalogue for an application that
        // declares nothing, so the framework's own handlers are not part of the contract.
        ErrorContractReport report = service(List.of(
                        descriptor(
                                CONTROLLER,
                                "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController",
                                "mediaTypeNotAcceptable",
                                List.of("org.springframework.web.HttpMediaTypeNotAcceptableException"),
                                ErrorHandlerDescriptor.SCOPE_CONTROLLER),
                        descriptor(
                                MAPPER,
                                "io.quarkus.resteasy.reactive.server.runtime.exceptionmappers"
                                        + ".AuthenticationFailedExceptionMapper",
                                "handle",
                                List.of("io.quarkus.security.AuthenticationFailedException"),
                                ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .report(null, null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.entries()).isEmpty();
        assertThat(report.total()).isZero();
        assertThat(report.handlerCount()).isZero();
        assertThat(report.componentCount()).isZero();
    }

    @Test
    void stillReportsAnApplicationHandlerForAFrameworkException() {
        // Only the declaring component decides ownership: mapping a framework exception is still the
        // application's own declaration.
        ErrorContractReport report = service(List.of(descriptor(
                        MAPPER,
                        "com.acme.ConstraintViolationMapper",
                        "toResponse",
                        List.of("jakarta.validation.ConstraintViolationException"),
                        ErrorHandlerDescriptor.SCOPE_GLOBAL)))
                .report(null, null, null);

        assertThat(report.entries())
                .extracting(ErrorContractEntryDto::component)
                .containsExactly("com.acme.ConstraintViolationMapper");
    }

    @Test
    void linksTheHighestRankedGlobalAdviceWhenSeveralDeclareTheSameException() {
        ErrorContractService service = service(List.of(
                ordered(ADVICE, "com.acme.FirstAdvice", "handle", "com.acme.NotFound", 10),
                ordered(ADVICE, "com.acme.SecondAdvice", "handle", "com.acme.NotFound", 20)));

        ErrorContractLinkDto link = service.resolve("com.acme.NotFound", "com.acme.OrderController#get");

        assertThat(link).isNotNull();
        assertThat(link.component()).isEqualTo("com.acme.FirstAdvice");
    }

    @Test
    void doesNotLinkWhenTheTopRankedGlobalAdvicesTieOnDeclaredEvidence() {
        ErrorContractService service = service(List.of(
                descriptor(ADVICE, "com.acme.FirstAdvice", "handle", List.of("com.acme.NotFound"), GLOBAL),
                descriptor(ADVICE, "com.acme.SecondAdvice", "handle", List.of("com.acme.NotFound"), GLOBAL)));

        assertThat(service.resolve("com.acme.NotFound", "com.acme.OrderController#get"))
                .isNull();
    }

    @Test
    void controllerLocalHandlersInDifferentControllersShareRankOneWithoutBeingAmbiguous() {
        List<ErrorContractEntryDto> entries = service(List.of(
                        local("com.acme.OrderController", "handle", "com.acme.NotFound"),
                        local("com.acme.CartController", "handle", "com.acme.NotFound")))
                .report(null, 0, 20)
                .entries();

        assertThat(entries)
                .extracting(ErrorContractEntryDto::component, ErrorContractEntryDto::precedence)
                .containsExactlyInAnyOrder(tuple("com.acme.OrderController", 1), tuple("com.acme.CartController", 1));
        assertThat(entries)
                .extracting(ErrorContractEntryDto::precedenceSource)
                .containsOnly(ErrorContractService.PRECEDENCE_DEFAULT);
    }

    @Test
    void twoHandlersInTheSameControllerRemainUnresolved() {
        List<ErrorContractEntryDto> entries = service(List.of(
                        local("com.acme.OrderController", "handleFirst", "com.acme.NotFound"),
                        local("com.acme.OrderController", "handleSecond", "com.acme.NotFound")))
                .report(null, 0, 20)
                .entries();

        assertThat(entries)
                .extracting(ErrorContractEntryDto::precedenceSource)
                .containsOnly(ErrorContractService.PRECEDENCE_UNRESOLVED);
    }

    @Test
    void anAdviceThatOrdersItselfAtRuntimeMakesTheGroupUnresolvedAndUnlinkable() {
        ErrorHandlerDescriptor runtimeOrdered = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.RuntimeOrderedAdvice",
                "handle",
                List.of("com.acme.NotFound"),
                GLOBAL,
                null,
                null,
                true,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of());
        ErrorContractService service = service(
                List.of(runtimeOrdered, ordered(ADVICE, "com.acme.OtherAdvice", "handle", "com.acme.NotFound", 10)));

        assertThat(service.report(null, 0, 20).entries())
                .extracting(ErrorContractEntryDto::precedenceSource)
                .containsOnly(ErrorContractService.PRECEDENCE_UNRESOLVED);
        assertThat(service.resolve("com.acme.NotFound", "com.acme.OrderController#get"))
                .isNull();
    }

    @Test
    void reportsDeclaredMediaTypesInOneStableOrderWhateverTheAdapterReported() {
        ErrorHandlerDescriptor descriptor = new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.GlobalAdvice",
                "handle",
                List.of("com.acme.NotFound"),
                GLOBAL,
                null,
                null,
                false,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of("application/xml", "application/problem+json", "application/xml"));

        assertThat(service(List.of(descriptor)).report(null, 0, 20).entries())
                .singleElement()
                .satisfies(entry ->
                        assertThat(entry.produces()).containsExactly("application/problem+json", "application/xml"));
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private static ErrorContractService service(List<ErrorHandlerDescriptor> handlers) {
        return new ErrorContractService(new StubProvider(handlers, true, null));
    }

    private static ErrorHandlerDescriptor descriptor(
            String source, String component, String method, List<String> exceptionTypes, String scope) {
        return new ErrorHandlerDescriptor(
                source,
                component,
                method,
                exceptionTypes,
                scope,
                null,
                null,
                false,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of());
    }

    private static ErrorHandlerDescriptor ordered(
            String source, String component, String method, String exceptionType, int order) {
        return new ErrorHandlerDescriptor(
                source,
                component,
                method,
                List.of(exceptionType),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                order,
                false,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of());
    }

    private static ErrorHandlerDescriptor local(String controller, String method, String exceptionType) {
        return new ErrorHandlerDescriptor(
                CONTROLLER,
                controller,
                method,
                List.of(exceptionType),
                ErrorHandlerDescriptor.SCOPE_CONTROLLER,
                controller,
                null,
                false,
                null,
                false,
                "com.acme.ErrorBody",
                "com.acme.ErrorBody",
                List.of());
    }

    private static String bodyCategoryOf(String bodyType, String returnType, boolean dynamicStatus) {
        return ErrorContractService.bodyCategory(new ErrorHandlerDescriptor(
                ADVICE,
                "com.acme.Advice",
                "handle",
                List.of("com.acme.NotFound"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                false,
                null,
                dynamicStatus,
                returnType,
                bodyType,
                List.of()));
    }

    private record StubProvider(List<ErrorHandlerDescriptor> handlers, boolean available, String reason)
            implements ErrorContractProvider {

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String unavailableReason() {
            return reason;
        }

        @Override
        public List<ErrorHandlerDescriptor> handlers() {
            return handlers;
        }
    }
}
