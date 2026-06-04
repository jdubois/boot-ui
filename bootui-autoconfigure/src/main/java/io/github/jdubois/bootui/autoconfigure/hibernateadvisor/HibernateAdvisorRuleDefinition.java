package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

record HibernateAdvisorRuleDefinition(
        String id,
        String name,
        HibernateAdvisorCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
