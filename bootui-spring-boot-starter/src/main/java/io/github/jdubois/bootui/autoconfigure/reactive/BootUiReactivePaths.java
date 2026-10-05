package io.github.jdubois.bootui.autoconfigure.reactive;

import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Resolves the context-relative request path the way WebFlux's handler mapping resolves it.
 *
 * <p>The path is rebuilt from {@link PathContainer.PathSegment#valueToMatch()}, which is what
 * {@code PathPattern} matches on: percent-decoded, with matrix parameters removed. The raw
 * {@code pathWithinApplication().value()} is neither, so a guard matching on it and a controller
 * selected by {@code PathPattern} disagree, and {@code /%62ootui/api/**} or {@code /bootui;x=1/api/**}
 * disarms the loopback, Host allow-list, cross-site-write, bearer-token and per-panel filters while
 * still reaching the controller.</p>
 *
 * <p>Every reactive BootUI filter shares this single implementation so the guards and the shell
 * guard cannot drift apart again.</p>
 */
public final class BootUiReactivePaths {

    private BootUiReactivePaths() {}

    public static String pathWithinApplication(ServerHttpRequest request) {
        StringBuilder path = new StringBuilder();
        for (PathContainer.Element element :
                request.getPath().pathWithinApplication().elements()) {
            path.append(
                    element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
        }
        return path.toString();
    }
}
