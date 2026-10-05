package io.github.jdubois.bootui.autoconfigure.web;

/**
 * Whether a decoded, context-relative request path targets BootUI's UI or API mount. Shared by BootUI's recording
 * and correlation filters so they skip exactly the same requests; never pass a raw URI or a query string.
 */
public final class BootUiMounts {

    private BootUiMounts() {}

    /**
     * @param requestPath the decoded path below the servlet context path or WebFlux base path
     * @param path {@code bootui.path}
     * @param apiPath {@code bootui.api-path}, or {@code null} to match the UI mount only
     */
    public static boolean contains(String requestPath, String path, String apiPath) {
        return isSameOrChild(requestPath, path) || isSameOrChild(requestPath, apiPath);
    }

    private static boolean isSameOrChild(String requestPath, String mount) {
        return requestPath != null
                && mount != null
                && (requestPath.equals(mount) || requestPath.startsWith(mount + "/"));
    }
}
