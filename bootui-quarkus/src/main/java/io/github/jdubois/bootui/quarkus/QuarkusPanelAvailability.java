package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;

/**
 * Computes the BootUI panel manifest for the Quarkus adapter.
 *
 * <p>The panel registry ({@link BootUiPanels}) is shared with the Spring adapter, so panel ids, titles
 * and order are identical and the shared Vue UI renders the same sidebar on both platforms. Availability
 * is platform-specific and computed here (the Spring adapter's {@code PanelsController} computes its own
 * over Actuator/bean presence). This release lights up the framework-neutral Threads, Heap Dump,
 * Live Memory, JVM Tuning and Loggers panels, the Memory advisor (always available: it aggregates
 * JMX heap/GC/thread/class-loading data and runs the shared static rule registry on demand), the Metrics panel (which reports real Micrometer meters when a
 * {@code quarkus-micrometer} registry is present and otherwise renders as unavailable), the HTTP Probe panel
 * (which probes the application's own loopback port), the Health panel (always available: it reports real
 * SmallRye Health when {@code quarkus-smallrye-health} is present and otherwise renders setup guidance), the
 * Architecture (ArchUnit) advisor (which bounds its bytecode import to the application base packages discovered
 * from the build-time Jandex index and runs the shared curated rule registry on demand), plus the
 * OpenTelemetry-backed Traces and AI Usage panels (whose read services are always wired — they simply
 * render empty until spans are captured, which requires {@code quarkus-opentelemetry} on the application
 * classpath), plus the Vulnerabilities panel (statically available: it lists the application's local
 * dependency inventory, captured from the build-time application model, and reaches out to OSV.dev only on
 * the user-initiated {@code POST /scan} — never on render); every other panel is reported unavailable with
 * a clear reason until its Quarkus backing is
 * ported. The <strong>Beans</strong> panel is also lit up (always available like Architecture/Metrics): the
 * shared engine {@code BeansService} reads the live Arc/CDI container through {@code QuarkusBeanProvider}
 * (the Quarkus analogue of the Spring adapter's Actuator-backed provider), with BootUI's own beans filtered
 * out; {@code resource} and inter-bean {@code dependencies} are empty on Quarkus (Arc exposes neither at
 * runtime) and {@code scope} uses the CDI vocabulary. The <strong>Pentesting</strong> (local OWASP hygiene) advisor is also lit up: it reuses the shared
 * engine {@code PentestingScanner}, whose framework-neutral value comes from two synthetic loopback probes
 * (security headers, cookies, CORS, TRACE, technology disclosure), so the Quarkus adapter supplies only the
 * live server port + context path and a deliberately neutral endpoint/security/config snapshot (the
 * Spring-Security and Actuator checks stay inert on Quarkus). Read-only is not yet modelled, so no panel is
 * read-only ({@code readOnlyReason} stays {@code null})
 * — note Traces (its buffer can be cleared), Loggers (a logger level can be set), HTTP Probe (it issues a
 * request), Architecture (it runs a scan and dismisses rules), Pentesting (it runs a scan), Vulnerabilities
 * (it runs an OSV scan) and Cache (it clears caches) are
 * action-capable, plus the Memory advisor (it runs a scan), so they are the Quarkus panels exposing state-changing actions.</p>
 *
 * <p>The <strong>Hibernate</strong> (ORM mapping) advisor is available <em>dynamically</em>: unlike the
 * statically-available panels above, it is lit up only when the application actually uses Hibernate ORM. The
 * deployment processor detects the {@code HIBERNATE_ORM} capability at build time and feeds the decision back
 * as the {@code bootui.internal.hibernate-present} runtime-config default (see
 * {@link #HIBERNATE_PRESENT_KEY}); when Hibernate ORM is absent the panel reports an honest capability hint
 * rather than the generic "not yet ported" reason. The engine scanner and its {@code GET}/{@code POST /scan}
 * resource are always wired regardless — only the optional {@code jakarta.persistence}-reading entity
 * discovery is capability-gated (R2) — so the panel renders a not-configured report rather than failing when
 * the extension is absent.</p>
 *
 * <p>The <strong>Scheduled Tasks</strong> panel is available <em>dynamically</em> in the same way: the deployment
 * processor detects the {@code SCHEDULER} capability at build time and feeds it back as the
 * {@code bootui.internal.scheduled-present} runtime-config default (see {@link #SCHEDULED_PRESENT_KEY}); when
 * {@code quarkus-scheduler} is absent the panel reports an honest capability hint. Unlike Hibernate, no producer is
 * capability-gated — the panel captures {@code @Scheduled} metadata at build time (no runtime
 * {@code io.quarkus.scheduler.*} import), so the engine service and its read resource always wire and render an
 * empty {@code schedulingPresent=false} report when the scheduler is absent.</p>
 * <p>The <strong>Cache</strong> panel (kept under the shared id {@code spring-cache}) is likewise available
 * <em>dynamically</em>: it is lit up only when the application uses Quarkus Cache. The deployment processor
 * detects the {@code CACHE} capability at build time and feeds the decision back as the
 * {@code bootui.internal.cache-present} runtime-config default (see {@link #CACHE_PRESENT_KEY}); when
 * {@code quarkus-cache} is absent the panel reports an honest capability hint rather than the generic "not yet
 * ported" reason. The cache-API-free engine {@code CacheService} and its {@code GET}/{@code POST /clear}
 * resource are always wired regardless — only the {@code io.quarkus.cache.*}-reading {@code CacheProvider}
 * impl is capability-gated (R2), so the panel renders {@code cacheAvailable:false} rather than failing when
 * the extension is absent. The {@code POST /clear} action makes Cache action-capable on Quarkus (behind the
 * shared {@code LocalhostGuard} write floor); Quarkus has no runtime cached-operation registry (its caching
 * annotations are build-time woven), so the operations list is empty by design.</p>
 *
 * <p>The <strong>Flyway</strong> panel is likewise available <em>dynamically</em>: it is lit up only when the
 * application uses Quarkus Flyway. The deployment processor detects the {@code FLYWAY} capability at build time
 * and feeds the decision back as the {@code bootui.internal.flyway-present} runtime-config default (see
 * {@link #FLYWAY_PRESENT_KEY}); when {@code quarkus-flyway} is absent the panel reports an honest capability
 * hint rather than the generic "not yet ported" reason. The Flyway-API-free engine {@code FlywayService} and
 * its {@code GET}/{@code POST migrate}/{@code POST clean} resource are always wired regardless — only the
 * {@code org.flywaydb.*}/{@code io.quarkus.flyway.*}-reading {@code FlywayProvider} impl is capability-gated
 * (R2), so the panel renders {@code flywayPresent:false} rather than failing when the extension is absent. The
 * {@code migrate}/{@code clean} actions make Flyway action-capable on Quarkus (behind the shared
 * {@code LocalhostGuard} write floor); {@code clean} preserves Flyway's disabled-by-default, confirmation-gated
 * semantics. Quarkus has no Spring-Modulith analogue, so the module-aware read-only views never appear.</p>
 *
 * <p>Note the Overview <em>panel</em> stays unavailable here even though {@code GET /bootui/api/overview}
 * <em>is</em> served on Quarkus (by {@code OverviewResource}/{@code QuarkusApplicationInfo}): that
 * endpoint is the shared shell's framework-neutral chrome/CSRF-priming source, which the shell needs on
 * every platform, whereas the Overview dashboard panel itself has not yet been ported.</p>
 *
 * <p>The Mappings panel is a deliberate, possibly permanent, exception rather than a not-yet-ported one:
 * unlike the Spring adapter (which flattens Actuator's {@code MappingsEndpoint} descriptor), Quarkus
 * exposes no clean <em>runtime</em> route-enumeration API. Vert.x {@code Router.getRoutes()} yields paths
 * but not the per-route method/produces/consumes the {@code MappingDto} contract needs, and RESTEasy
 * Reactive's resource model is a build-time artifact
 * ({@code ResteasyReactiveResourceMethodEntriesBuildItem}). Capturing it would require a new build-step +
 * {@code @Recorder} + {@code SyntheticBeanBuildItem} data-capture pattern (its own slice and critic
 * round); until then Mappings stays unavailable on Quarkus while remaining fully available on Spring.</p>
 *
 *
 * <p>The <strong>GraalVM</strong> and <strong>CRaC</strong> advisors are deliberate, permanent exceptions
 * (see {@link #NOT_APPLICABLE}): they have no meaningful Quarkus equivalent rather than simply not being
 * ported yet. GraalVM native-image readiness is a Spring-specific concern — Quarkus compiles native images
 * itself and generates its own reachability metadata at build time, so the Spring-oriented advisor and its
 * {@code reachability-metadata.json} / {@code Dockerfile-native} scaffolding do not apply. CRaC targets the
 * Spring Boot startup model ({@code spring.context.checkpoint=onRefresh}); Quarkus's fast startup comes from
 * build-time augmentation and native images instead. Both therefore report an honest, panel-specific reason so
 * the shared Vue unavailable-alert never implies a port is forthcoming. The <strong>Security</strong> advisor,
 * by contrast, is now lit up with a Quarkus-native ruleset (see {@code QuarkusSecurityScanner}), as is the
 * <strong>Spring</strong> advisor panel, which runs a Quarkus-native idiom ruleset (see {@code QuarkusAppScanner}).</p>
 */
