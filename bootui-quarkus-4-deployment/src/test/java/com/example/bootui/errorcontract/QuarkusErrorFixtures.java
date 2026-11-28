package com.example.bootui.errorcontract;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Application-shaped fixtures for the Quarkus error-contract build-step tests.
 *
 * <p>They deliberately live outside the {@code io.github.jdubois.bootui} package: the build step excludes
 * BootUI's own classes from the host application's contract, so fixtures declared in the test's own package
 * would be filtered out and the tests would pass vacuously.</p>
 */
public final class QuarkusErrorFixtures {

    private QuarkusErrorFixtures() {}

    public static class SampleNotFound extends RuntimeException {}

    @Provider
    @Priority(4000)
    @Produces(MediaType.APPLICATION_JSON)
    public static class NotFoundMapper implements ExceptionMapper<SampleNotFound> {

        @Override
        public Response toResponse(SampleNotFound exception) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    /** Implements the interface but is never registered, so it never participates in exception resolution. */
    public static class UnregisteredMapper implements ExceptionMapper<SampleNotFound> {

        @Override
        public Response toResponse(SampleNotFound exception) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    public abstract static class AbstractNotFoundMapper implements ExceptionMapper<SampleNotFound> {

        @Override
        public Response toResponse(SampleNotFound exception) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    /** Inherits the mapped type, so the handled exception is only readable through the superclass. */
    @Provider
    public static class InheritingMapper extends AbstractNotFoundMapper {}

    public static class PrioritisedMappers {

        @ServerExceptionMapper(value = SampleNotFound.class, priority = 1)
        public Response declaredPriority() {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        @ServerExceptionMapper(SampleNotFound.class)
        public Response defaultPriority() {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @Path("/samples")
    @Produces(MediaType.APPLICATION_JSON)
    public static class SampleResource {

        @ServerExceptionMapper
        public Uni<RestResponse<String>> handle(SampleNotFound exception) {
            return Uni.createFrom().nullItem();
        }
    }

    public static class MethodOnlyJaxRsResource {

        @jakarta.ws.rs.GET
        @Path("/items")
        public String list() {
            return "items";
        }

        @ServerExceptionMapper(SampleNotFound.class)
        public Object handleSuspend(
                SampleNotFound exception, kotlin.coroutines.Continuation<? super RestResponse<String>> continuation) {
            return null;
        }
    }
}
