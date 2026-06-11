package io.github.jdubois.bootui.autoconfigure;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * Allow non-loopback requests (explicit opt-out of safety).
     */
    private boolean allowNonLocalhost = false;
    /**
     * Additional Host header values accepted by the loopback safety filter, beyond the built-in
     * loopback names (localhost, 127.0.0.1, ::1). Used to defend against DNS-rebinding attacks while
     * still allowing custom local hostnames.
     */
    private String[] allowedHosts = {};
    /**
     * Source IP ranges (CIDR notation, e.g. {@code 172.16.0.0/12}) that are trusted in addition to
     * loopback by the safety filter. Lets local Docker-bridge callers reach BootUI without disabling
     * the Host allow-list (DNS-rebinding defense) or the cross-site write protection (CSRF defense),
     * unlike the all-or-nothing {@code bootui.allow-non-localhost}. Empty by default.
     */
    private String[] trustedProxies = {};
    /**
     * Whether to trust the auto-detected container default gateway as a single {@code /32}, so BootUI
     * works out of the box inside a container with a published port (where host&#8594;container traffic
     * is SNAT'd to the gateway) without configuring a broad {@code bootui.trusted-proxies} CIDR.
     * {@code AUTO} (default) trusts it only when running inside a container; {@code ON} trusts a
     * detected gateway even if container heuristics are inconclusive; {@code OFF} never trusts it.
     * Like {@code bootui.trusted-proxies} this relaxes only the source-address check — the Host
     * allow-list (DNS-rebinding defense) and cross-site write protection (CSRF defense) stay in force.
     */
    private Mode trustContainerGateway = Mode.AUTO;
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
     * Disable every browser-triggered action while keeping read-only panel data visible.
     */
    private boolean readOnly = false;
    /**
     * Profiles that trigger auto-activation.
     */
    private String[] enabledProfiles = BootUiDefaults.ENABLED_PROFILES.toArray(new String[0]);
    /**
     * Profiles that force BootUI off, even when enabled by other rules.
     */
    private String[] disabledProfiles = BootUiDefaults.DISABLED_PROFILES.toArray(new String[0]);
    /**
     * Where local runtime overrides are persisted.
     */
    private String overridesFile = ".bootui/application-bootui.properties";
    /**
     * Per-panel visibility and action settings keyed by panel id.
     */
    private Map<String, Panel> panels = new LinkedHashMap<>();
    /**
     * Monitoring screen filtering settings.
     */
    private Monitoring monitoring = new Monitoring();
    /**
     * Dev Services panel settings.
     */
    private DevServices devServices = new DevServices();
    /**
     * Spring Cache panel settings.
     */
    private Cache cache = new Cache();
    /**
     * Security Logs panel settings.
     */
    private SecurityLogs securityLogs = new SecurityLogs();
    /**
     * Dependency inventory and vulnerability scanning settings.
     */
    private Vulnerabilities vulnerabilities = new Vulnerabilities();
    /**
     * GitHub panel settings.
     */
    private GitHub github = new GitHub();
    /**
     * HTTP Exchanges panel settings.
     */
    private HttpExchanges httpExchanges = new HttpExchanges();
    /**
     * HTTP Sessions panel settings.
     */
    private HttpSessions httpSessions = new HttpSessions();
    /**
     * Heap Dump panel settings.
     */
    private HeapDump heapDump = new HeapDump();
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
    /**
     * Claude Code panel settings.
     */
    private ClaudeCode claudeCode = new ClaudeCode();
    /**
     * GraalVM readiness panel settings.
     */
    private Graalvm graalvm = new Graalvm();

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

    public Mode getTrustContainerGateway() {
        return trustContainerGateway;
    }

    public void setTrustContainerGateway(Mode trustContainerGateway) {
        this.trustContainerGateway = trustContainerGateway;
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

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
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

    public Map<String, Panel> getPanels() {
        return panels;
    }

    public void setPanels(Map<String, Panel> panels) {
        this.panels = panels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(panels);
    }

    public Monitoring getMonitoring() {
        return monitoring;
    }

    public void setMonitoring(Monitoring monitoring) {
        this.monitoring = monitoring == null ? new Monitoring() : monitoring;
    }

    public Panel panel(String id) {
        return panels.computeIfAbsent(id, ignored -> new Panel());
    }

    public boolean isPanelEnabled(String id) {
        Panel panel = panels.get(id);
        return panel == null || panel.isEnabled();
    }

    public boolean isPanelReadOnly(String id) {
        Panel panel = panels.get(id);
        return readOnly || (panel != null && panel.isReadOnly());
    }

    public String panelDisabledReason(String id) {
        return "Panel is disabled via bootui.panels." + id + ".enabled=false";
    }

    public String panelReadOnlyReason(String id) {
        if (readOnly) {
            return "BootUI is read-only via bootui.read-only=true";
        }
        return "Panel is read-only via bootui.panels." + id + ".read-only=true";
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
        this.cache = cache == null ? new Cache() : cache;
    }

    public SecurityLogs getSecurityLogs() {
        return securityLogs;
    }

    public void setSecurityLogs(SecurityLogs securityLogs) {
        this.securityLogs = securityLogs == null ? new SecurityLogs() : securityLogs;
    }

    public Vulnerabilities getVulnerabilities() {
        return vulnerabilities;
    }

    public void setVulnerabilities(Vulnerabilities vulnerabilities) {
        this.vulnerabilities = vulnerabilities;
    }

    public GitHub getGithub() {
        return github;
    }

    public void setGithub(GitHub github) {
        this.github = github == null ? new GitHub() : github;
    }

    public HttpExchanges getHttpExchanges() {
        return httpExchanges;
    }

    public void setHttpExchanges(HttpExchanges httpExchanges) {
        this.httpExchanges = httpExchanges == null ? new HttpExchanges() : httpExchanges;
    }

    public HttpSessions getHttpSessions() {
        return httpSessions;
    }

    public void setHttpSessions(HttpSessions httpSessions) {
        this.httpSessions = httpSessions == null ? new HttpSessions() : httpSessions;
    }

    public HeapDump getHeapDump() {
        return heapDump;
    }

    public void setHeapDump(HeapDump heapDump) {
        this.heapDump = heapDump == null ? new HeapDump() : heapDump;
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

    public ClaudeCode getClaudeCode() {
        return claudeCode;
    }

    public void setClaudeCode(ClaudeCode claudeCode) {
        this.claudeCode = claudeCode;
    }

    public Graalvm getGraalvm() {
        return graalvm;
    }

    public void setGraalvm(Graalvm graalvm) {
        this.graalvm = graalvm == null ? new Graalvm() : graalvm;
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

    public static class Panel {

        /**
         * Enable the panel and allow its API endpoints to respond.
         */
        private boolean enabled = true;

        /**
         * Disable browser-triggered actions for this panel while keeping read endpoints visible.
         */
        private boolean readOnly = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
        }
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

    public static class SecurityLogs {

        /**
         * Maximum number of recent audit events retained in a single Security Logs response.
         */
        private int maxLogs = 500;

        public int getMaxLogs() {
            return maxLogs;
        }

        public void setMaxLogs(int maxLogs) {
            this.maxLogs = maxLogs;
        }
    }

    public static class Vulnerabilities {

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

    public static class Graalvm {

        /**
         * Allow the GraalVM dependency survey to look up Oracle's GraalVM reachability metadata
         * repository for per-version coverage of classpath dependencies. The lookup is the panel's
         * only outbound network call and is performed only during a user-initiated dependency scan.
         */
        private boolean repositoryLookupEnabled = true;

        /**
         * Timeout applied to each reachability metadata repository request.
         */
        private Duration repositoryLookupTimeout = Duration.ofSeconds(2);

        /**
         * Maximum number of distinct dependency coordinates looked up against the reachability
         * metadata repository in a single scan.
         */
        private int maxRepositoryLookups = 500;

        public boolean isRepositoryLookupEnabled() {
            return repositoryLookupEnabled;
        }

        public void setRepositoryLookupEnabled(boolean repositoryLookupEnabled) {
            this.repositoryLookupEnabled = repositoryLookupEnabled;
        }

        public Duration getRepositoryLookupTimeout() {
            return repositoryLookupTimeout;
        }

        public void setRepositoryLookupTimeout(Duration repositoryLookupTimeout) {
            this.repositoryLookupTimeout = repositoryLookupTimeout;
        }

        public int getMaxRepositoryLookups() {
            return maxRepositoryLookups;
        }

        public void setMaxRepositoryLookups(int maxRepositoryLookups) {
            this.maxRepositoryLookups = maxRepositoryLookups;
        }
    }

    public static class GitHub {

        /**
         * Allow the GitHub panel refresh action to call GitHub APIs.
         */
        private boolean apiEnabled = true;

        /**
         * Timeout applied to each GitHub API request and local credential lookup.
         */
        private Duration requestTimeout = Duration.ofSeconds(5);

        /**
         * Maximum pull requests returned in one dashboard refresh.
         */
        private int maxPullRequests = 10;

        /**
         * Maximum issues returned in one dashboard refresh.
         */
        private int maxIssues = 25;

        /**
         * Maximum workflow runs returned in one dashboard refresh.
         */
        private int maxWorkflowRuns = 20;

        /**
         * Safety threshold below which optional GitHub calls are skipped.
         */
        private int quotaSafetyThreshold = 10;

        /**
         * Maximum number of GitHub API calls issued by one refresh.
         */
        private int maxApiCalls = 17;

        /**
         * Additional allowed GitHub API hosts. GitHub.com is allowed by default.
         */
        private String[] allowedApiHosts = {"api.github.com"};

        public boolean isApiEnabled() {
            return apiEnabled;
        }

        public void setApiEnabled(boolean apiEnabled) {
            this.apiEnabled = apiEnabled;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        }

        public int getMaxPullRequests() {
            return maxPullRequests;
        }

        public void setMaxPullRequests(int maxPullRequests) {
            this.maxPullRequests = maxPullRequests;
        }

        public int getMaxIssues() {
            return maxIssues;
        }

        public void setMaxIssues(int maxIssues) {
            this.maxIssues = maxIssues;
        }

        public int getMaxWorkflowRuns() {
            return maxWorkflowRuns;
        }

        public void setMaxWorkflowRuns(int maxWorkflowRuns) {
            this.maxWorkflowRuns = maxWorkflowRuns;
        }

        public int getQuotaSafetyThreshold() {
            return quotaSafetyThreshold;
        }

        public void setQuotaSafetyThreshold(int quotaSafetyThreshold) {
            this.quotaSafetyThreshold = quotaSafetyThreshold;
        }

        public int getMaxApiCalls() {
            return maxApiCalls;
        }

        public void setMaxApiCalls(int maxApiCalls) {
            this.maxApiCalls = maxApiCalls;
        }

        public String[] getAllowedApiHosts() {
            return allowedApiHosts;
        }

        public void setAllowedApiHosts(String[] allowedApiHosts) {
            this.allowedApiHosts = allowedApiHosts == null ? new String[] {"api.github.com"} : allowedApiHosts;
        }
    }

    public static class HttpExchanges {

        /**
         * Maximum number of HTTP exchanges retained in the in-memory repository.
         */
        private int maxExchanges = 200;

        public int getMaxExchanges() {
            return maxExchanges;
        }

        public void setMaxExchanges(int maxExchanges) {
            this.maxExchanges = maxExchanges;
        }
    }

    public static class HttpSessions {

        /**
         * Maximum number of active HTTP sessions returned by the HTTP Sessions panel.
         */
        private int maxSessions = 50;

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }
    }

    /**
     * Heap Dump panel settings.
     *
     * <p>A heap dump is a sensitive artifact: it contains plaintext secrets (passwords,
     * tokens, PII) pulled from live memory and bypasses BootUI's value masking. BootUI
     * writes dumps to a local directory and never exposes them over HTTP unless raw
     * download is explicitly enabled. Capture and delete are mutating actions, so they are
     * blocked when the panel or BootUI is read-only.</p>
     */
    public static class HeapDump {

        /**
         * Allow on-demand heap dump capture. When {@code false}, the panel still exposes the
         * live class histogram analysis but the capture endpoint is rejected.
         */
        private boolean captureEnabled = true;

        /**
         * Allow downloading the raw {@code .hprof} file over HTTP. Disabled by default because
         * the raw dump contains unmasked secrets.
         */
        private boolean allowRawDownload = false;

        /**
         * Directory where captured heap dumps are written.
         */
        private String outputDir = ".bootui/heap-dumps";

        /**
         * Maximum number of heap dump files retained on disk. Oldest dumps are deleted first.
         */
        private int maxDumps = 5;

        /**
         * Maximum number of classes retained in memory after a histogram analysis, ordered by
         * retained bytes. Capping this value prevents very large heaps from exhausting memory.
         * Must be &ge; {@code top-classes}.
         */
        private int maxClasses = 1000;

        /**
         * Maximum number of classes returned in the class histogram, ordered by retained bytes.
         */
        private int topClasses = 25;

        public boolean isCaptureEnabled() {
            return captureEnabled;
        }

        public void setCaptureEnabled(boolean captureEnabled) {
            this.captureEnabled = captureEnabled;
        }

        public boolean isAllowRawDownload() {
            return allowRawDownload;
        }

        public void setAllowRawDownload(boolean allowRawDownload) {
            this.allowRawDownload = allowRawDownload;
        }

        public String getOutputDir() {
            return outputDir;
        }

        public void setOutputDir(String outputDir) {
            this.outputDir = outputDir;
        }

        public int getMaxDumps() {
            return maxDumps;
        }

        public void setMaxDumps(int maxDumps) {
            this.maxDumps = maxDumps;
        }

        public int getMaxClasses() {
            return maxClasses;
        }

        public void setMaxClasses(int maxClasses) {
            this.maxClasses = maxClasses;
        }

        public int getTopClasses() {
            return topClasses;
        }

        public void setTopClasses(int topClasses) {
            this.topClasses = topClasses;
        }
    }

    public static class Monitoring {

        /**
         * Hide BootUI's own runtime data from monitoring panels.
         */
        private boolean excludeSelf = true;

        public boolean isExcludeSelf() {
            return excludeSelf;
        }

        public void setExcludeSelf(boolean excludeSelf) {
            this.excludeSelf = excludeSelf;
        }
    }

    public static class Telemetry {

        /**
         * Capture local trace spans and accept OTLP/HTTP trace payloads at the
         * BootUI OTLP endpoint.
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
         * Maximum number of recent session files parsed and retained in memory.
         */
        private int maxParsedSessions = 100;

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

        public Path defaultSessionStateDir() {
            return Paths.get(System.getProperty("user.home", ""), ".copilot", "session-state");
        }

        public String getPanelTitle() {
            return "Copilot";
        }

        public String getSessionSourceName() {
            return "Copilot CLI";
        }

        public String getWatcherThreadName() {
            return "bootui-copilot-watcher";
        }

        public String maxParsedSessionsPropertyName() {
            return "bootui.copilot.max-parsed-sessions";
        }

        public boolean isProjectSessionDirectoryLayout() {
            return false;
        }

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

        public int getMaxParsedSessions() {
            return maxParsedSessions;
        }

        public void setMaxParsedSessions(int maxParsedSessions) {
            this.maxParsedSessions = maxParsedSessions;
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

    /**
     * Claude Code panel settings.
     *
     * <p>The Claude Code panel reads sanitized JSONL session logs written by the
     * local Claude Code CLI under {@code ~/.claude/projects/}. It is read-only and
     * never modifies anything under that directory.</p>
     */
    public static class ClaudeCode extends Copilot {

        public ClaudeCode() {
            setAllowRawReveal(false);
        }

        @Override
        public Path defaultSessionStateDir() {
            return Paths.get(System.getProperty("user.home", ""), ".claude", "projects");
        }

        @Override
        public String getPanelTitle() {
            return "Claude Code";
        }

        @Override
        public String getSessionSourceName() {
            return "Claude Code";
        }

        @Override
        public String getWatcherThreadName() {
            return "bootui-claude-code-watcher";
        }

        @Override
        public String maxParsedSessionsPropertyName() {
            return "bootui.claude-code.max-parsed-sessions";
        }

        @Override
        public boolean isProjectSessionDirectoryLayout() {
            return true;
        }
    }
}
