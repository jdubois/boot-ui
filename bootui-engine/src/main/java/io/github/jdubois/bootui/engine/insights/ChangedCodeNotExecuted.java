package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.inventory.CodeChanges;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedClass;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedCode;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code changed-code-not-executed} ({@code docs/PLAN-v2.md} §5.17): per class, the methods changed or added since the
 * previous run that the agent tracked and that no request, job, or startup work executed in this run. Worded "your
 * change has not run yet", with the declared routes mapped to the methods themselves, by their own HTTP method and path,
 * and never another route of the class, whose method may differ (M4-20's adjudication follow-up 4). Reads Code Inventory, not the journal: it needs the BootUI agent's {@code inventory} sensor and a previous
 * run of the application kept in this JVM, and is not applicable without either. A changed method that executed is
 * never reported, and neither is one the agent could not track.
 */
public final class ChangedCodeNotExecuted implements Observation {

    public static final String KIND = "changed-code-not-executed";

    static final String REQUIRES_AGENT = "This observation requires the BootUI agent's inventory sensor, which records"
            + " which application methods ran: start the application with -javaagent:bootui-agent.jar (see the Java"
            + " Agent panel).";

    static final String NO_PREVIOUS_RUN = "This observation needs a previous run of this application kept in this JVM"
            + " to compare with: it applies after the next DevTools restart or Quarkus live reload.";

    static final String SCAN_RUNNING = "Code Inventory's scan of the application's class files is still running: this"
            + " observation applies once it has compared this run with the previous one.";

    static final String SCAN_FAILED = "Code Inventory's scan of the application's class files failed, so no change"
            + " since the previous run can be compared.";

    /** The most mapped routes its check names. */
    static final int MAX_ROUTES = 3;

    private volatile Supplier<ChangedCode> changes = () -> null;
    private volatile Supplier<List<MappingDto>> mappings = () -> null;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Changed code not executed";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of();
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of();
    }

    /** Installs what Code Inventory reports about this run's changes; without it, this does not apply. */
    void setChanges(Supplier<ChangedCode> changes) {
        this.changes = changes == null ? () -> null : changes;
    }

    /** Installs the application's declared routes, whose handlers name the methods they map to; without them, none. */
    void setMappings(Supplier<List<MappingDto>> mappings) {
        this.mappings = mappings == null ? () -> null : mappings;
    }

    /** The declared routes, or {@code null} when they cannot be read. */
    private List<MappingDto> declared() {
        try {
            return mappings.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** What Code Inventory reports now, or {@code null}. */
    ChangedCode current() {
        try {
            return changes.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        ChangedCode code = current();
        if (code == null) {
            return REQUIRES_AGENT;
        }
        if (code.unavailableReason() != null) {
            String reason = code.unavailableReason();
            // The agent's reasons read "Requires the BootUI agent's inventory sensor: …".
            return reason.startsWith("Requires ") ? "This observation r" + reason.substring(1) : reason;
        }
        if (code.scanFailed()) {
            // Never compared: a failed scan would otherwise read as nothing changed.
            return code.scanReason() == null ? SCAN_FAILED : SCAN_FAILED + " " + code.scanReason();
        }
        if (code.scanInProgress() && code.classes().isEmpty()) {
            return SCAN_RUNNING;
        }
        if (!code.previousRun()) {
            return code.note() == null ? NO_PREVIOUS_RUN : NO_PREVIOUS_RUN + " " + code.note();
        }
        return null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        ChangedCode code = current();
        if (code == null) {
            return new Evaluation(0, List.of());
        }
        List<Finding> findings = new ArrayList<>();
        List<MappingDto> declared = declared();
        long tracked = 0;
        long untracked = 0;
        for (ChangedClass changed : code.classes()) {
            List<List<String>> rows = new ArrayList<>();
            Set<String> mapped = new LinkedHashSet<>();
            boolean maybeInherited = false;
            long classTracked = 0;
            for (CodeInventoryMethodDto method : changed.methods()) {
                String status = method.status();
                if (CodeInventoryService.EXECUTED.equals(status)) {
                    classTracked++;
                } else if (CodeInventoryService.NEVER_EXECUTED.equals(status)) {
                    classTracked++;
                    rows.add(List.of(
                            InsightText.simpleName(method.className()) + "." + method.name() + method.descriptor(),
                            CodeChanges.ADDED.equals(method.change()) ? "added" : "changed",
                            "not executed in this run"));
                    mapped.addAll(routesMappedTo(declared, method));
                    maybeInherited |= mayBeInherited(declared, method);
                } else {
                    untracked++;
                }
            }
            tracked += classTracked;
            if (!rows.isEmpty()) {
                findings.add(finding(
                        changed,
                        classTracked,
                        rows,
                        List.copyOf(mapped),
                        declared != null && !maybeInherited,
                        code.note()));
            }
        }
        return new Evaluation(
                tracked,
                findings,
                untracked == 0
                        ? null
                        : InsightText.counted(untracked, "changed method")
                                + " the agent could not track are left out (see Code Inventory for why).");
    }

    private static Finding finding(
            ChangedClass changed,
            long eligible,
            List<List<String>> rows,
            List<String> mapped,
            boolean noneMapped,
            String note) {
        String simple = InsightText.simpleName(changed.className());
        String sentence = "Your change has not run yet: " + InsightText.counted(rows.size(), "changed method") + " of `"
                + simple + "` " + (rows.size() == 1 ? "was" : "were") + " not executed in this run.";
        List<String> whatToCheck = new ArrayList<>();
        if (!mapped.isEmpty()) {
            List<String> named = mapped.subList(0, Math.min(MAX_ROUTES, mapped.size()));
            whatToCheck.add("Send " + (named.size() == 1 ? "a request to the route" : "requests to the routes")
                    + " mapped to " + (rows.size() == 1 ? "it" : "them") + ", `" + String.join("`, `", named) + "`"
                    + (mapped.size() > named.size() ? ", and others" : "") + ", or run the test that reaches "
                    + (rows.size() == 1 ? "it" : "them") + ", then read Code Inventory again.");
        } else {
            whatToCheck.add((noneMapped ? "No declared route is mapped to " : "No route is known to be mapped to ")
                    + (rows.size() == 1 ? "it" : "them")
                    + ": run the test, or send the request, whose code path should call "
                    + (rows.size() == 1 ? "it" : "them") + ", then read Code Inventory again.");
        }
        whatToCheck.add("If it still does not run, check that the code path that should call it is wired: the right"
                + " bean, route, or condition.");
        List<String> limitations = new ArrayList<>();
        limitations.add(CodeInventoryService.NOT_SEEN_BEFORE_CLAIM);
        if (!changed.routes().isEmpty()) {
            limitations.add("Routes that executed this class's methods in this run: "
                    + String.join(", ", changed.routes().stream().limit(5).toList())
                    + (changed.routes().size() > 5 ? ", and others." : "."));
        }
        if (note != null) {
            limitations.add(note);
        }
        return new Finding(
                changed.className(),
                changed.className(),
                true,
                sentence,
                eligible,
                rows.size(),
                whatToCheck,
                List.of(),
                List.of("Method", "Change", "This run"),
                rows,
                limitations);
    }

    /**
     * The routes whose declared handler is {@code method}, as {@code METHOD /path} labels: a handler written
     * {@code com.example.Resource#name(...)}, as Spring and Quarkus describe their mappings, matched by class and method
     * name, so an overload is matched by name. A route without an HTTP method is labelled {@code ANY}.
     */
    static List<String> routesMappedTo(List<MappingDto> declared, CodeInventoryMethodDto method) {
        if (declared == null || method.className() == null || method.name() == null) {
            return List.of();
        }
        String className = method.className().replace('$', '.');
        Set<String> routes = new LinkedHashSet<>();
        for (MappingDto mapping : declared) {
            String[] handler = handler(mapping);
            if (handler == null || !handler[0].equals(className) || !handler[1].equals(method.name())) {
                continue;
            }
            String verb = mapping.method() == null || mapping.method().isBlank()
                    ? "ANY"
                    : mapping.method().trim().toUpperCase(Locale.ROOT);
            routes.add(RouteLabel.idOf(verb, RouteTemplateResolver.canonical(mapping.pattern())));
        }
        return List.copyOf(routes);
    }

    /**
     * Whether a declared route may still reach {@code method} through a class this engine cannot relate to it: Spring and
     * Quarkus describe an inherited handler by the concrete controller or resource class, and the engine does not know
     * the class hierarchy, so a handler of the same method name in another class may be {@code method} inherited. Only
     * when none exists is no declared route mapped to it; a route whose handler names no method, such as a functional
     * route, maps to no method.
     */
    static boolean mayBeInherited(List<MappingDto> declared, CodeInventoryMethodDto method) {
        if (declared == null || method.className() == null || method.name() == null) {
            return true;
        }
        String className = method.className().replace('$', '.');
        for (MappingDto mapping : declared) {
            String[] handler = handler(mapping);
            if (handler != null && !handler[0].equals(className) && handler[1].equals(method.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A mapping's handler class and method name, from {@code com.example.Resource#name(...)}, or {@code null} when it
     * names no method, as a functional route's does.
     */
    private static String[] handler(MappingDto mapping) {
        if (mapping == null || mapping.handler() == null || mapping.pattern() == null) {
            return null;
        }
        String handler = mapping.handler().trim();
        int hash = handler.indexOf('#');
        if (hash < 0) {
            return null;
        }
        int paren = handler.indexOf('(', hash);
        return new String[] {
            handler.substring(0, hash).replace('$', '.'),
            handler.substring(hash + 1, paren < 0 ? handler.length() : paren)
        };
    }
}
