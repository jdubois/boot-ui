package io.github.jdubois.bootui.engine.restapi.ruleaccuracy;

import io.smallrye.mutiny.Uni;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tags;
import jakarta.persistence.Entity;
import jakarta.servlet.ServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class RestApiRuleAccuracyFixtures {
    private RestApiRuleAccuracyFixtures() {}

    public record Payload(String name) {}

    @Entity
    public static class PersistentPayload {
        public String name;
    }

    @RestController
    @RequestMapping("/rule-accuracy")
    public static class Responses {
        @PostMapping("/default")
        public Payload createDefault() {
            return null;
        }

        @PostMapping("/accepted")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public Payload createAccepted() {
            return null;
        }

        @PostMapping("/dynamic")
        public Mono<ResponseEntity<Payload>> createDynamic() {
            return null;
        }

        @PostMapping("/imperative")
        public void createImperative(ServletResponse response) {}

        @DeleteMapping("/empty")
        public Mono<Void> deleteDefault() {
            return null;
        }

        @DeleteMapping("/accepted")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public Mono<Void> deleteAccepted() {
            return null;
        }

        @DeleteMapping("/dynamic")
        public Mono<ResponseEntity<Void>> deleteDynamic() {
            return null;
        }

        @DeleteMapping("/imperative")
        public void deleteImperative(Writer writer) {}

        @GetMapping("/empty")
        public CompletionStage<Void> emptyRead() {
            return null;
        }

        @GetMapping("/imperative")
        public void imperativeRead(OutputStream stream) {}

        @GetMapping("/contradiction")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public HttpEntity<Payload> noContentBody() {
            return null;
        }

        @GetMapping("/no-content")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public Mono<Void> noContentEmpty() {
            return null;
        }

        @GetMapping("/dynamic-no-content")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public Mono<ResponseEntity<Payload>> dynamicNoContent() {
            return null;
        }

        @GetMapping("/http-entity")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public HttpEntity<Payload> statusOnHttpEntity() {
            return null;
        }

        @GetMapping("/future-status")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public CompletionStage<ResponseEntity<Payload>> futureStatus() {
            return null;
        }

        @GetMapping("/reason-status")
        @ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "Declared error")
        public ResponseEntity<Payload> reasonStatus() {
            return null;
        }

        @GetMapping("/typed")
        public Mono<ResponseEntity<Payload>> typedEnvelope() {
            return null;
        }

        @GetMapping("/untyped")
        public Mono<ResponseEntity<Object>> untypedEnvelope() {
            return null;
        }

        @GetMapping("/untyped-http")
        public HttpEntity<Object> untypedHttpEntity() {
            return null;
        }

        @GetMapping("/nested-untyped-http")
        public Mono<HttpEntity<Object>> nestedUntypedHttpEntity() {
            return null;
        }

        @GetMapping("/nested-typed-http")
        public Mono<HttpEntity<Payload>> nestedTypedHttpEntity() {
            return null;
        }

        @PostMapping("/created")
        @ResponseStatus(HttpStatus.CREATED)
        public Payload created() {
            return null;
        }

        @RequestMapping(value = "/head", method = RequestMethod.HEAD)
        public Payload dedicatedHead() {
            return null;
        }

        @RequestMapping(
                value = "/combined",
                method = {RequestMethod.GET, RequestMethod.HEAD})
        public Payload combinedHead() {
            return null;
        }

        @RequestMapping(value = "/headers", method = RequestMethod.HEAD)
        public HttpHeaders headersHead() {
            return null;
        }

        @RequestMapping(value = "/empty-head", method = RequestMethod.HEAD)
        public Mono<Void> emptyHead() {
            return null;
        }

        @PatchMapping(value = "/xml", consumes = "application/xml;charset=UTF-8")
        public void xmlPatch() {}

        @PatchMapping(value = "/vendor", consumes = "application/vnd.example.patch")
        public void vendorPatch() {}

        @PatchMapping(value = "/binary", consumes = "application/octet-stream")
        public void binaryPatch() {}

        @PatchMapping("/unspecified")
        public void unspecifiedPatch() {}

        @PatchMapping(value = "/broad", consumes = "application/*")
        public void broadPatch() {}

        @GetMapping("/entities")
        public PersistentPayload[] entityArray() {
            return null;
        }

        @PostMapping("/entity-input")
        public void entityInput(@RequestBody Mono<PersistentPayload> body) {}

        @PostMapping("/validated-input")
        public void validatedInput(@jakarta.validation.Valid @RequestBody Mono<Payload> body) {}

        @GetMapping("/bytes")
        public byte[] bytes() {
            return null;
        }

        @GetMapping(value = "/events", produces = "text/event-stream")
        public Flux<Payload> events() {
            return null;
        }

        @GetMapping(value = "/envelope-events", produces = "text/event-stream")
        public ResponseEntity<Flux<Payload>> envelopeEvents() {
            return null;
        }

        @GetMapping(value = "/nested-envelope-events", produces = "application/x-ndjson")
        public Mono<ResponseEntity<Flux<Payload>>> nestedEnvelopeEvents() {
            return null;
        }

        @GetMapping(value = "/finite-envelope-events", produces = "text/event-stream")
        public ResponseEntity<List<Payload>> finiteEnvelopeEvents() {
            return null;
        }

        @GetMapping(value = "/finite-events", produces = "text/event-stream")
        public List<Payload> finiteEvents() {
            return null;
        }

        @GetMapping("/list")
        public List<Payload> collection() {
            return null;
        }

        @GetMapping("/page")
        public List<Payload> page(Pageable pageable) {
            return null;
        }

        @GetMapping("/retry")
        @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
        public Payload retry() {
            return null;
        }

        @GetMapping("/dynamic-retry")
        @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
        public Mono<ResponseEntity<Payload>> dynamicRetry() {
            return null;
        }
    }

    @RestController
    public static class Bindings {
        @GetMapping({"/required", "/required/{id}"})
        public Payload required(@PathVariable("id") String id) {
            return null;
        }

        @GetMapping({"/optional", "/optional/{id}"})
        public Payload optional(@PathVariable(value = "id", required = false) String id) {
            return null;
        }

        @GetMapping({"/optional-type", "/optional-type/{id}"})
        public Payload optionalType(@PathVariable("id") Optional<String> id) {
            return null;
        }

        @GetMapping("/aggregate-path")
        public Payload aggregatePath(@PathVariable Map<String, String> values) {
            return null;
        }

        @GetMapping("/${unresolved}")
        public Payload unresolved(@PathVariable("id") String id) {
            return null;
        }

        @GetMapping("/regex/{id:[a-z/]{2}}")
        public Payload regex(@PathVariable("id") String id) {
            return null;
        }

        @GetMapping("/regex-pair/{first:[a-z]{2}}/{second:[a-z]{2}}")
        public Payload distinctRegexNames() {
            return null;
        }

        @GetMapping("/duplicate/{id}/child/{id}")
        public Payload duplicateTokens() {
            return null;
        }

        @GetMapping("/boolean")
        public Payload booleanParam(@RequestParam(required = false) boolean flag) {
            return null;
        }

        @GetMapping("/number")
        public Payload numericParam(@RequestParam(required = false) int value) {
            return null;
        }

        @GetMapping("/default")
        public Payload numericDefault(@RequestParam(required = false, defaultValue = "1") int value) {
            return null;
        }

        @GetMapping("/named")
        public Payload namedMap(@RequestParam("filter") Map<String, String> filter) {
            return null;
        }

        @GetMapping("/aggregate")
        public Payload aggregateMap(@RequestParam Map<String, String> filter) {
            return null;
        }

        @GetMapping("/slash")
        public Payload slash() {
            return null;
        }

        @GetMapping("/slash/")
        public Payload trailingSlash() {
            return null;
        }
    }

    @RestController
    public static class PrefixAliases {
        @GetMapping({"/common/a", "/other/a"})
        public Payload first() {
            return null;
        }

        @GetMapping({"/common/b", "/other/b"})
        public Payload second() {
            return null;
        }
    }

    @RestController
    public static class RepeatedPrefix {
        @GetMapping({"/common/a", "/common/alias-a"})
        public Payload first() {
            return null;
        }

        @GetMapping("/common/b")
        public Payload second() {
            return null;
        }
    }

    @RestController
    public static class Versions {
        @GetMapping(value = "/vendor", produces = "application/vnd.example+json")
        public Payload vendorOnly() {
            return null;
        }

        @GetMapping(value = "/media-version", produces = "application/vnd.example.v2+json")
        public Payload mediaVersion() {
            return null;
        }

        @GetMapping(value = "/parameter-version", produces = "application/json;version=2")
        public Payload mediaParameter() {
            return null;
        }

        @GetMapping(value = "/header", headers = "X-API-Version=2")
        public Payload header() {
            return null;
        }

        @GetMapping(value = "/query", params = "version=2")
        public Payload query() {
            return null;
        }

        @GetMapping(value = "/negated", params = "!version", headers = "X-API-Version!=2")
        public Payload negated() {
            return null;
        }

        @GetMapping(value = "/native", version = "2")
        public Payload nativeVersion() {
            return null;
        }

        @GetMapping("/spring-version-bindings")
        public Payload springVersionBindings(
                @RequestParam("api_version") String query,
                @org.springframework.web.bind.annotation.RequestHeader("Api-Version") String header) {
            return null;
        }
    }

    @Path("/jax-rule")
    public static class JaxResource {
        @GET
        @Path("/duplicate/{id}/child/{id}")
        public Payload scopedTokens(@PathParam("id") String id) {
            return null;
        }

        @GET
        @Path("/missing")
        public Payload missingBinding(@PathParam("id") String id) {
            return null;
        }

        @jakarta.ws.rs.DELETE
        @Path("/empty")
        public Uni<Void> deleteEmpty() {
            return null;
        }

        @GET
        @Path("/dynamic")
        public Response dynamicResponse() {
            return null;
        }

        @GET
        @Path("/typed")
        public Uni<RestResponse<Payload>> typedResponse() {
            return null;
        }

        @GET
        @Path("/events")
        @jakarta.ws.rs.Produces("text/event-stream")
        public Uni<RestResponse<io.smallrye.mutiny.Multi<Payload>>> events() {
            return null;
        }

        @GET
        @Path("/versioned")
        public Payload headerVersion(@HeaderParam("Api-Version") String version) {
            return null;
        }

        @GET
        @Path("/duplicate-route")
        public Payload duplicateOne(@HeaderParam("Api-Version") String version) {
            return null;
        }

        @GET
        @Path("/duplicate-route")
        public Payload duplicateTwo(@QueryParam("version") String version) {
            return null;
        }

        @ServerExceptionMapper(IllegalStateException.class)
        public Payload nativeError(IllegalStateException exception) {
            return null;
        }
    }

    @Path("/jax-without-mapper")
    public static class JaxWithoutMapper {
        @GET
        public Payload get() {
            return null;
        }
    }

    @RestController
    public static class SpringSharedRoute {
        @GetMapping("/shared-route")
        public Payload get() {
            return null;
        }
    }

    @Path("/shared-route")
    public static class JaxSharedRoute {
        @GET
        public Payload get() {
            return null;
        }
    }

    public abstract static class GenericMapper<T extends Throwable> implements jakarta.ws.rs.ext.ExceptionMapper<T> {
        @Override
        public Response toResponse(T exception) {
            return null;
        }
    }

    @jakarta.ws.rs.ext.Provider
    public static class UnresolvedGenericMapper<T extends Throwable> extends GenericMapper<T> {}

    @RestControllerAdvice
    public static class DynamicAdvice {
        @ExceptionHandler(Exception.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Mono<ResponseEntity<Object>> dynamic(Exception exception) {
            return null;
        }
    }

    @RestControllerAdvice
    public static class FixedAdvice {
        @ExceptionHandler(exception = Exception.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Payload fixed(Exception exception) {
            return null;
        }
    }

    @RestControllerAdvice
    public static class FallbackAdvice {
        @ExceptionHandler(Exception.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        public Payload fallback(Exception exception) {
            return null;
        }
    }

    @RestControllerAdvice
    public static class ImperativeAdvice {
        @ExceptionHandler(Exception.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public void imperative(Exception exception, Writer writer) {}
    }

    @RestControllerAdvice
    public static class BodyAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        public HttpEntity<Payload> missingStatus(IllegalArgumentException exception) {
            return null;
        }
    }

    @RestControllerAdvice
    public static class UnknownAdvice {
        @ExceptionHandler(UnsupportedOperationException.class)
        public Object unknown(UnsupportedOperationException exception) {
            return null;
        }
    }

    public static class CoveredFailure extends Exception {}

    public static class UncoveredFailure extends Exception {}

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class AnnotatedFailure extends Exception {}

    @RestController
    public static class DeclaredFailures {
        @GetMapping("/covered-failure")
        public Payload covered() throws CoveredFailure {
            return null;
        }

        @GetMapping("/uncovered-failure")
        public Payload uncovered() throws UncoveredFailure {
            return null;
        }

        @GetMapping("/annotated-failure")
        public Payload annotated() throws AnnotatedFailure {
            return null;
        }
    }

    @RestControllerAdvice
    public static class AliasAdvice {
        @ExceptionHandler(exception = CoveredFailure.class)
        public ProblemDetail explicitAlias(Exception exception) {
            return null;
        }
    }

    @RestControllerAdvice
    public static class ProblemAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        public ProblemDetail problem(IllegalArgumentException exception) {
            return null;
        }
    }

    @RestControllerAdvice
    public static class TextAdvice {
        @ExceptionHandler(IllegalStateException.class)
        public String text(IllegalStateException exception) {
            return "";
        }
    }

    @RestControllerAdvice
    public static class NegotiatedAdvice {
        @ExceptionHandler(value = IllegalArgumentException.class, produces = "application/json")
        public ProblemDetail json(IllegalArgumentException exception) {
            return null;
        }

        @ExceptionHandler(value = IllegalArgumentException.class, produces = "text/plain")
        public String text(IllegalArgumentException exception) {
            return "";
        }
    }

    @RestControllerAdvice
    public static class SameSchemaAdvice {
        public record FirstError(String message) {}

        public record SecondError(String message) {}

        @ExceptionHandler(IllegalArgumentException.class)
        public FirstError first(IllegalArgumentException exception) {
            return null;
        }

        @ExceptionHandler(IllegalStateException.class)
        public SecondError second(IllegalStateException exception) {
            return null;
        }
    }

    @RestController
    public static class SensitiveDispatchConditions {
        @GetMapping(value = "/same-condition", headers = "X-Api-Key=fixture-value-not-for-output")
        public Payload first() {
            return null;
        }

        @GetMapping(value = "/same-condition", headers = "X-Api-Key=fixture-value-not-for-output")
        public Payload second() {
            return null;
        }
    }

    @Controller
    public static class Views {
        @ExceptionHandler(Exception.class)
        public String view(Exception exception) {
            return "error";
        }
    }

    @RestController
    @Tags({@Tag(name = "first"), @Tag(name = "second")})
    public static class ContainerTags {
        @GetMapping("/tagged-container")
        public Payload get() {
            return null;
        }
    }

    @RestController
    public static class OperationTags {
        @GetMapping("/tagged-operation")
        @Operation(tags = {"first", "second"})
        public Payload get() {
            return null;
        }
    }

    @Path("/mp-tags")
    @org.eclipse.microprofile.openapi.annotations.tags.Tags({
        @org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "first"),
        @org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "second")
    })
    public static class MicroProfileTags {
        @GET
        public Payload get() {
            return null;
        }
    }

    @RestController
    public static class RetiredSignals {
        @DeleteMapping("/export.json")
        @Deprecated
        public void delete() {}

        @ExceptionHandler(Exception.class)
        public Payload logged(Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }
}
