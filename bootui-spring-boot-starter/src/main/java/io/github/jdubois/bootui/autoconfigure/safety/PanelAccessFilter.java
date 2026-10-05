package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.journal.ControlMarkers;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.panel.BootUiGlobalWritePolicy;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Applies per-panel enabled and read-only settings to BootUI API routes.
 */
public class PanelAccessFilter extends AbstractBootUiFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private Supplier<RuntimeJournal> journal = () -> null;

    public PanelAccessFilter(BootUiProperties properties) {
        super(properties);
    }

    /** The journal a successful action is marked in ({@code docs/PLAN-v2.md} §5.18, M4-7). */
    public void setJournal(Supplier<RuntimeJournal> journal) {
        this.journal = journal == null ? () -> null : journal;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiApiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiRelativePath = apiRelativePath(request);
        String method = request.getMethod();
        if (!SAFE_METHODS.contains(method) && properties.isReadOnly()) {
            var globalSubject = BootUiGlobalWritePolicy.subjectFor(apiRelativePath);
            if (globalSubject.isPresent()) {
                writeBlockedResponse(
                        response,
                        "BootUI panel access denied",
                        globalSubject.get(),
                        properties.panelReadOnlyReason(globalSubject.get()));
                return;
            }
        }

        Panel panel = BootUiPanels.byApiPath(apiRelativePath).orElse(null);
        if (panel == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!properties.isPanelEnabled(panel.id())) {
            writeBlockedResponse(
                    response, "BootUI panel access denied", panel.id(), properties.panelDisabledReason(panel.id()));
            return;
        }

        if (panel.actionCapable() && !SAFE_METHODS.contains(method) && properties.isPanelReadOnly(panel.id())) {
            writeBlockedResponse(
                    response, "BootUI panel access denied", panel.id(), properties.panelReadOnlyReason(panel.id()));
            return;
        }

        chain.doFilter(request, response);
        if (panel.actionCapable() && !SAFE_METHODS.contains(method) && response.getStatus() < 400) {
            ControlMarkers.action(journal.get(), panel.id(), method, apiRelativePath);
        }
    }

    private String apiRelativePath(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        String apiPath = properties.getApiPath();
        if (path.equals(apiPath)) {
            return "/";
        }
        if (!path.startsWith(apiPath + "/")) {
            return null;
        }
        return path.substring(apiPath.length());
    }

    protected void writeBlockedResponse(HttpServletResponse response, String error, String panel, String reason)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"" + escape(error) + "\",\"panel\":\"" + escape(panel) + "\",\"reason\":\""
                        + escape(reason) + "\"}");
    }
}
