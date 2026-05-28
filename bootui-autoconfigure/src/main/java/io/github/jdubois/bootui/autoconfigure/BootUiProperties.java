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

    /**
     * Enable BootUI. AUTO activates only in dev/local contexts.
     */
    private Mode enabled = Mode.AUTO;
    /**
     * UI base path.
     */
    private String path = "/bootui";
    /**
     * Internal API base path.
     */
    private String apiPath = "/bootui/api";
    /**
     * Reject non-loopback requests.
     */
    private boolean localhostOnly = true;
    /**
     * Allow non-loopback requests (explicit opt-out of safety).
     */
    private boolean allowNonLocalhost = false;
    /**
     * Mask secret-like configuration values.
     */
    private boolean maskSecrets = true;
    /**
     * How configuration values are exposed in the Config panel.
     */
    private ValueExposure exposeValues = ValueExposure.MASKED;
    /**
     * Print the BootUI URL on application startup.
     */
    private boolean showBanner = true;
    /**
     * Profiles that trigger auto-activation.
     */
    private String[] enabledProfiles = {"dev", "local"};
    /**
     * Profiles that force BootUI off, even when enabled by other rules.
     */
    private String[] disabledProfiles = {"prod", "production"};
    /**
     * Where local runtime overrides are persisted.
     */
    private String overridesFile = ".bootui/application-bootui.properties";
    /**
     * Timeout applied to Actuator endpoint calls.
     */
    private Duration endpointTimeout = Duration.ofSeconds(5);
    /**
     * Dev Services panel settings.
     */
    private DevServices devServices = new DevServices();
    /**
     * Spring Cache panel settings.
     */
    private Cache cache = new Cache();
    /**
     * Dependency inventory and vulnerability scanning settings.
     */
    private Dependencies dependencies = new Dependencies();
    /**
     * OTLP-based telemetry receiver and trace store settings.
     */
    private Telemetry telemetry = new Telemetry();
    /**
     * AI Usage panel settings.
     */
    private Ai ai = new Ai();
    /**
     * Copilot panel settings.
     */
    private Copilot copilot = new Copilot();

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

    public Telemetry getTelemetry() {
        return telemetry;
    }

    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public Copilot getCopilot() {
        return copilot;
    }

    public void setCopilot(Copilot copilot) {
        this.copilot = copilot;
    }

    /**
     * Mode selector: AUTO (dev detection), ON (force enable), OFF (force disable).
     */
    public enum Mode {
        AUTO,
        ON,
        OFF
    }

    /**
     * How configuration values are displayed in the Config panel.
     */
    public enum ValueExposure {
        /**
         * Replace secret-like values with stars. Default.
         */
        MASKED,
        /**
         * Hide values entirely and only show metadata.
         */
        METADATA_ONLY,
        /**
         * Show all values, including secrets. Discouraged.
         */
        FULL
    }

    public static class DevServices {

        /**
         * Allow BootUI to restart Testcontainers-backed services.
         */
        private boolean restartEnabled = false;

        /**
         * Maximum bytes returned by a single Dev Services log request.
         */
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

        /**
         * Allow BootUI to clear application caches from the Spring Cache panel.
         */
        private boolean clearEnabled = true;

        public boolean isClearEnabled() {
            return clearEnabled;
        }

        public void setClearEnabled(boolean clearEnabled) {
            this.clearEnabled = clearEnabled;
        }
    }

    public static class Dependencies {

        /**
         * Allow on-demand vulnerability scans against OSV.dev.
         */
        private boolean osvEnabled = true;

        /**
         * Timeout applied to each OSV request.
         */
        private Duration requestTimeout = Duration.ofSeconds(10);

        /**
         * Maximum number of dependency packages included in one OSV batch query.
         */
        private int maxPackages = 250;

        /**
         * Maximum number of advisory details fetched after the package query.
         */
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

    public static class Telemetry {

        /**
         * Accept OTLP/HTTP trace payloads at the BootUI OTLP endpoint.
         */
        private boolean enabled = true;

        /**
         * Maximum number of distinct traces retained in memory. Oldest are evicted.
         */
        private int maxTraces = 500;

        /**
         * Maximum number of spans retained per trace.
         */
        private int maxSpansPerTrace = 500;

        /**
         * Maximum length of a single attribute string value before truncation.
         */
        private int maxAttributeValueBytes = 4 * 1024;

        /**
         * Drop spans whose route/path attribute starts with the BootUI API path.
         */
        private boolean excludeSelfSpans = true;

        /**
         * Maximum payload size (bytes) accepted by the OTLP receiver.
         */
        private int maxRequestBytes = 8 * 1024 * 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxTraces() {
            return maxTraces;
        }

        public void setMaxTraces(int maxTraces) {
            this.maxTraces = maxTraces;
        }

        public int getMaxSpansPerTrace() {
            return maxSpansPerTrace;
        }

        public void setMaxSpansPerTrace(int maxSpansPerTrace) {
            this.maxSpansPerTrace = maxSpansPerTrace;
        }

        public int getMaxAttributeValueBytes() {
            return maxAttributeValueBytes;
        }

        public void setMaxAttributeValueBytes(int maxAttributeValueBytes) {
            this.maxAttributeValueBytes = maxAttributeValueBytes;
        }

        public boolean isExcludeSelfSpans() {
            return excludeSelfSpans;
        }

        public void setExcludeSelfSpans(boolean excludeSelfSpans) {
            this.excludeSelfSpans = excludeSelfSpans;
        }

        public int getMaxRequestBytes() {
            return maxRequestBytes;
        }

        public void setMaxRequestBytes(int maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
        }
    }

    public static class Ai {

        /**
         * Number of minutes retained in the per-minute token-usage series.
         */
        private int tokenSeriesMinutes = 60;

        /**
         * Maximum number of recent chat completions surfaced by the AI panel.
         */
        private int maxRecentChats = 100;

        /**
         * When true, BootUI surfaces a banner explaining that prompt/completion content is not captured by default.
         */
        private boolean showContentCaptureBanner = true;

        public int getTokenSeriesMinutes() {
            return tokenSeriesMinutes;
        }

        public void setTokenSeriesMinutes(int tokenSeriesMinutes) {
            this.tokenSeriesMinutes = tokenSeriesMinutes;
        }

        public int getMaxRecentChats() {
            return maxRecentChats;
        }

        public void setMaxRecentChats(int maxRecentChats) {
            this.maxRecentChats = maxRecentChats;
        }

        public boolean isShowContentCaptureBanner() {
            return showContentCaptureBanner;
        }

        public void setShowContentCaptureBanner(boolean showContentCaptureBanner) {
            this.showContentCaptureBanner = showContentCaptureBanner;
        }
    }

    /**
     * Copilot panel settings.
     *
     * <p>The Copilot panel reads sanitized session state written by the local
     * Copilot CLI under {@code ~/.copilot/session-state/}, including per-session
     * {@code events.jsonl} files. It is read-only and never modifies anything
     * under that directory.</p>
     */
    public static class Copilot {

        /**
         * Enable the Copilot panel. AUTO activates only when the session-state directory exists.
         */
        private Mode enabled = Mode.AUTO;

        /**
         * Override the directory scanned for Copilot CLI session-state directories.
         * When {@code null}, defaults to {@code ${user.home}/.copilot/session-state}.
         */
        private String sessionStateDir;

        /**
         * Maximum number of activity events retained per session in memory.
         */
        private int maxEventsPerSession = 2000;

        /**
         * Maximum number of recent sessions returned to the session explorer.
         */
        private int maxSessions = 100;

        /**
         * Debounce window applied to file-system events before refreshing parsed sessions
         * and notifying SSE subscribers.
         */
        private Duration streamDebounce = Duration.ofMillis(400);

        /**
         * Allow the opt-in raw-event reveal endpoint. When {@code false}, the endpoint
         * returns HTTP 404 even on the loopback interface.
         */
        private boolean allowRawReveal = true;

        public Mode getEnabled() {
            return enabled;
        }

        public void setEnabled(Mode enabled) {
            this.enabled = enabled;
        }

        public String getSessionStateDir() {
            return sessionStateDir;
        }

        public void setSessionStateDir(String sessionStateDir) {
            this.sessionStateDir = sessionStateDir;
        }

        public int getMaxEventsPerSession() {
            return maxEventsPerSession;
        }

        public void setMaxEventsPerSession(int maxEventsPerSession) {
            this.maxEventsPerSession = maxEventsPerSession;
        }

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }

        public Duration getStreamDebounce() {
            return streamDebounce;
        }

        public void setStreamDebounce(Duration streamDebounce) {
            this.streamDebounce = streamDebounce;
        }

        public boolean isAllowRawReveal() {
            return allowRawReveal;
        }

        public void setAllowRawReveal(boolean allowRawReveal) {
            this.allowRawReveal = allowRawReveal;
        }
    }
}
