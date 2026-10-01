package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * The typed keys for every vendor catalog augmentation the {@link SchemaIntrospector} can read.
 *
 * <p>Each key names one read-only {@code pg_catalog}/{@code information_schema} query. Rules look their data
 * up by key and can always see whether it was available, not applicable for this dialect/version, or blocked
 * — which is what lets them report an honest {@code SKIPPED} instead of an unearned {@code PASS}.</p>
 */
final class VendorFindingKinds {

    static final VendorFindingKind<PostgresInvalidIndex> POSTGRES_INVALID_INDEXES = new VendorFindingKind<>(
            "postgresql.invalid-indexes", "PostgreSQL invalid indexes", PostgresInvalidIndex.class);

    static final VendorFindingKind<PostgresSequenceUsage> POSTGRES_SEQUENCES =
            new VendorFindingKind<>("postgresql.sequences", "PostgreSQL sequence usage", PostgresSequenceUsage.class);

    static final VendorFindingKind<PostgresPartitionInfo> POSTGRES_PARTITIONS = new VendorFindingKind<>(
            "postgresql.partitions", "PostgreSQL partitioned tables", PostgresPartitionInfo.class);

    static final VendorFindingKind<PostgresExtensionTable> POSTGRES_EXTENSION_TABLES = new VendorFindingKind<>(
            "postgresql.extension-tables", "PostgreSQL extension-owned tables", PostgresExtensionTable.class);

    static final VendorFindingKind<PostgresUnvalidatedConstraint> POSTGRES_UNVALIDATED_CONSTRAINTS =
            new VendorFindingKind<>(
                    "postgresql.unvalidated-constraints",
                    "PostgreSQL NOT VALID constraints",
                    PostgresUnvalidatedConstraint.class);

    static final VendorFindingKind<PostgresUnloggedTable> POSTGRES_UNLOGGED_TABLES = new VendorFindingKind<>(
            "postgresql.unlogged-tables", "PostgreSQL unlogged tables", PostgresUnloggedTable.class);

    static final VendorFindingKind<PostgresIndexDetail> POSTGRES_INDEX_DETAILS = new VendorFindingKind<>(
            "postgresql.index-details", "PostgreSQL index semantics", PostgresIndexDetail.class);

    static final VendorFindingKind<PostgresReplicaIdentityCandidate> POSTGRES_REPLICA_IDENTITY_CANDIDATES =
            new VendorFindingKind<>(
                    "postgresql.replica-identity-candidates",
                    "PostgreSQL tables in scope for logical replication",
                    PostgresReplicaIdentityCandidate.class);

    static final VendorFindingKind<MySqlTableInfo> MYSQL_TABLES =
            new VendorFindingKind<>("mysql.tables", "MySQL/MariaDB table metadata", MySqlTableInfo.class);

    static final VendorFindingKind<MySqlColumnCharset> MYSQL_COLUMN_CHARSETS = new VendorFindingKind<>(
            "mysql.column-charsets", "MySQL/MariaDB column character sets", MySqlColumnCharset.class);

    static final VendorFindingKind<MySqlAutoIncrementColumn> MYSQL_AUTO_INCREMENT_COLUMNS = new VendorFindingKind<>(
            "mysql.auto-increment-columns", "MySQL/MariaDB AUTO_INCREMENT columns", MySqlAutoIncrementColumn.class);

    static final VendorFindingKind<MySqlIndexDetail> MYSQL_INDEX_DETAILS =
            new VendorFindingKind<>("mysql.index-details", "MySQL/MariaDB index semantics", MySqlIndexDetail.class);

    static final VendorFindingKind<OracleIndexDetail> ORACLE_INDEX_DETAILS =
            new VendorFindingKind<>("oracle.index-details", "Oracle index status/visibility", OracleIndexDetail.class);

    static final VendorFindingKind<OracleIndexPartitionStatus> ORACLE_INDEX_PARTITION_STATUS = new VendorFindingKind<>(
            "oracle.index-partition-status",
            "Oracle index partition/subpartition status",
            OracleIndexPartitionStatus.class);

    static final VendorFindingKind<OracleConstraintDetail> ORACLE_CONSTRAINTS =
            new VendorFindingKind<>("oracle.constraints", "Oracle constraint status", OracleConstraintDetail.class);

    static final VendorFindingKind<OracleSequenceUsage> ORACLE_SEQUENCES =
            new VendorFindingKind<>("oracle.sequences", "Oracle sequence usage", OracleSequenceUsage.class);

    static final VendorFindingKind<OracleIdentityColumn> ORACLE_IDENTITY_COLUMNS =
            new VendorFindingKind<>("oracle.identity-columns", "Oracle identity columns", OracleIdentityColumn.class);

    private VendorFindingKinds() {}
}
