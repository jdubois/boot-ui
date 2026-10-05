package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link NetworkCapture} over the runtime journal ({@code docs/PLAN-v2.md} §5.16, M5-5b): an index of the REST client
 * calls the journal recorded, by host, at most {@value #MAX_CALLS} of them, the oldest dropped first. It reads only the entries recorded since its last refresh, and
 * starts over when the journal is cleared. The proxies the JVM is configured with ({@code http.proxyHost}, {@code
 * https.proxyHost}, {@code socksProxyHost} and their ports) are read when it is created: a connect to one of them is
 * captured when any REST client call matches it by owner or time.
 */
public final class JournalNetworkCapture implements NetworkCapture {

    /** The REST client calls indexed at most. */
    static final int MAX_CALLS = 4_096;

    /** How far, either side, a call's time window reaches to match an unowned connect. */
    static final long SLACK_MILLIS = 1_000L;

    private final RuntimeJournal journal;
    private final Set<String> proxies;
    private final Map<String, List<Call>> byHost = new HashMap<>();
    private final ArrayDeque<Call> order = new ArrayDeque<>();
    private long lastSequence = Long.MIN_VALUE;
    private long clears = Long.MIN_VALUE;
    private String runId;

    /** One REST client call: its host, port ({@code -1} when its authority named none), owner, and time window. */
    record Call(String host, int port, String requestId, String executionId, long startMillis, long endMillis) {}

    /**
     * @param journal the runtime journal, or {@code null}, when nothing is ever captured
     * @param properties reads a system property, such as {@code System::getProperty}
     */
    public JournalNetworkCapture(RuntimeJournal journal, Function<String, String> properties) {
        this.journal = journal;
        this.proxies = proxies(properties == null ? key -> null : properties);
    }

    /** Over {@code journal}, with the JVM's proxy settings. */
    public static JournalNetworkCapture of(RuntimeJournal journal) {
        return new JournalNetworkCapture(journal, System::getProperty);
    }

    private static Set<String> proxies(Function<String, String> properties) {
        Set<String> found = new HashSet<>();
        addProxy(found, properties.apply("http.proxyHost"), properties.apply("http.proxyPort"), 80);
        addProxy(found, properties.apply("https.proxyHost"), properties.apply("https.proxyPort"), 443);
        addProxy(found, properties.apply("socksProxyHost"), properties.apply("socksProxyPort"), 1080);
        return Set.copyOf(found);
    }

    private static void addProxy(Set<String> found, String host, String port, int defaultPort) {
        if (host == null || host.isBlank()) {
            return;
        }
        int number = defaultPort;
        if (port != null && !port.isBlank()) {
            try {
                number = Integer.parseInt(port.trim());
            } catch (NumberFormatException ex) {
                // The JVM falls back to the default port too.
            }
        }
        found.add(host.trim().toLowerCase(Locale.ROOT) + ":" + number);
    }

    @Override
    public synchronized void refresh() {
        if (journal == null) {
            return;
        }
        JournalStatus status = journal.status();
        if (status.clears() != clears || !status.runId().equals(runId)) {
            clears = status.clears();
            runId = status.runId();
            lastSequence = Long.MIN_VALUE;
            byHost.clear();
            order.clear();
        }
        List<JournalEntry> entries =
                lastSequence == Long.MIN_VALUE ? journal.entries() : journal.entriesAfter(lastSequence);
        for (JournalEntry entry : entries) {
            if (entry.sequence() <= lastSequence) {
                continue;
            }
            lastSequence = entry.sequence();
            learn(entry.event());
        }
    }

    /** Indexes one event; public for tests that build the index without a journal. */
    synchronized void learn(RuntimeEvent event) {
        Object payload = event.payload();
        if (payload instanceof RestClientPayload call && call.authority() != null) {
            String[] hostPort = hostPort(call.authority());
            if (hostPort == null) {
                return;
            }
            long start = event.epochMillis();
            long end = start + Math.max(0L, event.durationNanos()) / 1_000_000L;
            Call indexed = new Call(
                    hostPort[0],
                    hostPort[1] == null ? -1 : Integer.parseInt(hostPort[1]),
                    event.requestId(),
                    event.executionId(),
                    start,
                    end);
            byHost.computeIfAbsent(indexed.host(), host -> new ArrayList<>()).add(indexed);
            order.add(indexed);
            while (order.size() > MAX_CALLS) {
                Call oldest = order.poll();
                List<Call> calls = byHost.get(oldest.host());
                if (calls != null) {
                    calls.remove(oldest);
                    if (calls.isEmpty()) {
                        byHost.remove(oldest.host());
                    }
                }
            }
        }
    }

    @Override
    public synchronized boolean restClient(
            String host, int port, String requestId, String executionId, long firstMillis, long lastMillis) {
        if (host == null) {
            return false;
        }
        String key = host.toLowerCase(Locale.ROOT);
        List<Call> calls = byHost.get(key);
        if (calls != null) {
            for (Call call : calls) {
                boolean portMatches = call.port() == port || (call.port() < 0 && (port == 80 || port == 443));
                if (portMatches && matches(call, requestId, executionId, firstMillis, lastMillis)) {
                    return true;
                }
            }
        }
        if (proxies.contains(key + ":" + port)) {
            // Through a proxy, the connect names the proxy: any call of the same owner, or at the same time, used it.
            for (List<Call> all : byHost.values()) {
                for (Call call : all) {
                    if (matches(call, requestId, executionId, firstMillis, lastMillis)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code call} is the same owner's, or, when either names no owner, overlaps {@code [firstMillis,
     * lastMillis]} with {@value #SLACK_MILLIS} ms of slack either side.
     */
    private static boolean matches(Call call, String requestId, String executionId, long firstMillis, long lastMillis) {
        if (requestId != null && requestId.equals(call.requestId())) {
            return true;
        }
        if (requestId == null && executionId != null && executionId.equals(call.executionId())) {
            return true;
        }
        boolean callOwned = call.requestId() != null || call.executionId() != null;
        boolean owned = requestId != null || executionId != null;
        if (callOwned && owned) {
            return false;
        }
        return call.startMillis() - SLACK_MILLIS <= lastMillis && firstMillis <= call.endMillis() + SLACK_MILLIS;
    }

    /**
     * An authority or target split into its host, lower case, IPv6 bracketed, without user information, and its port,
     * {@code null} when it names none; {@code null} for an unparsable one.
     */
    static String[] hostPort(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String text = authority.trim();
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            text = text.substring(at + 1);
        }
        String host = text;
        String port = null;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = text.substring(0, close + 1);
            if (close + 1 < text.length() && text.charAt(close + 1) == ':') {
                port = text.substring(close + 2);
            }
        } else {
            int colon = text.lastIndexOf(':');
            if (colon >= 0 && text.indexOf(':') == colon) {
                host = text.substring(0, colon);
                port = text.substring(colon + 1);
            }
        }
        if (port != null && (port.isEmpty() || !port.chars().allMatch(Character::isDigit) || port.length() > 5)) {
            port = null;
        }
        return host.isEmpty() ? null : new String[] {host.toLowerCase(Locale.ROOT), port};
    }
}
