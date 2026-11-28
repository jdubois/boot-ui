package io.github.jdubois.bootui.sample.errors;

/**
 * Sample domain exception handled by a global {@code @Provider ExceptionMapper}, so the BootUI
 * error-contract catalogue can show a fully resolved row on the Quarkus stack.
 */
public class SampleProductNotFoundException extends RuntimeException {

    public SampleProductNotFoundException(String message) {
        super(message);
    }
}
