package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.insights.AiUsageByRoute;
import io.github.jdubois.bootui.engine.inventory.CodeInventorySettings;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.journal.ActivityFeedSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import io.github.jdubois.bootui.engine.postgres.PostgresRowLimits;
import io.github.jdubois.bootui.engine.resources.ResourceSettings;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "bootui")
public class BootUiProperties {

    private Mode enabled = Mode.AUTO;
    private String path = "/bootui";
    private String apiPath = null;
    private boolean allowNonLocalhost = false;
    private String[] allowedHosts = {};
    private String[] trustedProxies = {};
    private Mode trustContainerGateway = Mode.OFF;
    private Authentication authentication = new Authentication();
    private boolean maskSecrets = true;
    private ValueExposure exposeValues = ValueExposure.MASKED;
    private boolean showBanner = true;
    private boolean readOnly = false;
    private String[] enabledProfiles = BootUiDefaults.ENABLED_PROFILES.toArray(new String[0]);
    private String[] disabledProfiles = BootUiDefaults.DISABLED_PROFILES.toArray(new String[0]);
    private String overridesFile = ".bootui/application-bootui.properties";
    private Map<String, Panel> panels = new LinkedHashMap<>();
    private Advisors advisors = new Advisors();
    private Monitoring monitoring = new Monitoring();
    private DevServices devServices = new DevServices();
    private Cache cache = new Cache();
    private SecurityLogs securityLogs = new SecurityLogs();
    private Exceptions exceptions = new Exceptions();
    private Vulnerabilities vulnerabilities = new Vulnerabilities();
    private GitHub github = new GitHub();
    private HttpExchanges httpExchanges = new HttpExchanges();
    private LogTail logTail = new LogTail();
    private SqlTrace sqlTrace = new SqlTrace();
    private Postgresql postgresql = new Postgresql();
    private Mysql mysql = new Mysql();
    private Transactions transactions = new Transactions();
    private RestClientTrace restClientTrace = new RestClientTrace();
    private Kafka kafka = new Kafka();
    private Jms jms = new Jms();

