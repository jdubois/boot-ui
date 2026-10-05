package io.github.jdubois.bootui.autoconfigure.security;

record SecurityRuleDefinition(
        String id,
        String name,
        SecurityCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
