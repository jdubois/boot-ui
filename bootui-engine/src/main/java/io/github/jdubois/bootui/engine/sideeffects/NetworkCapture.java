package io.github.jdubois.bootui.engine.sideeffects;

/**
 * What tells Side Effects whether a panel shows a network observation's work ({@code docs/PLAN-v2.md} §5.16, M5-5b):
 * the REST client calls the runtime journal recorded, by host, port, owner, and time. Implementations are called under Side Effects' lock, from its drain thread or a
 * read; {@link #refresh()} first catches up with what was recorded since.
 */
public interface NetworkCapture {

    /** Nothing captured: every observation that waits for a REST client call is not captured. */
    NetworkCapture NONE = new NetworkCapture() {
        @Override
        public void refresh() {}

        @Override
        public boolean restClient(
                String host, int port, String requestId, String executionId, long firstMillis, long lastMillis) {
            return false;
        }
    };

    /** Catches up with the events recorded since the last call. */
    void refresh();

    /**
     * Whether a REST client call to {@code host} (case-insensitive) and {@code port} (or the host alone when the call
     * named no port and {@code port} is 80 or 443), or through a configured proxy, belongs to the same request or
     * execution as a connect or datagram, or, for one that names neither, overlapped its time, a second either side.
     */
    boolean restClient(String host, int port, String requestId, String executionId, long firstMillis, long lastMillis);
}
