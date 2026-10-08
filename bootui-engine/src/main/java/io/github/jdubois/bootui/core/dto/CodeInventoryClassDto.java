package io.github.jdubois.bootui.core.dto;

/**
 * One application class's method counts in Code Inventory.
 *
 * @param packageName its package
 * @param className its binary name
 * @param methods its methods with code
 * @param executed tracked methods that ran in this run
 * @param neverExecuted tracked methods that did not
 * @param notTracked methods the agent could not see
 * @param changed methods changed or added since the previous run
 */
public record CodeInventoryClassDto(
        String packageName,
        String className,
        int methods,
        int executed,
        int neverExecuted,
        int notTracked,
        int changed) {}
