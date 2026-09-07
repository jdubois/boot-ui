package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;

/** LAST_NUMBER is a disk/cache reservation frontier, not a count of used or committed identifiers. */
record OracleSequenceUsage(
        String schema,
        String sequence,
        BigInteger lastNumber,
        BigInteger maxValue,
        BigInteger minValue,
        BigInteger incrementBy,
        boolean cycle,
        boolean excluded,
        BigInteger cacheSize) {

    OracleSequenceUsage(
            String schema,
            String sequence,
            BigInteger lastNumber,
            BigInteger maxValue,
            BigInteger minValue,
            BigInteger incrementBy,
            boolean cycle,
            boolean excluded) {
        this(schema, sequence, lastNumber, maxValue, minValue, incrementBy, cycle, excluded, null);
    }

    String qualifiedName() {
        return schema == null || schema.isBlank() ? sequence : schema + "." + sequence;
    }

    BigInteger effectiveBound(OracleIdentityColumn identity) {
        if (incrementBy == null || minValue == null || maxValue == null) {
            return null;
        }
        BigInteger capacity = identity == null ? null : identity.capacity();
        return incrementBy.signum() < 0
                ? capacity == null ? minValue : minValue.max(capacity.negate())
                : capacity == null ? maxValue : maxValue.min(capacity);
    }

    boolean limitedByColumn(OracleIdentityColumn identity) {
        BigInteger bound = effectiveBound(identity);
        return bound != null && !bound.equals(incrementBy.signum() < 0 ? minValue : maxValue);
    }

    int percentUsed() {
        return percentUsed(null);
    }

    int percentUsed(OracleIdentityColumn identity) {
        BigInteger origin = incrementBy == null ? null : incrementBy.signum() < 0 ? maxValue : minValue;
        BigInteger capacity = identity == null ? null : identity.capacity();
        if (origin != null && capacity != null) {
            origin = incrementBy.signum() < 0 ? origin.min(capacity) : origin.max(capacity.negate());
        }
        return VendorRuleSupport.percentUsed(lastNumber, origin, effectiveBound(identity), incrementBy);
    }
}
