package io.github.jdubois.bootui.autoconfigure.spring;

record SpringRuleDefinition(
        String id,
        String name,
        SpringCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
