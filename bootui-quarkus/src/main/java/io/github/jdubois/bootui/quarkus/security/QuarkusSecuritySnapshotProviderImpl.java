package io.github.jdubois.bootui.quarkus.security;

import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshotProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Quarkus adapter that reads the live {@code quarkus.http.*} / {@code quarkus.oidc.*} security config from
 * MicroProfile {@link Config} (plus build-time annotation counts produced by the deployment processor) into a
 * neutral {@link QuarkusSecuritySnapshot} for the engine {@code QuarkusSecurityScanner}. Fails safe: any
 * unreadable value is treated as absent. Never exposes secret values — only suspicious key names are listed.
 */
public class QuarkusSecuritySnapshotProviderImpl implements QuarkusSecuritySnapshotProvider {

    static final String ROLES_KEY = "bootui.internal.sec.roles-allowed";
    static final String PERMIT_KEY = "bootui.internal.sec.permit-all";
    static final String DENY_KEY = "bootui.internal.sec.deny-all";
    static final String AUTH_KEY = "bootui.internal.sec.authenticated";
    static final String ENDPOINTS_KEY = "bootui.internal.sec.endpoints";
    static final String SECURED_KEY = "bootui.internal.sec.secured-endpoints";
    static final String CSRF_KEY = "bootui.internal.sec.csrf-present";
    static final String GRPC_PRESENT_KEY = "bootui.internal.sec.grpc-present";
    static final String GRAPHQL_PRESENT_KEY = "bootui.internal.sec.graphql-present";
    static final String QUARKUS_AUTHZ_KEY = "bootui.internal.sec.quarkus-authz";

