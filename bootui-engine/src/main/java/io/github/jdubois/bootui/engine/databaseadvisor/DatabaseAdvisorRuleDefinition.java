package io.github.jdubois.bootui.engine.databaseadvisor;

record DatabaseAdvisorRuleDefinition(
        String id,
        String name,
        DatabaseAdvisorCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