@ApplicationScoped
public class QuarkusPanelAvailability {

    /**
     * Runtime-config key carrying the build-time {@code HIBERNATE_ORM} capability decision. The deployment
     * processor emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}); this bean
     * reads it back to decide whether the dynamically-available Hibernate advisor panel is lit up. Shared with
     * {@code BootUiQuarkusProcessor} (the producer of the value), mirroring {@code QUARKUS_VERSION_KEY} /
     * {@code BASE_PACKAGES_KEY}.
     */
    public static final String HIBERNATE_PRESENT_KEY = "bootui.internal.hibernate-present";

    /**
     * Runtime-config key carrying the build-time {@code SCHEDULER} capability decision. The deployment
     * processor emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}) whenever
     * {@code quarkus-scheduler} is present in a non-production launch; this bean reads it back to decide
     * whether the dynamically-available Scheduled Tasks panel is lit up (true even with zero {@code @Scheduled}
     * methods). Mirrors {@link #HIBERNATE_PRESENT_KEY}.
     */
    public static final String SCHEDULED_PRESENT_KEY = "bootui.internal.scheduled-present";
    /**
     * Runtime-config key carrying the build-time {@code CACHE} capability decision. The deployment processor
     * emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}); this bean reads it
     * back to decide whether the dynamically-available Cache panel is lit up. Shared with
     * {@code BootUiQuarkusProcessor} (the producer of the value), mirroring {@link #HIBERNATE_PRESENT_KEY}.
     */
    public static final String CACHE_PRESENT_KEY = "bootui.internal.cache-present";
    /**
     * Runtime-config key carrying the build-time {@code AGROAL} capability decision. The deployment processor
     * emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}); this bean reads it
     * back to decide whether the dynamically-available Database Connection Pools panel is lit up. Shared with
     * {@code BootUiQuarkusProcessor} (the producer of the value), mirroring {@link #CACHE_PRESENT_KEY}.
     */
    public static final String CONNECTION_POOLS_PRESENT_KEY = "bootui.internal.connection-pools-present";

