package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;

interface HibernateRule {

    HibernateRuleDefinition definition();

    HibernateRuleResultDto evaluate(HibernateContext context);
}
