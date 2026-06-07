package io.github.jdubois.bootui.autoconfigure.springadvisor;

record SpringAdvisorRuleDefinition(
        String id,
        String name,
        SpringAdvisorCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
