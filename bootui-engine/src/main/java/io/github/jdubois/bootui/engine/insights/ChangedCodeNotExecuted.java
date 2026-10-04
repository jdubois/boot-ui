package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.engine.inventory.CodeChanges;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedClass;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedCode;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code changed-code-not-executed} ({@code docs/PLAN-v2.md} §5.17): per class, the methods changed or added since the
 * previous run that the agent tracked and that no request, job, or startup work executed in this run. Worded "your
 * change has not run yet", with the routes known to have executed the class's other methods, which are the likeliest to
 * reach it. Reads Code Inventory, not the journal: it needs the BootUI agent's {@code inventory} sensor and a previous
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

    private volatile Supplier<ChangedCode> changes = () -> null;

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
        long tracked = 0;
        long untracked = 0;
        for (ChangedClass changed : code.classes()) {
            List<List<String>> rows = new ArrayList<>();
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
                } else {
                    untracked++;
                }
            }
            tracked += classTracked;
            if (!rows.isEmpty()) {
                findings.add(finding(changed, classTracked, rows, code.note()));
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

    private static Finding finding(ChangedClass changed, long eligible, List<List<String>> rows, String note) {
        String simple = InsightText.simpleName(changed.className());
        String sentence = "Your change has not run yet: " + InsightText.counted(rows.size(), "changed method") + " of `"
                + simple + "` " + (rows.size() == 1 ? "was" : "were") + " not executed in this run.";
        List<String> whatToCheck = new ArrayList<>();
        if (changed.routes().isEmpty()) {
            whatToCheck.add("Run the test or send the request that should reach it, then read Code Inventory again.");
        } else {
            whatToCheck.add("Send a request to a route that ran this class's other methods, such as `"
                    + changed.routes().get(0) + "`, or run the test that reaches it.");
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
}
