package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityAware;
import io.github.jdubois.bootui.autoconfigure.datasource.DataSourceDeclarations;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.journal.RunStartEvents;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.StartupStepTiming;
import io.github.jdubois.bootui.engine.postgres.PostgresDataSourceDetection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.metrics.StartupStep;
import org.springframework.util.ClassUtils;

/**
 * Publishes the run's {@code RUN_STARTED} lifecycle event when the application is ready ({@code docs/PLAN-v2.md}
 * §5.18): its time to ready, its slowest bean instantiations from the {@link BufferingApplicationStartup} BootUI
 * installs, and its comparability facts. It reads what already exists: no bean is created and no connection opened.
 */
public final class RunStartPublisher implements ApplicationListener<ApplicationReadyEvent> {

    static final String BEAN_INSTANTIATION = "spring.beans.instantiate";

    private static final String TRACER = "io.micrometer.tracing.Tracer";

    private final RuntimeJournal journal;
    private final ApplicationContext context;
    private final BootUiSelfDataFilter selfData;
    private final AtomicBoolean published = new AtomicBoolean();

    public RunStartPublisher(RuntimeJournal journal, ApplicationContext context, BootUiSelfDataFilter selfData) {
        this.journal = journal;
        this.context = context;
        this.selfData = selfData == null ? BootUiSelfDataFilter.defaults() : selfData;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // A parent or child context's readiness is not this run's.
        if (event.getApplicationContext() != context || !published.compareAndSet(false, true)) {
            return;
        }
        Duration taken = event.getTimeTaken();
        Environment environment = context.getEnvironment();
        RunStartEvents.publish(
                journal,
                System.currentTimeMillis(),
                taken == null ? null : taken.toNanos(),
                slowestBeans(),
                Arrays.asList(environment.getActiveProfiles()),
                jdbcUrls(),
                cacheType(),
                tracing(environment));
    }

    private List<StartupStepTiming> slowestBeans() {
        if (!(context instanceof ConfigurableApplicationContext configurable)
                || !(configurable.getApplicationStartup() instanceof BufferingApplicationStartup buffering)) {
            return List.of();
        }
        List<StartupStepTiming> steps = new ArrayList<>();
        for (StartupTimeline.TimelineEvent timed :
                buffering.getBufferedTimeline().getEvents()) {
            StartupStep step = timed.getStartupStep();
            if (!BEAN_INSTANTIATION.equals(step.getName()) || timed.getDuration() == null) {
                continue;
            }
            List<Map.Entry<String, String>> tags = new ArrayList<>();
            String bean = null;
            for (StartupStep.Tag tag : step.getTags()) {
                tags.add(Map.entry(tag.getKey(), tag.getValue()));
                if ("beanName".equals(tag.getKey())) {
                    bean = tag.getValue();
                }
            }
            if (selfData.shouldIncludeStartupStep(step.getName(), tags)) {
                steps.add(new StartupStepTiming(
                        step.getName(), bean, timed.getDuration().toNanos()));
            }
        }
        return steps;
    }

    private Map<String, String> jdbcUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        DataSourceDeclarations.byBeanName(context).forEach((name, sources) -> {
            List<String> declared =
                    sources.stream().map(RunStartPublisher::urlOf).distinct().toList();
            urls.put(name, declared.size() == 1 ? declared.get(0) : null);
        });
        return urls;
    }

    private static String urlOf(DataSource dataSource) {
        String url = PostgresDataSourceDetection.jdbcUrlOf(dataSource);
        return url == null ? "unknown" : url;
    }

    /** The cache managers' classes, seen past BootUI's recording decorator, from the beans that already exist. */
    private String cacheType() {
        if (!(context.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory)) {
            return null;
        }
        String type = Arrays.stream(context.getBeanNamesForType(CacheManager.class, false, false))
                .map(factory::getSingleton)
                .filter(CacheManager.class::isInstance)
                .map(manager -> CacheActivityAware.unwrap((CacheManager) manager)
                        .getClass()
                        .getSimpleName())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        return type.isEmpty() ? null : type;
    }

    private boolean tracing(Environment environment) {
        if (!environment.getProperty("management.tracing.enabled", Boolean.class, true)
                || !ClassUtils.isPresent(TRACER, context.getClassLoader())) {
            return false;
        }
        try {
            return context.getBeanNamesForType(ClassUtils.forName(TRACER, context.getClassLoader()), false, false)
                            .length
                    > 0;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }
}
