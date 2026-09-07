package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;

/** Sequence definition and nullable cached frontier, independently of whether consumption is visible. */
record PostgresSequenceUsage(
        String schema,
        String sequence,
        BigInteger lastValue,
        BigInteger sequenceMax,
        BigInteger columnCapacity,
        boolean cycle,
        String ownerSchema,
        String ownerTable,
        String ownerColumn,
        String ownerType,
        Long incrementBy,
        BigInteger sequenceMin,
        BigInteger startValue,
        BigInteger columnMinimum,
        BigInteger cacheSize) {

    PostgresSequenceUsage(
            String schema,
            String sequence,
            BigInteger lastValue,
            BigInteger sequenceMax,
            BigInteger columnCapacity,
            boolean cycle,
            String ownerSchema,
            String ownerTable,
            String ownerColumn,
            String ownerType,
            Long incrementBy) {
        this(
                schema,
                sequence,
                lastValue,
                sequenceMax,
                columnCapacity,
                cycle,
                ownerSchema,
                ownerTable,
                ownerColumn,
                ownerType,
                incrementBy,
                null,
                null,
                null,
                null);
    }

    String qualifiedName() {
        return schema == null || schema.isBlank() ? sequence : schema + "." + sequence;
    }

    BigInteger effectiveMax() {
        return columnCapacity == null
                ? sequenceMax
                : sequenceMax == null ? columnCapacity : sequenceMax.min(columnCapacity);
    }

    BigInteger effectiveMin() {
        return columnMinimum == null
                ? sequenceMin
                : sequenceMin == null ? columnMinimum : sequenceMin.max(columnMinimum);
    }

    BigInteger effectiveBound() {
        return incrementBy == null ? null : incrementBy < 0 ? effectiveMin() : effectiveMax();
    }

    boolean limitedByColumn() {
        return incrementBy != null
                && (incrementBy < 0
                        ? columnMinimum != null && sequenceMin != null && columnMinimum.compareTo(sequenceMin) > 0
                        : columnCapacity != null && sequenceMax != null && columnCapacity.compareTo(sequenceMax) < 0);
    }

    String describeOwner() {
        if (ownerTable == null || ownerColumn == null) {
            return "no owning column";
        }
        String table = ownerSchema == null || ownerSchema.isBlank() ? ownerTable : ownerSchema + "." + ownerTable;
        return table + "." + ownerColumn + (ownerType == null ? "" : " (" + ownerType + ")");
    }

    int percentUsed() {
        return VendorRuleSupport.percentUsed(
                lastValue, startValue, effectiveBound(), incrementBy == null ? null : BigInteger.valueOf(incrementBy));
    }
}
