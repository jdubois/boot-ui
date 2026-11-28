package io.github.jdubois.bootui.sample.errors;

/**
 * Sample error payload returned by the sample's exception mappers.
 *
 * @param code a stable machine-readable error code
 * @param message a human-readable description
 */
public record SampleErrorBody(String code, String message) {}
