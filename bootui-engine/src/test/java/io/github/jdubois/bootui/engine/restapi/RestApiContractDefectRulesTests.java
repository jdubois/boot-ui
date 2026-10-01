package io.github.jdubois.bootui.engine.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner.SpringWebStack;
import io.github.jdubois.bootui.engine.restapi.jaxrs.quarkusstatus.QuarkusStatusResource;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Positive, negative and edge cases for the request/response contract defects added by the REST API advisor audit
 * (RAPI-VALID-006, RAPI-VER-007, RAPI-RESP-010, RAPI-RESP-011) and the fixes that accompany them.
 */
class RestApiContractDefectRulesTests {

    // --- RAPI-VALID-006 ------------------------------------------------------------------------------------------

    @Test
    void severalRequestBodiesAreReportedOnceAndSingleOrFormBindingsPass() {
        RestApiRuleResultDto result = new SingleRequestBodyRule().evaluate(context(TwoBodies.class));

        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("#transfer");
    }

    @Test
    void severalRequestBodiesRuleIsNotEvaluatedOnJaxRs() {
        assertThat(new SingleRequestBodyRule()
                        .evaluate(context(QuarkusStatusResource.class))
                        .status())
                .isEqualTo(RestApiRuleSupport.SKIPPED);
    }

    // --- RAPI-VER-007 --------------------------------------------------------------------------------------------

    @Test
    void classLevelConsumesOnBodylessHandlersIsReportedOncePerController() {
        RestApiRuleResultDto result =
                new BodylessHandlersDoNotRequireContentTypeRule().evaluate(context(ClassLevelConsumes.class));

        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("ClassLevelConsumes", "2 bodyless handler(s)", "#list", "#remove")
                .doesNotContain("#create", "#replace");
    }

