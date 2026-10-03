package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.insights.InsightsStack;

/**
 * What each stack records of an application event's publication ({@code docs/PLAN-v2.md} §5.18).
 *
 * <p>The Spring adapters see a publication because they wrap the application event multicaster, so the runtime model
 * draws a {@code PUBLISHES} edge from the publishing work to the event. Quarkus has no equivalent seam: CDI notifies
 * observers, and only an observer can be intercepted, so BootUI records that an event was <em>observed</em> and never
 * who fired it. Making one up would need BootUI to observe every event in the application, which changes how ArC
 * notifies them, so the publication is reported as unrecorded instead of guessed.</p>
 */
public final class AppEventPublications {

    private static final String QUARKUS_UNRECORDED =
            "Quarkus does not record who publishes an application event, only which observers ran, so a reach that"
                    + " exists only through an event is not counted. Follow the observer method instead.";

    private AppEventPublications() {}

    /**
     * Why this stack's application-event publications are missing from the runtime model, or {@code null} when it
     * records them.
     */
    public static String unrecordedReason(InsightsStack stack) {
        return stack == InsightsStack.QUARKUS ? QUARKUS_UNRECORDED : null;
    }
}
