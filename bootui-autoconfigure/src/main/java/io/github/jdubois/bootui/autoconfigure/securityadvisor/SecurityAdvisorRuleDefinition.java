package io.github.jdubois.bootui.autoconfigure.securityadvisor;

record SecurityAdvisorRuleDefinition(
        String id,
        String name,
        SecurityAdvisorCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
