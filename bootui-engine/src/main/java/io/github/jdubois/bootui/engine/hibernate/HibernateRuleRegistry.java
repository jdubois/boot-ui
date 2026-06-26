package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

final class HibernateRuleRegistry {

    private static final List<HibernateRule> ACTIVE_RULES = List.of(
            // Fetching
            new EagerFetchRule(),
            new CollectionJoinFetchPageableRule(),
            new MultipleBagCollectionRule(),
            new MissingBatchFetchRule(),
            new LobLazyFetchRule(),
            new CollectionFetchJoinAnnotationRule(),
            // Identifiers
            new IdentityIdentifierRule(),
            new TableIdentifierRule(),
            new SequenceAllocationSizeRule(),
            new GeneratedValueWithoutStrategyRule(),
            new UuidIdentifierGeneratorRule(),
            new IdentityDisablesBatchingRule(),
            // Mapping
            new UnidirectionalOneToManyRule(),
            new ManyToManyListRule(),
            new OrdinalEnumRule(),
            new ManyToManyRemoveCascadeRule(),
            new ManyToOneRemoveCascadeRule(),
            new OneToOneWithoutMapsIdRule(),
            new TablePerClassInheritanceRule(),
            new NotFoundIgnoreRule(),
            new OptionalPersistentAttributeRule(),
            new ElementCollectionListOrderRule(),
            new FinalEntityRule(),
            new SingleTableMissingDiscriminatorRule(),
            new StringColumnLengthRule(),
            new BigDecimalPrecisionRule(),
            new LegacyDateTimeRule(),
            new ManyToOneOptionalRule(),
            new LazyOneToOneEnhancementRule(),
            new NonOwningOneToOneEnhancementRule(),
            new MissingForeignKeyIndexRule(),
            new UnidirectionalOneToManyJoinColumnRule(),

            // Entity design
            new EqualsHashCodePairRule(),
            new OptimisticLockingDynamicUpdateRule(),
            new EqualsHashCodeAssociationsRule(),
            new ToStringAssociationsRule(),
            new PublicPersistentFieldRule(),
            new PrimitiveIdentifierOrVersionRule(),
            new AssignedIdPersistableRule(),
            new MissingVersionRule(),

            // Query
            new ModifyingClearAutomaticallyRule(),
            new StreamReturningMethodRule(),
            new NativePagedQueryCountRule(),
            new DerivedDeleteByQueryRule(),
            new EagerToOneFetchJoinRule(),
            new EntityProjectionQueryRule(),
            new MultipleCollectionJoinFetchRule(),
            // Configuration
            new OpenInViewRule(),
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
            new SqlLoggingInProductionRule(),
            new JdbcTimeZoneRule(),
            new HibernateBuiltinPoolRule(),
            new DeferDatasourceInitializationRule(),
            new FailOnPaginationOverCollectionFetchRule(),
            new FormatSqlInProductionRule(),

            // Caching
            new CacheAssociationCoverageRule(),
            new ReadOnlyCacheOnWritableEntityRule());

    private HibernateRuleRegistry() {}

    static List<HibernateRule> activeRules() {
        return ACTIVE_RULES;
    }
}
