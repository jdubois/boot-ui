package io.github.jdubois.bootui.engine.mysql;

import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Cooperative total budget; datasource acquisition remains controlled by the application's pool. */
final class MySqlReadBudget {
    private final LongSupplier ticker;
    private final long started;
    private final long nanos;

    MySqlReadBudget(Duration duration, LongSupplier ticker) {
        this.ticker = ticker;
        this.started = ticker.getAsLong();
        this.nanos = duration.toNanos();
    }

    int selectMillis() throws SQLTimeoutException {
        long remaining = nanos - (ticker.getAsLong() - started);
        if (remaining <= 0) {
            throw new SQLTimeoutException("MySQL read budget exhausted.", "HYT00");
        }
        return (int) Math.max(1, Math.min(5000, remaining / 1_000_000));
    }

    boolean exhausted() {
        return ticker.getAsLong() - started >= nanos;
    }
}
