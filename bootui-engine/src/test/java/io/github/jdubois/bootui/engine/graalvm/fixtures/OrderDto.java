package io.github.jdubois.bootui.engine.graalvm.fixtures;

/** Application DTO bound programmatically by {@link BindingClient}. */
public record OrderDto(String id, int quantity) {}
