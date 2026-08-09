package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

final class DatabaseAdvisorRuleRegistry {

    private static final List<DatabaseAdvisorRule> ACTIVE_RULES = List.of(
            // Schema (generic, all dialects)
            new MissingPrimaryKeyRule(),
            new MissingForeignKeyIndexRule(),
            new DuplicateIndexRule(),
            new ForeignKeyTypeMismatchRule(),
            new RedundantPrimaryKeyUniqueIndexRule(),
            // Schema (dialect-specific catalog augmentation)
            new PostgresInvalidIndexRule(),
            new PostgresSequenceExhaustionRule(),
            new MySqlNonInnodbEngineRule(),
            new MySqlNonUtf8mb4CharsetRule(),
            // Hibernate <-> physical schema cross-reference
            new HibernateMissingForeignKeyIndexRule(),
            new HibernateMissingTableRule(),
            new HibernateColumnMismatchRule(),
            new HibernateColumnLengthMismatchRule(),
            new HibernateMissingUniqueIndexRule());

    private DatabaseAdvisorRuleRegistry() {}

    static List<DatabaseAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
