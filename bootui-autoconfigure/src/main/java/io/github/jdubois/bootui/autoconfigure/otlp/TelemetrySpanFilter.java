package io.github.jdubois.bootui.autoconfigure.otlp;

public final class TelemetrySpanFilter {

    private TelemetrySpanFilter() {}

    public static boolean isSelfSpan(NormalizedSpan span, String apiPath) {
        if (span == null || apiPath == null || apiPath.isEmpty() || span.attributes() == null) {
            return false;
        }
        String route = stringAttribute(span, "http.route");
        if (startsWithBootUiPath(route, apiPath)) {
            return true;
        }
        String urlPath = stringAttribute(span, "url.path");
        if (startsWithBootUiPath(urlPath, apiPath)) {
            return true;
        }
        String target = stringAttribute(span, "http.target");
        return startsWithBootUiPath(target, apiPath);
    }

    private static boolean startsWithBootUiPath(String value, String apiPath) {
        return value != null && (value.startsWith(apiPath) || value.startsWith("/bootui"));
    }

    private static String stringAttribute(NormalizedSpan span, String key) {
        AttributeValue av = span.attributes().get(key);
        return av != null ? av.asString() : null;
    }
}
