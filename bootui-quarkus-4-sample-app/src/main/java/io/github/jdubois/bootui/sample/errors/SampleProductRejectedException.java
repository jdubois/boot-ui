package io.github.jdubois.bootui.sample.errors;

/**
 * Sample domain exception handled by a resource-local {@code @ServerExceptionMapper}, so the BootUI
 * error-contract catalogue can show a resource-scoped row that outranks any global mapper.
 */
public class SampleProductRejectedException extends RuntimeException {

    public SampleProductRejectedException(String message) {
        super(message);
    }
}
