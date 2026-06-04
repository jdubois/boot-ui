package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import java.util.List;

final class HibernateAdvisorRuleRegistry {

    private static final List<HibernateAdvisorRule> ACTIVE_RULES = List.of(
            new EagerAssociationFetchRule(),
            new IdentityIdentifierRule(),
            new UnidirectionalOneToManyRule(),
            new ManyToManyListRule(),
            new OrdinalEnumRule(),
            new OpenInViewRule(),
            new MissingBatchFetchRule(),
            new RiskyDdlAutoRule(),
            new EqualsHashCodePairRule());

    private HibernateAdvisorRuleRegistry() {}

    static List<HibernateAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
