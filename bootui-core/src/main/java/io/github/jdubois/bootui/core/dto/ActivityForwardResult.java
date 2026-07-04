package io.github.jdubois.bootui.core.dto;

/**
 * Result of a Live Activity forwarding-endpoint POST: how many entries a receiving BootUI instance
 * actually appended from an incoming {@link ActivityForwardBatchRequest}.
 *
 * @param status a short machine-readable outcome: {@code "accepted"}, {@code "unauthorized"}, {@code
 *     "invalid"} or {@code "failed"}
 * @param message human-readable detail, safe to log or surface to an operator inspecting the sender's
 *     own logs when a forward attempt did not succeed
 * @param accepted how many entries were actually appended to the receiver's local store; {@code 0} on
 *     any rejected or failed request
 */
public record ActivityForwardResult(String status, String message, int accepted) {}
