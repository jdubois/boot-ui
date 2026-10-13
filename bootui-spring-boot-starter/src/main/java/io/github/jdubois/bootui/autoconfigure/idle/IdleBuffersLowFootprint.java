package io.github.jdubois.bootui.autoconfigure.idle;

import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Lets low-footprint mode pause the Spring-side {@link IdleReclaimable} buffers (request and security correlation
 * windows, plus the SQL and trace capture bridges) the framework-neutral engine cannot see. It reuses their idle
 * suspend and resume, which are idempotent, so a buffer the engine also pauses directly is safe to pause twice.
 */
public final class IdleBuffersLowFootprint implements LowFootprintSuspendable {

    private final ObjectProvider<IdleReclaimable> buffers;

    public IdleBuffersLowFootprint(ObjectProvider<IdleReclaimable> buffers) {
        this.buffers = buffers;
    }

    @Override
    public String suspendId() {
        return "idle-buffers";
    }

    @Override
    public String suspendLabel() {
        return "Correlation and idle-reclaimable buffers";
    }

    @Override
    public void suspendForLowFootprint() {
        RuntimeException failure = null;
        for (IdleReclaimable buffer : current()) {
            try {
                buffer.suspendForIdle();
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void resumeFromLowFootprint() {
        RuntimeException failure = null;
        for (IdleReclaimable buffer : current()) {
            try {
                buffer.resumeFromIdle();
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private List<IdleReclaimable> current() {
        return buffers.orderedStream().toList();
    }
}
