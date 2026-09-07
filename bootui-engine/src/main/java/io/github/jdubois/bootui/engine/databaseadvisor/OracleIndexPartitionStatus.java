package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One unusable partition or subpartition of a partitioned Oracle index ({@code all_ind_partitions}/
 * {@code all_ind_subpartitions}). A partitioned index's own {@code all_indexes.status} reads {@code N/A}, so
 * this is the only place an unusable partition is visible at all — usually left behind by a partition
 * maintenance operation (a split, exchange, or truncate) that did not rebuild every index partition.
 */
record OracleIndexPartitionStatus(
        String schema,
        String table,
        String index,
        String partitionName,
        boolean subpartition,
        String status,
        String tableOwner) {

    OracleIndexPartitionStatus(
            String schema, String table, String index, String partitionName, boolean subpartition, String status) {
        this(schema, table, index, partitionName, subpartition, status, schema);
    }

    String qualifiedTable() {
        return tableOwner == null || tableOwner.isBlank() ? table : tableOwner + "." + table;
    }

    boolean unusable() {
        return "UNUSABLE".equalsIgnoreCase(status);
    }
}
