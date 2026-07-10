package io.github.jdubois.bootui.quarkus.security;

import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshotProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;

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

    private static final Pattern PERMISSION =
            Pattern.compile("^quarkus\\.http\\.auth\\.permission\\.([^.]+)\\.policy$");
    private static final Pattern SECRET_NAME = Pattern.compile(
            ".*(password|passwd|secret|token|api-?key|client-secret|private-key).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_TLS_KEY_STORE = Pattern.compile("^quarkus\\.tls\\.[^.]+\\.key-store\\..+$");
    private static final Pattern NAMED_TLS_TRUST_ALL = Pattern.compile("^quarkus\\.tls\\.[^.]+\\.trust-all$");
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
        boolean basic = bool("quarkus.http.auth.basic", false);
        boolean form = bool("quarkus.http.auth.form.enabled", false);
        boolean mtls = "required".equalsIgnoreCase(str("quarkus.http.ssl.client-auth", ""));
        String insecure = str("quarkus.http.insecure-requests", "enabled").toLowerCase();
        boolean ssl = has("quarkus.http.ssl.certificate.key-store-file")
                || has("quarkus.http.ssl.certificate.files")
                || has("quarkus.http.ssl.certificate.key-files")
                || has("quarkus.tls.key-store.p12.path")
                || has("quarkus.tls.key-store.pem.0.cert")
                || namedTlsBucketHasKeyStore();
        boolean cors = bool("quarkus.http.cors", false) || bool("quarkus.http.cors.enabled", false);
        String corsOrigins = str("quarkus.http.cors.origins", null);
        boolean corsCreds = corsCredentials(corsOrigins);
        boolean hsts = has("quarkus.http.header.\"Strict-Transport-Security\".value");
        boolean csp = has("quarkus.http.header.\"Content-Security-Policy\".value");
        boolean oidcVerifyNone =
                oidcTenants.stream().anyMatch(prefix -> "none".equalsIgnoreCase(str(prefix + ".tls.verification", "")));
        boolean swagger = bool("quarkus.swagger-ui.always-include", false);
        boolean openapi = bool("quarkus.smallrye-openapi.always-include", false);
        boolean csrfExtensionPresent = bool(CSRF_KEY, false);
        boolean csrf = csrfExtensionPresent && bool("quarkus.rest-csrf.enabled", true);

        boolean behindProxy = bool("quarkus.http.proxy.proxy-address-forwarding", false)
                || bool("quarkus.http.proxy.allow-forwarded", false)
                || bool("quarkus.http.proxy.allow-x-forwarded", false);
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
        boolean denyUnannotated = bool("quarkus.security.jaxrs.deny-unannotated-endpoints", false);
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
        boolean embeddedUsers = bool("quarkus.security.users.embedded.enabled", false);
        boolean jwtAudiences = has("mp.jwt.verify.audiences");
        boolean jwtInlineKey = has("mp.jwt.verify.publickey");
        boolean referrerPolicy = has("quarkus.http.header.\"Referrer-Policy\".value");
        boolean permissionsPolicy = has("quarkus.http.header.\"Permissions-Policy\".value");
        String nonApplicationRootPath = str("quarkus.http.non-application-root-path", "/q");
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
                openapi,
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
                oidcServiceTokenConsumer);
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
            if (name.equals("quarkus.oidc.auth-server-url") && has(name) && bool("quarkus.oidc.tenant-enabled", true)) {
                prefixes.add("quarkus.oidc");
            } else if (name.startsWith("quarkus.oidc.") && name.endsWith(".auth-server-url") && has(name)) {
                String prefix = name.substring(0, name.length() - ".auth-server-url".length());
                if (bool(prefix + ".tenant-enabled", true)) {
                    prefixes.add(prefix);
                }
            }
        }
        return prefixes;
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

    /** Raw-scans for any named TLS registry bucket ({@code quarkus.tls.<name>.key-store.*}) configuring a keystore,
     * so a keystore pinned to a non-default bucket (e.g. for a REST client or gRPC) still counts as "TLS configured". */
    private boolean namedTlsBucketHasKeyStore() {
        for (String name : config.getPropertyNames()) {
            if (NAMED_TLS_KEY_STORE.matcher(name).matches()) {
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
                String policy = str(name, "permit");
                String paths = str("quarkus.http.auth.permission." + key + ".paths", "/*");
                String methods = str("quarkus.http.auth.permission." + key + ".methods", null);
                byName.put(key, new QuarkusSecurityPermission(key, paths, policy, methods));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<String> suspectedSecrets() {
        List<String> out = new ArrayList<>();
        for (String name : config.getPropertyNames()) {
            if (!name.startsWith("bootui.") && SECRET_NAME.matcher(name).matches()) {
                String value = str(name, "");
                if (!value.isBlank() && !value.contains("${")) {
                    out.add(name);
                }
            }
        }
        return out;
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
