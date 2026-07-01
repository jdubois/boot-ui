package io.github.jdubois.bootui.quarkus.security;

import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshotProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final Config config;

    public QuarkusSecuritySnapshotProviderImpl(Config config) {
        this.config = config;
    }

    @Override
    public QuarkusSecuritySnapshot snapshot() {
        boolean oidc = has("quarkus.oidc.auth-server-url");
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
                || has("quarkus.tls.key-store.pem.0.cert");
        boolean cors = bool("quarkus.http.cors", false) || bool("quarkus.http.cors.enabled", false);
        String corsOrigins = str("quarkus.http.cors.origins", null);
        boolean corsCreds = bool("quarkus.http.cors.access-control-allow-credentials", false);
        boolean hsts = has("quarkus.http.header.\"Strict-Transport-Security\".value");
        boolean csp = has("quarkus.http.header.\"Content-Security-Policy\".value");
        boolean oidcVerifyNone = "none".equalsIgnoreCase(str("quarkus.oidc.tls.verification", ""));
        boolean swagger = bool("quarkus.swagger-ui.always-include", false);
        boolean openapi = bool("quarkus.smallrye-openapi.always-include", false);
        boolean csrfExtensionPresent = bool(CSRF_KEY, false);
        boolean csrf = csrfExtensionPresent && bool("quarkus.rest-csrf.enabled", true);

        boolean behindProxy = bool("quarkus.http.proxy.proxy-address-forwarding", false)
                || bool("quarkus.http.proxy.allow-forwarded", false)
                || bool("quarkus.http.proxy.allow-x-forwarded", false);
        boolean jwtIssuer = has("mp.jwt.verify.issuer");
        boolean proactiveAuthDisabled = !bool("quarkus.http.auth.proactive", true);
        boolean oidcAudience = has("quarkus.oidc.token.audience");
        String oidcAppType = str("quarkus.oidc.application-type", "").toLowerCase();
        boolean oidcCookieForceSecure = bool("quarkus.oidc.authentication.cookie-force-secure", false);
        boolean tlsTrustAll = bool("quarkus.tls.trust-all", false);
        String corsMethods = str("quarkus.http.cors.methods", null);
        String corsHeaders = str("quarkus.http.cors.headers", null);
        String hstsValue = str("quarkus.http.header.\"Strict-Transport-Security\".value", null);
        String cspValue = str("quarkus.http.header.\"Content-Security-Policy\".value", null);
        boolean xFrame = has("quarkus.http.header.\"X-Frame-Options\".value");
        boolean xContentType = has("quarkus.http.header.\"X-Content-Type-Options\".value");
        boolean denyUnannotated = bool("quarkus.security.jaxrs.deny-unannotated-endpoints", false);
        boolean managementEnabled = bool("quarkus.management.enabled", false);
        boolean managementHostNonLoopback =
                managementEnabled && !isLoopbackHost(str("quarkus.management.host", "0.0.0.0"));

        boolean jwtAllowUnsigned = bool("quarkus.smallrye-jwt.allow-unsigned-tokens", false);
        boolean jdbcClearPasswordMapper = jdbcClearPasswordMapperEnabled();
        boolean jdbcBcryptWorkFactorLow = jdbcBcryptWorkFactorLow();
        boolean embeddedUsers = bool("quarkus.security.users.embedded.enabled", false);
        boolean jwtAudiences = has("mp.jwt.verify.audiences");
        boolean jwtInlineKey = has("mp.jwt.verify.publickey");
        boolean referrerPolicy = has("quarkus.http.header.\"Referrer-Policy\".value");
        boolean permissionsPolicy = has("quarkus.http.header.\"Permissions-Policy\".value");
        String nonApplicationRootPath = str("quarkus.http.non-application-root-path", "/q");
        boolean grpcPresent = bool(GRPC_PRESENT_KEY, false);
        boolean grpcReflectionProd = grpcPresent && grpcReflectionEnabledInProdProfile();
        boolean graphqlPresent = bool(GRAPHQL_PRESENT_KEY, false);
        boolean graphqlIntrospection = bool("quarkus.smallrye-graphql.introspection-enabled", true);
        boolean graphqlUiAlwaysInclude = bool("quarkus.smallrye-graphql.ui.always-include", false);
        boolean messagingCredsWithoutTls = messagingCredentialsWithoutTls();
        boolean formCookieHttpOnly = bool("quarkus.http.auth.form.http-only-cookie", false);
        boolean formCookieSameSiteNone =
                "none".equalsIgnoreCase(str("quarkus.http.auth.form.cookie-same-site", "strict"));
        boolean formSessionTimeoutExcessive = formSessionTimeoutExcessive();

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
                jwtAllowUnsigned,
                jdbcClearPasswordMapper,
                jdbcBcryptWorkFactorLow,
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
                messagingCredsWithoutTls,
                formCookieHttpOnly,
                formCookieSameSiteNone,
                formSessionTimeoutExcessive);
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

    private boolean jdbcBcryptWorkFactorLow() {
        for (String name : config.getPropertyNames()) {
            if (name.contains("principal-query") && name.endsWith("bcrypt-password-mapper.work-factor")) {
                Integer workFactor =
                        config.getOptionalValue(name, Integer.class).orElse(null);
                if (workFactor != null && workFactor < 10) {
                    return true;
                }
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

    private boolean messagingCredentialsWithoutTls() {
        boolean saslCredentialsConfigured = false;
        boolean encryptedProtocolConfigured = false;
        for (String name : config.getPropertyNames()) {
            String lower = name.toLowerCase();
            if ((lower.endsWith(".sasl.password") || lower.endsWith(".sasl.jaas.config"))
                    && !str(name, "").isBlank()) {
                saslCredentialsConfigured = true;
            } else if (lower.endsWith(".security.protocol")) {
                String protocol = str(name, "").toUpperCase();
                if (protocol.equals("SASL_SSL") || protocol.equals("SSL")) {
                    encryptedProtocolConfigured = true;
                }
            }
        }
        return saslCredentialsConfigured && !encryptedProtocolConfigured;
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
                byName.put(key, new QuarkusSecurityPermission(key, paths, policy));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<String> suspectedSecrets() {
        List<String> out = new ArrayList<>();
        for (String name : config.getPropertyNames()) {
            if (!name.startsWith("quarkus.")
                    && !name.startsWith("bootui.")
                    && SECRET_NAME.matcher(name).matches()) {
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
