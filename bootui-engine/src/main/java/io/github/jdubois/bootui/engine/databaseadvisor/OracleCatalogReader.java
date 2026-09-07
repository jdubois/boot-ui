package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The Oracle-only, read-only {@code ALL_*}/{@code SYS_CONTEXT} augmentation the generic JDBC metadata API
 * cannot answer: index status/visibility/automatic-backing and partition usability, constraint
 * status/validation, and sequence (including identity-column-backed) consumption.
 *
 * <p>Every statement reads only {@code ALL_*} dictionary views — never {@code DBA_*} (which needs the
 * {@code SELECT ANY DICTIONARY}/{@code SELECT_CATALOG_ROLE} privilege this advisor must not require), never
 * an application row, and never across a database link — and every one is scoped to the connected session's
 * {@code CURRENT_SCHEMA} with a bound {@code OWNER = ?} parameter, using only JDK JDBC APIs (no
 * {@code oracle.jdbc.*} import anywhere in this class). Every query is allowed to fail: a role without
 * {@code SELECT} on a given {@code ALL_*} view yields a {@link VendorAugmentation.Status#FAILED} augmentation
 * whose reason the matching rule reports as {@code SKIPPED}, never a clean result it did not earn.</p>
 */
final class OracleCatalogReader {

    private static final String INDEX_DETAILS_SQL = """
            select i.owner as schema_name, i.table_name as table_name, i.index_name as index_name,
                   i.index_type as index_type, i.uniqueness as uniqueness, i.status as status,
                   i.visibility as visibility, i.generated as generated, i.partitioned as partitioned,
                   i.table_owner as table_owner
            from all_indexes i
            where i.table_owner = ?
            order by i.table_name, i.index_name
            fetch first ? rows only
            """;

    private static final String INDEX_PARTITION_STATUS_SQL = """
            select i.owner as schema_name, i.table_name as table_name, i.index_name as index_name,
                   p.partition_name as partition_name, 0 as is_subpartition, p.status as status, i.table_owner as table_owner
            from all_ind_partitions p
            join all_indexes i on i.owner = p.index_owner and i.index_name = p.index_name
            where i.table_owner = ? and p.status = 'UNUSABLE' and i.index_type <> 'DOMAIN'
            union all
            select i.owner as schema_name, i.table_name as table_name, i.index_name as index_name,
                   sp.subpartition_name as partition_name, 1 as is_subpartition, sp.status as status, i.table_owner as table_owner
            from all_ind_subpartitions sp
            join all_indexes i on i.owner = sp.index_owner and i.index_name = sp.index_name
            where i.table_owner = ? and sp.status = 'UNUSABLE' and i.index_type <> 'DOMAIN'
            order by table_name, index_name, partition_name
            fetch first ? rows only
            """;

    private static final String CONSTRAINTS_SQL = """
            select c.owner as schema_name, c.table_name as table_name, c.constraint_name as constraint_name,
                   c.constraint_type as constraint_type, c.status as status, c.validated as validated,
                   c.generated as generated, c.search_condition_vc as search_condition,
                   c.index_owner as index_owner, c.index_name as index_name,
                   c.deferrable as deferrable, c.deferred as deferred, c.rely as rely
            from all_constraints c
            where c.owner = ?
              and c.constraint_type in ('P', 'U', 'R', 'C')
              and c.table_name not like 'BIN$%'
            order by c.table_name, c.constraint_name
            fetch first ? rows only
            """;

    private static final String SEQUENCES_SQL = """
            select s.sequence_owner as schema_name, s.sequence_name as sequence_name,
                   s.min_value as min_value, s.max_value as max_value, s.increment_by as increment_by,
                   s.cycle_flag as cycle_flag, s.last_number as last_number,
                   s.session_flag as session_flag, s.scale_flag as scale_flag, s.sharded_flag as sharded_flag,
                   s.cache_size as cache_size
            from all_sequences s
            where s.sequence_owner = ?
            order by s.sequence_name
            fetch first ? rows only
            """;

    private static final String IDENTITY_COLUMNS_SQL = """
            select ic.owner as schema_name, ic.table_name as table_name, ic.column_name as column_name,
                   ic.sequence_name as sequence_name, c.data_type as data_type,
                   c.data_precision as data_precision, c.data_scale as data_scale
            from all_tab_identity_cols ic
            join all_tab_columns c on c.owner = ic.owner and c.table_name = ic.table_name
                                  and c.column_name = ic.column_name
            where ic.owner = ?
            order by ic.table_name, ic.column_name
            fetch first ? rows only
            """;

    private OracleCatalogReader() {}

    static void read(
            Connection connection,
            String currentSchema,
            DatabaseVersion version,
            DialectCapabilities capabilities,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            VendorFindings.Builder findings) {
        if (!capabilities.oracleCatalog()) {
            String reason = "Oracle catalog augmentation requires Oracle Database 19c or later (server reports "
                    + version.describe() + ").";
            findings.add(VendorAugmentation.notApplicable(VendorFindingKinds.ORACLE_INDEX_DETAILS, reason));
            findings.add(VendorAugmentation.notApplicable(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, reason));
            findings.add(VendorAugmentation.notApplicable(VendorFindingKinds.ORACLE_CONSTRAINTS, reason));
            findings.add(VendorAugmentation.notApplicable(VendorFindingKinds.ORACLE_SEQUENCES, reason));
            findings.add(VendorAugmentation.notApplicable(VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, reason));
            return;
        }
        if (currentSchema == null || currentSchema.isBlank()) {
            String reason = "Oracle's CURRENT_SCHEMA could not be determined, so the catalog augmentation was "
                    + "not scoped to it and was skipped rather than risk scanning an unintended schema.";
            findings.add(VendorAugmentation.failed(VendorFindingKinds.ORACLE_INDEX_DETAILS, reason));
            findings.add(VendorAugmentation.failed(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, reason));
            findings.add(VendorAugmentation.failed(VendorFindingKinds.ORACLE_CONSTRAINTS, reason));
            findings.add(VendorAugmentation.failed(VendorFindingKinds.ORACLE_SEQUENCES, reason));
            findings.add(VendorAugmentation.failed(VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, reason));
            return;
        }
        List<Object> schemaParam = List.of(currentSchema);
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.ORACLE_INDEX_DETAILS,
                INDEX_DETAILS_SQL,
                schemaParam,
                budget,
                limits,
                OracleCatalogReader::readIndexDetail));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS,
                INDEX_PARTITION_STATUS_SQL,
                List.of(currentSchema, currentSchema),
                budget,
                limits,
                OracleCatalogReader::readPartitionStatus));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.ORACLE_CONSTRAINTS,
                CONSTRAINTS_SQL,
                schemaParam,
                budget,
                limits,
                OracleCatalogReader::readConstraint));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.ORACLE_SEQUENCES,
                SEQUENCES_SQL,
                schemaParam,
                budget,
                limits,
                OracleCatalogReader::readSequence));
        findings.add(CatalogQuery.read(
                connection,
                VendorFindingKinds.ORACLE_IDENTITY_COLUMNS,
                IDENTITY_COLUMNS_SQL,
                schemaParam,
                budget,
                limits,
                OracleCatalogReader::readIdentityColumn));
    }

    private static OracleIndexDetail readIndexDetail(ResultSet rs) throws SQLException {
        String uniqueness = rs.getString("uniqueness");
        return new OracleIndexDetail(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                rs.getString("index_type"),
                "UNIQUE".equalsIgnoreCase(uniqueness),
                rs.getString("status"),
                rs.getString("visibility"),
                "Y".equalsIgnoreCase(rs.getString("generated")),
                "YES".equalsIgnoreCase(rs.getString("partitioned")),
                rs.getString("table_owner"),
                "UNIQUE".equalsIgnoreCase(uniqueness) || "NONUNIQUE".equalsIgnoreCase(uniqueness));
    }

    private static OracleIndexPartitionStatus readPartitionStatus(ResultSet rs) throws SQLException {
        return new OracleIndexPartitionStatus(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("index_name"),
                rs.getString("partition_name"),
                rs.getInt("is_subpartition") != 0,
                rs.getString("status"),
                rs.getString("table_owner"));
    }

    private static OracleConstraintDetail readConstraint(ResultSet rs) throws SQLException {
        return new OracleConstraintDetail(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("constraint_name"),
                rs.getString("constraint_type"),
                rs.getString("status"),
                rs.getString("validated"),
                "GENERATED NAME".equalsIgnoreCase(rs.getString("generated")),
                rs.getString("search_condition"),
                rs.getString("index_owner"),
                rs.getString("index_name"),
                rs.getString("deferrable"),
                rs.getString("deferred"),
                rs.getString("rely"));
    }

    private static OracleSequenceUsage readSequence(ResultSet rs) throws SQLException {
        boolean excluded = !"N".equalsIgnoreCase(rs.getString("session_flag"))
                || !"N".equalsIgnoreCase(rs.getString("scale_flag"))
                || !"N".equalsIgnoreCase(rs.getString("sharded_flag"));
        return new OracleSequenceUsage(
                rs.getString("schema_name"),
                rs.getString("sequence_name"),
                bigInteger(rs, "last_number"),
                bigInteger(rs, "max_value"),
                bigInteger(rs, "min_value"),
                bigInteger(rs, "increment_by"),
                "Y".equalsIgnoreCase(rs.getString("cycle_flag")),
                excluded,
                bigInteger(rs, "cache_size"));
    }

    private static OracleIdentityColumn readIdentityColumn(ResultSet rs) throws SQLException {
        return new OracleIdentityColumn(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("sequence_name"),
                rs.getString("data_type"),
                nullableInt(rs, "data_precision"),
                nullableInt(rs, "data_scale"));
    }

    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private static BigInteger bigInteger(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.toBigInteger();
    }
}