    private FaultTolerance faultTolerance = new FaultTolerance();
    private Rabbitmq rabbitmq = new Rabbitmq();
    private WebSockets websockets = new WebSockets();
    private HttpSessions httpSessions = new HttpSessions();
    private HeapDump heapDump = new HeapDump();
    private Threads threads = new Threads();
    private Telemetry telemetry = new Telemetry();
    private Ai ai = new Ai();
    private Copilot copilot = new Copilot();
    private ClaudeCode claudeCode = new ClaudeCode();
    private Graalvm graalvm = new Graalvm();
    private Mcp mcp = new Mcp();
    private Cli cli = new Cli();
    private Activity activity = new Activity();
    private RuntimeJournal runtimeJournal = new RuntimeJournal();
    private RuntimeInsights runtimeInsights = new RuntimeInsights();
    private CodeInventory codeInventory = new CodeInventory();
    private Resources resources = new Resources();
    private Agent agent = new Agent();
    private Email email = new Email();
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
        this.path = BootUiPathNormalizer.normalize(path);
    }

    public String getApiPath() {
        return apiPath != null ? apiPath : (path + "/api");
    }

    public void setApiPath(String apiPath) {
        this.apiPath = BootUiPathNormalizer.normalizeApiPath(apiPath);
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

    public Authentication getAuthentication() {
        return authentication;
    }

    public void setAuthentication(Authentication authentication) {
        this.authentication = authentication == null ? new Authentication() : authentication;
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

    public Advisors getAdvisors() {
        return advisors;
    }

    public void setAdvisors(Advisors advisors) {
        this.advisors = java.util.Objects.requireNonNull(advisors);
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

    public Postgresql getPostgresql() {
        return postgresql;
    }

    public void setPostgresql(Postgresql postgresql) {
        this.postgresql = postgresql == null ? new Postgresql() : postgresql;
    }

    public Mysql getMysql() {
        return mysql;
    }

    public void setMysql(Mysql mysql) {
        this.mysql = mysql == null ? new Mysql() : mysql;
    }

    public Transactions getTransactions() {
        return transactions;
    }

    public void setTransactions(Transactions transactions) {
        this.transactions = transactions == null ? new Transactions() : transactions;
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

    public Jms getJms() {
        return jms;
    }

    public WebSockets getWebsockets() {
        return websockets;
    }

    public void setWebsockets(WebSockets websockets) {
        this.websockets = websockets == null ? new WebSockets() : websockets;
    }

    public FaultTolerance getFaultTolerance() {
        return faultTolerance;
    }

    public void setFaultTolerance(FaultTolerance faultTolerance) {
        this.faultTolerance = faultTolerance == null ? new FaultTolerance() : faultTolerance;
    }

    public void setJms(Jms jms) {
        this.jms = jms == null ? new Jms() : jms;
    }

    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    public void setRabbitmq(Rabbitmq rabbitmq) {
        this.rabbitmq = rabbitmq == null ? new Rabbitmq() : rabbitmq;
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

    public Threads getThreads() {
        return threads;
    }

    public void setThreads(Threads threads) {
        this.threads = threads == null ? new Threads() : threads;
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

    public Cli getCli() {
        return cli;
    }

    public void setCli(Cli cli) {
        this.cli = cli == null ? new Cli() : cli;
    }

    public Activity getActivity() {
        return activity;
    }

    public RuntimeJournal getRuntimeJournal() {
        return runtimeJournal;
    }

    public void setRuntimeJournal(RuntimeJournal runtimeJournal) {
        this.runtimeJournal = runtimeJournal == null ? new RuntimeJournal() : runtimeJournal;
    }

    public RuntimeInsights getRuntimeInsights() {
        return runtimeInsights;
    }

    public void setRuntimeInsights(RuntimeInsights runtimeInsights) {
        this.runtimeInsights = runtimeInsights == null ? new RuntimeInsights() : runtimeInsights;
    }

    public CodeInventory getCodeInventory() {
        return codeInventory;
    }

    public void setCodeInventory(CodeInventory codeInventory) {
        this.codeInventory = codeInventory == null ? new CodeInventory() : codeInventory;
    }

    public Resources getResources() {
        return resources;
    }

    public void setResources(Resources resources) {
        this.resources = resources == null ? new Resources() : resources;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent == null ? new Agent() : agent;
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

    public enum Mode {
        AUTO,
        ON,
        OFF
    }

    public static class Panel {

        private boolean enabled = true;

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

        private boolean restartEnabled = false;

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

        private boolean clearEnabled = true;

        private boolean activityCaptureEnabled = true;

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

        private int maxLogs = 500;

        public int getMaxLogs() {
            return maxLogs;
        }

        public void setMaxLogs(int maxLogs) {
            this.maxLogs = maxLogs;
        }
    }

    public static class Exceptions {

        private int maxGroups = 100;

        private int maxOccurrencesPerGroup = 25;

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

        private boolean osvEnabled = true;

        private Duration requestTimeout = Duration.ofSeconds(10);

        private int maxPackages = 500;

        private int maxAdvisories = 200;

        private String osvBaseUri = "https://api.osv.dev";

        private boolean epssEnabled = true;

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

        private boolean repositoryLookupEnabled = true;

        private Duration repositoryLookupTimeout = Duration.ofSeconds(2);

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

        private boolean apiEnabled = true;

        private Duration requestTimeout = Duration.ofSeconds(5);

        private int maxPullRequests = 10;

        private int maxIssues = 25;

        private int maxSecurityAlerts = 50;

        private int maxWorkflowRuns = 20;

        private int quotaSafetyThreshold = 10;

        private int maxApiCalls = 17;

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

        private int maxExchanges = 200;

        private int reservedSharePercent = 25;

        public int getMaxExchanges() {
            return maxExchanges;
        }

        public void setMaxExchanges(int maxExchanges) {
            this.maxExchanges = maxExchanges;
        }

        public int getReservedSharePercent() {
            return reservedSharePercent;
        }

        public void setReservedSharePercent(int reservedSharePercent) {
            this.reservedSharePercent = reservedSharePercent;
        }
    }

    public static class LogTail {

        private long maxBytes = 0;

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Postgresql {

        private int maxSessions = 100;
        private int maxStatements = 100;
        private int maxIndexes = 500;
        private int maxTables = 200;
        private int maxVacuumTables = 200;
        private int maxReplicas = 10;
        private int maxSettings = 40;

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = PostgresRowLimits.requireValid("bootui.postgresql.max-sessions", maxSessions);
        }

        public int getMaxStatements() {
            return maxStatements;
        }

        public void setMaxStatements(int maxStatements) {
            this.maxStatements = PostgresRowLimits.requireValid("bootui.postgresql.max-statements", maxStatements);
        }

        public int getMaxIndexes() {
            return maxIndexes;
        }

        public void setMaxIndexes(int maxIndexes) {
            this.maxIndexes = PostgresRowLimits.requireValid("bootui.postgresql.max-indexes", maxIndexes);
        }

        public int getMaxTables() {
            return maxTables;
        }

        public void setMaxTables(int maxTables) {
            this.maxTables = PostgresRowLimits.requireValid("bootui.postgresql.max-tables", maxTables);
        }

        public int getMaxVacuumTables() {
            return maxVacuumTables;
        }

        public void setMaxVacuumTables(int maxVacuumTables) {
            this.maxVacuumTables =
                    PostgresRowLimits.requireValid("bootui.postgresql.max-vacuum-tables", maxVacuumTables);
        }

        public int getMaxReplicas() {
            return maxReplicas;
        }

        public void setMaxReplicas(int maxReplicas) {
            this.maxReplicas = PostgresRowLimits.requireValid("bootui.postgresql.max-replicas", maxReplicas);
        }

        public int getMaxSettings() {
            return maxSettings;
        }

        public void setMaxSettings(int maxSettings) {
            this.maxSettings = PostgresRowLimits.requireValid("bootui.postgresql.max-settings", maxSettings);
        }
    }

    public static class Mysql {

        private int maxSessions = 100;
        private int maxStatements = 100;
        private int maxIndexes = 500;
        private int maxTables = 200;
        private int maxLockWaits = 100;
        private int maxReplicationChannels = 10;
        private int maxSettings = 40;

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int value) {
            maxSessions = MySqlRowLimits.requireValid("bootui.mysql.max-sessions", value);
        }

        public int getMaxStatements() {
            return maxStatements;
        }

        public void setMaxStatements(int value) {
            maxStatements = MySqlRowLimits.requireValid("bootui.mysql.max-statements", value);
        }

        public int getMaxIndexes() {
            return maxIndexes;
        }

        public void setMaxIndexes(int value) {
            maxIndexes = MySqlRowLimits.requireValid("bootui.mysql.max-indexes", value);
        }

        public int getMaxTables() {
            return maxTables;
        }

        public void setMaxTables(int value) {
            maxTables = MySqlRowLimits.requireValid("bootui.mysql.max-tables", value);
        }

        public int getMaxLockWaits() {
            return maxLockWaits;
        }

        public void setMaxLockWaits(int value) {
            maxLockWaits = MySqlRowLimits.requireValid("bootui.mysql.max-lock-waits", value);
        }

        public int getMaxReplicationChannels() {
            return maxReplicationChannels;
        }

        public void setMaxReplicationChannels(int value) {
            maxReplicationChannels = MySqlRowLimits.requireValid("bootui.mysql.max-replication-channels", value);
        }

        public int getMaxSettings() {
            return maxSettings;
        }

        public void setMaxSettings(int value) {
            maxSettings = MySqlRowLimits.requireValid("bootui.mysql.max-settings", value);
        }
    }

    public static class SqlTrace {

        private boolean enabled = true;

        private boolean recording = true;

        private boolean captureParameters = false;

        private boolean captureCallSite = true;

        private int maxEntries = 200;

        private long slowQueryThresholdMillis = 100;

        private int reservedSharePercent = 25;

        private int maxSqlLength = 2000;

        private int maxParameterLength = 200;

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

        public int getReservedSharePercent() {
            return reservedSharePercent;
        }

        public void setReservedSharePercent(int reservedSharePercent) {
            this.reservedSharePercent = reservedSharePercent;
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

    public static class Transactions {

        private boolean enabled = true;

        private boolean recording = true;

        private int maxEntries = 200;

        private long slowTransactionThresholdMillis = 200;

        private long connectionHoldThresholdMillis = 500;

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

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public long getSlowTransactionThresholdMillis() {
            return slowTransactionThresholdMillis;
        }

        public void setSlowTransactionThresholdMillis(long slowTransactionThresholdMillis) {
            this.slowTransactionThresholdMillis = slowTransactionThresholdMillis;
        }

        public long getConnectionHoldThresholdMillis() {
            return connectionHoldThresholdMillis;
        }

        public void setConnectionHoldThresholdMillis(long connectionHoldThresholdMillis) {
            this.connectionHoldThresholdMillis = connectionHoldThresholdMillis;
        }
    }

    public static class RestClientTrace {

        private boolean enabled = true;

        private boolean recording = true;

        private boolean captureHeaders = false;

        private boolean captureCallSite = true;

        private int maxEntries = 200;

        private long slowCallThresholdMillis = 1000;

        private int reservedSharePercent = 25;

        private int maxUriLength = 2000;

        private int maxHeaderValueLength = 200;

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

        public int getReservedSharePercent() {
            return reservedSharePercent;
        }

        public void setReservedSharePercent(int reservedSharePercent) {
            this.reservedSharePercent = reservedSharePercent;
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

        private boolean enabled = true;

        private boolean captureKey = true;

        private int maxEntries = 200;

        private int maxKeyLength = 16;

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

    public static class Jms {

        private boolean enabled = true;

        private boolean captureMessageId = true;

        private int maxEntries = 200;

        private int maxMessageIdLength = 16;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCaptureMessageId() {
            return captureMessageId;
        }

        public void setCaptureMessageId(boolean captureMessageId) {
            this.captureMessageId = captureMessageId;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public int getMaxMessageIdLength() {
            return maxMessageIdLength;
        }

        public void setMaxMessageIdLength(int maxMessageIdLength) {
            this.maxMessageIdLength = maxMessageIdLength;
        }
    }

    public static class WebSockets {

        private boolean enabled = true;

        private boolean capturing = true;

        private int maxEndpoints = 200;

        private int maxSessions = 200;

        private int maxSubscriptions = 500;

        private int maxActivityEntries = 500;

        private int maxTrackedSessions = 2000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCapturing() {
            return capturing;
        }

        public void setCapturing(boolean capturing) {
            this.capturing = capturing;
        }

        public int getMaxEndpoints() {
            return maxEndpoints;
        }

        public void setMaxEndpoints(int maxEndpoints) {
            this.maxEndpoints = maxEndpoints;
        }

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }

        public int getMaxSubscriptions() {
            return maxSubscriptions;
        }

        public void setMaxSubscriptions(int maxSubscriptions) {
            this.maxSubscriptions = maxSubscriptions;
        }

        public int getMaxActivityEntries() {
            return maxActivityEntries;
        }

        public void setMaxActivityEntries(int maxActivityEntries) {
            this.maxActivityEntries = maxActivityEntries;
        }

        public int getMaxTrackedSessions() {
            return maxTrackedSessions;
        }

        public void setMaxTrackedSessions(int maxTrackedSessions) {
            this.maxTrackedSessions = maxTrackedSessions;
        }
    }

    public static class FaultTolerance {

        private boolean enabled = true;

        private int maxEvents = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }
    }

    public static class Rabbitmq {
        private boolean enabled = true;

        private boolean captureCorrelationId = false;

        private int maxEntries = 200;

        private int maxCorrelationIdLength = 16;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCaptureCorrelationId() {
            return captureCorrelationId;
        }

        public void setCaptureCorrelationId(boolean captureCorrelationId) {
            this.captureCorrelationId = captureCorrelationId;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public int getMaxCorrelationIdLength() {
            return maxCorrelationIdLength;
        }

        public void setMaxCorrelationIdLength(int maxCorrelationIdLength) {
            this.maxCorrelationIdLength = maxCorrelationIdLength;
        }
    }

    public static class HttpSessions {

        private int maxSessions = 50;

        public int getMaxSessions() {
            return maxSessions;
        }

        public void setMaxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
        }
    }

    public static class Threads {
        private int maxThreads = 2048;
        private int maxStackDepth = 128;
        private int maxOwnedLocks = 256;
        private long captureDeadlineMillis = 2000;
        private boolean includeSynchronizers;
        private int maxCaptures = 6;
        private long maxRetainedBytes = 16 * 1024 * 1024L;
        private long maxCaptureBytes = 4 * 1024 * 1024L;
        private long ttlMillis = 300_000;
        private int observationSamples = 3;
        private long observationIntervalMillis = 5000;

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int value) {
            maxThreads = value;
        }

        public int getMaxStackDepth() {
            return maxStackDepth;
        }

        public void setMaxStackDepth(int value) {
            maxStackDepth = value;
        }

        public int getMaxOwnedLocks() {
            return maxOwnedLocks;
        }

        public void setMaxOwnedLocks(int value) {
            maxOwnedLocks = value;
        }

        public long getCaptureDeadlineMillis() {
            return captureDeadlineMillis;
        }

        public void setCaptureDeadlineMillis(long value) {
            captureDeadlineMillis = value;
        }

        public boolean isIncludeSynchronizers() {
            return includeSynchronizers;
        }

        public void setIncludeSynchronizers(boolean value) {
            includeSynchronizers = value;
        }

        public int getMaxCaptures() {
            return maxCaptures;
        }

        public void setMaxCaptures(int value) {
            maxCaptures = value;
        }

        public long getMaxRetainedBytes() {
            return maxRetainedBytes;
        }

        public void setMaxRetainedBytes(long value) {
            maxRetainedBytes = value;
        }

        public long getMaxCaptureBytes() {
            return maxCaptureBytes;
        }

        public void setMaxCaptureBytes(long value) {
            maxCaptureBytes = value;
        }

        public long getTtlMillis() {
            return ttlMillis;
        }

        public void setTtlMillis(long value) {
            ttlMillis = value;
        }

        public int getObservationSamples() {
            return observationSamples;
        }

        public void setObservationSamples(int value) {
            observationSamples = value;
        }

        public long getObservationIntervalMillis() {
            return observationIntervalMillis;
        }

        public void setObservationIntervalMillis(long value) {
            observationIntervalMillis = value;
        }
    }

    public static class HeapDump {

        private boolean captureEnabled = true;

        private boolean allowRawDownload = false;

        private String outputDir = ".bootui/heap-dumps";

        private int maxDumps = 5;

        private int maxClasses = 1000;

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

    public static class Advisors {
        private volatile int maxRetainedViolations = 10_000;

        public int getMaxRetainedViolations() {
            return maxRetainedViolations;
        }

        public void setMaxRetainedViolations(int maxRetainedViolations) {
            if (maxRetainedViolations <= 0) {
                throw new IllegalArgumentException("bootui.advisors.max-retained-violations must be positive.");
            }
            this.maxRetainedViolations = maxRetainedViolations;
        }
    }

    public static class Monitoring {

        private boolean excludeSelf = true;

        public boolean isExcludeSelf() {
            return excludeSelf;
        }

        public void setExcludeSelf(boolean excludeSelf) {
            this.excludeSelf = excludeSelf;
        }
    }

    public static class Telemetry {

        private boolean enabled = true;

        private int maxTraces = 500;

        private int maxSpansPerTrace = 500;

        private int maxAttributeValueBytes = 4 * 1024;

        private boolean excludeSelfSpans = true;

        private boolean enrich = true;

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

        private int tokenSeriesMinutes = 60;

        private int maxRecentChats = 100;

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

    public static class Copilot implements io.github.jdubois.bootui.spi.agent.AgentSessionProperties {

        private Mode enabled = Mode.AUTO;

        @Override
        public boolean enabledOn() {
            return enabled == Mode.ON;
        }

        @Override
        public boolean enabledAuto() {
            return enabled == Mode.AUTO;
        }

        private String sessionStateDir;

        private int maxEventsPerSession = 2000;

        private int maxSessions = 100;

        private int maxParsedSessions = 100;

        private Duration streamDebounce = Duration.ofMillis(400);

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

    public static class Mcp {

        private Mode enabled = Mode.OFF;

        private int maxResults = 200;

        private int maxPayloadBytes = 1 * 1024 * 1024;

        private int maxConcurrentCalls = 20;

        private Duration executionTimeout = Duration.ofSeconds(30);

        private int maxResponseBytes = 4 * 1024 * 1024;

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

        public int getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        public void setMaxPayloadBytes(int maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
        }

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public Duration getExecutionTimeout() {
            return executionTimeout;
        }

        public void setExecutionTimeout(Duration executionTimeout) {
            this.executionTimeout = executionTimeout == null ? Duration.ofSeconds(30) : executionTimeout;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }
    }

    public static class Cli {

        private boolean enabled = true;

        private int maxResults = 200;

        private int maxConcurrentCalls = McpProtocol.DEFAULT_MAX_CONCURRENT_CALLS;

        private Duration executionTimeout = Duration.ofMillis(McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS);

        private String instancesDir;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public Duration getExecutionTimeout() {
            return executionTimeout;
        }

        public void setExecutionTimeout(Duration executionTimeout) {
            this.executionTimeout = executionTimeout == null
                    ? Duration.ofMillis(McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS)
                    : executionTimeout;
        }

        public String getInstancesDir() {
            return instancesDir;
        }

        public void setInstancesDir(String instancesDir) {
            this.instancesDir = instancesDir;
        }
    }

    public static class RuntimeInsights {

        private long aiTokenThreshold = AiUsageByRoute.DEFAULT_TOKEN_THRESHOLD;

        public long getAiTokenThreshold() {
            return aiTokenThreshold;
        }

        public void setAiTokenThreshold(long aiTokenThreshold) {
            this.aiTokenThreshold = AiUsageByRoute.validateTokenThreshold(aiTokenThreshold);
        }
    }

    public static class CodeInventory {

        private int maxClasses = CodeInventorySettings.DEFAULT_MAX_CLASSES;

        private Duration scanTimeout = CodeInventorySettings.DEFAULT_SCAN_TIMEOUT;

        public int getMaxClasses() {
            return maxClasses;
        }

        public void setMaxClasses(int maxClasses) {
            if (maxClasses < 1) {
                throw new IllegalArgumentException("bootui.code-inventory.max-classes must be positive.");
            }
            this.maxClasses = maxClasses;
        }

        public Duration getScanTimeout() {
            return scanTimeout;
        }

        public void setScanTimeout(Duration scanTimeout) {
            if (scanTimeout == null || scanTimeout.isNegative() || scanTimeout.isZero()) {
                throw new IllegalArgumentException("bootui.code-inventory.scan-timeout must be positive.");
            }
            this.scanTimeout = scanTimeout;
        }

        public CodeInventorySettings toSettings() {
            return new CodeInventorySettings(maxClasses, scanTimeout);
        }
    }

    public static class RuntimeJournal {

        private boolean enabled = true;

        private int maxEvents = RuntimeJournalSettings.DEFAULT_MAX_EVENTS;

        private DataSize maxBytes;

        private DataSize agentEvidenceMaxBytes;

        private int queueCapacity = RuntimeJournalSettings.DEFAULT_QUEUE_CAPACITY;

        private List<String> sources;

        private String baselineFile;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaselineFile() {
            return baselineFile;
        }

        public void setBaselineFile(String baselineFile) {
            this.baselineFile = baselineFile;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public DataSize getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(DataSize maxBytes) {
            this.maxBytes = maxBytes;
        }

        public DataSize getAgentEvidenceMaxBytes() {
            return agentEvidenceMaxBytes;
        }

        public void setAgentEvidenceMaxBytes(DataSize agentEvidenceMaxBytes) {
            this.agentEvidenceMaxBytes = agentEvidenceMaxBytes;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public List<String> getSources() {
            return sources;
        }

        public void setSources(List<String> sources) {
            this.sources = sources;
        }

        public RuntimeJournalSettings toSettings() {
            return RuntimeJournalSettings.of(
                    enabled,
                    maxEvents,
                    maxBytes == null ? null : maxBytes.toBytes(),
                    queueCapacity,
                    sources == null ? null : String.join(",", sources));
        }
    }

    public static class Agent {

        private boolean enabled = true;

        private boolean allowInDisabledProfiles;

        private List<String> packages = new ArrayList<>();

        private String mode = "auto";

        private List<String> sensors = new ArrayList<>(AgentSensorSettings.DEFAULT_SENSORS);

        private int ringCapacity = AgentSensorSettings.DEFAULT_RING_CAPACITY;

        private final Executors executors = new Executors();

        private final SecuritySinks securitySinks = new SecuritySinks();

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isAllowInDisabledProfiles() {
            return allowInDisabledProfiles;
        }

        public void setAllowInDisabledProfiles(boolean allowInDisabledProfiles) {
            this.allowInDisabledProfiles = allowInDisabledProfiles;
        }

        public SecuritySinks getSecuritySinks() {
            return securitySinks;
        }

        public List<String> getSensors() {
            return sensors;
        }

        public void setSensors(List<String> sensors) {
            this.sensors = sensors == null ? new ArrayList<>() : sensors;
        }

        public Executors getExecutors() {
            return executors;
        }

        public int getRingCapacity() {
            return ringCapacity;
        }

        public void setRingCapacity(int ringCapacity) {
            this.ringCapacity = ringCapacity;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getPackages() {
            return packages;
        }

        public void setPackages(List<String> packages) {
            this.packages = packages == null ? new ArrayList<>() : packages;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            String value = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
            if (!value.equals("auto") && !value.equals("dev") && !value.equals("test")) {
                throw new IllegalArgumentException("bootui.agent.mode must be auto, dev, or test.");
            }
            this.mode = value;
        }

        public static class SecuritySinks {

            private boolean requestValues;

            public boolean isRequestValues() {
                return requestValues;
            }

            public void setRequestValues(boolean requestValues) {
                this.requestValues = requestValues;
            }
        }

        public static class Executors {

            private List<String> skipTasks = new ArrayList<>(AgentSensorSettings.DEFAULT_SKIP_TASKS);

            private List<String> skipThreads = new ArrayList<>(AgentSensorSettings.DEFAULT_SKIP_THREADS);

            private Duration maxHandoff = AgentHandoffs.DEFAULT_MAX_HANDOFF;

            public List<String> getSkipTasks() {
                return skipTasks;
            }

            public void setSkipTasks(List<String> skipTasks) {
                this.skipTasks = skipTasks == null ? new ArrayList<>() : skipTasks;
            }

            public List<String> getSkipThreads() {
                return skipThreads;
            }

            public void setSkipThreads(List<String> skipThreads) {
                this.skipThreads = skipThreads == null ? new ArrayList<>() : skipThreads;
            }

            public Duration getMaxHandoff() {
                return maxHandoff;
            }

            public void setMaxHandoff(Duration maxHandoff) {
                if (maxHandoff == null || maxHandoff.isNegative() || maxHandoff.isZero()) {
                    throw new IllegalArgumentException("bootui.agent.executors.max-handoff must be positive.");
                }
                this.maxHandoff = maxHandoff;
            }
        }
    }

    public static class Resources {

        private Duration sampleInterval = ResourceSettings.DEFAULT_SAMPLE_INTERVAL;

        private int maxThreads = ResourceSettings.DEFAULT_MAX_THREADS;

        private final Jfr jfr = new Jfr();

        public Duration getSampleInterval() {
            return sampleInterval;
        }

        public void setSampleInterval(Duration sampleInterval) {
            this.sampleInterval = sampleInterval;
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
        }

        public Jfr getJfr() {
            return jfr;
        }

        public ResourceSettings toSettings() {
            return new ResourceSettings(sampleInterval, maxThreads, jfr.getMaxDuration());
        }

        public static class Jfr {

            private Duration maxDuration = ResourceSettings.DEFAULT_JFR_MAX_DURATION;

            public Duration getMaxDuration() {
                return maxDuration;
            }

            public void setMaxDuration(Duration maxDuration) {
                this.maxDuration = maxDuration;
            }
        }
    }

    public static class Activity {

        private int maxEntries = 200;

        private long requestSlowThresholdMs = 1000;

        private int nPlusOneThreshold = 5;

        private ActivityPersistence persistence = new ActivityPersistence();

        private int maxScheduledTaskRuns = 200;

        private String feedSource = "journal";

        public String getFeedSource() {
            return feedSource;
        }

        public void setFeedSource(String feedSource) {
            ActivityFeedSource.parseConfigured(feedSource);
            this.feedSource = feedSource;
        }

        public ActivityFeedSource feedSource() {
            return ActivityFeedSource.parseConfigured(feedSource);
        }

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

    public static class Email {

        private int maxEntries = 100;

        private boolean devTrap = false;

        private boolean maskContent = false;

        private int maxBodyLength = 200_000;

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

        public boolean isMaskContent() {
            return maskContent;
        }

        public void setMaskContent(boolean maskContent) {
            this.maskContent = maskContent;
        }

        public int getMaxBodyLength() {
            return maxBodyLength;
        }

        public void setMaxBodyLength(int maxBodyLength) {
            this.maxBodyLength = maxBodyLength;
        }
    }

    public static class ActivityPersistence {

        private boolean enabled = false;

        private DataSourceMode dataSourceMode = DataSourceMode.SHARED;

        private String dedicatedJdbcUrl;

        private String dedicatedUsername;

        private String dedicatedPassword;

        private String dedicatedDriverClassName;

        private String tableName = "bootui_activity";

        private Duration flushInterval = Duration.ofSeconds(5);

        private int bufferMaxEntries = 500;

        private Duration retention = Duration.ofDays(7);

        private String instanceId;

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

        public enum DataSourceMode {
            SHARED,
            DEDICATED
        }
    }

    public static class Authentication {

        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class FreeOnIdle {

        private boolean enabled = true;

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
