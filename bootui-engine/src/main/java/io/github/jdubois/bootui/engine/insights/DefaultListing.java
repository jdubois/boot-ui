package io.github.jdubois.bootui.engine.insights;

/**
 * Which findings the default list shows ({@code docs/PLAN-v2.md} M4-19, M4-20): the rules that apply to a whole kind. A
 * kind's own rules, such as a route's prominence, are decided where it is evaluated. A finding left out stays in the
 * report with its reason, so <b>Show all routes</b>, a search, its id, and an agent query naming its kind or route, or
 * {@code all}, still reach it.
 */
final class DefaultListing {

    /** Why a garbage-collection or heap row is left out: it is reached from the Memory panel (revisiting D18). */
    static final String MEMORY =
            "Garbage collection and heap rows are reached from the Memory panel rather than listed" + " by default.";

    private DefaultListing() {}

    /**
     * {@code finding} of {@code kind}, left out of the default list when its kind's external validation says so
     * ({@link ExternalValidation}). Its unlisted reason, which never mentions the validation, replaces the kind's own,
     * since no row of the kind is listed.
     */
    static Finding apply(String kind, Finding finding) {
        ExternalValidation.Entry validation = ExternalValidation.of(kind);
        return validation.outcome().listed() ? finding : finding.unlistedWhole(validation.unlistedReason());
    }
}
