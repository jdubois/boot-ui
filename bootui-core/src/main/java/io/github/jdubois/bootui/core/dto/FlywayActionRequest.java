package io.github.jdubois.bootui.core.dto;

/**
 * Request for a mutating Flyway action targeting one discovered Flyway bean.
 */
public record FlywayActionRequest(String beanName, Boolean confirm) {}
