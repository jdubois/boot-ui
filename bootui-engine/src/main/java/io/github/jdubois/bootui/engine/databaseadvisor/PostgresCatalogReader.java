package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The PostgreSQL-only, read-only {@code pg_catalog} augmentation the generic JDBC metadata API cannot answer:
 * broken indexes, index semantics (partial/expression/method/validity), declarative partitioning, extension
 * ownership, {@code NOT VALID} constraints, and sequence consumption against the owning column's capacity.
 *
 * <p>Every statement is a bounded {@code SELECT} against system catalogs — never application data, never
 * DDL — and every one of them is allowed to fail: a locked-down role that cannot read {@code pg_index} yields
 * a {@link VendorAugmentation.Status#FAILED} augmentation whose reason the matching rule reports as
 * {@code SKIPPED}, while the generic scan continues unaffected.</p>
 */
final class PostgresCatalogReader {

    private static final String SYSTEM_SCHEMA_FILTER =
            " and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')"
                    + " and n.nspname not like 'pg\\_temp%' and n.nspname not like 'pg\\_toast%'";

    private static final String INVALID_INDEXES_BASE_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.relname as index_name,
                   i.indisvalid as is_valid, i.indisready as is_ready, i.indislive as is_live,
                   i.indisunique as is_unique
            from pg_index i
            join pg_class c on c.oid = i.indexrelid
            join pg_class t on t.oid = i.indrelid
            join pg_namespace n on n.oid = t.relnamespace
            where (i.indisvalid = false or i.indisready = false or i.indislive = false)
              and c.relkind = 'i'
              and t.relkind <> 'p'
            """ + SYSTEM_SCHEMA_FILTER + """
              and not exists (
                    select 1 from pg_depend d
                    where d.classid = 'pg_class'::regclass and d.objid = c.oid
                      and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e')
            """;

    /** {@code pg_stat_progress_create_index} requires PostgreSQL 12 or later. */
    private static final String EXCLUDE_INDEXES_BUILDING_CONCURRENTLY = """
              and not exists (
                    select 1 from pg_stat_progress_create_index p where p.index_relid = c.oid)
            """;

    private static final String ORDER_AND_LIMIT_SQL = """
            order by n.nspname, t.relname, c.relname
            limit ?
            """;

    private static final String INDEX_DETAILS_BASE_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.relname as index_name,
                   i.indisvalid as is_valid, i.indisready as is_ready, i.indislive as is_live,
                   i.indisunique as is_unique, i.indisprimary as is_primary,
                   (select min(pc.conname) from pg_constraint pc where pc.conindid = i.indexrelid
                       and pc.contype in ('p', 'u', 'x')) as constraint_name,
                   (select min(pc.contype::text) from pg_constraint pc where pc.conindid = i.indexrelid
                       and pc.contype in ('p', 'u', 'x')) as constraint_type,
                   (i.indpred is not null) as is_partial,
                   pg_get_expr(i.indpred, i.indrelid) as predicate,
                   (i.indexprs is not null) as has_expression,
                   am.amname as method""";

    private static final String INDEX_DETAILS_FROM_WHERE_SQL = """

            from pg_index i
            join pg_class c on c.oid = i.indexrelid
            join pg_class t on t.oid = i.indrelid
            join pg_namespace n on n.oid = t.relnamespace
            join pg_am am on am.oid = c.relam
            where true
            """ + SYSTEM_SCHEMA_FILTER;

    private static final String PARTITIONS_SQL = """
            select n.nspname as schema_name, c.relname as table_name,
                   (c.relkind = 'p') as is_partitioned_parent,
                   c.relispartition as is_partition_child
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where c.relkind in ('r', 'p')
              and (c.relkind = 'p' or c.relispartition)
            """ + SYSTEM_SCHEMA_FILTER + """
            order by n.nspname, c.relname
            limit ?
            """;

    private static final String EXTENSION_TABLES_SQL = """
            select n.nspname as schema_name, c.relname as table_name, e.extname as extension_name
            from pg_depend d
            join pg_class c on c.oid = d.objid
            join pg_namespace n on n.oid = c.relnamespace
            join pg_extension e on e.oid = d.refobjid
            where d.classid = 'pg_class'::regclass
              and d.refclassid = 'pg_extension'::regclass
              and d.deptype = 'e'
              and c.relkind in ('r', 'p')
            """ + SYSTEM_SCHEMA_FILTER + """
            order by n.nspname, c.relname
            limit ?
            """;

    private static final String UNVALIDATED_CONSTRAINTS_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.conname as constraint_name,
                   c.contype as constraint_type, pg_get_constraintdef(c.oid) as definition,
                   %s as is_enforced
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            join pg_namespace n on n.oid = t.relnamespace
            where c.convalidated = false
              and c.contype in ('f', 'c')
            """ + SYSTEM_SCHEMA_FILTER + """
              and not exists (
                    select 1 from pg_depend d
                    where d.classid = 'pg_constraint'::regclass and d.objid = c.oid
                      and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e')
            order by n.nspname, t.relname, c.conname
            limit ?
            """;

    private static final String SEQUENCES_SQL = """
            select s.schemaname as schema_name, s.sequencename as sequence_name,
                   s.last_value as last_value, s.max_value as max_value, s.cycle as is_cycle,
                   s.increment_by as increment_by,
                   s.min_value as min_value, s.start_value as start_value, s.cache_size as cache_size,
                   owner_ns.nspname as owner_schema, owner_table.relname as owner_table,
                   owner_column.attname as owner_column, owner_type.typname as owner_type
            from pg_sequences s
            join pg_class seq on seq.relname = s.sequencename
            join pg_namespace n on n.oid = seq.relnamespace and n.nspname = s.schemaname
            left join pg_depend d on d.classid = 'pg_class'::regclass and d.objid = seq.oid
                 and d.refclassid = 'pg_class'::regclass and d.deptype in ('a', 'i')
            left join pg_class owner_table on owner_table.oid = d.refobjid
            left join pg_namespace owner_ns on owner_ns.oid = owner_table.relnamespace
            left join pg_attribute owner_column on owner_column.attrelid = d.refobjid
                 and owner_column.attnum = d.refobjsubid
            left join pg_type owner_type on owner_type.oid = owner_column.atttypid
            where seq.relkind = 'S'
            """ + SYSTEM_SCHEMA_FILTER + """
            order by s.schemaname, s.sequencename
            limit ?
            """;

    /** Expanded membership honors all-table/schema publications and publish_via_partition_root. */
    private static final String REPLICA_IDENTITY_CANDIDATES_SQL = """
            select distinct n.nspname as schema_name, t.relname as table_name,
                   t.relreplident as replica_identity,
                   exists (select 1 from pg_index i where i.indrelid = t.oid
                           and i.indisreplident and i.indisvalid and i.indisready and i.indislive) as has_identity_index
            from pg_publication_tables pt
            join pg_publication p on p.pubname = pt.pubname
            join pg_namespace n on n.nspname = pt.schemaname
            join pg_class t on t.relnamespace = n.oid and t.relname = pt.tablename
            where t.relkind in ('r', 'p') and (p.pubupdate or p.pubdelete)
            """ + SYSTEM_SCHEMA_FILTER + """
            order by n.nspname, t.relname
            limit ?
            """;

    private PostgresCatalogReader() {}

    static void read(
            Connection connection,
            DatabaseVersion version,
            DialectCapabilities capabilities,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            VendorFindings.Builder findings) {
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                invalidIndexesSql(capabilities),
                budget,
                limits,
                PostgresCatalogReader::readInvalidIndex));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_INDEX_DETAILS,
                indexDetailsSql(capabilities),
                budget,
                limits,
                resultSet -> readIndexDetail(resultSet, capabilities)));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_EXTENSION_TABLES,
                EXTENSION_TABLES_SQL,
                budget,
                limits,
                PostgresCatalogReader::readExtensionTable));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS,
                unvalidatedConstraintsSql(version),
                budget,
                limits,
                PostgresCatalogReader::readUnvalidatedConstraint));
        if (capabilities.declarativePartitioning()) {
            findings.add(CatalogQuery.read(
                    connection,
                    VendorFindingKinds.POSTGRES_PARTITIONS,
                    PARTITIONS_SQL,
                    budget,
                    limits,
                    PostgresCatalogReader::readPartition));
        } else {
            findings.add(VendorAugmentation.notApplicable(
                    VendorFindingKinds.POSTGRES_PARTITIONS,
                    "Declarative partitioning requires PostgreSQL 10 or later (server reports " + version.describe()
                            + ")."));
        }
        if (capabilities.sequencesView()) {
            findings.add(CatalogQuery.read(
                    connection,
                    VendorFindingKinds.POSTGRES_SEQUENCES,
                    SEQUENCES_SQL,
                    budget,
                    limits,
                    PostgresCatalogReader::readSequence));
        } else {
            findings.add(VendorAugmentation.notApplicable(
                    VendorFindingKinds.POSTGRES_SEQUENCES,
                    "The pg_sequences view requires PostgreSQL 10 or later (server reports " + version.describe()
                            + ")."));
        }
        if (version.atLeast(10, 0)) {
            findings.add(CatalogQuery.read(
                    connection,
                    VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                    REPLICA_IDENTITY_CANDIDATES_SQL,
                    budget,
                    limits,
                    PostgresCatalogReader::readReplicaIdentityCandidate));
        } else {
            findings.add(VendorAugmentation.notApplicable(
                    VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                    "Publication catalogs require PostgreSQL 10 or later; server version must be known."));
        }
    }

    static String unvalidatedConstraintsSql(DatabaseVersion version) {
        return UNVALIDATED_CONSTRAINTS_SQL.replace(
                "%s", version.atLeast(18, 0) ? "c.conenforced" : version.known() ? "true" : "null::boolean");
    }

    private static String invalidIndexesSql(DialectCapabilities capabilities) {
        StringBuilder sql = new StringBuilder(INVALID_INDEXES_BASE_SQL);
        if (capabilities.indexBuildProgressView()) {
            // An index still being built CONCURRENTLY is transiently invalid by design; excluding it here is
            // the only way to tell that healthy, in-progress state from one a failed build left behind.
            sql.append(EXCLUDE_INDEXES_BUILDING_CONCURRENTLY);
        }
        sql.append(ORDER_AND_LIMIT_SQL);
        return sql.toString();
    }

    private static String indexDetailsSql(DialectCapabilities capabilities) {
        StringBuilder sql = new StringBuilder(INDEX_DETAILS_BASE_SQL);
        String keyCount = capabilities.indexIncludeColumns() ? "i.indnkeyatts" : "i.indnatts";
        sql.append(",\n                   ").append(keyCount).append(" as key_column_count");
        if (capabilities.nullsNotDistinct()) {
            sql.append(",\n                   i.indnullsnotdistinct as nulls_not_distinct");
        }
        sql.append("""
                ,
                   array(select a.attname::text from generate_series(1, %s) k(pos)
                         left join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[k.pos - 1]
                         order by k.pos) as key_columns,
                   array(select case when i.indkey[k.pos - 1] = 0
                                     then pg_get_indexdef(i.indexrelid, k.pos, false) else null end
                         from generate_series(1, %s) k(pos) order by k.pos) as key_expressions,
                   array(select i.indoption[k.pos - 1]::text || ':' || i.indcollation[k.pos - 1]::text
                                     || ':' || i.indclass[k.pos - 1]::text
                         from generate_series(1, %s) k(pos) order by k.pos) as key_semantics,
                   array(select a.attname::text from generate_series(%s + 1, i.indnatts) k(pos)
                         join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[k.pos - 1]
                         order by k.pos) as included_columns
                """.formatted(keyCount, keyCount, keyCount, keyCount));
        sql.append(INDEX_DETAILS_FROM_WHERE_SQL).append(ORDER_AND_LIMIT_SQL);
        return sql.toString();
    }

    private static PostgresInvalidIndex readInvalidIndex(ResultSet rs) throws SQLException {
        return new PostgresInvalidIndex(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                nullableBoolean(rs, "is_valid"),
                nullableBoolean(rs, "is_ready"),
                nullableBoolean(rs, "is_live"),
                nullableBoolean(rs, "is_unique"));
    }

    private static PostgresIndexDetail readIndexDetail(ResultSet rs, DialectCapabilities capabilities)
            throws SQLException {
        Integer keyCount = nullableInt(rs, "key_column_count");
        List<String> columns = strings(rs, "key_columns");
        List<String> expressions = strings(rs, "key_expressions");
        List<String> semantics = strings(rs, "key_semantics");
        List<String> included = strings(rs, "included_columns");
        List<IndexKeyPart> parts = new ArrayList<>();
        boolean complete = keyCount != null
                && keyCount > 0
                && columns != null
                && expressions != null
                && semantics != null
                && included != null
                && columns.size() == keyCount
                && expressions.size() == keyCount
                && semantics.size() == keyCount
                && !semantics.contains(null)
                && !included.contains(null);
        if (complete) {
            for (int position = 0; position < keyCount; position++) {
                String column = columns.get(position);
                String expression = expressions.get(position);
                if (column == null && expression == null) {
                    complete = false;
                }
                String[] flags = semantics.get(position).split(":");
                Boolean ascending = flags.length == 3 ? (Integer.parseInt(flags[0]) & 1) == 0 : null;
                if (ascending == null) {
                    complete = false;
                }
                parts.add(new IndexKeyPart(column, expression, ascending, null, flags.length == 3 ? flags[1] : null));
            }
        }
        Boolean valid = nullableBoolean(rs, "is_valid");
        Boolean ready = nullableBoolean(rs, "is_ready");
        Boolean live = nullableBoolean(rs, "is_live");
        Boolean unique = nullableBoolean(rs, "is_unique");
        Boolean primary = nullableBoolean(rs, "is_primary");
        Boolean partial = nullableBoolean(rs, "is_partial");
        Boolean expression = nullableBoolean(rs, "has_expression");
        Boolean nullsNotDistinct =
                capabilities.nullsNotDistinct() ? nullableBoolean(rs, "nulls_not_distinct") : Boolean.FALSE;
        String method = rs.getString("method");
        return new PostgresIndexDetail(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                Boolean.TRUE.equals(valid),
                Boolean.TRUE.equals(partial),
                rs.getString("predicate"),
                Boolean.TRUE.equals(expression),
                method,
                keyCount,
                Boolean.TRUE.equals(nullsNotDistinct),
                ready,
                live,
                unique,
                primary,
                rs.getString("constraint_name"),
                rs.getString("constraint_type"),
                parts,
                included == null || included.contains(null) ? List.of() : included,
                semantics == null || semantics.contains(null) ? List.of() : semantics,
                complete
                        && valid != null
                        && ready != null
                        && live != null
                        && unique != null
                        && primary != null
                        && partial != null
                        && expression != null
                        && nullsNotDistinct != null
                        && method != null
                        && !method.isBlank());
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return null;
        }
        try {
            Object[] values = (Object[]) array.getArray();
            List<String> result = new ArrayList<>(values.length);
            for (Object value : values) {
                result.add(value == null ? null : value.toString());
            }
            return result;
        } finally {
            array.free();
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static PostgresPartitionInfo readPartition(ResultSet rs) throws SQLException {
        return new PostgresPartitionInfo(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getBoolean("is_partitioned_parent"),
                rs.getBoolean("is_partition_child"));
    }

    private static PostgresExtensionTable readExtensionTable(ResultSet rs) throws SQLException {
        return new PostgresExtensionTable(
                rs.getString("schema_name"), rs.getString("table_name"), rs.getString("extension_name"));
    }

    private static PostgresReplicaIdentityCandidate readReplicaIdentityCandidate(ResultSet rs) throws SQLException {
        return new PostgresReplicaIdentityCandidate(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("replica_identity"),
                nullableBoolean(rs, "has_identity_index"));
    }

    private static PostgresUnvalidatedConstraint readUnvalidatedConstraint(ResultSet rs) throws SQLException {
        return new PostgresUnvalidatedConstraint(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("constraint_name"),
                rs.getString("constraint_type"),
                rs.getString("definition"),
                nullableBoolean(rs, "is_enforced"));
    }

    private static PostgresSequenceUsage readSequence(ResultSet rs) throws SQLException {
        BigInteger lastValue = bigInteger(rs, "last_value");
        String ownerType = rs.getString("owner_type");
        long incrementBy = rs.getLong("increment_by");
        Long incrementByOrNull = rs.wasNull() ? null : incrementBy;
        return new PostgresSequenceUsage(
                rs.getString("schema_name"),
                rs.getString("sequence_name"),
                lastValue,
                bigInteger(rs, "max_value"),
                capacityOf(ownerType),
                rs.getBoolean("is_cycle"),
                rs.getString("owner_schema"),
                rs.getString("owner_table"),
                rs.getString("owner_column"),
                ownerType,
                incrementByOrNull,
                bigInteger(rs, "min_value"),
                bigInteger(rs, "start_value"),
                capacityOf(ownerType) == null
                        ? null
                        : capacityOf(ownerType).negate().subtract(BigInteger.ONE),
                bigInteger(rs, "cache_size"));
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    /** The largest value the sequence's owning column type can hold, or {@code null} when not classified. */
    private static BigInteger capacityOf(String pgTypeName) {
        if (pgTypeName == null) {
            return null;
        }
        return switch (pgTypeName.toLowerCase(Locale.ROOT)) {
            case "int2", "smallint", "smallserial" -> BigInteger.valueOf(Short.MAX_VALUE);
            case "int4", "integer", "serial" -> BigInteger.valueOf(Integer.MAX_VALUE);
            case "int8", "bigint", "bigserial" -> BigInteger.valueOf(Long.MAX_VALUE);
            default -> null;
        };
    }

    private static BigInteger bigInteger(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.toBigInteger();
    }
}
