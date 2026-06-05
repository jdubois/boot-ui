package io.github.jdubois.bootui.autoconfigure.copilotfix;

/**
 * Sink for sanitized progress events emitted while a {@link CopilotFixAgent} runs.
 *
 * <p>Implementations append the event to the run's history and fan it out to any live SSE
 * subscribers. Messages must already be sanitized: no secrets, raw tool arguments or command
 * output.
 */
interface CopilotFixListener {

    /**
     * Records a progress event.
     *
     * @param type event category: {@code status}, {@code log}, {@code tool}, {@code diff},
     *     {@code error} or {@code done}
     * @param message human-readable, sanitized message
     */
    void onEvent(String type, String message);
}
