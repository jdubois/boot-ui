package io.github.jdubois.bootui.core.dto;

/**
 * One application package's method counts in Code Inventory.
 *
 * @param name the package
 * @param classes its classes
 * @param methods its methods with code
 * @param executed tracked methods that ran in this run
 * @param neverExecuted tracked methods that did not
 * @param notTracked methods the agent could not see
 */
public record CodeInventoryPackageDto(
        String name, int classes, int methods, int executed, int neverExecuted, int notTracked) {}
