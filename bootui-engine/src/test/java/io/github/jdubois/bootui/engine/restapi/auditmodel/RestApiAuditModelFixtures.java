package io.github.jdubois.bootui.engine.restapi.auditmodel;

import io.smallrye.mutiny.Uni;
import jakarta.persistence.Entity;
import jakarta.servlet.ServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

public final class RestApiAuditModelFixtures {
    private RestApiAuditModelFixtures() {}

    public record Payload(String value) {}

    @Entity
    public static class PersistentPayload {
        public String value;
    }

    @RestController
    @RequestMapping("/audit")
    public static class SpringShapes {
        @GetMapping("/async")
        public Mono<ResponseEntity<Payload>> asyncEnvelope() {
            return null;
        }

        @GetMapping("/async-http-entity")
        public Mono<HttpEntity<Object>> asyncHttpEntity() {
            return null;
        }

        @GetMapping(value = "/stream", produces = "text/event-stream")
        public ResponseEntity<Flux<Payload>> bodyStream() {
            return null;
        }

        @GetMapping("/future")
        public CompletionStage<ResponseEntity<Payload>> futureEnvelope() {
            return null;
        }

        @GetMapping("/body")
        public ResponseEntity<Mono<Payload>> asyncBody() {
            return null;
        }

        @GetMapping("/http-entity")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public HttpEntity<Payload> headersAndBody() {
            return null;
        }

        @GetMapping("/nested-http-entity")
        public HttpEntity<ResponseEntity<Payload>> nestedHttpEntity() {
            return null;
        }

        @GetMapping("/optional-response")
        public Optional<ResponseEntity<Payload>> optionalEnvelope() {
            return null;
        }

        @GetMapping("/optional-void")
        public Optional<Void> optionalVoid() {
            return null;
        }

        @GetMapping("/empty")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public Mono<Void> asyncEmpty() {
            return null;
        }

        @GetMapping("/empty-envelope")
        public Mono<ResponseEntity<Void>> asyncEmptyEnvelope() {
            return null;
        }

        @GetMapping("/headers")
        public HttpHeaders headersOnly() {
            return null;
        }

        @GetMapping("/headers-payload")
        public HttpEntity<HttpHeaders> headersPayload() {
            return null;
        }

        @GetMapping("/async-headers-payload")
        public Mono<ResponseEntity<HttpHeaders>> asyncHeadersPayload() {
            return null;
        }

        @GetMapping("/multi-envelope")
        public Flux<ResponseEntity<Payload>> unsupportedMultipleEnvelopes() {
            return null;
        }

        @GetMapping("/nullable-items")
        public List<Void> nullableItems() {
            return null;
        }

        @GetMapping("/entity-array")
        public PersistentPayload[] entityArray() {
            return null;
        }

        @GetMapping("/bytes")
        public byte[] binary() {
            return null;
        }

        @PostMapping("/entity-input")
        public void reactiveInput(@RequestBody Mono<PersistentPayload> payload) {}

        @PostMapping("/stream-input")
        public void reactiveStreamInput(@RequestBody Flux<PersistentPayload> payload) {}

        @PostMapping("/binary-input")
        public void binaryInput(@RequestBody Mono<byte[]> payload) {}

        @GetMapping("/primitive")
        public String optionalNumber(@RequestParam(required = false) int number) {
            return "";
        }

        @GetMapping("/boolean")
        public String optionalBoolean(@RequestParam(required = false) boolean value) {
            return "";
        }

        @GetMapping("/default")
        public String emptyDefault(@RequestParam(required = false, defaultValue = "") int number) {
            return "";
        }

        @GetMapping("/blank-default")
        public String blankDefault(@RequestParam(defaultValue = " ") int number) {
            return "";
        }

        @GetMapping("/numeric-default")
        public String numericDefault(@RequestParam(required = false, defaultValue = "0") int number) {
            return "";
        }

        @GetMapping("/aggregate-map")
        public String aggregateMap(@RequestParam Map<String, String> values) {
            return "";
        }

        @GetMapping("/named-map")
        public String namedMap(@RequestParam("filter") Map<String, String> values) {
            return "";
        }

