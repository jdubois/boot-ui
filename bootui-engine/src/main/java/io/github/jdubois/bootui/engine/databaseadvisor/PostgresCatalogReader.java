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
 * ownership, {@code NOT VALID} constraints, native foreign-key trigger enforcement, and sequence consumption
 * against the owning column's capacity.
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
                   c.relispartition as is_partition_child,
                   (c.relkind = 'p'
                       and not exists (select 1 from pg_inherits inh where inh.inhparent = c.oid)
                       and not exists (
                             select 1 from pg_depend d
                             where d.classid = 'pg_class'::regclass and d.objid = c.oid
                               and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e'))
                       as is_empty_partitioned_parent
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
              and c.contype in ('f', 'c', 'n')
            """ + SYSTEM_SCHEMA_FILTER + """
              and not exists (
                    select 1 from pg_depend d
                    where d.classid = 'pg_constraint'::regclass and d.objid = c.oid
                      and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e')
            order by n.nspname, t.relname, c.conname
            limit ?
            """;

    private static final String FOREIGN_KEY_DETAILS_SQL = """
            select n.nspname as schema_name, t.relname as table_name, c.conname as constraint_name,
                   c.convalidated as is_validated, %s as declared_enforced,
                   (t.relkind = 'r' and referenced.relkind = 'r'
                       and not exists (select 1 from pg_inherits inh
                           where inh.inhrelid in (c.conrelid, c.confrelid)
                              or inh.inhparent in (c.conrelid, c.confrelid))) as ordinary_relations,
                   count(g.oid) as trigger_count,
                   count(g.oid) filter (where g.tgrelid = c.conrelid and g.tgconstrrelid = c.confrelid
                       and g.tgtype = 5
                       and g.tgfoid = 'pg_catalog."RI_FKey_check_ins"()'::regprocedure) as child_insert_count,
                   count(g.oid) filter (where g.tgrelid = c.conrelid and g.tgconstrrelid = c.confrelid
                       and g.tgtype = 17
                       and g.tgfoid = 'pg_catalog."RI_FKey_check_upd"()'::regprocedure) as child_update_count,
                   count(g.oid) filter (where g.tgrelid = c.confrelid and g.tgconstrrelid = c.conrelid
                       and g.tgtype = 9 and g.tgfoid = case c.confdeltype
                           when 'a' then 'pg_catalog."RI_FKey_noaction_del"()'::regprocedure
                           when 'r' then 'pg_catalog."RI_FKey_restrict_del"()'::regprocedure
                           when 'c' then 'pg_catalog."RI_FKey_cascade_del"()'::regprocedure
                           when 'n' then 'pg_catalog."RI_FKey_setnull_del"()'::regprocedure
                           when 'd' then 'pg_catalog."RI_FKey_setdefault_del"()'::regprocedure
                       end) as parent_delete_count,
                   count(g.oid) filter (where g.tgrelid = c.confrelid and g.tgconstrrelid = c.conrelid
                       and g.tgtype = 17 and g.tgfoid = case c.confupdtype
                           when 'a' then 'pg_catalog."RI_FKey_noaction_upd"()'::regprocedure
                           when 'r' then 'pg_catalog."RI_FKey_restrict_upd"()'::regprocedure
                           when 'c' then 'pg_catalog."RI_FKey_cascade_upd"()'::regprocedure
                           when 'n' then 'pg_catalog."RI_FKey_setnull_upd"()'::regprocedure
                           when 'd' then 'pg_catalog."RI_FKey_setdefault_upd"()'::regprocedure
                       end) as parent_update_count,
                   bool_and(g.tgisinternal and g.tgqual is null and g.tgnargs = 0
                       and g.tgattr::text = '') as native_trigger_shape,
                   bool_and(g.tgenabled in ('O', 'D', 'R', 'A')) as trigger_modes_known,
                   bool_and(case current_setting('session_replication_role')
                       when 'origin' then g.tgenabled in ('O', 'A')
                       when 'local' then g.tgenabled in ('O', 'A')
                       when 'replica' then g.tgenabled in ('R', 'A')
                       else null::boolean end) as triggers_enabled,
                   bool_and(case current_setting('session_replication_role')
                       when 'origin' then g.tgenabled in ('O', 'A')
                       when 'local' then g.tgenabled in ('O', 'A')
                       when 'replica' then g.tgenabled in ('R', 'A')
                       else null::boolean end)
                       filter (where g.tgrelid = c.confrelid and g.tgconstrrelid = c.conrelid
                           and g.tgtype = 9 and g.tgfoid = case c.confdeltype
                               when 'a' then 'pg_catalog."RI_FKey_noaction_del"()'::regprocedure
                               when 'r' then 'pg_catalog."RI_FKey_restrict_del"()'::regprocedure
                               when 'c' then 'pg_catalog."RI_FKey_cascade_del"()'::regprocedure
                               when 'n' then 'pg_catalog."RI_FKey_setnull_del"()'::regprocedure
                               when 'd' then 'pg_catalog."RI_FKey_setdefault_del"()'::regprocedure
                           end) as parent_delete_enabled,
                   bool_and(case current_setting('session_replication_role')
                       when 'origin' then g.tgenabled in ('O', 'A')
                       when 'local' then g.tgenabled in ('O', 'A')
                       when 'replica' then g.tgenabled in ('R', 'A')
                       else null::boolean end)
                       filter (where g.tgrelid = c.confrelid and g.tgconstrrelid = c.conrelid
                           and g.tgtype = 17 and g.tgfoid = case c.confupdtype
                               when 'a' then 'pg_catalog."RI_FKey_noaction_upd"()'::regprocedure
                               when 'r' then 'pg_catalog."RI_FKey_restrict_upd"()'::regprocedure
                               when 'c' then 'pg_catalog."RI_FKey_cascade_upd"()'::regprocedure
                               when 'n' then 'pg_catalog."RI_FKey_setnull_upd"()'::regprocedure
                               when 'd' then 'pg_catalog."RI_FKey_setdefault_upd"()'::regprocedure
                           end) as parent_update_enabled
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            join pg_namespace n on n.oid = t.relnamespace
            join pg_class referenced on referenced.oid = c.confrelid
            left join pg_trigger g on g.tgconstraint = c.oid
            where c.contype = 'f'
            """ + SYSTEM_SCHEMA_FILTER + """
            group by n.nspname, t.relname, c.oid, c.conname, c.convalidated,
                     c.conrelid, c.confrelid, t.relkind, referenced.relkind
            order by n.nspname, t.relname, c.conname
            limit ?
            """;

    /**
     * Unlogged ordinary tables and leaf partitions; partitioned parents are excluded because PostgreSQL 13-18
     * rejects unlogged partitioned tables, so their {@code relpersistence} carries no storage meaning.
     */
    private static final String UNLOGGED_TABLES_SQL = """
            select n.nspname as schema_name, c.relname as table_name, %s as is_partition
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where c.relkind = 'r'
              and c.relpersistence = 'u'
            """ + SYSTEM_SCHEMA_FILTER + """
              and not exists (
                    select 1 from pg_depend d
                    where d.classid = 'pg_class'::regclass and d.objid = c.oid
                      and d.refclassid = 'pg_extension'::regclass and d.deptype = 'e')
            order by n.nspname, c.relname
            limit ?
            """;

    private static final String SEQUENCES_SQL = """
            select s.schemaname as schema_name, s.sequencename as sequence_name,
                   s.last_value as last_value, s.max_value as max_value, s.cycle as is_cycle,
                   s.increment_by as increment_by,
                   s.min_value as min_value, s.start_value as start_value, s.cache_size as cache_size,
                   (s.last_value is null and has_sequence_privilege(seq.oid, 'SELECT, USAGE')
                       and not (pg_is_in_recovery() and seq.relpersistence = 'u')) as never_used,
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

    /**
     * The {@code pg_settings} names the advisor reads, bound as parameters. {@code idle_session_timeout} exists from
     * PostgreSQL 14, {@code reserved_connections} from 16 and {@code idle_in_transaction_session_timeout} from 9.6; a
     * name the server lacks, or hides from this role, simply returns no row.
     */
    static final List<String> SERVER_SETTING_NAMES = List.of(
            "max_connections",
            "superuser_reserved_connections",
            "reserved_connections",
            "idle_session_timeout",
            "idle_in_transaction_session_timeout",
            "autovacuum",
            "track_io_timing",
            "shared_preload_libraries");

    /** Whether {@code pg_stat_statements} is created in the connected database; a constant, so nothing is bound. */
    static final String PG_STAT_STATEMENTS_EXTENSION_SQL =
            "select extname from pg_extension where extname = 'pg_stat_statements' limit ?";

    private PostgresCatalogReader() {}

    static void read(
            Connection connection,
            DatabaseVersion version,
            DialectCapabilities capabilities,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            VendorFindings.Builder findings) {
        // First, and tiny: the other catalog reads must not spend the scan budget before the pool rules get their
        // limits.
        findings.add(readServerSettings(connection, budget, limits));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_PG_STAT_STATEMENTS_EXTENSION,
                PG_STAT_STATEMENTS_EXTENSION_SQL,
                budget,
                limits,
                resultSet -> new PostgresInstalledExtension(resultSet.getString("extname"))));
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
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_FOREIGN_KEY_DETAILS,
                foreignKeyDetailsSql(version),
                budget,
                limits,
                PostgresCatalogReader::readForeignKeyDetail));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_UNLOGGED_TABLES,
                unloggedTablesSql(capabilities),
                budget,
                limits,
                PostgresCatalogReader::readUnloggedTable));
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

    /** The session values of {@link #SERVER_SETTING_NAMES}, through the bounded, parameterized read. */
    static VendorAugmentation<ServerSetting> readServerSettings(
            Connection connection, ScanBudget budget, DatabaseAdvisorLimits limits) {
        return CatalogQuery.read(
                connection,
                VendorFindingKinds.POSTGRES_SERVER_SETTINGS,
                serverSettingsSql(SERVER_SETTING_NAMES.size()),
                List.copyOf(SERVER_SETTING_NAMES),
                budget,
                limits,
                PostgresCatalogReader::readServerSetting);
    }

    static String serverSettingsSql(int names) {
        return "select name, setting, unit from pg_settings where name in ("
                + String.join(", ", java.util.Collections.nCopies(names, "?")) + ") order by name limit ?";
    }

    private static ServerSetting readServerSetting(ResultSet rs) throws SQLException {
        return new ServerSetting(rs.getString("name"), rs.getString("setting"), rs.getString("unit"));
    }

    static String unloggedTablesSql(DialectCapabilities capabilities) {
        return UNLOGGED_TABLES_SQL.replace("%s", capabilities.declarativePartitioning() ? "c.relispartition" : "false");
    }

    static String unvalidatedConstraintsSql(DatabaseVersion version) {
        return UNVALIDATED_CONSTRAINTS_SQL.replace(
                "%s", version.atLeast(18, 0) ? "c.conenforced" : version.known() ? "true" : "null::boolean");
    }

    static String foreignKeyDetailsSql(DatabaseVersion version) {
        return FOREIGN_KEY_DETAILS_SQL.replace(
                "%s", version.atLeast(18, 0) ? "bool_and(c.conenforced)" : version.known() ? "true" : "null::boolean");
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
                   array(select coalesce(oc.opcdefault, false)::text from generate_series(1, %s) k(pos)
                         left join pg_opclass oc on oc.oid = i.indclass[k.pos - 1]
                         order by k.pos) as key_default_operator_classes,
                   array(select a.attname::text from generate_series(%s + 1, i.indnatts) k(pos)
                         join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[k.pos - 1]
                         order by k.pos) as included_columns
                """.formatted(keyCount, keyCount, keyCount, keyCount, keyCount));
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

    /**
     * {@code pg_index.indcollation} is never SQL NULL: {@code 0} (InvalidOid) marks a non-collatable key and
     * {@code 100} (the pinned {@code default} collation) an ordinary text key. Only an explicit other collation
     * changes comparison semantics.
     */
    static String explicitCollation(String collationOid) {
        return collationOid == null || collationOid.isEmpty() || "0".equals(collationOid) || "100".equals(collationOid)
                ? null
                : collationOid;
    }

    private static PostgresIndexDetail readIndexDetail(ResultSet rs, DialectCapabilities capabilities)
            throws SQLException {
        Integer keyCount = nullableInt(rs, "key_column_count");
        List<String> columns = strings(rs, "key_columns");
        List<String> expressions = strings(rs, "key_expressions");
        List<String> semantics = strings(rs, "key_semantics");
        List<String> defaultOperatorClasses = strings(rs, "key_default_operator_classes");
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
                // An unreported operator class is not evidence of ordinary equality.
                boolean defaultOperatorClass = defaultOperatorClasses != null
                        && defaultOperatorClasses.size() == keyCount
                        && "true".equals(defaultOperatorClasses.get(position));
                parts.add(new IndexKeyPart(
                        column,
                        expression,
                        ascending,
                        null,
                        flags.length == 3 ? explicitCollation(flags[1]) : null,
                        defaultOperatorClass));
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
                rs.getBoolean("is_partition_child"),
                rs.getBoolean("is_empty_partitioned_parent"));
    }

    private static PostgresUnloggedTable readUnloggedTable(ResultSet rs) throws SQLException {
        return new PostgresUnloggedTable(
                rs.getString("schema_name"), rs.getString("table_name"), rs.getBoolean("is_partition"));
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

    private static PostgresForeignKeyDetail readForeignKeyDetail(ResultSet rs) throws SQLException {
        Boolean enforced = nullableBoolean(rs, "declared_enforced");
        Boolean deleteActionEnabled = enforced;
        Boolean updateActionEnabled = enforced;
        if (!Boolean.FALSE.equals(enforced)) {
            boolean supported = Boolean.TRUE.equals(enforced)
                    && Boolean.TRUE.equals(nullableBoolean(rs, "ordinary_relations"))
                    && rs.getInt("trigger_count") == 4
                    && rs.getInt("child_insert_count") == 1
                    && rs.getInt("child_update_count") == 1
                    && rs.getInt("parent_delete_count") == 1
                    && rs.getInt("parent_update_count") == 1
                    && Boolean.TRUE.equals(nullableBoolean(rs, "native_trigger_shape"))
                    && Boolean.TRUE.equals(nullableBoolean(rs, "trigger_modes_known"));
            enforced = supported ? nullableBoolean(rs, "triggers_enabled") : null;
            deleteActionEnabled = supported ? nullableBoolean(rs, "parent_delete_enabled") : null;
            updateActionEnabled = supported ? nullableBoolean(rs, "parent_update_enabled") : null;
        }
        return new PostgresForeignKeyDetail(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("constraint_name"),
                enforced,
                nullableBoolean(rs, "is_validated"),
                deleteActionEnabled,
                updateActionEnabled);
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
                bigInteger(rs, "cache_size"),
                lastValue == null && rs.getBoolean("never_used"));
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
