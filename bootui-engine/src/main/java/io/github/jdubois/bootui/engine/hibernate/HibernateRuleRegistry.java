package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;
import java.util.Set;

final class HibernateRuleRegistry {

    /**
     * Rule IDs that were removed from the catalogue. They stay reserved so existing dismissals never silently apply
     * to a different check; see docs/HIBERNATE-CHECKS.md.
     */
    static final Set<String> RETIRED_RULE_IDS = Set.of(
            "HIB-FETCH-004",
            "HIB-MAP-012",
            "HIB-MAP-017",
            "HIB-MAP-019",
            "HIB-MAP-021",
            "HIB-ENTITY-002",
            "HIB-ENTITY-003",
            "HIB-ENTITY-004",
            "HIB-QUERY-006",
            "HIB-CONFIG-001",
            "HIB-CONFIG-007",
            "HIB-CONFIG-011");

    private static final List<HibernateRule> ACTIVE_RULES = List.of(
            // Fetching
            new EagerFetchRule(),
            new CollectionJoinFetchPageableRule(),
            new MissingBatchFetchRule(),
            new LobLazyFetchRule(),
            new LazyBasicWithoutEnhancementRule(),
            new CollectionFetchJoinAnnotationRule(),
            new SubselectCollectionFetchRule(),
            new MultipleLazyLobSameGroupRule(),
            // Identifiers
            new IdentityIdentifierRule(),
            new TableIdentifierRule(),
            new SequenceAllocationSizeRule(),
            new GeneratedValueWithoutStrategyRule(),
            new UuidIdentifierGeneratorRule(),
            new IdentityDisablesBatchingRule(),
            new CompositeIdentifierContractRule(),
            // Mapping
            new UnidirectionalOneToManyRule(),
            new ManyToManyListRule(),
            new OrdinalEnumRule(),
            new ExplicitOrdinalEnumRule(),
            new ManyToManyRemoveCascadeRule(),
            new ManyToOneRemoveCascadeRule(),
            new OneToOneWithoutMapsIdRule(),
            new TablePerClassInheritanceRule(),
            new NotFoundIgnoreRule(),
            new OptionalPersistentAttributeRule(),
            new ElementCollectionListOrderRule(),
            new FinalEntityRule(),
            new StringColumnLengthRule(),
            new BigDecimalPrecisionRule(),
            new LegacyDateTimeRule(),
            new ManyToOneOptionalRule(),
            new NonOwningOneToOneEnhancementRule(),
            new UnidirectionalOneToManyJoinColumnRule(),
            new EmbeddableSetEqualityRule(),
            new PostgresLobRule(),
            new MismatchedAccessPlacementRule(),
            new DeprecatedGenericGeneratorRule(),

            // Entity design
            new EqualsHashCodePairRule(),
            new PublicPersistentFieldRule(),
            new PrimitiveIdentifierOrVersionRule(),
            new AssignedIdPersistableRule(),
            new MissingVersionRule(),
            new NaturalIdCandidateRule(),
            new TemporalVersionRule(),
            new KotlinDataClassEntityRule(),
            new PanacheEntityIdShadowingRule(),

            // Query
            new ModifyingClearAutomaticallyRule(),
            new StreamReturningMethodRule(),
            new NativePagedQueryCountRule(),
            new DerivedDeleteByQueryRule(),
            new EagerToOneFetchJoinRule(),
            new MultipleCollectionJoinFetchRule(),
            new BulkUpdateVersionRule(),
            new OpenProjectionValueRule(),
            // Configuration
            new LazyLoadNoTransRule(),
            new JdbcBatchSizeRule(),
            new OrderedBatchingRule(),
            new SlowQueryLogRule(),
            new ProviderDisablesAutocommitRule(),
            new ProviderAutoCommitContradictionRule(),
            new InClausePaddingRule(),
            new QueryCacheRegionFactoryRule(),
            new RiskyDdlAutoRule(),
            new SqlLoggingInProductionRule(),
            new JdbcTimeZoneRule(),
            new HibernateBuiltinPoolRule(),
            new DeferDatasourceInitializationRule(),
            new FailOnPaginationOverCollectionFetchRule(),
            new FormatSqlInProductionRule(),
            new BindParameterLoggingInProductionRule(),
            new SqlCommentsRule(),
            new OracleJdbcFetchSizeRule(),

            // Caching
            new CacheAssociationCoverageRule(),
            new ReadOnlyCacheOnWritableEntityRule(),
            new ImmutableEntityCacheStrategyRule(),
            new NaturalIdCacheWithoutEntityCacheRule());

    private HibernateRuleRegistry() {}

    static List<HibernateRule> activeRules() {
        return ACTIVE_RULES;
    }
}
