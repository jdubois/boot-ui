package org.acme.nativeconsole.orm;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionEventListener;

@RegisterForReflection(methods = false, fields = false)
public class HostSessionListener implements SessionEventListener {

    private static final AtomicLong STATEMENTS = new AtomicLong();

    @Override
    public void jdbcExecuteStatementEnd() {
        STATEMENTS.incrementAndGet();
    }

    static long statements() {
        return STATEMENTS.get();
    }
}