    /**
     * Runtime-config key carrying the build-time {@code FLYWAY} capability decision. The deployment processor
     * emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}) whenever
     * {@code quarkus-flyway} is present in a non-production launch; this bean reads it back to decide whether
     * the dynamically-available Flyway panel is lit up (true even with zero managed datasources). Shared with
     * {@code BootUiQuarkusProcessor} (the producer of the value), mirroring {@link #CACHE_PRESENT_KEY}.
     */
    public static final String FLYWAY_PRESENT_KEY = "bootui.internal.flyway-present";

    /**
     * Runtime-config key carrying the build-time {@code LIQUIBASE} capability decision. The deployment
     * processor emits it as a {@code RunTimeConfigurationDefaultBuildItem} (default {@code false}); this bean
     * reads it back to decide whether the dynamically-available Liquibase panel is lit up. Shared with
     * {@code BootUiQuarkusProcessor} (the producer of the value), mirroring {@link #HIBERNATE_PRESENT_KEY}.
     */
    public static final String LIQUIBASE_PRESENT_KEY = "bootui.internal.liquibase-present";

    private static final String NOT_YET_AVAILABLE = "Not yet available on Quarkus.";

    private static final String HIBERNATE_ABSENT =
            "Not available: this application does not use Hibernate ORM. Add the quarkus-hibernate-orm"
                    + " extension to enable the JPA mapping advisor.";

