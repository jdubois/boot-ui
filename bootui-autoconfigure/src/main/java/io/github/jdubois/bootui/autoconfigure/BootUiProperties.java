package io.github.jdubois.bootui.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties bound under the {@code bootui.*} prefix.
 *
 * <p>BootUI activates only in local development by default. See
 * {@link BootUiActivationCondition} for the activation rules.</p>
 */
@ConfigurationProperties(prefix = "bootui")
public class BootUiProperties {

    /** Mode selector: AUTO (dev detection), ON (force enable), OFF (force disable). */
    public enum Mode {
        AUTO,
        ON,
        OFF
    }

    /** How configuration values are displayed in the Config panel. */
    public enum ValueExposure {
        /** Replace secret-like values with stars. Default. */
        MASKED,
        /** Hide values entirely and only show metadata. */
        METADATA_ONLY,
        /** Show all values, including secrets. Discouraged. */
        FULL
    }

    /** Enable BootUI. AUTO activates only in dev/local contexts. */
    private Mode enabled = Mode.AUTO;

    /** UI base path. */
    private String path = "/bootui";

    /** Internal API base path. */
    private String apiPath = "/bootui/api";

    /** Reject non-loopback requests. */
    private boolean localhostOnly = true;

    /** Allow non-loopback requests (explicit opt-out of safety). */
    private boolean allowNonLocalhost = false;

    /** Mask secret-like configuration values. */
    private boolean maskSecrets = true;

    /** How configuration values are exposed in the Config panel. */
    private ValueExposure exposeValues = ValueExposure.MASKED;

    /** Print the BootUI URL on application startup. */
    private boolean showBanner = true;

    /** Profiles that trigger auto-activation. */
    private String[] enabledProfiles = { "dev", "local" };

    /** Profiles that force BootUI off, even when enabled by other rules. */
    private String[] disabledProfiles = { "prod", "production" };

    /** Where local runtime overrides are persisted. */
    private String overridesFile = ".bootui/application-bootui.properties";

    /** Timeout applied to Actuator endpoint calls. */
    private Duration endpointTimeout = Duration.ofSeconds(5);

    /** Limits applied to large collections returned by BootUI endpoints. */
    private Limits limits = new Limits();

    /** Dev Services panel settings. */
    private DevServices devServices = new DevServices();

    /** Spring Cache panel settings. */
    private Cache cache = new Cache();

    /** Dependency inventory and vulnerability scanning settings. */
    private Dependencies dependencies = new Dependencies();

    public static class Limits {

        /** Maximum beans returned by the Beans panel. */
        private int maxBeans = 500;

        /** Maximum HTTP mappings returned by the Mappings panel. */
        private int maxMappings = 500;

        /** Maximum loggers returned by the Loggers panel. */
        private int maxLoggers = 1000;

        /** Maximum conditions entries returned by the Conditions panel. */
        private int maxConditions = 1000;

        /** Maximum scheduled tasks returned by the Scheduled panel. */
        private int maxScheduled = 200;

        public int getMaxBeans() {
            return maxBeans;
        }

        public void setMaxBeans(int maxBeans) {
            this.maxBeans = maxBeans;
        }

        public int getMaxMappings() {
            return maxMappings;
        }

        public void setMaxMappings(int maxMappings) {
            this.maxMappings = maxMappings;
        }

        public int getMaxLoggers() {
            return maxLoggers;
        }

        public void setMaxLoggers(int maxLoggers) {
            this.maxLoggers = maxLoggers;
        }

        public int getMaxConditions() {
            return maxConditions;
        }

        public void setMaxConditions(int maxConditions) {
            this.maxConditions = maxConditions;
        }

        public int getMaxScheduled() {
            return maxScheduled;
        }

        public void setMaxScheduled(int maxScheduled) {
            this.maxScheduled = maxScheduled;
        }
    }

    public static class DevServices {

        /** Allow BootUI to restart Testcontainers-backed services. */
        private boolean restartEnabled = false;

