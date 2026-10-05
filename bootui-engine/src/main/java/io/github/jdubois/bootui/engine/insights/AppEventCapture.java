package io.github.jdubois.bootui.engine.insights;

import java.util.function.Supplier;

/**
 * Whether this application's application events can be recorded at all, which the {@code app-event} source of the
 * runtime journal depends on ({@code docs/PLAN-v2.md} §5.18): on Spring, BootUI records them as the context's
 * application event multicaster, and backs off when the application defines its own, as Spring Modulith's event
 * publication registry does. Without it, a check that reads only application events would run over nothing and report
 * no finding, which must never read as a healthy result.
 *
 * @param recorded whether application events can be recorded
 * @param reason why they cannot, when not recorded, otherwise {@code null}
 */
public record AppEventCapture(boolean recorded, String reason) {

    /** Why application events are not recorded when the application defines its own event multicaster. */
    public static final String CUSTOM_MULTICASTER = "This application defines its own application event multicaster,"
            + " such as Spring Modulith's event publication registry, so BootUI does not record application events.";

    /** Why application events are not recorded when the context kept its default multicaster instead of BootUI's. */
    public static final String NOT_INSTALLED = "BootUI's application event multicaster is not installed in this"
            + " application context, as when its auto-configuration is excluded, so BootUI does not record application"
            + " events.";

    /** Application events are recorded. */
    public static AppEventCapture capturing() {
        return new AppEventCapture(true, null);
    }

    /** Application events are not recorded, for {@code reason}. */
    public static AppEventCapture notRecorded(String reason) {
        return new AppEventCapture(false, reason == null || reason.isBlank() ? CUSTOM_MULTICASTER : reason);
    }

    /**
     * What {@code supplier} answers, never throwing: without a supplier, or with one answering {@code null}, application
     * events are assumed recorded, and a supplier that fails reads as not recorded, naming the failure.
     */
    public static AppEventCapture read(Supplier<AppEventCapture> supplier) {
        if (supplier == null) {
            return capturing();
        }
        try {
            AppEventCapture capture = supplier.get();
            return capture == null ? capturing() : capture;
        } catch (RuntimeException ex) {
            return notRecorded("Whether this application's application events are recorded could not be read ("
                    + ex.getClass().getSimpleName() + "), so none is counted.");
        }
    }
}
