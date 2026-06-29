package io.github.jdubois.bootui.quarkus.security;

import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshotProvider;
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
        boolean csrf = bool(CSRF_KEY, false);

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
                suspectedSecrets());
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