    @Test
    void methodLevelConsumesOnGetIsReportedPerHandler() {
        RestApiRuleResultDto result =
                new BodylessHandlersDoNotRequireContentTypeRule().evaluate(context(MethodLevelConsumes.class));

        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).singleElement().asString().contains("#search");
    }

    @Test
    void bodylessConsumesThatAcceptMissingContentTypeOrHaveAlternativesPass() {
        assertThat(new BodylessHandlersDoNotRequireContentTypeRule()
                        .evaluate(context(TolerantConsumes.class))
                        .status())
                .isEqualTo(RestApiRuleSupport.PASS);
        assertThat(new BodylessHandlersDoNotRequireContentTypeRule()
                        .evaluate(context(ContentTypeDispatch.class))
                        .status())
                .isEqualTo(RestApiRuleSupport.PASS);
    }

    // --- RAPI-RESP-010 -------------------------------------------------------------------------------------------

    @Test
    void responseStatusReasonOnBodyHandlersIsReportedOnSpringMvc() {
        RestApiContext context = context(SpringWebStack.SERVLET, ReasonController.class);
        RestApiRuleResultDto result = new ResponseStatusReasonOnBodyHandlersRule().evaluate(context);

        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.sampleViolations())
                .hasSize(3)
                .anyMatch(detail -> detail.contains("#created"))
                .anyMatch(detail -> detail.contains("#envelope"))
                .anyMatch(detail -> detail.contains("#onFailure"))
                .noneMatch(detail -> detail.contains("#deleted") || detail.contains("#plain"));
        // The reason already discards the ResponseEntity, so the overlap prompt does not report it twice.
        assertThat(new ResponseStatusIgnoredWithResponseEntityRule()
                        .evaluate(context(SpringWebStack.SERVLET, ReasonController.class))
                        .sampleViolations())
                .noneMatch(detail -> detail.contains("#envelope"));
    }

    @Test
    void responseStatusReasonIsSkippedOnWebFluxAndUnknownStacks() {
        RestApiContext reactive = context(SpringWebStack.REACTIVE, ReasonController.class);
        assertThat(new ResponseStatusReasonOnBodyHandlersRule()
                        .evaluate(reactive)
                        .status())
                .isEqualTo(RestApiRuleSupport.SKIPPED);
        assertThat(reactive.evidence().requiredUnknown()).isFalse();
        assertThat(new ResponseStatusIgnoredWithResponseEntityRule()
                        .evaluate(context(SpringWebStack.REACTIVE, ReasonController.class))
                        .sampleViolations())
                .anyMatch(detail -> detail.contains("#envelope"));

        RestApiContext unknown = context(ReasonController.class);
        assertThat(new ResponseStatusReasonOnBodyHandlersRule()
                        .evaluate(unknown)
                        .status())
                .isEqualTo(RestApiRuleSupport.SKIPPED);
        assertThat(unknown.evidence().requiredUnknown()).isTrue();
    }

    // --- RAPI-RESP-011 -------------------------------------------------------------------------------------------

    @Test
    void optionalReadsAreReportedUnlessTheApplicationOwnsTheStatus() {
        RestApiRuleResultDto result = new OptionalReadsMapAbsenceRule().evaluate(context(OptionalReads.class));

        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.sampleViolations())
                .hasSize(2)
                .anyMatch(detail -> detail.contains("#find"))
                .anyMatch(detail -> detail.contains("#findAsync"))
                .noneMatch(detail -> detail.contains("#findEntity") || detail.contains("#save"));
    }

    // --- Fixes ---------------------------------------------------------------------------------------------------

    @Test
    void quarkusResponseStatusAndHeadersAreReadLikeSpringDeclarations() {
        RestApiContext context = context(QuarkusStatusResource.class);

        RestApiRuleResultDto creation = new CreationReturns201Rule().evaluate(context);
        assertThat(creation.sampleViolations()).singleElement().asString().contains("#addGizmo");

        RestApiRuleResultDto location = new CreatedResponsesExposeLocationRule().evaluate(context);
        assertThat(location.sampleViolations()).singleElement().asString().contains("#createGizmo");

        RestApiRuleResultDto retryAfter = new RetryAfterOnThrottlingResponsesRule().evaluate(context);
        assertThat(retryAfter.sampleViolations())
                .singleElement()
                .asString()
                .contains("#throttled", "TOO_MANY_REQUESTS");
    }

    @Test
    void aLeadingV3SegmentIsAVersionNotDocumentation() {
        RestApiRuleResultDto result = new ApiIsVersionedRule().evaluate(context(V3Api.class));

        assertThat(result.status()).isEqualTo(RestApiRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("1 of 2 API handler(s)", "#unversioned")
                .doesNotContain("#docs");
    }

    private static RestApiContext context(Class<?>... types) {
        return context(SpringWebStack.UNKNOWN, types);
    }

    private static RestApiContext context(SpringWebStack stack, Class<?>... types) {
        var model = RestApiHandlerModelBuilder.build(new ClassFileImporter().importClasses(types));
        RestApiContext context = new RestApiContext(
                List.of(types[0].getPackageName()),
                model.controllers(),
                model.handlers(),
                model.exceptionHandlers(),
                false,
                false,
                model.hasExceptionHandling(),
                model.responseStatusExceptionClasses(),
                model.thrownExceptions(),
                model.framework());
        context.evidence().springWebStack(stack);
        return context;
    }

    record Payload(String value) {}

    @RestController
    static class TwoBodies {
        @PostMapping("/transfers")
        String transfer(@RequestBody Payload from, @RequestBody Payload to) {
            return "";
        }

        @PostMapping("/single")
        String single(@RequestBody Payload payload) {
            return "";
        }

        @PostMapping(value = "/form", consumes = "application/x-www-form-urlencoded")
        String form(@RequestBody Map<String, String> first, @RequestBody Map<String, String> second) {
            return "";
        }
    }

    @RestController
    @RequestMapping(value = "/orders", consumes = "application/json")
    static class ClassLevelConsumes {
        @GetMapping
        List<Payload> list() {
            return List.of();
        }

        @DeleteMapping("/{id}")
        void remove() {}

        @PostMapping
        Payload create(@RequestBody Payload payload) {
            return payload;
        }

        @PutMapping("/{id}")
        Payload replace(@RequestBody Payload payload) {
            return payload;
        }
    }

    @RestController
    static class MethodLevelConsumes {
        @GetMapping(value = "/search", consumes = "application/json")
        List<Payload> search() {
            return List.of();
        }

        @GetMapping(value = "/optional-body", consumes = "application/json")
        List<Payload> optionalBody(@RequestBody(required = false) Payload filter) {
            return List.of();
        }
    }

    @RestController
    static class TolerantConsumes {
        @GetMapping(value = "/any", consumes = "*/*")
        String any() {
            return "";
        }

        @GetMapping(
                value = "/application",
                consumes = {"application/json", "application/*"})
        String application() {
            return "";
        }

        @GetMapping(value = "/negated", consumes = "!text/plain")
        String negated() {
            return "";
        }

        @GetMapping(value = "/raw", consumes = "application/json")
        String raw(InputStream body) {
            return "";
        }

        @GetMapping(value = "/entity", consumes = "application/json")
        String entity(HttpEntity<String> body) {
            return "";
        }
    }

    @RestController
    static class ContentTypeDispatch {
        @GetMapping(value = "/reports", consumes = "application/json")
        String json() {
            return "";
        }

        @GetMapping("/reports")
        String plain() {
            return "";
        }
    }

    @RestController
    static class ReasonController {
        @PostMapping("/created")
        @ResponseStatus(code = HttpStatus.CREATED, reason = "Created")
        Payload created() {
            return new Payload("x");
        }

        @GetMapping("/envelope")
        @ResponseStatus(code = HttpStatus.OK, reason = "Fine")
        ResponseEntity<Payload> envelope() {
            return ResponseEntity.ok(new Payload("x"));
        }

        @DeleteMapping("/deleted")
        @ResponseStatus(code = HttpStatus.NO_CONTENT, reason = "Deleted")
        void deleted() {}

        @GetMapping("/plain")
        @ResponseStatus(HttpStatus.ACCEPTED)
        Payload plain() {
            return new Payload("x");
        }

        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(code = HttpStatus.CONFLICT, reason = "Conflict")
        ProblemDetail onFailure() {
            return ProblemDetail.forStatus(HttpStatus.CONFLICT);
        }
    }

    @RestController
    static class OptionalReads {
        @GetMapping("/items/{id}")
        Optional<Payload> find() {
            return Optional.empty();
        }

        @GetMapping("/async/{id}")
        CompletableFuture<Optional<Payload>> findAsync() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @GetMapping("/entities/{id}")
        ResponseEntity<Optional<Payload>> findEntity() {
            return ResponseEntity.ok(Optional.empty());
        }

        @PostMapping("/items")
        Optional<Payload> save() {
            return Optional.empty();
        }

        @GetMapping("/reactive/{id}")
        Mono<Payload> reactive() {
            return Mono.empty();
        }
    }

    @RestController
    static class V3Api {
        @GetMapping("/v3/orders")
        String versioned() {
            return "";
        }

        @GetMapping("/orders")
        String unversioned() {
            return "";
        }

        @GetMapping("/v3/api-docs")
        String docs() {
            return "";
        }
    }
}
