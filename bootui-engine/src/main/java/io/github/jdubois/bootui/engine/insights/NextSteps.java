package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeNextStepDto;
import io.github.jdubois.bootui.engine.cli.CliCommandPaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The follow-up calls a Runtime Insights answer names ({@code docs/PLAN-v2.md} M4-21): at most {@value #MAX}, each
 * spelled as the {@code bootui} command and as the MCP tool with its arguments, and only for tools this application
 * advertises, so a Quarkus application is never told to run a Spring-only command.
 */
final class NextSteps {

    /** The steps one answer names at most. */
    static final int MAX = 3;

    private final Predicate<String> callable;
    private final int max;
    private final List<RuntimeNextStepDto> steps = new ArrayList<>();

    NextSteps(Predicate<String> callable) {
        this(callable, MAX);
    }

    /** Steps that name at most {@code max} calls, so an answer can keep room for others. */
    NextSteps(Predicate<String> callable, int max) {
        this.callable = callable == null ? tool -> true : callable;
        this.max = Math.max(0, Math.min(MAX, max));
    }

    /** Adds a call with no arguments. */
    NextSteps add(String tool, String why) {
        return add(tool, Map.of(), why);
    }

    /** Adds a call with one argument. */
    NextSteps add(String tool, String argument, Object value, String why) {
        if (value == null) {
            return this;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(argument, value);
        return add(tool, arguments, why);
    }

    /** Adds a call unless the answer already names {@value #MAX}, the tool is not advertised, or it is a repeat. */
    NextSteps add(String tool, Map<String, Object> arguments, String why) {
        if (steps.size() >= max || !callable.test(tool) || CliCommandPaths.commandFor(tool) == null) {
            return this;
        }
        String command = CliCommandPaths.command(tool, arguments);
        if (steps.stream().anyMatch(step -> step.command().equals(command))) {
            return this;
        }
        steps.add(new RuntimeNextStepDto(command, tool, arguments, why));
        return this;
    }

    List<RuntimeNextStepDto> list() {
        return List.copyOf(steps);
    }

    int size() {
        return steps.size();
    }

    /** These steps, then those of {@code others} not already named, at most {@value #MAX} in all. */
    List<RuntimeNextStepDto> then(NextSteps others) {
        List<RuntimeNextStepDto> all = new ArrayList<>(steps);
        for (RuntimeNextStepDto step : others.steps) {
            if (all.size() < MAX
                    && all.stream().noneMatch(named -> named.command().equals(step.command()))) {
                all.add(step);
            }
        }
        return List.copyOf(all);
    }
}
