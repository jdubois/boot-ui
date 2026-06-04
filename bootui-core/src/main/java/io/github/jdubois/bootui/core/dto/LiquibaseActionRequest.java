package io.github.jdubois.bootui.core.dto;

/**
 * Request for a mutating Liquibase action targeting one discovered SpringLiquibase bean.
 */
public record LiquibaseActionRequest(String beanName, Boolean confirm) {}