        @GetMapping("/version-bindings")
        public String versionBindings(
                @RequestParam("version") String version,
                @org.springframework.web.bind.annotation.RequestHeader("Api-Version") String header) {
            return "";
        }

        @GetMapping({"/optional", "/optional/{id}"})
        public Payload optionalPath(@PathVariable(value = "id", required = false) String id) {
            return null;
        }

        @GetMapping({"/optional-type", "/optional-type/{id}"})
        public Payload optionalTypePath(@PathVariable("id") Optional<String> id) {
            return null;
        }

        @GetMapping({"/required", "/required/{id}"})
        public Payload requiredPath(@PathVariable("id") String id) {
            return null;
        }

        @GetMapping("/slash/")
        public Payload trailingSlash() {
            return null;
        }

        @GetMapping("/slash")
        public Payload withoutSlash() {
            return null;
        }

        @GetMapping("/regex/{id:[a-z/]+}")
        public Payload regexInterior() {
            return null;
        }

        @GetMapping("/double//slash")
        public Payload doubleSlash() {
            return null;
        }

        @GetMapping("/servlet")
        public void servletResponse(ServletResponse response) {}

        @GetMapping("/output")
        public void outputStream(OutputStream response) {}

        @GetMapping("/writer")
        public void writer(Writer response) {}

        @GetMapping("/tag")
        @io.swagger.v3.oas.annotations.Operation(tags = "widgets")
        public Payload operationTags() {
            return null;
        }

        @GetMapping("/tags")
        @io.swagger.v3.oas.annotations.tags.Tag(name = "widgets")
        @io.swagger.v3.oas.annotations.tags.Tag(name = "public")
        public Payload repeatedTags() {
            return null;
        }
    }

    @RestController
    @RequestMapping(value = "/union", method = RequestMethod.GET)
    public static class MethodUnion {
        @PostMapping
        public Payload union() {
            return null;
        }
    }

    @RestControllerAdvice
    @RequestMapping(produces = "application/xml")
    public static class Advice {
        @ExceptionHandler(exception = IllegalArgumentException.class)
        public Mono<ResponseEntity<Payload>> aliasedException() {
            return null;
        }

        @ExceptionHandler
        public HttpEntity<Payload> throwableParameter(Throwable exception) {
            return null;
        }

        @ExceptionHandler(exception = IllegalStateException.class, produces = "text/plain")
        public String declaredMedia() {
            return "";
        }
    }

    @RestControllerAdvice
    public static class ReactiveAdvice
            extends org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler {}

    @Path("/jax")
    public static class JaxShapes {
        @GET
        @Path("/async")
        public Uni<RestResponse<Payload>> mutinyEnvelope() {
            return null;
        }

        @GET
        @Path("/stream")
        @jakarta.ws.rs.Produces("application/x-ndjson")
        public Uni<RestResponse<io.smallrye.mutiny.Multi<Payload>>> mutinyBodyStream() {
            return null;
        }

        @GET
        @Path("/response")
        public CompletionStage<Response> responseEnvelope() {
            return null;
        }

        @GET
        @Path("/empty")
        public Uni<Void> mutinyEmpty() {
            return null;
        }

        @GET
        @Path("/empty-envelope")
        public Uni<RestResponse<Void>> mutinyEmptyEnvelope() {
            return null;
        }

        @GET
        @Path("/same")
        public Payload versionHeader(@HeaderParam("Api-Version") String version) {
            return null;
        }

        @GET
        @Path("/same")
        public Payload versionQuery(@QueryParam("version") String version) {
            return null;
        }
    }

    public static class UnrootedResource {
        @GET
        @Path("/unknown-root")
        public Payload unrooted() {
            return null;
        }
    }

    public abstract static class BaseMapper implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return null;
        }
    }

    @Provider
    public static class InheritedMapper extends BaseMapper {}

    public static class QuarkusMappers {
        @ServerExceptionMapper({IllegalArgumentException.class, IllegalStateException.class})
        public Uni<RestResponse<Payload>> explicitMapper(Object unrelated) {
            return null;
        }
    }
}
