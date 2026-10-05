package io.github.jdubois.bootui.autoconfigure.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Passive discovery of OAuth2 client provider endpoints configured with plain HTTP. Provider and
 * registration ids are discovered only from bounded native map-backed property sources; no custom
 * source, converter or registration repository is invoked. {@code issuer-uri} is deliberately out of
 * scope because the Pentesting advisor owns it (PT-A07-006). Only property names leave this class.
 */
final class OAuth2ClientEndpoints {

    private static final String PROVIDER = "spring.security.oauth2.client.provider.";
    private static final String REGISTRATION = "spring.security.oauth2.client.registration.";
    private static final Set<String> ENDPOINTS = Set.of("authorizationuri", "tokenuri", "jwkseturi", "userinfouri");
    private static final Set<String> MAP_SOURCES = Set.of(
            "org.springframework.core.env.MapPropertySource",
            "org.springframework.core.env.PropertiesPropertySource",
            "org.springframework.boot.env.OriginTrackedMapPropertySource",
            "org.springframework.boot.env.DefaultPropertiesPropertySource",
            "org.springframework.mock.env.MockPropertySource");
    private static final int MAX_SOURCES = 128;
    private static final int MAX_KEYS = 5000;
    private static final int MAX_RESULTS = 64;

    /**
     * @param plainHttpKeys property names of registration-linked provider endpoints using plain HTTP
     * @param linkedEndpointPresent whether any registration-linked provider endpoint is configured
     * @param complete {@code false} when an inventory bound was reached
     */
    record Result(List<String> plainHttpKeys, boolean linkedEndpointPresent, boolean complete) {}

    private OAuth2ClientEndpoints() {}

    static Result observe(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return new Result(List.of(), false, false);
        }
        Set<String> endpointKeys = new TreeSet<>();
        Set<String> registrationIds = new LinkedHashSet<>();
        int sources = 0;
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (++sources > MAX_SOURCES) return new Result(List.of(), false, false);
            if (!MAP_SOURCES.contains(source.getClass().getName()) || !(source instanceof MapPropertySource mapSource))
                continue;
            Map<String, Object> map = mapSource.getSource();
            if (map.size() > MAX_KEYS) return new Result(List.of(), false, false);
            for (String name : map.keySet()) {
                if (name.startsWith(PROVIDER)) {
                    String[] parts = name.substring(PROVIDER.length()).split("\\.", -1);
                    if (parts.length == 2 && simpleId(parts[0]) && ENDPOINTS.contains(normalize(parts[1]))) {
                        endpointKeys.add(name);
                    }
                } else if (name.startsWith(REGISTRATION)) {
                    String id = name.substring(REGISTRATION.length()).split("\\.", -1)[0];
                    if (simpleId(id)) registrationIds.add(id);
                }
                if (endpointKeys.size() > MAX_RESULTS || registrationIds.size() > MAX_RESULTS) {
                    return new Result(List.of(), false, false);
                }
            }
        }
        Set<String> usedProviders = new LinkedHashSet<>();
        for (String registration : registrationIds) {
            String provider = environment.getProperty(REGISTRATION + registration + ".provider");
            usedProviders.add(provider == null || provider.isBlank() ? registration : provider.trim());
        }
        List<String> plainHttp = new java.util.ArrayList<>();
        boolean linked = false;
        for (String key : endpointKeys) {
            String provider = key.substring(PROVIDER.length(), key.lastIndexOf('.'));
            if (!usedProviders.contains(provider)) continue;
            linked = true;
            String value = environment.getProperty(key);
            if (value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("http://")) {
                plainHttp.add(key);
            }
        }
        return new Result(List.copyOf(plainHttp), linked, true);
    }

    private static boolean simpleId(String id) {
        return !id.isEmpty() && id.length() <= 128 && id.chars().noneMatch(c -> c == '[' || c == ']');
    }

    private static String normalize(String endpoint) {
        return endpoint.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
