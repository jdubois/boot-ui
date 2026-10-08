package io.github.jdubois.bootui.core.dto;

/**
 * Canonical response returned when an explicit BootUI action loses per-service single-flight
 * admission.
 *
 * @param error stable error category
 * @param operation operation the caller attempted to start
 * @param activeOperation operation currently holding the service's single-flight admission
 * @param message human-readable canonical explanation
 */
public record ActionBusyResult(String error, String operation, String activeOperation, String message) {}
