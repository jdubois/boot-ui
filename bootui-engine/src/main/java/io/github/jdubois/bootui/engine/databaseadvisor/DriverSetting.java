package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One configured, allow-listed driver setting. {@code value} is the text as configured, trimmed and capped; a
 * conflicting setting keeps the URL's value, which a rule must not use.
 *
 * @param source where the value came from; the URL when both the URL and the pool configure it
 * @param state {@link State#CONFLICTING} when the URL and the pool disagree and the driver defines no winner
 */
record DriverSetting(String value, Source source, State state) {

    enum Source {
        URL,
        POOL
    }

    enum State {
        KNOWN,
        CONFLICTING
    }

    boolean conflicting() {
        return state == State.CONFLICTING;
    }

    /** The human label of where this setting was configured, for a finding. */
    String label() {
        return source == Source.URL ? "JDBC URL" : "pool driver properties";
    }
}
