package io.github.jdubois.bootui.engine.transactions;

/** Observed transaction boundaries, not the propagation declared on an application method. */
public enum TransactionBoundary {
    NEW,
    REQUIRES_NEW,
    NESTED,
    UNKNOWN;

    public static TransactionBoundary observed(boolean known, boolean nested, boolean savepoint) {
        return !known ? UNKNOWN : savepoint ? NESTED : nested ? REQUIRES_NEW : NEW;
    }

    public boolean independent() {
        return this == NEW || this == REQUIRES_NEW;
    }

    /** The journal historically names an unparented physical boundary REQUIRED, not NEW. */
    public String journalPropagation() {
        return this == NEW ? "REQUIRED" : name();
    }
}
