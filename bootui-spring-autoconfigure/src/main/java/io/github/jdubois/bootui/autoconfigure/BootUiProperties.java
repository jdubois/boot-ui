package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.core.ValueExposure;
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
     * Whether to trust the auto-detected container gateway as a single {@code /32}, so BootUI can be
     * reached inside a container with a published port (where host&#8594;container traffic is SNAT'd to
     * the gateway) without configuring a broad {@code bootui.trusted-proxies} CIDR. Detection covers
     * both the Linux Docker Engine bridge gateway (from {@code /proc/net/route}) and the Docker Desktop
     * {@code gateway.docker.internal} address. Defaults to {@code OFF} (fail closed); {@code AUTO} and
     * {@code ON} are explicit opt-ins. {@code OFF} never trusts it; {@code AUTO} auto-detects and trusts
     * the gateway {@code /32} only when running inside a container; {@code ON} trusts a detected gateway
     * {@code /32} even if container heuristics are inconclusive. Like {@code bootui.trusted-proxies} this
     * relaxes only the source-address check — the Host allow-list (DNS-rebinding defense) and cross-site
     * write protection (CSRF defense) stay in force.
     */
    private Mode trustContainerGateway = Mode.OFF;
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
     * Cache panel settings.
     */
    private Cache cache = new Cache();
    /**
     * Security Logs panel settings.
     */
    private SecurityLogs securityLogs = new SecurityLogs();
    /**
     * Exceptions panel settings.
     */
    private Exceptions exceptions = new Exceptions();
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
     * Log Tail panel settings.
     */
    private LogTail logTail = new LogTail();
    /**
     * SQL Trace panel settings.
     */
    private SqlTrace sqlTrace = new SqlTrace();
    /**
     * REST Client panel settings.
     */
    private RestClientTrace restClientTrace = new RestClientTrace();
    /**
     * Kafka message capture settings, feeding the Live Activity stream's {@code MESSAGING} entries.
     */
    private Kafka kafka = new Kafka();
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
    /**
     * Local MCP (Model Context Protocol) server settings.
     */
    private Mcp mcp = new Mcp();
    /**
     * Live Activity panel settings (merged activity stream and per-request profiler).
     */
    private Activity activity = new Activity();
    /**
     * Email Viewer panel settings (captured outgoing mail).
     */
    private Email email = new Email();
    /**
     * Free-on-idle memory reclamation settings.
     */
    private FreeOnIdle freeOnIdle = new FreeOnIdle();

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

    public Exceptions getExceptions() {
        return exceptions;
    }

    public void setExceptions(Exceptions exceptions) {
        this.exceptions = exceptions == null ? new Exceptions() : exceptions;
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

    public LogTail getLogTail() {
        return logTail;
    }

    public void setLogTail(LogTail logTail) {
        this.logTail = logTail == null ? new LogTail() : logTail;
    }

    public SqlTrace getSqlTrace() {
        return sqlTrace;
    }

    public void setSqlTrace(SqlTrace sqlTrace) {
        this.sqlTrace = sqlTrace == null ? new SqlTrace() : sqlTrace;
    }

    public RestClientTrace getRestClientTrace() {
        return restClientTrace;
    }

    public void setRestClientTrace(RestClientTrace restClientTrace) {
        this.restClientTrace = restClientTrace == null ? new RestClientTrace() : restClientTrace;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka == null ? new Kafka() : kafka;
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

    public Mcp getMcp() {
        return mcp;
    }

    public void setMcp(Mcp mcp) {
        this.mcp = mcp == null ? new Mcp() : mcp;
    }

    public Activity getActivity() {
        return activity;
    }

    public void setActivity(Activity activity) {
        this.activity = activity == null ? new Activity() : activity;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email == null ? new Email() : email;
    }

    public FreeOnIdle getFreeOnIdle() {
        return freeOnIdle;
    }

    public void setFreeOnIdle(FreeOnIdle freeOnIdle) {
        this.freeOnIdle = freeOnIdle == null ? new FreeOnIdle() : freeOnIdle;
    }

    /**
     * Mode selector: AUTO (dev detection), ON (force enable), OFF (force disable).
     */
    public enum Mode {
        AUTO,
        ON,
        OFF
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
         * Allow BootUI to clear application caches from the Cache panel.
         */
        private boolean clearEnabled = true;

        /**
         * Whether BootUI wraps {@code CacheManager} beans to capture hit/miss/put/evict/clear accesses
         * into the Live Activity panel's {@code CACHE} event type. Cache keys are always hashed, never
         * recorded raw, regardless of value exposure. When {@code false}, no {@code CacheManager} is
         * wrapped and cache accesses never appear in Live Activity.
         */
        private boolean activityCaptureEnabled = true;

        /**
         * Maximum number of recent cache accesses retained in the in-memory ring buffer feeding Live
         * Activity.
         */
        private int activityMaxEvents = 500;

        public boolean isClearEnabled() {
            return clearEnabled;
        }

        public void setClearEnabled(boolean clearEnabled) {
            this.clearEnabled = clearEnabled;
        }

        public boolean isActivityCaptureEnabled() {
            return activityCaptureEnabled;
        }

        public void setActivityCaptureEnabled(boolean activityCaptureEnabled) {
            this.activityCaptureEnabled = activityCaptureEnabled;
        }

        public int getActivityMaxEvents() {
            return activityMaxEvents;
        }

        public void setActivityMaxEvents(int activityMaxEvents) {
            this.activityMaxEvents = activityMaxEvents;
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

    public static class Exceptions {

        /**
         * Maximum number of distinct exception groups retained by the Exceptions panel. When the
         * limit is reached the group with the oldest most-recent occurrence is evicted.
         */
        private int maxGroups = 100;

        /**
         * Maximum number of recent occurrences retained per exception group.
         */
        private int maxOccurrencesPerGroup = 25;

        /**
         * Maximum number of stack-trace frames retained per exception (and per cause).
         */
        private int maxStackFrames = 50;

        public int getMaxGroups() {
            return maxGroups;
        }

        public void setMaxGroups(int maxGroups) {
            this.maxGroups = maxGroups;
        }

        public int getMaxOccurrencesPerGroup() {
            return maxOccurrencesPerGroup;
        }

        public void setMaxOccurrencesPerGroup(int maxOccurrencesPerGroup) {
            this.maxOccurrencesPerGroup = maxOccurrencesPerGroup;
        }

        public int getMaxStackFrames() {
            return maxStackFrames;
        }

        public void setMaxStackFrames(int maxStackFrames) {
            this.maxStackFrames = maxStackFrames;
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

        /**
         * Base URI of the OSV.dev API queried during a scan. Configurable mainly so tests can point at a
         * local stub; defaults to the public OSV endpoint, preserving the panel's existing network
         * behaviour, and matches the Quarkus adapter's {@code bootui.vulnerabilities.osv-base-uri} key.
         */
        private String osvBaseUri = "https://api.osv.dev";

        /**
         * Allow enriching CVE-aliased advisories with FIRST.org EPSS (exploit-probability) scores during a
         * scan. Same on-demand-only network behaviour as {@code osvEnabled}: only called from the
         * user-initiated scan action, never on page render.
         */
        private boolean epssEnabled = true;

        /**
         * Base URI of the FIRST.org EPSS API queried during a scan. Configurable mainly so tests can point
         * at a local stub; defaults to the public EPSS endpoint, and matches the Quarkus adapter's
         * {@code bootui.vulnerabilities.epss-base-uri} key.
         */
        private String epssBaseUri = "https://api.first.org";

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

        public String getOsvBaseUri() {
            return osvBaseUri;
        }

        public void setOsvBaseUri(String osvBaseUri) {
            this.osvBaseUri = osvBaseUri;
        }

        public boolean isEpssEnabled() {
            return epssEnabled;
        }

        public void setEpssEnabled(boolean epssEnabled) {
            this.epssEnabled = epssEnabled;
        }

        public String getEpssBaseUri() {
            return epssBaseUri;
        }

        public void setEpssBaseUri(String epssBaseUri) {
            this.epssBaseUri = epssBaseUri;
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
         * Maximum Dependabot alert details listed in one dashboard refresh. The alert count is still
         * exact; only the inlined detail list is capped. Code scanning and secret scanning remain
         * count-only and never inline alert details.
         */
        private int maxSecurityAlerts = 50;

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

        public int getMaxSecurityAlerts() {
            return maxSecurityAlerts;
        }

        public void setMaxSecurityAlerts(int maxSecurityAlerts) {
            this.maxSecurityAlerts = maxSecurityAlerts;
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

    public static class LogTail {

        /**
         * Approximate retained-byte budget for the in-memory Log Tail ring buffer, bounding it alongside
         * its fixed line cap (oldest evicted first when either bound trips). {@code 0} (the default) means
         * unbounded — preserving the Spring adapter's historical behaviour — and matches the Quarkus
         * adapter's {@code bootui.log-tail.max-bytes} default, so the same key sizes the buffer identically
         * on both frameworks.
         */
        private long maxBytes = 0;

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class SqlTrace {

        /**
         * Whether BootUI wraps {@code DataSource} beans with its hand-written JDBC tracing
         * proxy to capture executed SQL. When {@code false}, no data source is wrapped.
         */
        private boolean enabled = true;

        /**
         * Whether new executions are recorded into the in-memory buffer. Recording can be
         * paused and resumed at runtime from the panel without unwrapping {@code DataSource}
         * beans; this sets the initial state.
         */
        private boolean recording = true;

        /**
         * Whether bound statement parameters are captured alongside the SQL text. Off by
         * default because parameter values may contain sensitive data; metadata-only value
         * exposure additionally suppresses parameters even when this is enabled.
         */
        private boolean captureParameters = false;

        /**
         * Whether each captured statement records the first application stack frame that
         * triggered it (e.g. {@code OrderRepository.findByCustomer(OrderRepository.java:42)}),
         * shown per execution and aggregated per group so a flagged N+1 group shows exactly
         * where in the code to go fix it. On by default: unlike bound parameters, a call site
         * names only the application's own code (class/method/line), never a value, so it
         * carries no sensitive-data risk — this toggle is purely about the small per-statement
         * stack-walk cost, not privacy.
         */
        private boolean captureCallSite = true;

        /**
         * Maximum number of executed statements retained in the in-memory ring buffer.
         */
        private int maxEntries = 200;

        /**
         * Executions taking at least this many milliseconds are flagged as slow. Set to
         * {@code 0} to disable slow-query flagging.
         */
        private long slowQueryThresholdMillis = 100;

        /**
         * Maximum retained SQL text length; longer statements are truncated.
         */
        private int maxSqlLength = 2000;

        /**
         * Maximum retained length of a single captured parameter value.
         */
        private int maxParameterLength = 200;

        /**
         * Number of times an identical {@code SELECT} must repeat within the buffer before it
         * is flagged as a likely N+1 access pattern. Minimum {@code 2}.
         */
        private int nPlusOneThreshold = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRecording() {
            return recording;
        }

        public void setRecording(boolean recording) {
            this.recording = recording;
        }

        public boolean isCaptureParameters() {
            return captureParameters;
        }

        public void setCaptureParameters(boolean captureParameters) {
            this.captureParameters = captureParameters;
        }

        public boolean isCaptureCallSite() {
            return captureCallSite;
        }

        public void setCaptureCallSite(boolean captureCallSite) {
            this.captureCallSite = captureCallSite;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public long getSlowQueryThresholdMillis() {
            return slowQueryThresholdMillis;
        }

        public void setSlowQueryThresholdMillis(long slowQueryThresholdMillis) {
            this.slowQueryThresholdMillis = slowQueryThresholdMillis;
        }

        public int getMaxSqlLength() {
            return maxSqlLength;
        }

        public void setMaxSqlLength(int maxSqlLength) {
            this.maxSqlLength = maxSqlLength;
        }

        public int getMaxParameterLength() {
            return maxParameterLength;
        }

        public void setMaxParameterLength(int maxParameterLength) {
            this.maxParameterLength = maxParameterLength;
        }

        public int getNPlusOneThreshold() {
            return nPlusOneThreshold;
        }

        public void setNPlusOneThreshold(int nPlusOneThreshold) {
            this.nPlusOneThreshold = nPlusOneThreshold;
        }
    }

    public static class RestClientTrace {

        /**
         * Whether BootUI instruments Spring's {@code RestClient}, {@code RestTemplate}, and
         * {@code WebClient} beans to capture outbound HTTP calls. When {@code false}, no
         * customizer is registered.
         */
        private boolean enabled = true;

        /**
         * Whether new calls are recorded into the in-memory buffer. Recording can be paused
         * and resumed at runtime from the panel without removing the client instrumentation;
         * this sets the initial state.
         */
        private boolean recording = true;

        /**
         * Whether request headers are captured alongside each call. Off by default because
         * header values may contain sensitive data; when enabled, values are captured as-is
         * (subject to truncation) and then masked by header name (e.g. {@code Authorization})
         * at read/report time according to the live exposure policy.
         */
        private boolean captureHeaders = false;

        /**
         * Whether each captured call records the first application stack frame that triggered
         * it (e.g. {@code OrderClient.findAll(OrderClient.java:42)}), shown per call and
         * aggregated per group so a flagged chatty group shows exactly where in the code to go
         * fix it. On by default: unlike headers, a call site names only the application's own
         * code (class/method/line), never a value, so it carries no sensitive-data risk — this
         * toggle is purely about the small per-call stack-walk cost, not privacy.
         */
        private boolean captureCallSite = true;

        /**
         * Maximum number of outbound calls retained in the in-memory ring buffer.
         */
        private int maxEntries = 200;

        /**
         * Calls taking at least this many milliseconds are flagged as slow. Set to {@code 0} to
         * disable slow-call flagging. Defaults higher than SQL Trace's threshold since outbound
         * HTTP calls are typically slower than a single SQL execution.
         */
        private long slowCallThresholdMillis = 1000;

        /**
         * Maximum retained length of the request URI and path; longer values are truncated.
         */
        private int maxUriLength = 2000;

        /**
         * Maximum retained length of a single captured header value.
         */
        private int maxHeaderValueLength = 200;

        /**
         * Number of calls to the same method/host/path (with numeric and UUID path segments
         * normalized) within the buffer before the group is flagged as a likely chatty
         * (repeated-call) access pattern. Minimum {@code 2}.
         */
        private int chattyCallThreshold = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRecording() {
            return recording;
        }

        public void setRecording(boolean recording) {
            this.recording = recording;
        }

        public boolean isCaptureHeaders() {
            return captureHeaders;
        }

        public void setCaptureHeaders(boolean captureHeaders) {
            this.captureHeaders = captureHeaders;
        }

        public boolean isCaptureCallSite() {
            return captureCallSite;
        }

        public void setCaptureCallSite(boolean captureCallSite) {
            this.captureCallSite = captureCallSite;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public long getSlowCallThresholdMillis() {
            return slowCallThresholdMillis;
        }

        public void setSlowCallThresholdMillis(long slowCallThresholdMillis) {
            this.slowCallThresholdMillis = slowCallThresholdMillis;
        }

        public int getMaxUriLength() {
            return maxUriLength;
        }

        public void setMaxUriLength(int maxUriLength) {
            this.maxUriLength = maxUriLength;
        }

        public int getMaxHeaderValueLength() {
            return maxHeaderValueLength;
        }

        public void setMaxHeaderValueLength(int maxHeaderValueLength) {
            this.maxHeaderValueLength = maxHeaderValueLength;
        }

        public int getChattyCallThreshold() {
            return chattyCallThreshold;
        }

        public void setChattyCallThreshold(int chattyCallThreshold) {
            this.chattyCallThreshold = chattyCallThreshold;
        }
    }

    public static class Kafka {

        /**
         * Whether BootUI captures {@code KafkaTemplate} sends and {@code @KafkaListener} deliveries
         * into the Live Activity stream as {@code MESSAGING} entries. When {@code false}, no
         * {@code KafkaTemplate}/listener container factory bean is post-processed. Only takes effect
         * when {@code spring-kafka} is on the classpath.
         */
        private boolean enabled = true;

        /**
         * Whether the message key is retained alongside each captured entry (truncated to {@link
         * #maxKeyLength}). On by default since a Kafka key is typically a correlation/partitioning id
         * rather than a secret, but this lets an application disable it when its keys do carry sensitive
         * data. The message value/payload is never captured at all, regardless of this setting: unlike a
         * SQL statement or a config value, it is an arbitrary, potentially large application payload
         * with no generic masking strategy.
         */
        private boolean captureKey = true;

        /**
         * Maximum number of captured messages retained in the in-memory ring buffer.
         */
        private int maxEntries = 200;

        /**
         * Maximum retained length of a captured message key; longer keys are truncated.
         */
        private int maxKeyLength = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCaptureKey() {
            return captureKey;
        }

        public void setCaptureKey(boolean captureKey) {
            this.captureKey = captureKey;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public int getMaxKeyLength() {
            return maxKeyLength;
        }

        public void setMaxKeyLength(int maxKeyLength) {
            this.maxKeyLength = maxKeyLength;
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
         * Stamp BootUI {@code bootui.*} enrichment attributes on request spans (service identity, SQL
         * volume / N+1 suspicion, exception presence) so a cross-service trace waterfall carries BootUI
         * depth. On by default when telemetry is enabled; set to {@code false} to leave capture on but
         * suppress enrichment.
         */
        private boolean enrich = true;

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

        public boolean isEnrich() {
            return enrich;
        }

        public void setEnrich(boolean enrich) {
            this.enrich = enrich;
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
    public static class Copilot implements io.github.jdubois.bootui.spi.agent.AgentSessionProperties {

        /**
         * Enable the Copilot panel. AUTO activates only when the session-state directory exists.
         */
        private Mode enabled = Mode.AUTO;

        @Override
        public boolean enabledOn() {
            return enabled == Mode.ON;
        }

        @Override
        public boolean enabledAuto() {
            return enabled == Mode.AUTO;
        }

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

    /**
     * Settings for the local, opt-in MCP (Model Context Protocol) server that exposes BootUI
     * advisors and diagnostics as tools to local AI agents.
     *
     * <p>Disabled by default ({@link Mode#OFF}). This property sets the <em>initial</em> state of the
     * server; {@code bootui.mcp.enabled=ON} starts it enabled and exposes the JSON-RPC endpoint at
     * {@code /bootui/api/mcp}. The MCP Server panel can toggle the server on or off at runtime
     * (overriding this value) via {@code POST /bootui/api/mcp-server/toggle}. Even when enabled, the
     * endpoint stays behind the same loopback / Host allow-list / cross-site write defenses as the
     * rest of the BootUI API, and every tool honors the per-panel {@code bootui.panels.*} enable and
     * read-only toggles.
     */
    public static class Mcp {

        /**
         * Initial state of the local MCP server. {@link Mode#OFF} by default (fail closed);
         * {@link Mode#ON} starts the server enabled. {@link Mode#AUTO} is treated the same as
         * {@link Mode#OFF} so the server is never silently exposed. The MCP Server panel can override
         * this at runtime.
         */
        private Mode enabled = Mode.OFF;

        /**
         * Maximum number of items returned by paginated read tools (config, beans, mappings,
         * security logs, traces, HTTP exchanges) in a single call.
         */
        private int maxResults = 200;

        public Mode getEnabled() {
            return enabled;
        }

        public void setEnabled(Mode enabled) {
            this.enabled = enabled == null ? Mode.OFF : enabled;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }

    /**
     * Settings for the read-only Live Activity panel, which merges BootUI's existing in-memory
     * signal buffers (HTTP exchanges, SQL trace, exceptions, security events) into one
     * reverse-chronological stream and offers a Symfony-style per-request profile. All data is
     * sourced through the existing controllers, so masking, self-filtering and buffer bounds are
     * inherited; these settings only tune presentation and correlation heuristics.
     */
    public static class Activity {

        /**
         * Maximum number of normalized entries returned in a single activity-stream page after
         * merging and sorting all sources.
         */
        private int maxEntries = 200;

        /**
         * Threshold in milliseconds above which a request is flagged as slow in the stream and KPI
         * strip.
         */
        private long requestSlowThresholdMs = 1000;

        /**
         * Number of identical normalized SELECT statements correlated to a single request above
         * which an N+1 hint is surfaced in the per-request profile.
         */
        private int nPlusOneThreshold = 5;

        /**
         * Optional durable-storage backend for captured entries, in addition to today's
         * in-memory-only default.
         */
        private ActivityPersistence persistence = new ActivityPersistence();

        /**
         * Maximum number of recent {@code @Scheduled} task executions retained for the
         * {@code SCHEDULED_TASK} entries in the activity stream.
         */
        private int maxScheduledTaskRuns = 200;

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public long getRequestSlowThresholdMs() {
            return requestSlowThresholdMs;
        }

        public void setRequestSlowThresholdMs(long requestSlowThresholdMs) {
            this.requestSlowThresholdMs = requestSlowThresholdMs;
        }

        public int getNPlusOneThreshold() {
            return nPlusOneThreshold;
        }

        public void setNPlusOneThreshold(int nPlusOneThreshold) {
            this.nPlusOneThreshold = nPlusOneThreshold;
        }

        public ActivityPersistence getPersistence() {
            return persistence;
        }

        public void setPersistence(ActivityPersistence persistence) {
            this.persistence = persistence == null ? new ActivityPersistence() : persistence;
        }

        public int getMaxScheduledTaskRuns() {
            return maxScheduledTaskRuns;
        }

        public void setMaxScheduledTaskRuns(int maxScheduledTaskRuns) {
            this.maxScheduledTaskRuns = maxScheduledTaskRuns;
        }
    }

    /**
     * Settings for the Email Viewer panel, which intercepts every {@code JavaMailSender.send(...)} call
     * into a bounded ring buffer before delegating to the real sender (pass-through by default).
     */
    public static class Email {

        /** Maximum number of captured messages retained; the oldest is evicted once full. */
        private int maxEntries = 100;

        /**
         * When {@code true}, captured messages are recorded but never actually handed to the real mail
         * transport (like MailDev/GreenMail's dev-trap behavior). Off by default, so BootUI never
         * silently swallows application mail.
         */
        private boolean devTrap = false;

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public boolean isDevTrap() {
            return devTrap;
        }

        public void setDevTrap(boolean devTrap) {
            this.devTrap = devTrap;
        }
    }

    /**
     * Configures the optional durable-storage backend for Live Activity. Disabled by default, which
     * keeps today's behavior unchanged: entries live only in the small in-memory buffers of the four
     * signal sources and nothing survives a restart. When enabled, captured entries are additionally
     * buffered and flushed to a SQL database over direct JDBC, so the dashboard can page back through
     * history beyond what fits in memory.
     */
    public static class ActivityPersistence {

        /**
         * Whether captured Live Activity entries are also durably persisted, in addition to today's
         * in-memory-only default. Disabled by default: no background thread, connection or bean beyond
         * what already exists is created while this is {@code false}.
         */
        private boolean enabled = false;

        /**
         * Where the durable store gets its JDBC connections from: {@code SHARED} reuses the host
         * application's own {@code DataSource} bean (the same one BootUI's SQL Trace panel may already
         * be tracing), {@code DEDICATED} opens a small, non-pooled connection of BootUI's own using the
         * {@code dedicated-*} properties below.
         */
        private DataSourceMode dataSourceMode = DataSourceMode.SHARED;

        /** JDBC URL for {@code data-source-mode=DEDICATED}, otherwise ignored. */
        private String dedicatedJdbcUrl;

        /** Username for {@code data-source-mode=DEDICATED}, otherwise ignored. */
        private String dedicatedUsername;

        /** Password for {@code data-source-mode=DEDICATED}, otherwise ignored. */
        private String dedicatedPassword;

        /**
         * Optional explicit JDBC driver class to load for {@code data-source-mode=DEDICATED}; blank
         * lets the driver auto-register itself from the classpath (the usual case for a modern JDBC 4+
         * driver).
         */
        private String dedicatedDriverClassName;

        /**
         * Table name every BootUI instance pointed at the same database shares. Must be a plain SQL
         * identifier; created automatically on first use if it does not already exist.
         */
        private String tableName = "bootui_activity";

        /** How often buffered entries are flushed to durable storage. */
        private Duration flushInterval = Duration.ofSeconds(5);

        /**
         * Capacity of both the in-memory hot read cache (entries visible immediately, even before
         * their scheduled flush) and the pending-flush queue (entries awaiting their first durable
         * write).
         */
        private int bufferMaxEntries = 500;

        /**
         * How long persisted rows are kept before being pruned; entries older than this are eligible
         * for deletion on this instance's own next prune pass. Only this instance's own rows are ever
         * pruned.
         */
        private Duration retention = Duration.ofDays(7);

        /**
         * The multi-tenant partition key this running instance writes and reads its rows under, so
         * several BootUI instances can safely share one database table. Defaults to the {@code
         * HOSTNAME} environment variable (a natural, stable identity for a container/Kubernetes pod) or
         * else a generated {@code <app-name>-<random>} id, computed once at startup.
         */
        private String instanceId;

        /** How often the capture coordinator polls the merged Live Activity feed for new entries. */
        private Duration captureInterval = Duration.ofSeconds(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public DataSourceMode getDataSourceMode() {
            return dataSourceMode;
        }

        public void setDataSourceMode(DataSourceMode dataSourceMode) {
            this.dataSourceMode = dataSourceMode == null ? DataSourceMode.SHARED : dataSourceMode;
        }

        public String getDedicatedJdbcUrl() {
            return dedicatedJdbcUrl;
        }

        public void setDedicatedJdbcUrl(String dedicatedJdbcUrl) {
            this.dedicatedJdbcUrl = dedicatedJdbcUrl;
        }

        public String getDedicatedUsername() {
            return dedicatedUsername;
        }

        public void setDedicatedUsername(String dedicatedUsername) {
            this.dedicatedUsername = dedicatedUsername;
        }

        public String getDedicatedPassword() {
            return dedicatedPassword;
        }

        public void setDedicatedPassword(String dedicatedPassword) {
            this.dedicatedPassword = dedicatedPassword;
        }

        public String getDedicatedDriverClassName() {
            return dedicatedDriverClassName;
        }

        public void setDedicatedDriverClassName(String dedicatedDriverClassName) {
            this.dedicatedDriverClassName = dedicatedDriverClassName;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public Duration getFlushInterval() {
            return flushInterval;
        }

        public void setFlushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
        }

        public int getBufferMaxEntries() {
            return bufferMaxEntries;
        }

        public void setBufferMaxEntries(int bufferMaxEntries) {
            this.bufferMaxEntries = bufferMaxEntries;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public Duration getCaptureInterval() {
            return captureInterval;
        }

        public void setCaptureInterval(Duration captureInterval) {
            this.captureInterval = captureInterval;
        }

        /** Where the durable store gets its JDBC connections from. */
        public enum DataSourceMode {
            SHARED,
            DEDICATED
        }
    }

    public static class FreeOnIdle {

        /**
         * Whether BootUI releases its live in-memory diagnostic buffers (captured SQL, ingested
         * traces, and the request/security correlation windows) after the console has been idle for
         * {@code timeout}, and stops recording into them until the console is used again. The
         * Exceptions and Log Tail buffers are kept so a recent error is still visible when you open
         * the console. Has no effect in production, where BootUI is inactive.
         */
        private boolean enabled = true;

        /**
         * How long the console may go without any request before its live buffers are released. The
         * timer resets on every BootUI request (loading the UI, API polling, or opening a stream), so
         * an open console never reclaims; only a genuinely unused one does. Clamped to a minimum of
         * one second.
         */
        private Duration timeout = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