    private static final String SCHEDULED_ABSENT =
            "Not available: this application has no scheduler. Add the quarkus-scheduler extension and"
                    + " annotate a method with @Scheduled to enable the Scheduled Tasks panel.";

    private static final String CACHE_ABSENT =
            "Not available: this application does not use Quarkus Cache. Add the quarkus-cache extension to"
                    + " enable the cache panel.";

    private static final String FLYWAY_ABSENT =
            "Not available: this application does not use Flyway. Add the quarkus-flyway extension to enable"
                    + " the Flyway migrations panel.";

    private static final String LIQUIBASE_ABSENT =
            "Not available: this application does not use Liquibase. Add the quarkus-liquibase extension and a"
                    + " change log to enable the Liquibase panel.";

    private static final String CONNECTION_POOLS_ABSENT =
            "Not available: this application has no JDBC datasource. Add a JDBC datasource extension (e.g."
                    + " quarkus-jdbc-postgresql) to enable the Database Connection Pools panel.";

    /**
     * Panels that are deliberately and permanently unavailable on Quarkus because they have no meaningful
     * Quarkus equivalent — as opposed to the generic {@link #NOT_YET_AVAILABLE} panels that simply have not
     * been ported yet. Each maps to an honest, panel-specific reason.
     */
    private static final Map<String, String> NOT_APPLICABLE = Map.of(
            BootUiPanels.GRAALVM,
            "Not applicable on Quarkus: Quarkus builds native images and generates its own reachability"
                    + " metadata at build time, so this Spring-oriented native-image readiness advisor is not"
                    + " used here.",
            BootUiPanels.CRAC,
            "Not applicable on Quarkus: this CRaC checkpoint/restore advisor targets the Spring Boot startup"
                    + " model, and Quarkus's fast startup comes from build-time augmentation and native images"
                    + " instead, so it is not used here.");

    private static final Set<String> AVAILABLE_PANELS = Set.of(
            BootUiPanels.THREADS,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.LIVE_MEMORY,
            BootUiPanels.JVM_TUNING,
            BootUiPanels.MEMORY,
            BootUiPanels.METRICS,
            BootUiPanels.LOGGERS,
            BootUiPanels.LOG_TAIL,
            BootUiPanels.BEANS,
            BootUiPanels.CONFIG,
            BootUiPanels.HEALTH,
            BootUiPanels.HTTP_PROBE,
            BootUiPanels.ARCHITECTURE,
            BootUiPanels.SPRING,
            BootUiPanels.PENTESTING,
            BootUiPanels.SECURITY,
            BootUiPanels.TRACES,
            BootUiPanels.AI,
            BootUiPanels.VULNERABILITIES);

    private static final String CONFIG_READONLY =
            "Runtime config overrides are not available on Quarkus (they target the Spring bootstrap"
                    + " property sources); properties remain fully visible.";

    private final boolean hibernatePresent;

    private final boolean schedulingPresent;

    private final boolean cachePresent;

    private final boolean flywayPresent;
    private final boolean liquibasePresent;
    private final boolean connectionPoolsPresent;

    private final List<String> githubAllowedApiHosts;

    private final boolean profilesActive;

    @Inject
    public QuarkusPanelAvailability(Config config) {
        this.hibernatePresent =
                config.getOptionalValue(HIBERNATE_PRESENT_KEY, Boolean.class).orElse(false);
        this.schedulingPresent =
                config.getOptionalValue(SCHEDULED_PRESENT_KEY, Boolean.class).orElse(false);
        this.cachePresent =
                config.getOptionalValue(CACHE_PRESENT_KEY, Boolean.class).orElse(false);
        this.flywayPresent =
                config.getOptionalValue(FLYWAY_PRESENT_KEY, Boolean.class).orElse(false);
        this.liquibasePresent =
                config.getOptionalValue(LIQUIBASE_PRESENT_KEY, Boolean.class).orElse(false);
        this.connectionPoolsPresent = config.getOptionalValue(CONNECTION_POOLS_PRESENT_KEY, Boolean.class)
                .orElse(false);
        this.githubAllowedApiHosts = BootUiEngineProducer.gitHubAllowedApiHosts(config);
        this.profilesActive = activeProfiles(config);
    }

