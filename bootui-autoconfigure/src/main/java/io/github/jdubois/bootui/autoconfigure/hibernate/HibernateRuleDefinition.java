package io.github.jdubois.bootui.autoconfigure.hibernate;

record HibernateRuleDefinition(
        String id,
        String name,
        HibernateCategory category,
        String severity,
        String description,
        String recommendation,
        String learnMoreUrl) {}
