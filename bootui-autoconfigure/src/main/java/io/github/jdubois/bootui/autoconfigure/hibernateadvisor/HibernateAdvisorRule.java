package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorRuleResultDto;

interface HibernateAdvisorRule {

    HibernateAdvisorRuleDefinition definition();

    HibernateAdvisorRuleResultDto evaluate(HibernateAdvisorContext context);
}