    private static boolean activeProfiles(Config config) {
        try {
            return !config.unwrap(SmallRyeConfig.class).getProfiles().isEmpty();
        } catch (UnsupportedOperationException notSmallRye) {
            return false;
        }
    }

    public PanelsReport manifest() {
        return new PanelsReport(
                PanelsReport.PLATFORM_QUARKUS,
                BootUiPanels.all().stream().map(this::toDto).toList());
    }

    private PanelDto toDto(BootUiPanels.Panel panel) {
        boolean available = AVAILABLE_PANELS.contains(panel.id())
                || (BootUiPanels.HIBERNATE.equals(panel.id()) && hibernatePresent)
                || (BootUiPanels.SCHEDULED.equals(panel.id()) && schedulingPresent)
                || (BootUiPanels.SPRING_CACHE.equals(panel.id()) && cachePresent)
                || (BootUiPanels.FLYWAY.equals(panel.id()) && flywayPresent)
                || (BootUiPanels.LIQUIBASE.equals(panel.id()) && liquibasePresent)
                || (BootUiPanels.DATABASE_CONNECTION_POOLS.equals(panel.id()) && connectionPoolsPresent)
                || (BootUiPanels.PROFILE_DIFF.equals(panel.id()) && profilesActive)
                || (BootUiPanels.GITHUB.equals(panel.id()) && githubAvailable());
        String unavailableReason = available ? null : unavailableReason(panel.id());
        boolean readOnly = available && BootUiPanels.CONFIG.equals(panel.id());
        String readOnlyReason = readOnly ? CONFIG_READONLY : null;
        return new PanelDto(panel.id(), panel.title(), available, unavailableReason, true, readOnly, readOnlyReason);
    }

    private String unavailableReason(String panelId) {
        if (BootUiPanels.HIBERNATE.equals(panelId)) {
            return HIBERNATE_ABSENT;
        }
        if (BootUiPanels.SCHEDULED.equals(panelId)) {
            return SCHEDULED_ABSENT;
        }
        if (BootUiPanels.SPRING_CACHE.equals(panelId)) {
            return CACHE_ABSENT;
        }
        if (BootUiPanels.FLYWAY.equals(panelId)) {
            return FLYWAY_ABSENT;
        }
        if (BootUiPanels.LIQUIBASE.equals(panelId)) {
            return LIQUIBASE_ABSENT;
        }
        if (BootUiPanels.DATABASE_CONNECTION_POOLS.equals(panelId)) {
            return CONNECTION_POOLS_ABSENT;
        }
        if (BootUiPanels.PROFILE_DIFF.equals(panelId)) {
            return "Not available: no profiles are active. Run with a profile (e.g. quarkus.profile=dev) to"
                    + " compare profile-specific configuration.";
        }
        if (BootUiPanels.GITHUB.equals(panelId)) {
            return githubUnavailableReason();
        }
        return NOT_APPLICABLE.getOrDefault(panelId, NOT_YET_AVAILABLE);
    }

    /**
     * GitHub panel availability is <em>dynamic</em>, mirroring the Spring adapter's
     * {@code PanelsController.githubAvailable()}: the panel is available only when the host application's
     * working directory is a git checkout with a GitHub-origin remote on an allow-listed API host. Uses the
     * shared engine {@link GitHubRepositoryDetector}, so both adapters light up the panel under identical
     * conditions. Detection is local-only (reads the git config / filesystem) and never calls the network.
     */
    private boolean githubAvailable() {
        return GitHubRepositoryDetector.detect(githubWorkingDirectory(), githubAllowedApiHosts)
                .isPresent();
    }

    private String githubUnavailableReason() {
        return GitHubRepositoryDetector.unavailableReason(githubWorkingDirectory(), githubAllowedApiHosts);
    }

    private static Path githubWorkingDirectory() {
        return Path.of(System.getProperty("user.dir", "."));
    }
}