        /** Maximum bytes returned by a single Dev Services log request. */
        private int logTailBytes = 64 * 1024;

        public boolean isRestartEnabled() {
            return restartEnabled;
        }

        public void setRestartEnabled(boolean restartEnabled) {
            this.restartEnabled = restartEnabled;
        }

        public int getLogTailBytes() {
            return logTailBytes;
        }

        public void setLogTailBytes(int logTailBytes) {
            this.logTailBytes = logTailBytes;
        }
    }

    public static class Cache {

        /** Allow BootUI to clear application caches from the Spring Cache panel. */
        private boolean clearEnabled = true;

        public boolean isClearEnabled() {
            return clearEnabled;
        }

        public void setClearEnabled(boolean clearEnabled) {
            this.clearEnabled = clearEnabled;
        }
    }

    public static class Dependencies {

        /** Allow on-demand vulnerability scans against OSV.dev. */
        private boolean osvEnabled = true;

        /** Timeout applied to each OSV request. */
        private Duration requestTimeout = Duration.ofSeconds(10);

        /** Maximum number of dependency packages included in one OSV batch query. */
        private int maxPackages = 250;

        /** Maximum number of advisory details fetched after the package query. */
        private int maxAdvisories = 200;

        public boolean isOsvEnabled() {
            return osvEnabled;
        }

        public void setOsvEnabled(boolean osvEnabled) {
            this.osvEnabled = osvEnabled;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public int getMaxPackages() {
            return maxPackages;
        }

        public void setMaxPackages(int maxPackages) {
            this.maxPackages = maxPackages;
        }

        public int getMaxAdvisories() {
            return maxAdvisories;
        }

        public void setMaxAdvisories(int maxAdvisories) {
            this.maxAdvisories = maxAdvisories;
        }
    }

    public Mode getEnabled() {
        return enabled;
    }

    public void setEnabled(Mode enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public boolean isLocalhostOnly() {
        return localhostOnly;
    }

    public void setLocalhostOnly(boolean localhostOnly) {
        this.localhostOnly = localhostOnly;
    }

    public boolean isAllowNonLocalhost() {
        return allowNonLocalhost;
    }

    public void setAllowNonLocalhost(boolean allowNonLocalhost) {
        this.allowNonLocalhost = allowNonLocalhost;
    }

    public boolean isMaskSecrets() {
        return maskSecrets;
    }

    public void setMaskSecrets(boolean maskSecrets) {
        this.maskSecrets = maskSecrets;
    }

    public ValueExposure getExposeValues() {
        return exposeValues;
    }

    public void setExposeValues(ValueExposure exposeValues) {
        this.exposeValues = exposeValues;
    }

    public boolean isShowBanner() {
        return showBanner;
    }

    public void setShowBanner(boolean showBanner) {
        this.showBanner = showBanner;
    }

    public String[] getEnabledProfiles() {
        return enabledProfiles;
    }

    public void setEnabledProfiles(String[] enabledProfiles) {
        this.enabledProfiles = enabledProfiles;
    }

    public String[] getDisabledProfiles() {
        return disabledProfiles;
    }

    public void setDisabledProfiles(String[] disabledProfiles) {
        this.disabledProfiles = disabledProfiles;
    }

    public String getOverridesFile() {
        return overridesFile;
    }

    public void setOverridesFile(String overridesFile) {
        this.overridesFile = overridesFile;
    }

    public Duration getEndpointTimeout() {
        return endpointTimeout;
    }

    public void setEndpointTimeout(Duration endpointTimeout) {
        this.endpointTimeout = endpointTimeout;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    public DevServices getDevServices() {
        return devServices;
    }

    public void setDevServices(DevServices devServices) {
        this.devServices = devServices;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Dependencies getDependencies() {
        return dependencies;
    }

    public void setDependencies(Dependencies dependencies) {
        this.dependencies = dependencies;
    }
}
