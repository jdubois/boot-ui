package io.github.jdubois.bootui.core.dto;

/**
 * Code Inventory's method counts: "{@code executed} of {@code tracked} application methods executed". Executed and
 * never executed count only the methods the agent tracked in this run.
 *
 * @param packages the application packages scanned
 * @param classes the application classes scanned
 * @param methods the methods with code of those classes
 * @param tracked the methods whose executions the agent sees in this run: executed plus never executed
 * @param executed the tracked methods that ran at least once in this run
 * @param neverExecuted the tracked methods that did not run in this run
 * @param notTracked the methods the agent could not see, each with its reason
 * @param generated methods that ran but have no class file on disk, such as generated classes
 */
public record CodeInventoryMethodCountsDto(
        int packages,
        int classes,
        int methods,
        int tracked,
        int executed,
        int neverExecuted,
        int notTracked,
        int generated) {}
