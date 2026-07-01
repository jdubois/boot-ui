package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the BootUI single-page application at {@code /bootui} and {@code /bootui/}.
 *
 * <p>The compiled Vue assets ship inside {@code bootui-ui} at
 * {@code META-INF/resources/bootui/} (Spring Boot serves the static files automatically;
 * see {@link BootUiStaticResourceConfigurer}). The generated {@code index.html} references its
 * assets relatively (e.g. {@code ./assets/index-*.js}) and the SPA calls its API relatively
 * (e.g. {@code fetch('api/overview')}) so it keeps working under a host
 * {@code server.servlet.context-path} without baking an absolute path at build time (see #332).</p>
 *
 * <p>Those relative URLs only resolve correctly when the document's base URL ends in
 * {@code /bootui/}. Rather than answer {@code GET /bootui} with a {@code 302 -> /bootui/}, this
 * controller serves the SPA shell for <em>both</em> {@code /bootui} and {@code /bootui/} and injects
 * a {@code <base href="{contextPath}/bootui/">} tag so assets, API calls, and chunk loading resolve
 * regardless of whether the request URL carries a trailing slash. Removing the redirect makes BootUI
 * immune to host applications (or proxies) that strip trailing slashes, which previously turned the
 * {@code /bootui -> /bootui/} redirect into an infinite loop (#456).</p>
 */
@Controller
public class BootUiIndexController {

    static final String INDEX_LOCATION = "META-INF/resources/bootui/index.html";

    private static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head[^>]*>");

    private static final Pattern EXISTING_BASE = Pattern.compile("(?i)<base\\b");

    private final BootUiProperties properties;

    private final Resource indexResource;

    private volatile String cachedTemplate;

    @Autowired
    public BootUiIndexController(BootUiProperties properties) {
        this(properties, new ClassPathResource(INDEX_LOCATION));
    }

    BootUiIndexController(BootUiProperties properties, Resource indexResource) {
        this.properties = properties;
        this.indexResource = indexResource;
    }

    @GetMapping({"/bootui", "/bootui/"})
    public void spaIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Include the servlet context path so the SPA's relative assets/API resolve when the host app
        // sets server.servlet.context-path (e.g. /api/bootui/ instead of /bootui/). See #332.
        String baseHref = request.getContextPath() + properties.getPath() + "/";
        String html = injectBaseHref(template(), baseHref);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(html);
    }

    private String template() {
        String html = cachedTemplate;
        if (html == null) {
            html = readTemplate();
            cachedTemplate = html;
        }
        return html;
    }

    private String readTemplate() {
        try (var in = indexResource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read BootUI index.html from " + INDEX_LOCATION, ex);
        }
    }

    /**
     * Inserts a {@code <base href>} as the first child of {@code <head>} so it precedes every relative
     * asset/API URL in the document. Returns the markup unchanged when it already declares a
     * {@code <base>} tag or has no {@code <head>}.
     */
    static String injectBaseHref(String html, String baseHref) {
        if (EXISTING_BASE.matcher(html).find()) {
            return html;
        }
        Matcher matcher = HEAD_OPEN.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        int insertAt = matcher.end();
        String baseTag = "\n    <base href=\"" + escapeAttribute(baseHref) + "\" />";
        return html.substring(0, insertAt) + baseTag + html.substring(insertAt);
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