    private static final Pattern PERMISSION =
            Pattern.compile("^quarkus\\.http\\.auth\\.permission\\.([^.]+)\\.policy$");
    private static final Pattern SECRET_NAME = Pattern.compile(
            "^(?:.*[._-])?(?:password|passwd|secret|token|api-?key|client-secret|private-key|"
                    + "access-?token|refresh-?token)(?:\\.value)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_TLS_TRUST_ALL = Pattern.compile("^quarkus\\.tls\\.(.+)\\.trust-all$");
    private static final Pattern NAMED_TLS_HOSTNAME_VERIFICATION =
            Pattern.compile("^quarkus\\.tls\\.(.+)\\.hostname-verification-algorithm$");
    private static final Pattern NAMED_OIDC_TENANT =
            Pattern.compile("^quarkus\\.oidc\\.([^.]+)\\.(?:auth-server-url|public-key|certificate-chain\\..+)$");
    private static final Pattern SASL_CREDENTIAL = Pattern.compile("(?i)^(.*)\\.sasl\\.(?:password|jaas\\.config)$");
    private static final Pattern SECURITY_PROTOCOL = Pattern.compile("(?i)^(.*)\\.security\\.protocol$");
    private static final Set<String> SECURE_KAFKA_PROTOCOLS = Set.of("SASL_SSL", "SSL");
    private final Config config;

    public QuarkusSecuritySnapshotProviderImpl(Config config) {
        this.config = config;
    }

    @Override
    public QuarkusSecuritySnapshot snapshot() {
        Set<String> oidcTenants = oidcTenantPrefixes();
        boolean oidc = !oidcTenants.isEmpty();
        boolean jwt = has("mp.jwt.verify.publickey")
                || has("mp.jwt.verify.publickey.location")
                || has("mp.jwt.verify.issuer");
        boolean form = bool("quarkus.http.auth.form.enabled", false);
        String clientAuth = str("quarkus.http.ssl.client-auth", "none");
        boolean mtls = "required".equalsIgnoreCase(clientAuth) || "request".equalsIgnoreCase(clientAuth);
        boolean embeddedUsers = bool("quarkus.security.users.embedded.enabled", false);
        boolean basic = isConfigured("quarkus.http.auth.basic")
                ? bool("quarkus.http.auth.basic", false)
                : implicitBasicAuth(oidc, jwt, form, mtls, embeddedUsers);
        String insecure = effectiveInsecureRequests(clientAuth);
        boolean ssl = httpServerTlsConfigured();
        boolean cors = bool("quarkus.http.cors", false) || bool("quarkus.http.cors.enabled", false);
        String corsOrigins = str("quarkus.http.cors.origins", null);
        boolean corsCreds = corsCredentials(corsOrigins);
        boolean hsts = has("quarkus.http.header.\"Strict-Transport-Security\".value");
        boolean csp = has("quarkus.http.header.\"Content-Security-Policy\".value");
        boolean oidcVerifyNone = oidcTenants.stream()
                .anyMatch(prefix -> !has(prefix + ".tls.tls-configuration-name")
                        && "none".equalsIgnoreCase(str(prefix + ".tls.verification", "")));
        boolean swagger = bool("quarkus.swagger-ui.always-include", false);
        boolean csrfExtensionPresent = bool(CSRF_KEY, false);
        boolean csrf = csrfExtensionPresent && bool("quarkus.rest-csrf.enabled", true);

        boolean behindProxy = bool("quarkus.http.proxy.proxy-address-forwarding", false);
        boolean jwtIssuer = has("mp.jwt.verify.issuer");
        boolean proactiveAuthDisabled = !bool("quarkus.http.auth.proactive", true);
        boolean oidcServiceTokenConsumer = oidcTenants.stream().anyMatch(this::isServiceOidcTenant);
        boolean oidcAudience = !oidcServiceTokenConsumer
                || oidcTenants.stream()
                        .filter(this::isServiceOidcTenant)
                        .allMatch(prefix -> has(prefix + ".token.audience"));
        boolean oidcWebApp = oidcTenants.stream().anyMatch(this::isWebOidcTenant);
        String oidcAppType = oidcWebApp
                ? (oidcServiceTokenConsumer ? "hybrid" : "web-app")
                : (oidcServiceTokenConsumer ? "service" : "");
        boolean oidcCookieForceSecure = !oidcWebApp
                || oidcTenants.stream()
                        .filter(this::isWebOidcTenant)
                        .allMatch(prefix -> bool(prefix + ".authentication.cookie-force-secure", false));
        boolean tlsTrustAll = bool("quarkus.tls.trust-all", false) || namedTlsBucketTrustAll();
        String corsMethods = str("quarkus.http.cors.methods", null);
        String corsHeaders = str("quarkus.http.cors.headers", null);
        String hstsValue = str("quarkus.http.header.\"Strict-Transport-Security\".value", null);
        String cspValue = str("quarkus.http.header.\"Content-Security-Policy\".value", null);
        boolean xFrame = has("quarkus.http.header.\"X-Frame-Options\".value");
        boolean xContentType = has("quarkus.http.header.\"X-Content-Type-Options\".value");
        boolean denyUnannotated = prodAwareBoolean("quarkus.security.jaxrs.deny-unannotated-endpoints");
        boolean defaultRolesAllowed = prodAwareValuePresent("quarkus.security.jaxrs.default-roles-allowed");
        boolean managementEnabled = bool("quarkus.management.enabled", false);
        String managementHostPin = managementHostPinnedForProd();
        boolean managementHostNonLoopback =
                managementEnabled && managementHostPin != null && !isLoopbackHost(managementHostPin);
        boolean managementHostUnpinnedForProd = managementEnabled && managementHostPin == null;

        String jwksLocation = str("mp.jwt.verify.publickey.location", null);
        boolean jwksLocationRemote = jwksLocation != null
                && (jwksLocation.toLowerCase().startsWith("http://")
                        || jwksLocation.toLowerCase().startsWith("https://"));
        boolean jwtAlgorithmUnpinnedForRemoteJwks = jwksLocationRemote && !has("mp.jwt.verify.publickey.algorithm");
        boolean jdbcClearPasswordMapper = jdbcClearPasswordMapperEnabled();
        boolean jwtAudiences = has("mp.jwt.verify.audiences");
        boolean jwtInlineKey = has("mp.jwt.verify.publickey");
        boolean referrerPolicy = has("quarkus.http.header.\"Referrer-Policy\".value");
        boolean permissionsPolicy = has("quarkus.http.header.\"Permissions-Policy\".value");
        String httpRootPath = str("quarkus.http.root-path", "/");
        String nonApplicationRootPath = str("quarkus.http.non-application-root-path", "q");
        if ("${quarkus.http.root-path}".equals(nonApplicationRootPath)) {
            nonApplicationRootPath = httpRootPath;
        }
        boolean nonApplicationRootPathMerged = nonApplicationRootPathMerged(httpRootPath, nonApplicationRootPath);
        boolean grpcPresent = bool(GRPC_PRESENT_KEY, false);
        boolean grpcReflectionProd = grpcPresent && grpcReflectionEnabledInProdProfile();
        boolean graphqlPresent = bool(GRAPHQL_PRESENT_KEY, false);
        boolean graphqlIntrospection = graphqlIntrospectionEnabled();
        boolean graphqlUiAlwaysInclude = bool("quarkus.smallrye-graphql.ui.always-include", false);
        List<String> insecureMessagingChannels = messagingChannelsWithCredentialsWithoutTls();
        boolean formCookieHttpOnly = bool("quarkus.http.auth.form.http-only-cookie", false);
        boolean formCookieSameSiteNone =
                "none".equalsIgnoreCase(str("quarkus.http.auth.form.cookie-same-site", "strict"));
        boolean formSessionTimeoutExcessive = formSessionTimeoutExcessive();
        boolean oidcHasClientSecret = !oidcWebApp
                || oidcTenants.stream().filter(this::isWebOidcTenant).allMatch(this::oidcTenantHasClientSecret);
        boolean oidcPkceRequired = !oidcWebApp
                || oidcTenants.stream()
                        .filter(this::isWebOidcTenant)
                        .allMatch(prefix -> oidcTenantHasClientSecret(prefix)
                                || bool(prefix + ".authentication.pkce-required", false));
        boolean healthUiAlwaysInclude = bool("quarkus.smallrye-health.ui.always-include", false);
        boolean insecureIdentityProviderUrl =
                oidcTenants.stream().anyMatch(prefix -> isHttpUrl(str(prefix + ".auth-server-url", null)))
                        || isHttpUrl(str("mp.jwt.verify.publickey.location", null));
        boolean oidcIssuerAny =
                oidcTenants.stream().anyMatch(prefix -> "any".equalsIgnoreCase(str(prefix + ".token.issuer", "")));
        boolean embeddedUsersPlainText = bool("quarkus.security.users.embedded.plain-text", false);
        List<String> tlsHostnameVerificationDisabled = tlsHostnameVerificationDisabled(oidcTenants);

        return new QuarkusSecuritySnapshot(
                oidc,
                jwt,
                basic,
                form,
                mtls,
                insecure,
                ssl,
                cors,
                corsOrigins,
                corsCreds,
                hsts,
                csp,
                oidcVerifyNone,
                swagger,
                false,
                csrf,
                permissions(),
                count(ROLES_KEY),
                count(PERMIT_KEY),
                count(DENY_KEY),
                count(AUTH_KEY),
                count(ENDPOINTS_KEY),
                count(SECURED_KEY),
                suspectedSecrets(),
                behindProxy,
                jwtIssuer,
                proactiveAuthDisabled,
                oidcAudience,
                oidcAppType,
                oidcCookieForceSecure,
                tlsTrustAll,
                corsMethods,
                corsHeaders,
                hstsValue,
                cspValue,
                xFrame,
                xContentType,
                denyUnannotated,
                managementEnabled,
                managementHostNonLoopback,
                managementHostUnpinnedForProd,
                jwtAlgorithmUnpinnedForRemoteJwks,
                jdbcClearPasswordMapper,
                embeddedUsers,
                jwtAudiences,
                jwtInlineKey,
                referrerPolicy,
                permissionsPolicy,
                nonApplicationRootPath,
                grpcReflectionProd,
                graphqlPresent,
                graphqlIntrospection,
                graphqlUiAlwaysInclude,
                insecureMessagingChannels,
                formCookieHttpOnly,
                formCookieSameSiteNone,
                formSessionTimeoutExcessive,
                oidcHasClientSecret,
                oidcPkceRequired,
                healthUiAlwaysInclude,
                insecureIdentityProviderUrl,
                oidcIssuerAny,
                oidcServiceTokenConsumer,
                embeddedUsersPlainText,
                tlsHostnameVerificationDisabled,
                nonApplicationRootPathMerged,
                count(QUARKUS_AUTHZ_KEY),
                defaultRolesAllowed);
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase();
        return h.equals("localhost")
                || h.equals("127.0.0.1")
                || h.startsWith("127.")
                || h.equals("::1")
                || h.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isHttpUrl(String value) {
        return value != null && value.trim().toLowerCase().startsWith("http://");
    }

    private Set<String> oidcTenantPrefixes() {
        Set<String> prefixes = new java.util.LinkedHashSet<>();
        for (String name : config.getPropertyNames()) {
            String prefix = oidcTenantPrefix(name);
            if (prefix != null && has(name) && bool(prefix + ".tenant-enabled", true)) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    private static String oidcTenantPrefix(String name) {
        if (name.equals("quarkus.oidc.auth-server-url")
                || name.equals("quarkus.oidc.public-key")
                || name.startsWith("quarkus.oidc.certificate-chain.")) {
            return "quarkus.oidc";
        }
        var matcher = NAMED_OIDC_TENANT.matcher(name);
        return matcher.matches() ? "quarkus.oidc." + matcher.group(1) : null;
    }

    private boolean isServiceOidcTenant(String prefix) {
        String applicationType = str(prefix + ".application-type", "service");
        return "service".equalsIgnoreCase(applicationType) || "hybrid".equalsIgnoreCase(applicationType);
    }

    private boolean isWebOidcTenant(String prefix) {
        String applicationType = str(prefix + ".application-type", "service");
        return "web-app".equalsIgnoreCase(applicationType) || "hybrid".equalsIgnoreCase(applicationType);
    }

    private boolean oidcTenantHasClientSecret(String prefix) {
        return has(prefix + ".credentials.secret") || has(prefix + ".credentials.client-secret.value");
    }

    private boolean httpServerTlsConfigured() {
        if (has("quarkus.http.ssl.certificate.key-store-file")
                || has("quarkus.http.ssl.certificate.files")
                || has("quarkus.http.ssl.certificate.key-files")) {
            return true;
        }
        String selectedBucket = str("quarkus.http.tls-configuration-name", null);
        String keyStorePrefix = selectedBucket == null || selectedBucket.isBlank()
                ? "quarkus.tls.key-store."
                : "quarkus.tls." + selectedBucket + ".key-store.";
        return tlsBucketHasKeyStore(keyStorePrefix);
    }

    private boolean tlsBucketHasKeyStore(String keyStorePrefix) {
        for (String name : config.getPropertyNames()) {
            if (!name.startsWith(keyStorePrefix) || !has(name)) {
                continue;
            }
            String suffix = name.substring(keyStorePrefix.length());
            if ("p12.path".equals(suffix)
                    || "jks.path".equals(suffix)
                    || (suffix.startsWith("pem.") && (suffix.endsWith(".cert") || suffix.endsWith(".key")))) {
                return true;
            }
        }
        return false;
    }

    /** Raw-scans for any named TLS registry bucket ({@code quarkus.tls.<name>.trust-all}) set to {@code true}. */
    private boolean namedTlsBucketTrustAll() {
        for (String name : config.getPropertyNames()) {
            if (NAMED_TLS_TRUST_ALL.matcher(name).matches() && bool(name, false)) {
                return true;
            }
        }
        return false;
    }

    private List<String> tlsHostnameVerificationDisabled(Set<String> oidcTenants) {
        Set<String> disabled = new LinkedHashSet<>();
        if ("none".equalsIgnoreCase(str("quarkus.tls.hostname-verification-algorithm", ""))) {
            disabled.add("default TLS registry bucket");
        }
        for (String name : config.getPropertyNames()) {
            var matcher = NAMED_TLS_HOSTNAME_VERIFICATION.matcher(name);
            if (matcher.matches() && "none".equalsIgnoreCase(str(name, ""))) {
                disabled.add("TLS registry bucket " + matcher.group(1));
            }
        }
        for (String prefix : oidcTenants) {
            if (!has(prefix + ".tls.tls-configuration-name")
                    && "certificate-validation".equalsIgnoreCase(str(prefix + ".tls.verification", ""))) {
                disabled.add(oidcTenantLabel(prefix));
            }
        }
        return disabled.stream().sorted().toList();
    }

    private static String oidcTenantLabel(String prefix) {
        return "quarkus.oidc".equals(prefix)
                ? "default OIDC tenant"
                : "OIDC tenant " + prefix.substring("quarkus.oidc.".length());
    }

    /**
     * Mirrors Quarkus's real {@code CORSFilter} default: {@code accessControlAllowCredentials().orElse(originMatches)}.
     * If the property is explicitly set, that value wins; otherwise credentials are implicitly allowed whenever
     * origins are configured as one or more precisely-pinned literal values (not a wildcard, not a {@code /regex/}),
     * since that is the only case where Quarkus's real request-time {@code originMatches} check can be statically
     * approximated as always-true from config alone.
     */
    private boolean corsCredentials(String corsOrigins) {
        if (has("quarkus.http.cors.access-control-allow-credentials")) {
            return bool("quarkus.http.cors.access-control-allow-credentials", false);
        }
        return originsArePreciselyPinned(corsOrigins);
    }

    private static boolean originsArePreciselyPinned(String corsOrigins) {
        if (corsOrigins == null || corsOrigins.isBlank()) {
            return false;
        }
        for (String entry : corsOrigins.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || trimmed.equals("*") || (trimmed.startsWith("/") && trimmed.endsWith("/"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks only the literal {@code quarkus.management.host} or {@code %prod.quarkus.management.host} keys (not
     * the profile-resolved value), mirroring {@link #grpcReflectionEnabledInProdProfile()}. Quarkus's own built-in
     * default for {@code host} is profile-dependent ({@code localhost} in dev/test, {@code 0.0.0.0} in prod), and
     * BootUI's Quarkus advisor only ever runs in dev/test {@code LaunchMode}, so a resolved read would never
     * observe the prod default it is trying to catch. Returns {@code null} when neither literal key is present.
     */
    private String managementHostPinnedForProd() {
        String prodScoped = literalPropertyValue("%prod.quarkus.management.host");
        if (prodScoped != null) {
            return prodScoped;
        }
        return literalPropertyValue("quarkus.management.host");
    }

    private String literalPropertyValue(String literalKey) {
        for (String name : config.getPropertyNames()) {
            if (name.equals(literalKey)) {
                return str(literalKey, null);
            }
        }
        return null;
    }

    private boolean jdbcClearPasswordMapperEnabled() {
        for (String name : config.getPropertyNames()) {
            if (name.contains("principal-query")
                    && name.endsWith("clear-password-mapper.enabled")
                    && bool(name, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks only the literal {@code quarkus.grpc.server.enable-reflection-service} or
     * {@code %prod.quarkus.grpc.server.enable-reflection-service} keys (not the profile-resolved value), so a
     * dev/test-scoped override doesn't trigger a false positive while the advisor itself runs in dev/test mode.
     */
    private boolean grpcReflectionEnabledInProdProfile() {
        for (String name : config.getPropertyNames()) {
            if ((name.equals("quarkus.grpc.server.enable-reflection-service")
                            || name.equals("%prod.quarkus.grpc.server.enable-reflection-service"))
                    && bool(name, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Real Quarkus has no {@code quarkus.smallrye-graphql.introspection-enabled} property; introspection is
     * disabled via the {@code no-introspection} token in the comma-separated
     * {@code quarkus.smallrye-graphql.field-visibility} list (see {@code SmallRyeGraphQLRuntimeConfig}).
     */
    private boolean graphqlIntrospectionEnabled() {
        String fieldVisibility = str("quarkus.smallrye-graphql.field-visibility", "default");
        for (String token : fieldVisibility.split(",")) {
            if ("no-introspection".equalsIgnoreCase(token.trim())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates each Kafka/Reactive-Messaging channel prefix (e.g. {@code mp.messaging.incoming.orders}, or the
     * bare {@code kafka} global-default bucket) independently, so one channel's secure protocol can't mask
     * another channel's insecure one. A channel with its own {@code security.protocol} uses that value; a
     * channel without one falls back to the global {@code kafka.security.protocol} (mirroring Kafka client
     * config inheritance), and the global bucket itself is checked as a channel too when it directly configures
     * credentials.
     */
    private List<String> messagingChannelsWithCredentialsWithoutTls() {
        Map<String, Boolean> credentialsByPrefix = new LinkedHashMap<>();
        Map<String, String> protocolByPrefix = new LinkedHashMap<>();
        for (String name : config.getPropertyNames()) {
            var credMatch = SASL_CREDENTIAL.matcher(name);
            if (credMatch.matches()) {
                if (!str(name, "").isBlank()) {
                    credentialsByPrefix.put(credMatch.group(1), Boolean.TRUE);
                }
                continue;
            }
            var protoMatch = SECURITY_PROTOCOL.matcher(name);
            if (protoMatch.matches()) {
                protocolByPrefix.put(protoMatch.group(1), str(name, "").toUpperCase());
            }
        }
        String globalProtocol = protocolByPrefix.get("kafka");
        List<String> insecureChannels = new ArrayList<>();
        for (String prefix : credentialsByPrefix.keySet()) {
            String effectiveProtocol =
                    protocolByPrefix.getOrDefault(prefix, "kafka".equals(prefix) ? null : globalProtocol);
            if (effectiveProtocol == null || !SECURE_KAFKA_PROTOCOLS.contains(effectiveProtocol)) {
                insecureChannels.add("kafka".equals(prefix) ? "kafka (global default)" : prefix);
            }
        }
        return insecureChannels;
    }

    private boolean formSessionTimeoutExcessive() {
        return config.getOptionalValue("quarkus.http.auth.form.timeout", Duration.class)
                .map(timeout -> timeout.toHours() >= 8)
                .orElse(false);
    }

    private List<QuarkusSecurityPermission> permissions() {
        Map<String, QuarkusSecurityPermission> byName = new LinkedHashMap<>();
        for (String name : config.getPropertyNames()) {
            var m = PERMISSION.matcher(name);
            if (m.matches()) {
                String key = m.group(1);
                String prefix = "quarkus.http.auth.permission." + key;
                if (!bool(prefix + ".enabled", true)) {
                    continue;
                }
                String policy = str(name, "permit");
                String paths = str(prefix + ".paths", null);
                if (paths == null || paths.isBlank()) {
                    continue;
                }
                String methods = str(prefix + ".methods", null);
                byName.put(key, new QuarkusSecurityPermission(key, paths, policy, methods));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<String> suspectedSecrets() {
        Set<String> out = new LinkedHashSet<>();
        for (ConfigSource source : config.getConfigSources()) {
            if (isExternalRuntimeSource(source.getName())) {
                continue;
            }
            for (String name : source.getPropertyNames()) {
                if (name.startsWith("bootui.")
                        || name.startsWith("%dev.")
                        || name.startsWith("%test.")
                        || !SECRET_NAME.matcher(name).matches()) {
                    continue;
                }
                String rawValue = source.getValue(name);
                if (rawValue != null && !rawValue.isBlank() && !rawValue.contains("${")) {
                    out.add(name);
                }
            }
        }
        return out.stream().sorted().toList();
    }

    private static boolean isExternalRuntimeSource(String sourceName) {
        if (sourceName == null) {
            return false;
        }
        String normalized = sourceName.toLowerCase(Locale.ROOT);
        return normalized.contains("envconfigsource")
                || normalized.contains("syspropconfigsource")
                || normalized.contains("system properties");
    }

    private String effectiveInsecureRequests(String clientAuth) {
        if (isConfigured("quarkus.http.insecure-requests")) {
            return str("quarkus.http.insecure-requests", "enabled").toLowerCase();
        }
        return "required".equalsIgnoreCase(clientAuth) ? "disabled" : "enabled";
    }

    private boolean implicitBasicAuth(boolean oidc, boolean jwt, boolean form, boolean mtls, boolean embeddedUsers) {
        if (oidc || jwt || form || mtls) {
            return false;
        }
        if (embeddedUsers || bool("quarkus.security.users.file.enabled", false)) {
            return true;
        }
        return bool("quarkus.security.jdbc.enabled", false);
    }

    private boolean prodAwareBoolean(String key) {
        return bool(key, false) || "true".equalsIgnoreCase(literalPropertyValue("%prod." + key));
    }

    private boolean prodAwareValuePresent(String key) {
        return has(key) || nonBlank(literalPropertyValue("%prod." + key));
    }

    private boolean isConfigured(String key) {
        ConfigValue value = config.getConfigValue(key);
        return value != null && value.getRawValue() != null;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeRootPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean nonApplicationRootPathMerged(String httpRootPath, String nonApplicationRootPath) {
        String root = normalizeRootPath(httpRootPath);
        String effectiveNonApplicationRoot = nonApplicationRootPath.startsWith("/")
                ? normalizeRootPath(nonApplicationRootPath)
                : normalizeRootPath(("/".equals(root) ? "" : root) + "/" + nonApplicationRootPath);
        return root.equals(effectiveNonApplicationRoot);
    }

    private boolean has(String key) {
        return config.getOptionalValue(key, String.class)
                .filter(v -> !v.isBlank())
                .isPresent();
    }

    private boolean bool(String key, boolean def) {
        return config.getOptionalValue(key, Boolean.class).orElse(def);
    }

    private String str(String key, String def) {
        return config.getOptionalValue(key, String.class).orElse(def);
    }

    private int count(String key) {
        return config.getOptionalValue(key, Integer.class).orElse(0);
    }
}
