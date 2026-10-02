package io.github.jdubois.bootui.core.dto;

/**
 * A frame on CPU during a route's requests in a <b>Profile resources</b> session ({@code docs/PLAN-v2.md} §5.11).
 *
 * @param frame the first application frame of the samples, such as {@code com.example.OrderService.price:42}, or their
 *     top frame when they have none
 * @param samples the CPU samples it was in
 */
public record RuntimeResourceProfileFrameDto(String frame, long samples) {}
