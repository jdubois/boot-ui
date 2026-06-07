package io.github.jdubois.bootui.autoconfigure.memory;

record MemoryRuleDefinition(
        String id,
        String name,
        MemoryCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
