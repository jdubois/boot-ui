package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

record MemoryAdvisorRuleDefinition(
        String id,
        String name,
        MemoryAdvisorCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
