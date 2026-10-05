package io.github.jdubois.bootui.autoconfigure.idle;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Marks the BootUI console active on every request to a BootUI route, feeding
 * {@link ConsoleActivityTracker}.
 *
 * <p>It is registered after {@link io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter},
 * so only trusted local requests that survive the safety checks count as activity. Loading the SPA
 * shell, the UI's periodic API polling, and opening an SSE stream all flow through here, which keeps
 * the console "active" for as long as a developer has it open and lets the tracker reclaim memory only
 * once it is genuinely unused.</p>
 */
public final class ConsoleActivityFilter extends AbstractBootUiFilter {

    private final ConsoleActivityTracker tracker;

    public ConsoleActivityFilter(BootUiProperties properties, ConsoleActivityTracker tracker) {
        super(properties);
        this.tracker = tracker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        tracker.markActive();
        chain.doFilter(request, response);
    }
}
