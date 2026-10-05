package com.example.bootui.errorcontract;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Application-shaped fixtures for the Spring error-contract provider tests.
 *
 * <p>They deliberately live outside the {@code io.github.jdubois.bootui} package: the provider excludes
 * BootUI's own components from the host application's contract, so fixtures declared in the test's own
 * package would be filtered out and the tests would pass vacuously.</p>
 */
public final class ErrorContractFixtures {

    private ErrorContractFixtures() {}

    public record ErrorBody(String message) {}

    @RestControllerAdvice
    @Order(10)
    public static class GlobalAdvice {

        @ExceptionHandler(value = IllegalStateException.class, produces = "application/problem+json")
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public ProblemDetail handleNotFound() {
            return ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        }
    }

    @ControllerAdvice
    public static class ParameterTypedAdvice {

        @ExceptionHandler
        public ProblemDetail handle(IllegalArgumentException exception) {
            return ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        }
    }

    @ControllerAdvice(assignableTypes = LocalController.class)
    public static class ScopedAdvice {

        @ExceptionHandler(IllegalStateException.class)
        public ProblemDetail handle() {
            return ProblemDetail.forStatus(HttpStatus.CONFLICT);
        }
    }

    @ControllerAdvice
    public static class EmptyAdvice {}

    /**
     * A plain {@code @ControllerAdvice} whose handler renders a view: the returned {@code String} is a view
     * name, not a response body.
     */
    @ControllerAdvice
    public static class ViewRenderingAdvice {

        @ExceptionHandler(IllegalStateException.class)
        public String handle() {
            return "error/not-found";
        }
    }

    /** An advice that decides its own ordering at runtime, which no declaration can reveal. */
    @RestControllerAdvice
    public static class RuntimeOrderedAdvice implements Ordered {

        @ExceptionHandler(IllegalStateException.class)
        public ErrorBody handle() {
            return new ErrorBody("ordered at runtime");
        }

        @Override
        public int getOrder() {
            return 5;
        }
    }

    @ControllerAdvice
    public static class DynamicAdvice {

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<ErrorBody> handleDynamic() {
            return ResponseEntity.badRequest().build();
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public CompletableFuture<ResponseEntity<ErrorBody>> handleAsync() {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        @ExceptionHandler(UnsupportedOperationException.class)
        public void handleVoid() {}
    }

    @Controller
    public static class LocalController {

        @ExceptionHandler(IllegalStateException.class)
        public ProblemDetail handleLocally() {
            return ProblemDetail.forStatus(HttpStatus.CONFLICT);
        }
    }

    /**
     * A lazily-registered {@code FactoryBean} that records every time it is instantiated or asked to build
     * its object.
     *
     * <p>Opening a read-only panel must never run application code, so discovering exception handlers must
     * not force this factory into existence.</p>
     */
    @Lazy
    public static class TrackingFactoryBean implements FactoryBean<ErrorBody> {

        public static final AtomicInteger INSTANTIATIONS = new AtomicInteger();

        public static final AtomicInteger OBJECTS_BUILT = new AtomicInteger();

        public TrackingFactoryBean() {
            INSTANTIATIONS.incrementAndGet();
        }

        @Override
        public ErrorBody getObject() {
            OBJECTS_BUILT.incrementAndGet();
            return new ErrorBody("never built for a panel read");
        }

        @Override
        public Class<?> getObjectType() {
            return ErrorBody.class;
        }
    }
}
