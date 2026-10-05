package io.github.jdubois.bootui.autoconfigure.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.handler.HandlerMethod;
import org.springframework.messaging.simp.SimpMessageMappingInfo;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.util.PathMatcher;

/**
 * Resolves a STOMP destination to the template of the {@code @MessageMapping} or {@code @SubscribeMapping} that
 * handles it, such as {@code /app/chat/{room}} for {@code /app/chat/42}, so the runtime journal names a handler once
 * rather than once per room, order, or user (M4-10).
 *
 * <p>It reads only the handler's declared mappings, prefixes, and path matcher, never the message. A destination no
 * mapping matches resolves to {@code null}: no application code runs for it. Resolutions are cached up to a bound,
 * after which they are computed again rather than retained, since concrete destinations can be unbounded.</p>
 */
final class StompDestinationTemplates {

    private static final int MAX_CACHED = 512;
    private static final String NONE = "";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** The template of the mapping of {@code type} that handles {@code destination}, or {@code null} when none does. */
    String template(SimpAnnotationMethodMessageHandler handler, SimpMessageType type, String destination) {
        if (destination == null || type == null) {
            return null;
        }
        String key = type.name() + ' ' + destination;
        String cached = cache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = resolve(handler, type, destination);
        if (cache.size() < MAX_CACHED) {
            cache.put(key, resolved == null ? NONE : resolved);
        }
        return resolved;
    }

    static String resolve(SimpAnnotationMethodMessageHandler handler, SimpMessageType type, String destination) {
        String base = null;
        String rest = null;
        if (handler.getDestinationPrefixes().isEmpty()) {
            base = "";
            rest = destination;
        } else {
            for (String prefix : handler.getDestinationPrefixes()) {
                if (destination.startsWith(prefix)) {
                    rest = destination.substring(prefix.length());
                    base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
                    break;
                }
            }
        }
        if (rest == null) {
            return null;
        }
        String bare = strip(rest);
        PathMatcher matcher = handler.getPathMatcher();
        List<String> matches = new ArrayList<>();
        Map<SimpMessageMappingInfo, HandlerMethod> methods = handler.getHandlerMethods();
        for (SimpMessageMappingInfo info : methods.keySet()) {
            if (info.getMessageTypeMessageCondition().getMessageType() != type) {
                continue;
            }
            for (String pattern : info.getDestinationConditions().getPatterns()) {
                // Compared without a leading slash, which a slash-separated mapping adds and a dot-separated one lacks.
                String candidate = strip(pattern);
                if (candidate.equals(bare) || matcher.match(candidate, bare)) {
                    matches.add(candidate);
                }
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(matcher.getPatternComparator(bare));
        return base + "/" + matches.get(0);
    }

    private static String strip(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
