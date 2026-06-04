package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import java.util.List;

final class HibernateAdvisorRuleRegistry {

    private static final List<HibernateAdvisorRule> ACTIVE_RULES = List.of(
            new EagerAssociationFetchRule(),
            new CollectionJoinFetchPageableRule(),
            new MultipleBagCollectionRule(),
            new IdentityIdentifierRule(),
            new TableIdentifierRule(),
            new SequenceAllocationSizeRule(),
            new UnidirectionalOneToManyRule(),
            new ManyToManyListRule(),
            new OrdinalEnumRule(),
            new ManyToManyRemoveCascadeRule(),
            new ManyToOneRemoveCascadeRule(),
            new OneToOneWithoutMapsIdRule(),
            new TablePerClassInheritanceRule(),
            new NotFoundIgnoreRule(),
            new OptionalPersistentAttributeRule(),
            new OpenInViewRule(),
            new MissingBatchFetchRule(),
            new LazyLoadNoTransRule(),
            new JdbcBatchSizeRule(),
            new OrderedBatchingRule(),
            new SlowQueryLogRule(),
            new HibernateStatisticsRule(),
            new ProviderDisablesAutocommitRule(),
            new InClausePaddingRule(),
            new QueryCacheRegionFactoryRule(),
            new CacheableWithoutCacheStrategyRule(),
            new RiskyDdlAutoRule(),
            new EqualsHashCodePairRule(),
            new OptimisticLockingDynamicUpdateRule());

    private HibernateAdvisorRuleRegistry() {}

    static List<HibernateAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
