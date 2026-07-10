package io.github.jdubois.bootui.engine.safety;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Defines the BootUI response-header policy applied by each adapter's thin binding.
 *
 * <p>Security headers are applied to <em>all</em> responses on the BootUI surface ({@code /bootui} and
 * {@code /bootui/api/**}) including 403 rejections from the localhost guard and panel-access filter.
 * Cache-control headers are differentiated by response class: API responses are never cached, content-hashed
 * successfully served static assets are cached indefinitely, and the SPA shell and error responses are
 * revalidated on every request.</p>
 *
 * <p>This class is pure Java with no framework or transport dependencies, so it is safe to use in the Spring
 * Boot (servlet and reactive) and Quarkus adapters without triggering the engine boundary check.</p>
 *
 * <p><strong>Header policy</strong></p>
 * <ul>
 *   <li>{@code Content-Security-Policy} — locks scripts to same-origin, permits inline styles for
 *       Bootstrap/Vue, restricts frame embedding via {@code frame-ancestors 'none'}, and requires no
 *       {@code unsafe-eval} because the production Vue bundle uses build-time compilation (Vite).</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type sniffing.</li>
 *   <li>{@code X-Frame-Options: DENY} — denies frame embedding (reinforces the CSP directive for
 *       older browsers that do not support {@code frame-ancestors}).</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — sends origin only on cross-origin
 *       requests, suppresses the referrer on downgrade.</li>
 *   <li>{@code Permissions-Policy} — disables browser capabilities the local console never uses.</li>
 *   <li>{@code Cache-Control} — see {@link #cacheControl(String, String, int)}.</li>
 * </ul>
 */
public final class BootUiSecurityHeaders {

    private static final Pattern HASHED_ASSET = Pattern.compile("(?:^|/)assets/[^/]+-[A-Za-z0-9_-]{8,}\\.[^/]+$");

    // -----------------------------------------------------------------------
    // Header names
    // -----------------------------------------------------------------------

    /** Header name: Content Security Policy. */
    public static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    /** Header name: MIME-sniffing protection. */
    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** Header name: clickjacking / frame-embedding restriction. */
    public static final String X_FRAME_OPTIONS = "X-Frame-Options";

    /** Header name: referrer control. */
    public static final String REFERRER_POLICY = "Referrer-Policy";

    /** Header name: browser capability restrictions. */
    public static final String PERMISSIONS_POLICY = "Permissions-Policy";

    /** Header name: cache control. */
    public static final String CACHE_CONTROL = "Cache-Control";

    /** Header name: legacy HTTP/1.0 no-cache pragma. */
    public static final String PRAGMA = "Pragma";

    // -----------------------------------------------------------------------
    // Header values — one canonical definition shared across all three adapters
    // -----------------------------------------------------------------------

    /**
     * CSP compatible with the packaged Vue 3 + Bootstrap 5 production bundle:
     * <ul>
     *   <li>No {@code unsafe-eval} — the Vite build uses build-time component compilation.</li>
     *   <li>{@code unsafe-inline} for {@code style-src} only — Bootstrap and Vue may emit inline
     *       {@code style} attributes; all script execution is locked to {@code 'self'}.</li>
     *   <li>{@code data:} allowed for images and fonts — Bootstrap Icons are subset into a woff2
     *       data URI served via CSS, and SVG icons may appear as inline data URIs.</li>
     *   <li>{@code connect-src 'self'} — the SPA's {@code fetch()} and SSE calls target the same
     *       origin only.</li>
     *   <li>{@code frame-ancestors 'none'} — BootUI must never be embedded in a frame.</li>
     * </ul>
     */
    public static final String CSP_VALUE = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';"
            + " img-src 'self' data:; font-src 'self' data:; connect-src 'self';"
            + " object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    /** {@code X-Content-Type-Options} value. */
    public static final String NOSNIFF = "nosniff";

    /** {@code X-Frame-Options} value — denies all frame embedding. */
    public static final String DENY = "DENY";

    /** {@code Referrer-Policy} value. */
    public static final String STRICT_ORIGIN_WHEN_CROSS_ORIGIN = "strict-origin-when-cross-origin";

    /** Browser capabilities that BootUI does not use and therefore denies on its document. */
    public static final String PERMISSIONS_POLICY_VALUE =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()";

    /**
     * {@code Cache-Control} for API responses: do not cache, do not serve stale content.
     * Paired with {@code Pragma: no-cache} for HTTP/1.0 compatibility.
     */
    public static final String NO_STORE = "no-store, must-revalidate";

    /**
     * {@code Cache-Control} for content-hashed static assets (files under {@code /assets/}): the
     * hash in the filename guarantees freshness, so the asset is safe to cache indefinitely.
     */
    public static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /**
     * {@code Cache-Control} for the SPA shell ({@code index.html} and the BootUI root): clients must
     * revalidate on every request because the shell references the current hashed bundle filenames.
     * Paired with {@code Pragma: no-cache} for HTTP/1.0 compatibility.
     */
    public static final String NO_CACHE = "no-cache";

    /** {@code Pragma} value paired with {@link #NO_STORE} and {@link #NO_CACHE}. */
    public static final String PRAGMA_NO_CACHE = "no-cache";

    // -----------------------------------------------------------------------
    // Path classification
    // -----------------------------------------------------------------------

    /**
     * Returns the appropriate {@code Cache-Control} value for the given request path and response status.
     *
     * <p>The {@code path} is the full request path relative to the servlet context root or Quarkus
     * root-path (not stripped of the BootUI prefix). The {@code apiPath} is the configured
     * {@code bootui.api-path} (default {@code /bootui/api}).</p>
     *
     * <ul>
     *   <li>API paths ({@code path} equals {@code apiPath} or starts with {@code apiPath + "/"}) → {@link #NO_STORE}</li>
     *   <li>Successfully served Vite-style hashed asset paths under {@code /assets/} → {@link #IMMUTABLE}</li>
     *   <li>Everything else (SPA shell, error pages) → {@link #NO_CACHE}</li>
     * </ul>
     */
    public static String cacheControl(String path, String apiPath, int statusCode) {
        if (path == null) {
            return NO_CACHE;
        }
        if (apiPath != null && (path.equals(apiPath) || path.startsWith(apiPath + "/"))) {
            return NO_STORE;
        }
        if (isCacheableAssetStatus(statusCode) && HASHED_ASSET.matcher(path).find()) {
            return IMMUTABLE;
        }
        return NO_CACHE;
    }

    /**
     * Returns the complete canonical header set for a BootUI response path.
     *
     * <p>Adapters use this map as their only source of header names and values. Cache headers are included
     * according to the response class; {@code Pragma} is intentionally absent for immutable assets.</p>
     */
    public static Map<String, String> headersFor(String path, String apiPath, int statusCode) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CONTENT_SECURITY_POLICY, CSP_VALUE);
        headers.put(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        headers.put(X_FRAME_OPTIONS, DENY);
        headers.put(REFERRER_POLICY, STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
        headers.put(PERMISSIONS_POLICY, PERMISSIONS_POLICY_VALUE);

        String cacheControl = cacheControl(path, apiPath, statusCode);
        headers.put(CACHE_CONTROL, cacheControl);
        if (shouldSetPragma(cacheControl)) {
            headers.put(PRAGMA, PRAGMA_NO_CACHE);
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Returns whether BootUI must own an existing response header value.
     *
     * <p>Cache directives are response-class semantics, not optional defense-in-depth, so adapters replace
     * them. Other security headers are baseline defaults: an already-present host policy wins, and a host
     * filter that runs later may replace BootUI's value.</p>
     */
    public static boolean overridesExisting(String headerName) {
        return CACHE_CONTROL.equalsIgnoreCase(headerName) || PRAGMA.equalsIgnoreCase(headerName);
    }

    /** Returns whether an existing legacy no-cache pragma must be removed for this response path. */
    public static boolean removesPragma(String path, String apiPath, int statusCode) {
        return !shouldSetPragma(cacheControl(path, apiPath, statusCode));
    }

    /**
     * Returns {@code true} when the {@code Cache-Control} value requires a paired
     * {@code Pragma: no-cache} header for HTTP/1.0 compatibility.
     *
     * <p>Immutable hashed assets ({@link #IMMUTABLE}) do not need {@code Pragma} because
     * they are served as indefinitely-cacheable and HTTP/1.0 agents should not bypass the cache
     * for them. All other BootUI cache-control values ({@link #NO_STORE} and {@link #NO_CACHE})
     * are paired with {@code Pragma: no-cache}.</p>
     *
     * @param cacheControl the value returned by {@link #cacheControl(String, String, int)}
     * @return {@code true} if {@code Pragma: no-cache} should be added alongside
     */
    public static boolean shouldSetPragma(String cacheControl) {
        return !IMMUTABLE.equals(cacheControl);
    }

    private static boolean isCacheableAssetStatus(int statusCode) {
        return (statusCode >= 200 && statusCode < 300) || statusCode == 304;
    }

    private BootUiSecurityHeaders() {}
}
