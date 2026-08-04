package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.quarkus.QuarkusBootUiPaths;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;

/**
 * Serves the BootUI single-page application shell from its private {@code /bootui} JAX-RS mount.
 *
 * <p>Quarkus' static-resource handler serves the compiled Vue assets shipped inside {@code bootui-ui} at
 * {@code META-INF/resources/bootui/}. {@code BootUiPathRewriteFilter} maps the configured public UI path
 * to that private mount and sends both its bare and trailing-slash shell requests to this resource. The
 * generated {@code index.html} references assets relatively, so the injected browser base must end in a
 * slash.</p>
 *
 * <p>Rather than redirect the bare path (which a proxy that strips trailing slashes could turn into an
 * infinite loop, see #456), this resource answers directly and injects a {@code <base>} plus
 * {@code bootui-api-path} metadata composed from normalized configuration. Those paths are read from
 * {@code quarkus.http.root-path}, {@code bootui.path}, and {@code bootui.api-path}, not from the
 * attacker-influenced request URI.</p>
 */
@Path("/bootui")
public class QuarkusIndexResource {

    static final String INDEX_LOCATION = "META-INF/resources/bootui/index.html";

    private static final Pattern HEAD_OPEN = Pattern.compile("(?i)<head[^>]*>");

    private static final Pattern EXISTING_BASE = Pattern.compile("(?i)<base\\b");

    private static final Pattern EXISTING_API_PATH =
            Pattern.compile("(?i)<meta\\b[^>]*\\bname=[\"']bootui-api-path[\"']");

    private final Config config;

    private volatile String cachedTemplate;

    @Inject
    public QuarkusIndexResource(Config config) {
        this.config = config;
    }

    @GET
    public Response index() {
        String baseHref = QuarkusBootUiPaths.applicationPath(config, QuarkusBootUiPaths.uiPath(config)) + "/";
        String apiPath = QuarkusBootUiPaths.applicationPath(config, QuarkusBootUiPaths.apiPath(config));
        String html = injectRuntimePaths(template(), baseHref, apiPath);
        return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
    }

    private String template() {
        String html = cachedTemplate;
        if (html == null) {
            html = readTemplate();
            cachedTemplate = html;
        }
        return html;
    }

    private static String readTemplate() {
        try (InputStream in = QuarkusIndexResource.class.getClassLoader().getResourceAsStream(INDEX_LOCATION)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("BootUI index.html not found at " + INDEX_LOCATION));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

    static String injectRuntimePaths(String html, String baseHref, String apiPath) {
        String rewritten = injectBaseHref(html, baseHref);
        if (EXISTING_API_PATH.matcher(rewritten).find()) {
            return rewritten;
        }
        Matcher matcher = HEAD_OPEN.matcher(rewritten);
        if (!matcher.find()) {
            return rewritten;
        }
        int insertAt = matcher.end();
        String meta = "\n    <meta content=\"" + escapeAttribute(apiPath) + "\" name=\"bootui-api-path\" />";
        return rewritten.substring(0, insertAt) + meta + rewritten.substring(insertAt);
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
