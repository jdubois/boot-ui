package io.github.jdubois.bootui.console.safety;

import io.github.jdubois.bootui.engine.safety.GatewayTrust;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Activity Console's own, deliberately small local-only access configuration &mdash; the console's
 * equivalent of the handful of {@code bootui.*} safety settings on {@code BootUiProperties}, without
 * pulling in that class's ~40 unrelated panel/feature settings (see the package-level Javadoc for why
 * the console does not depend on {@code bootui-spring-autoconfigure}).
 *
 * <p>Reuses the same {@code bootui.*} property names host applications already know (so a user who has
 * configured a Spring Boot or Quarkus adapter's safety settings can reuse the same keys here), and binds
 * {@link #trustContainerGateway} directly to the framework-neutral {@link GatewayTrust} enum &mdash; no
 * adapter-local {@code Mode} translation layer is needed since {@link GatewayTrust} carries no framework
 * annotations and Spring Boot's relaxed binding handles a plain enum directly.
 */
@ConfigurationProperties(prefix = "bootui")
public class ConsoleSafetyProperties {

    /** Mirrors {@code bootui.allow-non-localhost}: bypasses every {@code LocalhostGuard} check. */
    private boolean allowNonLocalhost = false;

    /** Mirrors {@code bootui.allowed-hosts}: additional {@code Host} header allow-list entries. */
    private String[] allowedHosts = {};

    /** Mirrors {@code bootui.trusted-proxies}: additional trusted source CIDR ranges. */
    private String[] trustedProxies = {};

    /** Mirrors {@code bootui.trust-container-gateway}: whether to trust an auto-detected container gateway. */
    private GatewayTrust trustContainerGateway = GatewayTrust.OFF;

    public boolean isAllowNonLocalhost() {
        return allowNonLocalhost;
    }

    public void setAllowNonLocalhost(boolean allowNonLocalhost) {
        this.allowNonLocalhost = allowNonLocalhost;
    }

    public String[] getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(String[] allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public String[] getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(String[] trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public GatewayTrust getTrustContainerGateway() {
        return trustContainerGateway;
    }

    public void setTrustContainerGateway(GatewayTrust trustContainerGateway) {
        this.trustContainerGateway = trustContainerGateway;
    }
}
