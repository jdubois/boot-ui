package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code anonymous-success-on-restricted-route} ({@code docs/PLAN-v2.md} §5.9): successful responses to requests an
 * authorization decision proved anonymous, on a route whose rules this run saw restrict: they denied another anonymous
 * caller, or required an authority. Worded as a successful anonymous response, not proof that the rule is wrong.
 */
public final class AnonymousSuccessOnRestrictedRoute implements Observation {

    public static final String KIND = "anonymous-success-on-restricted-route";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Anonymous success on a restricted route";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.HTTP, JournalSource.AUTHORIZATION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<List<String>> successes = new ArrayList<>();
            List<List<String>> restrictions = new ArrayList<>();
            long anonymous = 0;
            for (ProjectedRequest request : route.getValue()) {
                List<AuthorizationPayload> decisions = AnonymousAccess.decisions(request);
                if (AnonymousAccess.provenAnonymous(decisions)) {
                    anonymous++;
                    if (AnonymousAccess.succeeded(request, decisions)) {
                        successes.add(row(request, "granted", decisions.get(0).rule()));
                    }
                }
                for (AuthorizationPayload decision : decisions) {
                    boolean deniedAnonymous = !decision.granted()
                            && (AuthorizationPayload.ANONYMOUS.equals(decision.authentication())
                                    || AuthorizationPayload.NONE.equals(decision.authentication()));
                    boolean requiresAuthority =
                            decision.rule() != null && decision.rule().startsWith("hasAnyAuthority");
                    if (deniedAnonymous || requiresAuthority) {
                        restrictions.add(row(request, decision.granted() ? "granted" : "denied", decision.rule()));
                        break;
                    }
                }
            }
            eligible += anonymous;
            if (!successes.isEmpty() && !restrictions.isEmpty()) {
                findings.add(finding(route.getKey(), successes, restrictions, anonymous));
            }
        }
        return new Evaluation(eligible, findings);
    }

    private static List<String> row(ProjectedRequest request, String decision, String rule) {
        return List.of(request.requestId(), String.valueOf(request.status()), decision, rule == null ? "" : rule);
    }

    private static Finding finding(
            String route, List<List<String>> successes, List<List<String>> restrictions, long anonymous) {
        List<List<String>> rows = new ArrayList<>(successes);
        rows.addAll(restrictions);
        return new Finding(
                route,
                route,
                true,
                "`" + route + "` answered " + InsightText.counted(successes.size(), "anonymous request")
                        + " with 2xx, while its rules restricted "
                        + InsightText.counted(restrictions.size(), "other request")
                        + ": a successful anonymous response, not proof that the rule is wrong.",
                anonymous,
                successes.size(),
                List.of(
                        "Compare the paths of the granted and the restricted requests: a matcher that covers only some"
                                + " of them, or a more permissive rule declared first, lets anonymous callers through.",
                        AnonymousAccess.VERIFY),
                successes.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Status", "Decision", "Rule"),
                rows,
                List.of("A route counts as restricted from this run's decisions on it, not from the declared rules: a"
                        + " route whose anonymous callers were never denied is not reported."));
    }
}
