package io.github.jdubois.bootui.quarkus.security;

import io.github.jdubois.bootui.engine.security.CspPolicy;
import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.github.jdubois.bootui.spi.QuarkusSecurityEvidence;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshotProvider;
import io.smallrye.config.SmallRyeConfig;
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
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Quarkus adapter that reads the live {@code quarkus.http.*} / {@code quarkus.oidc.*} security config from
 * MicroProfile {@link Config} (plus build-time annotation counts produced by the deployment processor) into a
 * neutral {@link QuarkusSecuritySnapshot}. Unsupported observations remain incomplete; conversion failures
 * have value-free error categories. No policy, tenant resolver or credential provider is executed.
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
    private static final Pattern NAMED_OIDC_TENANT = Pattern.compile(
            "^quarkus\\.oidc\\.([^.]+)\\.(?:auth-server-url|public-key|provider|certificate-chain\\..+)$");
    private static final Set<String> SECURE_KAFKA_PROTOCOLS = Set.of("SASL_SSL", "SSL");
    private final Config config;
    private static final int LIMIT = 4096;
    private static final int VALUE_LIMIT = 8192;
    private final Set<String> names = new LinkedHashSet<>();
    private final List<ConfigSource> localSources = new ArrayList<>();
    private final List<ConfigSource> credentialMetadataSources = new ArrayList<>();
    private boolean credentialMetadataComplete = true;
    private List<String> activeProfiles = List.of();
    private final Set<String> unknownRules = new LinkedHashSet<>();
    private final Set<String> incomplete = new LinkedHashSet<>();
    private final Set<String> failures = new LinkedHashSet<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<String> read = new LinkedHashSet<>();
    private final Set<String> failedKeys = new LinkedHashSet<>();

    public QuarkusSecuritySnapshotProviderImpl(Config config) {
        this.config = config;
    }

    @Override
    public QuarkusSecuritySnapshot snapshot() {
        return new QuarkusSecuritySnapshotProviderImpl(config).collect();
    }

    private QuarkusSecuritySnapshot collect() {
        inventory();
        List<QuarkusSecurityEndpoint> endpoints = endpoints();
        boolean endpointMetadata = bool("bootui.internal.sec.endpoint-metadata", false);
        if (!endpointMetadata || bool("bootui.internal.sec.incomplete", false)) {
            unknown("REST endpoint metadata", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
        }
        if (!"/".equals(normalizeRootPath(str("quarkus.http.root-path", "/")))
                || !"/".equals(normalizeRootPath(str("quarkus.rest.path", "/")))) {
            unknown("non-default REST listener prefix", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
        }
        if (bool("bootui.internal.sec.custom-authorization", false)) {
            unknown("custom authorization declarations", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
        }
        if (bool("bootui.internal.sec.custom-identity", false)) {
            unknown(
                    "custom identity or tenant declarations",
                    "QS-AUTH-001",
                    "QS-AUTH-004",
                    "QS-AUTH-008",
                    "QS-OIDC-001",
                    "QS-OIDC-003");
        }
        if (bool("bootui.internal.sec.custom-tls", false)) {
            unknown("programmatic TLS configuration", "QS-TLS-002", "QS-HDR-003");
        }
        if (bool("bootui.internal.sec.custom-headers", false)) {
            unknown(
                    "custom response filter ordering",
                    "QS-HDR-002",
                    "QS-HDR-003",
                    "QS-HDR-004",
                    "QS-HDR-005",
                    "QS-HDR-006");
        }
        Set<String> oidcTenants = oidcTenantPrefixes();
        boolean oidc = !oidcTenants.isEmpty();
        boolean jwt = capability("jwt")
                && (has("mp.jwt.verify.publickey")
                        || has("mp.jwt.verify.publickey.location")
                        || has("smallrye.jwt.verify.key.location"));
        boolean security = capability("security");
        boolean form = security && bool("quarkus.http.auth.form.enabled", false);
        String clientAuth = str("quarkus.http.ssl.client-auth", "none");
        boolean mtls = "required".equalsIgnoreCase(clientAuth) || "request".equalsIgnoreCase(clientAuth);
        boolean embeddedUsers = capability("properties") && bool("quarkus.security.users.embedded.enabled", false);
        boolean basic = security
                && (isConfigured("quarkus.http.auth.basic")
                        ? bool("quarkus.http.auth.basic", false)
                        : implicitBasicAuth(oidc, jwt, form, mtls, embeddedUsers));
        String insecure = effectiveInsecureRequests(clientAuth);
        boolean ssl = httpServerTlsConfigured();
        boolean cors = declared("quarkus.http.cors.enabled")
                ? bool("quarkus.http.cors.enabled", false)
                : declared("quarkus.http.cors")
                        ? bool("quarkus.http.cors", false)
                        : bool("quarkus.http.cors.enabled", false);
        String corsOrigins = csv("quarkus.http.cors.origins", null);
        if (cors && corsOrigins != null) {
            for (String origin : corsOrigins.split(",")) {
                String value = origin.trim();
                if (value.startsWith("/") && !Set.of("/.*/", "/^.*$/").contains(value)) {
                    unknown("custom CORS regex is not interpreted");
                }
            }
        }
        boolean corsCreds = corsCredentials(corsOrigins);
        String hstsValue = header("Strict-Transport-Security", "QS-HDR-001", "QS-HDR-003");
        String cspValue = header("Content-Security-Policy", "QS-HDR-002", "QS-HDR-004", "QS-HDR-005");
        boolean hsts = nonBlank(hstsValue);
        boolean csp = nonBlank(cspValue);
        if (csp && !CspPolicy.analyze(cspValue).complete()) {
            unknown("unsupported CSP composition", "QS-HDR-002", "QS-HDR-005");
        }
        if (!endpoints.stream().anyMatch(QuarkusSecurityEndpoint::document)) {
            unknown("document applicability is not established", "QS-HDR-003", "QS-HDR-004", "QS-HDR-005");
        }
        boolean oidcVerifyNone = oidcTenants.stream()
                .anyMatch(prefix -> !has(prefix + ".tls.tls-configuration-name")
                        && "none".equalsIgnoreCase(str(prefix + ".tls.verification", "")));
        boolean swagger = capability("openapi") && prodAwareBoolean("quarkus.swagger-ui.always-include");
        boolean csrfExtensionPresent = bool(CSRF_KEY, false);
        if (form && !isConfigured(CSRF_KEY)) {
            unknown("CSRF extension metadata unavailable", "QS-AUTH-003");
        }
        boolean csrf = csrfExtensionPresent
                && bool("quarkus.rest-csrf.enabled", true)
                && bool("quarkus.rest-csrf.verify-token", true);

        boolean behindProxy = bool("quarkus.http.proxy.proxy-address-forwarding", false);
        boolean jwtIssuer = has("mp.jwt.verify.issuer");
        boolean proactiveAuthDisabled = !bool("quarkus.http.auth.proactive", true);
        boolean oidcServiceTokenConsumer = oidcTenants.stream().anyMatch(this::isServiceOidcTenant);
        boolean oidcAudience = !oidcServiceTokenConsumer
                || oidcTenants.stream().filter(this::isServiceOidcTenant).allMatch(this::oidcAudience);
        boolean knownMissingAudience = false;
        for (String prefix : oidcTenants) {
            String audienceKey = prefix + ".token.audience";
            if (isServiceOidcTenant(prefix)) {
                boolean validates = oidcAudience(prefix);
                knownMissingAudience |= !validates && !failedKeys.contains(audienceKey);
            }
        }
        if (knownMissingAudience && !bool("bootui.internal.sec.custom-identity", false)) {
            unknownRules.remove("QS-OIDC-001");
        }
        boolean oidcWebApp = oidcTenants.stream().anyMatch(this::isWebOidcTenant);
        String oidcAppType = oidcWebApp
                ? (oidcServiceTokenConsumer ? "hybrid" : "web-app")
                : (oidcServiceTokenConsumer ? "service" : "");
        boolean oidcCookieForceSecure = !oidcWebApp
                || oidcTenants.stream()
                        .filter(this::isWebOidcTenant)
                        .allMatch(prefix -> bool(prefix + ".authentication.cookie-force-secure", false));
        boolean tlsTrustAll = bool("quarkus.tls.trust-all", false) || namedTlsBucketTrustAll();
        String corsMethods = csv("quarkus.http.cors.methods", null);
        String corsHeaders = csv("quarkus.http.cors.headers", null);
        String frameValue = header("X-Frame-Options", "QS-HDR-005");
        boolean xFrame = "DENY".equalsIgnoreCase(frameValue) || "SAMEORIGIN".equalsIgnoreCase(frameValue);
        boolean xContentType = "nosniff".equalsIgnoreCase(header("X-Content-Type-Options", "QS-HDR-006"));
        boolean denyUnannotated = bool("quarkus.security.jaxrs.deny-unannotated-endpoints", false);
        boolean defaultRolesAllowed = nonBlank(csv("quarkus.security.jaxrs.default-roles-allowed", null));
        boolean managementEnabled = bool("quarkus.management.enabled", false);
        String managementHostPin = str("quarkus.management.host", null);
        boolean managementHostNonLoopback =
                managementEnabled && managementHostPin != null && !isLoopbackHost(managementHostPin);
        boolean managementHostUnpinnedForProd =
                prodAwareBoolean("quarkus.management.enabled") && managementHostPinnedForProd() == null;

        String jwksLocation = effectiveJwtLocation();
        boolean jwksLocationRemote = jwksLocation != null
                && (jwksLocation.toLowerCase().startsWith("http://")
                        || jwksLocation.toLowerCase().startsWith("https://"));
        boolean jwtAlgorithmUnpinnedForRemoteJwks = jwksLocationRemote && !has("mp.jwt.verify.publickey.algorithm");
        boolean jdbcClearPasswordMapper = jdbcClearPasswordMapperEnabled();
        boolean jwtAudiences = nonBlank(csv("mp.jwt.verify.audiences", null));
        boolean jwtInlineKey = has("mp.jwt.verify.publickey") && !nonBlank(effectiveJwtLocation());
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
        boolean graphqlUiAlwaysInclude =
                graphqlPresent && prodAwareBoolean("quarkus.smallrye-graphql.ui.always-include");
        List<String> insecureMessagingChannels = messagingChannelsWithCredentialsWithoutTls();
        boolean formCookieHttpOnly = bool("quarkus.http.auth.form.http-only-cookie", false);
        String sameSite = str("quarkus.http.auth.form.cookie-same-site", "strict");
        if (!Set.of("strict", "lax", "none").contains(sameSite.toLowerCase(Locale.ROOT))) {
            failed("quarkus.http.auth.form.cookie-same-site");
        }
        boolean formCookieSameSiteNone = "none".equalsIgnoreCase(sameSite);
        boolean formSessionTimeoutExcessive = formSessionTimeoutExcessive();
        boolean oidcHasClientSecret = !oidcWebApp
                || oidcTenants.stream().filter(this::isWebOidcTenant).allMatch(this::oidcTenantHasClientSecret);
        boolean oidcPkceRequired = !oidcWebApp
                || oidcTenants.stream()
                        .filter(this::isWebOidcTenant)
                        .allMatch(prefix -> oidcTenantHasClientSecret(prefix) || oidcPkce(prefix));
        for (String prefix : oidcTenants) {
            if (isWebOidcTenant(prefix)
                    && !oidcTenantHasClientSecret(prefix)
                    && !oidcPkce(prefix)
                    && !credentialMetadataComplete) {
                unknown("client authentication metadata is incomplete", "QS-OIDC-003");
            }
        }
        boolean healthUiAlwaysInclude =
                capability("health") && prodAwareBoolean("quarkus.smallrye-health.ui.always-include");
        boolean insecureIdentityProviderUrl =
                oidcTenants.stream().anyMatch(prefix -> isHttpUrl(str(prefix + ".auth-server-url", null)))
                        || (jwt && isHttpUrl(effectiveJwtLocation()));
        boolean oidcIssuerAny = oidcTenants.stream().anyMatch(this::oidcIssuerAny);
        boolean embeddedUsersPlainText = bool("quarkus.security.users.embedded.plain-text", false);
        List<String> tlsHostnameVerificationDisabled = tlsHostnameVerificationDisabled(oidcTenants);

        List<QuarkusSecurityPermission> permissions = permissions();
        List<String> secrets = suspectedSecrets();
        if (tlsTrustAll) {
            unknownRules.remove("QS-TLS-003");
        }
        if (oidcIssuerAny) {
            unknownRules.remove("QS-OIDC-004");
        }
        if (insecureIdentityProviderUrl) {
            unknownRules.remove("QS-TLS-004");
        }
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
                permissions,
                count(ROLES_KEY),
                count(PERMIT_KEY),
                count(DENY_KEY),
                count(AUTH_KEY),
                count(ENDPOINTS_KEY),
                count(SECURED_KEY),
                secrets,
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
                defaultRolesAllowed,
                new QuarkusSecurityEvidence(
                        unknownRules, List.copyOf(incomplete), List.copyOf(failures), endpoints, endpointMetadata));
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (!h.matches("127(?:\\.\\d{1,3}){3}")) {
            return false;
        }
        return java.util.Arrays.stream(h.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
    }

    private static boolean isHttpUrl(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("http://");
    }

    private Set<String> oidcTenantPrefixes() {
        Set<String> prefixes = new java.util.LinkedHashSet<>();
        if (!capability("oidc") || !bool("quarkus.oidc.enabled", true)) {
            return prefixes;
        }
        for (String name : names) {
            String prefix = oidcTenantPrefix(name);
            if (prefix != null && has(name) && bool(prefix + ".tenant-enabled", true)) {
                prefixes.add(prefix);
                if (prefixes.size() >= 64) {
                    unknown("OIDC tenant inventory limit", "QS-AUTH-001");
                    break;
                }
            }
        }
        return prefixes;
    }

    private static String oidcTenantPrefix(String name) {
        if (name.equals("quarkus.oidc.auth-server-url")
                || name.equals("quarkus.oidc.public-key")
                || name.equals("quarkus.oidc.provider")
                || name.startsWith("quarkus.oidc.certificate-chain.")) {
            return "quarkus.oidc";
        }
        var matcher = NAMED_OIDC_TENANT.matcher(name);
        return matcher.matches() ? "quarkus.oidc." + matcher.group(1) : null;
    }

    private boolean isServiceOidcTenant(String prefix) {
        String applicationType = oidcType(prefix);
        return "service".equalsIgnoreCase(applicationType) || "hybrid".equalsIgnoreCase(applicationType);
    }

    private boolean isWebOidcTenant(String prefix) {
        String applicationType = oidcType(prefix);
        return "web-app".equalsIgnoreCase(applicationType) || "hybrid".equalsIgnoreCase(applicationType);
    }

    private boolean oidcTenantHasClientSecret(String prefix) {
        return declared(prefix + ".credentials.secret")
                || declared(prefix + ".credentials.client-secret.value")
                || declared(prefix + ".credentials.client-secret.provider.key")
                || declared(prefix + ".credentials.client-secret.provider.name")
                || declared(prefix + ".credentials.jwt.secret")
                || declared(prefix + ".credentials.jwt.secret-provider.key")
                || declared(prefix + ".credentials.jwt.key")
                || declared(prefix + ".credentials.jwt.key-file")
                || declared(prefix + ".credentials.jwt.key-store-file")
                || declared(prefix + ".credentials.jwt.source")
                || "apple".equals(str(prefix + ".provider", ""));
    }

    private boolean httpServerTlsConfigured() {
        if (has("quarkus.http.ssl.certificate.key-store-file")) {
            return true;
        }
        boolean certificates = nonBlank(csv("quarkus.http.ssl.certificate.files", null));
        boolean keys = nonBlank(csv("quarkus.http.ssl.certificate.key-files", null));
        if (certificates && keys) {
            return true;
        }
        if (certificates != keys) {
            unknown("incomplete listener TLS material", "QS-TLS-002", "QS-HDR-003");
        }
        String selectedBucket = str("quarkus.http.tls-configuration-name", null);
        String keyStorePrefix = selectedBucket == null || selectedBucket.isBlank()
                ? "quarkus.tls.key-store."
                : "quarkus.tls." + selectedBucket + ".key-store.";
        return tlsBucketHasKeyStore(keyStorePrefix);
    }

    private boolean tlsBucketHasKeyStore(String keyStorePrefix) {
        if (has(keyStorePrefix + "p12.path") || has(keyStorePrefix + "jks.path")) {
            return true;
        }
        boolean incompleteMaterial = false;
        for (String name : names) {
            if (!name.startsWith(keyStorePrefix)) {
                continue;
            }
            String suffix = name.substring(keyStorePrefix.length());
            if (!suffix.startsWith("pem.") || (!suffix.endsWith(".cert") && !suffix.endsWith(".key"))) {
                continue;
            }
            if (!has(name)) {
                continue;
            }
            String counterpart = suffix.endsWith(".cert")
                    ? name.substring(0, name.length() - 5) + ".key"
                    : name.substring(0, name.length() - 4) + ".cert";
            if (has(counterpart)) {
                return true;
            }
            incompleteMaterial = true;
        }
        if (incompleteMaterial) {
            unknown("incomplete TLS registry material", "QS-TLS-002", "QS-HDR-003");
        }
        return false;
    }

    /** Raw-scans for any named TLS registry bucket ({@code quarkus.tls.<name>.trust-all}) set to {@code true}. */
    private boolean namedTlsBucketTrustAll() {
        for (String name : names) {
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
        for (String name : names) {
            var matcher = NAMED_TLS_HOSTNAME_VERIFICATION.matcher(name);
            if (matcher.matches() && "none".equalsIgnoreCase(str(name, ""))) {
                disabled.add("named TLS registry declaration");
            }
        }
        for (String prefix : oidcTenants) {
            if (!has(prefix + ".tls.tls-configuration-name")
                    && "certificate-validation".equalsIgnoreCase(str(prefix + ".tls.verification", ""))) {
                disabled.add("OIDC tenant declaration");
            }
        }
        return disabled.stream().sorted().toList();
    }

    /**
     * Mirrors Quarkus's real {@code CORSFilter} default: {@code accessControlAllowCredentials().orElse(originMatches)}.
     * If the property is explicitly set, that value wins; otherwise credentials are implicitly allowed whenever
     * the request Origin matches a configured literal or regex. A sole literal wildcard differs: it does not
     * establish {@code originMatches}, so credentials then default false.
     */
    private boolean corsCredentials(String corsOrigins) {
        if (has("quarkus.http.cors.access-control-allow-credentials")) {
            return bool("quarkus.http.cors.access-control-allow-credentials", false);
        }
        if (corsOrigins == null || corsOrigins.isBlank() || "*".equals(corsOrigins.trim())) {
            return false;
        }
        // Regex origin matches, like exact matches, default credentials to true. The response reflects Origin.
        return true;
    }

    /**
     * Checks only the literal {@code quarkus.management.host} or {@code %prod.quarkus.management.host} keys (not
     * the profile-resolved value), mirroring {@link #grpcReflectionEnabledInProdProfile()}. Quarkus's own built-in
     * default for {@code host} is profile-dependent ({@code localhost} in dev/test, {@code 0.0.0.0} in prod), and
     * a local declaration is not an observed production deployment. Launch mode and profile are independent.
     * Returns {@code null} when neither literal key is present.
     */
    private String managementHostPinnedForProd() {
        String prodScoped = literalPropertyValue("%prod.quarkus.management.host");
        if (prodScoped != null) {
            return prodScoped;
        }
        return literalPropertyValue("quarkus.management.host");
    }

    private String literalPropertyValue(String literalKey) {
        String value = null;
        int ordinal = Integer.MIN_VALUE;
        for (ConfigSource source : localSources) {
            try {
                String candidate = source.getValue(literalKey);
                if (candidate != null && source.getOrdinal() > ordinal) {
                    value = candidate;
                    ordinal = source.getOrdinal();
                }
            } catch (RuntimeException ex) {
                failed(literalKey);
            }
        }
        if (value != null && (value.contains("${") || value.length() > VALUE_LIMIT)) {
            unknown("unresolved production declaration", rules(literalKey));
            return null;
        }
        return value;
    }

    private boolean jdbcClearPasswordMapperEnabled() {
        if (!capability("jdbc") || !bool("quarkus.security.jdbc.enabled", false)) {
            return false;
        }
        for (String name : names) {
            if (name.startsWith("quarkus.security.jdbc.principal-query.")
                    && name.endsWith(".clear-password-mapper.enabled")
                    && !name.matches(
                            "quarkus\\.security\\.jdbc\\.principal-query\\.(?:[^.]+\\.)?clear-password-mapper\\.enabled")) {
                unknown("unsupported named JDBC query");
            }
            if (name.matches(
                            "quarkus\\.security\\.jdbc\\.principal-query\\.(?:[^.]+\\.)?clear-password-mapper\\.enabled")
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
        return bool("bootui.internal.sec.grpc-services", false)
                && prodAwareBoolean("quarkus.grpc.server.enable-reflection-service");
    }

    /**
     * Real Quarkus has no {@code quarkus.smallrye-graphql.introspection-enabled} property; introspection is
     * disabled via the {@code no-introspection} token in the comma-separated
     * {@code quarkus.smallrye-graphql.field-visibility} list (see {@code SmallRyeGraphQLRuntimeConfig}).
     */
    private boolean graphqlIntrospectionEnabled() {
        String fieldVisibility = productionValue("quarkus.smallrye-graphql.field-visibility", "default");
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
        List<String> insecureChannels = new ArrayList<>();
        if (!capability("kafka")) {
            return insecureChannels;
        }
        Set<String> channels = new LinkedHashSet<>();
        for (String name : names) {
            var match = Pattern.compile("^(mp\\.messaging\\.(?:incoming|outgoing)\\.[^.]+)\\..+$")
                    .matcher(name);
            if (match.matches()) {
                channels.add(match.group(1));
            }
        }
        for (String channel : channels) {
            if (insecureChannels.size() >= 128) {
                unknown("messaging result inventory limit");
                break;
            }
            if (!bool(channel + ".enabled", true)) {
                continue;
            }
            String connector = str(channel + ".connector", null);
            if (connector == null) {
                unknown("implicit messaging connector");
                continue;
            }
            if (!"smallrye-kafka".equals(connector)) {
                continue;
            }
            if (has(channel + ".kafka-configuration")) {
                unknown("custom Kafka configuration map");
                continue;
            }
            String connectorPrefix = "mp.messaging.connector.smallrye-kafka";
            boolean credentials = false;
            for (String suffix : List.of(".sasl.jaas.config", ".sasl.password")) {
                credentials |=
                        declared(channel + suffix) || declared(connectorPrefix + suffix) || declared("kafka" + suffix);
            }
            String protocol = str(
                    channel + ".security.protocol",
                    str(connectorPrefix + ".security.protocol", str("kafka.security.protocol", "PLAINTEXT")));
            if (credentials && ("PLAINTEXT".equals(protocol) || "SASL_PLAINTEXT".equals(protocol))) {
                insecureChannels.add("Kafka channel declaration (" + protocol + ")");
            } else if (!SECURE_KAFKA_PROTOCOLS.contains(protocol)
                    && !"PLAINTEXT".equals(protocol)
                    && !"SASL_PLAINTEXT".equals(protocol)) {
                unknown("unsupported Kafka protocol");
            }
        }
        return insecureChannels;
    }

    private boolean formSessionTimeoutExcessive() {
        String key = "quarkus.http.auth.form.timeout";
        String value = str(key, null);
        if (value == null) {
            return false;
        }
        try {
            Duration duration = value.matches("\\d+[dD]")
                    ? Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1)))
                    : value.matches("(?i)(?=.+)(?:\\d+h)?(?:\\d+m)?(?:\\d+(?:\\.\\d+)?s)?")
                            ? Duration.parse("PT" + value.toUpperCase(Locale.ROOT))
                            : value.matches("\\d+") ? Duration.ofSeconds(Long.parseLong(value)) : Duration.parse(value);
            if (duration.isNegative()) {
                throw new IllegalArgumentException();
            }
            return duration.compareTo(Duration.ofHours(8)) >= 0;
        } catch (RuntimeException ex) {
            failed(key);
            return false;
        }
    }

    private List<QuarkusSecurityPermission> permissions() {
        Map<String, QuarkusSecurityPermission> byName = new LinkedHashMap<>();
        for (String name : names) {
            var m = PERMISSION.matcher(name);
            if (name.startsWith("quarkus.http.auth.permission.") && name.endsWith(".policy") && !m.matches()) {
                unknown("unsupported named HTTP permission", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
            }
            if (m.matches()) {
                if (byName.size() >= 128) {
                    unknown("HTTP permission inventory limit", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
                    break;
                }
                String key = m.group(1);
                String prefix = "quarkus.http.auth.permission." + key;
                if (!bool(prefix + ".enabled", true)) {
                    continue;
                }
                String policy = str(name, "permit");
                String paths = csv(prefix + ".paths", null);
                if (paths == null || paths.isBlank()) {
                    continue;
                }
                String methods = csv(prefix + ".methods", null);
                boolean known = Set.of("permit", "deny", "authenticated").contains(policy)
                        || nonBlank(csv("quarkus.http.auth.policy." + policy + ".roles-allowed", null));
                if (!known) {
                    unknown("custom HTTP policy", "QS-AUTH-001", "QS-AUTHZ-001");
                }
                String root = str("quarkus.http.root-path", "/");
                paths = java.util.Arrays.stream(paths.split(","))
                        .map(String::trim)
                        .map(path -> path.startsWith("/") ? path : normalizeRootPath(root) + "/" + path)
                        .map(path -> path.replace("//", "/"))
                        .collect(java.util.stream.Collectors.joining(","));
                String scope = str(prefix + ".applies-to", "all").toLowerCase(Locale.ROOT);
                if (!Set.of("all", "jaxrs").contains(scope)) {
                    unknown(
                            "unsupported HTTP permission scope",
                            "QS-AUTH-001",
                            "QS-AUTHZ-001",
                            "QS-AUTHZ-002",
                            "QS-AUTHZ-004");
                }
                byName.put(
                        key,
                        new QuarkusSecurityPermission(
                                "permission declaration",
                                paths,
                                policy,
                                methods,
                                bool(prefix + ".shared", false),
                                scope,
                                known));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<String> suspectedSecrets() {
        Set<String> out = new LinkedHashSet<>();
        for (ConfigSource source : localSources) {
            if (!source.getName().contains("application.properties")
                    && !source.getName().contains("application.yaml")
                    && !source.getName().contains("application.yml")) {
                continue;
            }
            for (String name : names) {
                if (name.startsWith("bootui.")
                        || name.startsWith("%dev.")
                        || name.startsWith("%test.")
                        || !SECRET_NAME.matcher(name).matches()) {
                    continue;
                }
                String rawValue;
                try {
                    rawValue = source.getValue(name);
                } catch (RuntimeException ex) {
                    failed("credential hygiene");
                    continue;
                }
                if (rawValue != null && rawValue.length() > VALUE_LIMIT) {
                    unknown("credential value observation limit");
                } else if (rawValue != null && !rawValue.isBlank() && !rawValue.contains("${")) {
                    if (safeLabel(name)) {
                        out.add(name);
                    } else {
                        out.add("local credential declaration (label omitted)");
                    }
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
            String value = str("quarkus.http.insecure-requests", "").toLowerCase(Locale.ROOT);
            if (!Set.of("enabled", "disabled", "redirect").contains(value)) {
                failed("quarkus.http.insecure-requests");
                return "";
            }
            return value;
        }
        if (!Set.of("none", "request", "required").contains(clientAuth.toLowerCase(Locale.ROOT))) {
            failed("quarkus.http.ssl.client-auth");
            return "";
        }
        return "required".equalsIgnoreCase(clientAuth) ? "disabled" : "enabled";
    }

    private boolean implicitBasicAuth(boolean oidc, boolean jwt, boolean form, boolean mtls, boolean embeddedUsers) {
        if (oidc || jwt || form || mtls) {
            return false;
        }
        if (embeddedUsers || (capability("properties") && bool("quarkus.security.users.file.enabled", false))) {
            return true;
        }
        return capability("jdbc") && bool("quarkus.security.jdbc.enabled", false);
    }

    private boolean prodAwareBoolean(String key) {
        String value = productionValue(key, "false");
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            failed(key);
            return false;
        }
        return Boolean.parseBoolean(value);
    }

    private boolean isConfigured(String key) {
        return str(key, null) != null;
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
        return nonBlank(str(key, null));
    }

    private boolean bool(String key, boolean def) {
        String value = str(key, null);
        if (value == null) {
            return def;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            failed(key);
            return def;
        }
        return Boolean.parseBoolean(value);
    }

    private String str(String key, String def) {
        if (read.add(key)) {
            try {
                String value = config.getOptionalValue(key, String.class).orElse(null);
                if ("quarkus.http.non-application-root-path".equals(key) && "${quarkus.http.root-path}".equals(value)) {
                    value = str("quarkus.http.root-path", "/");
                }
                if (value != null && (value.length() > VALUE_LIMIT || value.contains("${"))) {
                    failedKeys.add(key);
                    unknown("unresolved or oversized configuration", rules(key));
                } else if (value != null) {
                    values.put(key, value.trim());
                }
            } catch (RuntimeException | LinkageError ex) {
                failed(key);
            }
        }
        return values.getOrDefault(key, def);
    }

    private int count(String key) {
        try {
            int value = Integer.parseInt(str(key, "0"));
            int limit = ENDPOINTS_KEY.equals(key) ? 256 : LIMIT;
            if (value < 0 || value > limit) {
                unknown("bounded endpoint inventory", rules(key));
                return Math.max(0, Math.min(value, limit));
            }
            return value;
        } catch (RuntimeException ex) {
            failed(key);
            return 0;
        }
    }

    private void inventory() {
        if (config instanceof SmallRyeConfig smallRye) {
            activeProfiles = smallRye.getProfiles();
            if (activeProfiles.size() > 1) {
                credentialMetadataComplete = false;
                unknown("multi-profile credential metadata is not inferred");
            }
        } else {
            credentialMetadataComplete = false;
            unknown("active profile metadata unavailable");
        }
        int sources = 0;
        try {
            for (ConfigSource source : config.getConfigSources()) {
                if (++sources > 128) {
                    credentialMetadataComplete = false;
                    unknown("configuration source limit", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
                    break;
                }
                String type = source.getClass().getName();
                boolean supported = type.equals("io.smallrye.config.PropertiesConfigSource")
                        || type.equals("io.smallrye.config.source.yaml.YamlConfigSource");
                boolean local = supported && localApplicationSource(source.getName());
                boolean nativeEnvironment = type.equals("io.smallrye.config.EnvConfigSource");
                boolean nativeRuntime = nativeEnvironment || type.equals("io.smallrye.config.SysPropConfigSource");
                if (local || nativeRuntime) {
                    credentialMetadataSources.add(source);
                }
                if (!local && !nativeRuntime) {
                    // Custom sources are not enumerated. Native environment/system names are metadata, not hygiene
                    // inputs.
                    // These native bootstrap bridges expose runtime/test URLs, not a declarative configuration
                    // inventory.
                    boolean bootstrap = type.equals("io.quarkus.runtime.ValueRegistryConfigSource")
                            || type.equals(
                                    "io.quarkus.test.common.http.TestHTTPConfigSourceProvider$TestURLConfigSource");
                    if (!bootstrap
                            && ((supported && !isExternalRuntimeSource(source.getName()))
                                    || (!type.startsWith("io.smallrye.config.")
                                            && !type.startsWith("io.quarkus.runtime.configuration.")))) {
                        unknown(
                                "custom configuration source inventory",
                                "QS-DEV-002",
                                "QS-DEV-003",
                                "QS-GRPC-001",
                                "QS-GRAPHQL-001",
                                "QS-MGMT-003",
                                "QS-AUTH-001",
                                "QS-AUTHZ-001",
                                "QS-AUTHZ-004",
                                "QS-TLS-002",
                                "QS-HDR-003");
                        credentialMetadataComplete = false;
                    }
                    continue;
                }
                if (local) {
                    localSources.add(source);
                }
                int visited = 0;
                for (String name : source.getPropertyNames()) {
                    if (++visited > LIMIT || names.size() >= LIMIT) {
                        unknown("configuration key limit", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
                        break;
                    }
                    if (name != null && name.length() <= 512) {
                        // Resolved reads use the active profile, never infer launch mode from its name.
                        if (name.startsWith("%") && name.indexOf('.') > 0) {
                            inventoryName(name.substring(name.indexOf('.') + 1));
                        }
                        inventoryName(name);
                        if (nativeEnvironment) {
                            // SmallRye exposes dotted aliases for environment names; canonicalize known OIDC suffixes.
                            inventoryName(name.replaceFirst("\\.auth\\.server\\.url$", ".auth-server-url")
                                    .replaceFirst("\\.public\\.key$", ".public-key")
                                    .replaceFirst("\\.certificate\\.chain\\.", ".certificate-chain."));
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            failed("inventory");
        }
    }

    private void inventoryName(String name) {
        if (names.size() >= LIMIT && !names.contains(name)) {
            unknown("configuration key limit", "QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
        } else {
            names.add(name);
        }
    }

    private boolean capability(String capability) {
        String key = "bootui.internal.sec." + capability + "-present";
        if (!isConfigured(key)) {
            unknown("capability metadata unavailable", "QS-AUTH-001");
            return false;
        }
        return bool(key, false);
    }

    private List<QuarkusSecurityEndpoint> endpoints() {
        List<QuarkusSecurityEndpoint> endpoints = new ArrayList<>();
        int total = count(ENDPOINTS_KEY);
        for (int i = 0; i < total; i++) {
            String prefix = "bootui.internal.sec.endpoint." + i;
            String path = str(prefix + ".path", null);
            String method = str(prefix + ".method", null);
            try {
                var access = QuarkusSecurityEndpoint.Access.valueOf(str(prefix + ".access", "UNKNOWN"));
                if (access == QuarkusSecurityEndpoint.Access.UNKNOWN) {
                    unknown("unknown endpoint authorization", "QS-AUTH-001", "QS-AUTHZ-001");
                }
                if (access == QuarkusSecurityEndpoint.Access.UNKNOWN
                        || path == null
                        || method == null
                        || path.contains("{")) {
                    unknown("unsupported REST endpoint declaration");
                }
                endpoints.add(new QuarkusSecurityEndpoint(path, method, access, bool(prefix + ".document", false)));
            } catch (RuntimeException ex) {
                failed(prefix);
            }
        }
        return endpoints;
    }

    private String header(String header, String... rules) {
        String result = null;
        int declarations = 0;
        Pattern pattern = Pattern.compile("^quarkus\\.http\\.header\\.\"?([^\".]+)\"?\\.value$");
        for (String name : names) {
            var matcher = pattern.matcher(name);
            if (!matcher.matches() || !header.equalsIgnoreCase(matcher.group(1))) {
                continue;
            }
            String prefix = name.substring(0, name.length() - ".value".length());
            String value = str(name, null);
            if (!nonBlank(value)) {
                continue;
            }
            String path = str(prefix + ".path", "/*");
            if (!"/*".equals(path) || nonBlank(csv(prefix + ".methods", null))) {
                unknown("path/method scoped response header", rules);
                continue;
            }
            if (++declarations > 1 || value.contains("\r") || value.contains("\n") || value.contains(",")) {
                unknown("multiple or invalid response header declarations", rules);
                continue;
            }
            result = value;
        }
        return result;
    }

    private String effectiveJwtLocation() {
        String override = str("smallrye.jwt.verify.key.location", null);
        return failedKeys.contains("smallrye.jwt.verify.key.location")
                ? null
                : override != null ? override : str("mp.jwt.verify.publickey.location", null);
    }

    private boolean oidcIssuerAny(String prefix) {
        String key = prefix + ".token.issuer";
        String issuer = str(key, "microsoft".equals(str(prefix + ".provider", "")) ? "any" : "");
        return !failedKeys.contains(key) && "any".equals(issuer);
    }

    private boolean oidcAudience(String prefix) {
        String audience = csv(prefix + ".token.audience", null);
        if (!nonBlank(audience)) {
            return false;
        }
        List<String> values = java.util.Arrays.stream(audience.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return !values.isEmpty() && !(values.size() == 1 && "any".equals(values.get(0)));
    }

    private String csv(String key, String fallback) {
        String scalar = str(key, null);
        if (scalar != null) {
            return scalar;
        }
        List<String> entries = new ArrayList<>();
        Pattern indexed = Pattern.compile(Pattern.quote(key) + "\\[\\d+\\]");
        for (String name : names) {
            if (!indexed.matcher(name).matches()) {
                continue;
            }
            if (entries.size() >= 128) {
                failedKeys.add(key);
                unknown("configuration list limit", rules(key));
                break;
            }
            String entry = str(name, null);
            if (entry != null) {
                entries.add(entry);
            } else {
                failedKeys.add(key);
                unknown("unreadable configuration list", rules(key));
            }
        }
        return entries.isEmpty() ? fallback : String.join(",", entries);
    }

    private String oidcType(String prefix) {
        String explicit = str(prefix + ".application-type", null);
        if (explicit != null) {
            if (!Set.of("service", "web-app", "hybrid").contains(explicit)) {
                failed(prefix + ".application-type");
            }
            return explicit;
        }
        // KnownOidcProviders builds web-app presets. Unknown presets are never executed here.
        String provider = str(prefix + ".provider", null);
        if (provider != null) {
            if (Set.of(
                            "apple",
                            "discord",
                            "facebook",
                            "github",
                            "google",
                            "microsoft",
                            "spotify",
                            "twitch",
                            "twitter",
                            "mastodon",
                            "slack",
                            "linkedin",
                            "strava",
                            "x")
                    .contains(provider)) {
                return "web-app";
            }
            unknown("unsupported OIDC provider preset", "QS-OIDC-001", "QS-OIDC-002", "QS-OIDC-003");
            return "";
        }
        return "service";
    }

    private boolean oidcPkce(String prefix) {
        String key = prefix + ".authentication.pkce-required";
        if (isConfigured(key)) {
            return bool(key, false);
        }
        return Set.of("spotify", "twitter", "x").contains(str(prefix + ".provider", ""));
    }

    private boolean declared(String key) {
        // Metadata-only credential presence: never resolve expressions or read credential providers.
        if (activeProfiles.size() == 1) {
            String scoped = credentialDeclaration("%" + activeProfiles.get(0) + "." + key);
            if (scoped != null) {
                return nonBlank(scoped);
            }
        }
        return nonBlank(credentialDeclaration(key));
    }

    private String credentialDeclaration(String key) {
        String value = null;
        int ordinal = Integer.MIN_VALUE;
        for (ConfigSource source : credentialMetadataSources) {
            try {
                String candidate = source.getValue(key);
                if (candidate != null && source.getOrdinal() > ordinal) {
                    if (candidate.length() > VALUE_LIMIT) {
                        credentialMetadataComplete = false;
                        unknown("credential metadata value limit");
                        return null;
                    }
                    value = candidate;
                    ordinal = source.getOrdinal();
                }
            } catch (RuntimeException ex) {
                failed("credential metadata");
            }
        }
        return value;
    }

    private String productionValue(String key, String fallback) {
        String value = literalPropertyValue("%prod." + key);
        return value == null ? java.util.Objects.requireNonNullElse(literalPropertyValue(key), fallback) : value;
    }

    private static boolean safeLabel(String label) {
        return label.length() <= 160 && label.matches("[%A-Za-z0-9_.-]+");
    }

    private static boolean localApplicationSource(String name) {
        if (name == null || isExternalRuntimeSource(name)) {
            return false;
        }
        int start = name.indexOf("[source=");
        String location = start < 0 ? name : name.substring(start + 8).replaceFirst("\\]$", "");
        boolean application = location.contains("application.properties")
                || location.contains("application.yaml")
                || location.contains("application.yml");
        return application
                && (location.startsWith("file:")
                        || location.startsWith("jar:file:")
                        || location.startsWith("classpath:")
                        || !location.contains(":"));
    }

    private void unknown(String category, String... rules) {
        incomplete.add(category);
        unknownRules.addAll(List.of(rules));
    }

    private void failed(String key) {
        failedKeys.add(key);
        String category = key.contains("cors")
                ? "CORS configuration"
                : key.contains("oidc")
                        ? "OIDC configuration"
                        : key.contains("jwt")
                                ? "JWT configuration"
                                : key.contains("header")
                                        ? "header configuration"
                                        : key.contains("timeout")
                                                ? "session timeout"
                                                : key.equals("inventory")
                                                        ? "configuration inventory"
                                                        : "security configuration";
        failures.add(category + " could not be read or converted");
        unknownRules.addAll(List.of(rules(key)));
    }

    private static String[] rules(String key) {
        if (key.contains("cors")) {
            return new String[] {"QS-CORS-001", "QS-CORS-002"};
        }
        if (key.contains("oidc")) {
            if (key.contains(".tls.")) {
                return new String[] {"QS-DEV-001", "QS-TLS-005"};
            }
            if (key.endsWith(".token.audience")) {
                return new String[] {"QS-OIDC-001"};
            }
            if (key.endsWith(".token.issuer")) {
                return new String[] {"QS-OIDC-004"};
            }
            if (key.contains("cookie-force-secure")) {
                return new String[] {"QS-OIDC-002"};
            }
            if (key.contains("pkce") || key.contains("credentials")) {
                return new String[] {"QS-OIDC-003"};
            }
            return new String[] {
                "QS-AUTH-001", "QS-OIDC-001", "QS-OIDC-002", "QS-OIDC-003", "QS-OIDC-004", "QS-DEV-001", "QS-TLS-004"
            };
        }
        if (key.contains("jwt")) {
            return new String[] {"QS-AUTH-001", "QS-AUTH-004", "QS-AUTH-008", "QS-AUTH-009", "QS-TLS-004"};
        }
        if (key.contains("header")) {
            return new String[] {"QS-HDR-001", "QS-HDR-002", "QS-HDR-003", "QS-HDR-004", "QS-HDR-005", "QS-HDR-006"};
        }
        if (key.contains("timeout")) {
            return new String[] {"QS-SESSION-003"};
        }
        if (key.contains("cookie")) {
            return new String[] {"QS-SESSION-001", "QS-SESSION-002"};
        }
        if (key.contains("csrf")) {
            return new String[] {"QS-AUTH-003"};
        }
        if (key.contains("insecure-requests") || key.contains("ssl") || key.contains("tls")) {
            return new String[] {
                "QS-TLS-001",
                "QS-TLS-002",
                "QS-TLS-003",
                "QS-TLS-005",
                "QS-AUTH-002",
                "QS-AUTH-012",
                "QS-OIDC-002",
                "QS-HDR-003"
            };
        }
        if (key.contains("management")) {
            return new String[] {"QS-MGMT-001", "QS-MGMT-003"};
        }
        if (key.contains("graphql")) {
            return new String[] {"QS-GRAPHQL-001", "QS-DEV-002"};
        }
        if (key.contains("grpc")) {
            return new String[] {"QS-GRPC-001"};
        }
        if (key.contains("always-include")) {
            return new String[] {"QS-DEV-002", "QS-DEV-003"};
        }
        if (key.contains("messaging") || key.contains("kafka")) {
            return new String[] {"QS-MSG-001"};
        }
        if (key.contains("jdbc")) {
            return new String[] {"QS-AUTH-010", "QS-AUTH-001"};
        }
        if (key.contains("embedded")) {
            return new String[] {"QS-AUTH-007", "QS-AUTH-013", "QS-AUTH-001"};
        }
        return new String[] {"QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004"};
    }
}
