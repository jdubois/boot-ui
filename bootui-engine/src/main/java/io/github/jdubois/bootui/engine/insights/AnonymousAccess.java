package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import java.util.List;

/**
 * How a request's caller was authenticated, from its {@code authorization} decisions ({@code docs/PLAN-v2.md} §5.9):
 * its request's own decision, else its first method's. A request no rule checked has no decision, so it is never
 * taken for anonymous.
 */
final class AnonymousAccess {

    private AnonymousAccess() {}

    /** The request's decisions, its own first. */
    static List<AuthorizationPayload> decisions(ProjectedRequest request) {
        return request.children(JournalSource.AUTHORIZATION).stream()
                .map(RuntimeEvent::payload)
                .filter(AuthorizationPayload.class::isInstance)
                .map(AuthorizationPayload.class::cast)
                .sorted((a, b) -> Boolean.compare(b.request(), a.request()))
                .toList();
    }

    /** Whether a decision proved the request's caller anonymous. */
    static boolean provenAnonymous(List<AuthorizationPayload> decisions) {
        return !decisions.isEmpty()
                && AuthorizationPayload.ANONYMOUS.equals(decisions.get(0).authentication());
    }

    /** Whether every decision granted access and the response was a success. */
    static boolean succeeded(ProjectedRequest request, List<AuthorizationPayload> decisions) {
        return request.status() >= 200
                && request.status() < 300
                && decisions.stream().allMatch(AuthorizationPayload::granted);
    }

    static final String VERIFY =
            "Do not add authorization from this row alone: a public route or write, such as a catalog, a sign-up, or a"
                    + " contact form, is often intended.";
}
