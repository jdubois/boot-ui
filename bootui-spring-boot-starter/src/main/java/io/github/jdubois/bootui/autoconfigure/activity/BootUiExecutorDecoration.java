package io.github.jdubois.bootui.autoconfigure.activity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Gives the application's own {@link ThreadPoolTaskExecutor}, {@link ThreadPoolTaskScheduler}, and
 * {@link SimpleAsyncTaskExecutor} beans the request
 * propagation that M4-15 gives Spring Boot's auto-configured ones ({@code docs/PLAN-v2.md} D30, M4-22), so an
 * {@code @Async} method on an executor the application builds itself, like JHipster's {@code AsyncConfigurer}, runs as an
 * execution of the request that called it.
 *
 * <p>An executor with no decorator gets {@link BootUiTaskDecorator}. One with the application's own decorator keeps it:
 * BootUI's is <em>composed</em> inside it, never in its place, so the application's decorator still sees every task first
 * and BootUI's touches only BootUI's own correlation. An executor that already carries BootUI's is left alone. An
 * executor bean that wraps a pool, as JHipster's {@code ExceptionHandlingAsyncTaskExecutor} does, is looked into one
 * level, through its own fields.
 *
 * <p>Only beans are seen: an executor an {@code AsyncConfigurer} or {@code SchedulingConfigurer} creates without
 * declaring it a bean, or a {@code FactoryBean}'s product, keeps no request link.
 *
 * <p>A {@code SimpleAsyncTaskScheduler} runs its tasks from its own trigger thread, so a task it schedules during a
 * request keeps no request link; Spring's {@code VirtualThreadTaskExecutor} takes no decorator.
 *
 * <p>Spring Framework 7 reads an executor's decorator when each task is submitted, so this applies whenever it is set.
 * Spring keeps the decorator in a private field with no getter, so it is read reflectively; when it cannot be read, the
 * executor is skipped rather than risk displacing a decorator. Nothing here can fail a bean's creation.
 */
public final class BootUiExecutorDecoration implements BeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(BootUiExecutorDecoration.class);

    /** What was done to one executor. */
    enum Outcome {
        /** It had no decorator and got BootUI's. */
        DECORATED,
        /** It had the application's decorator, and BootUI's now runs inside it. */
        COMPOSED,
        /** It already carries BootUI's decorator. */
        ALREADY,
        /** Its decorator could not be read, so it was left as it was. */
        SKIPPED
    }

    /**
     * Registered with the first post-processors, as it depends on nothing, so an executor another post-processor needs
     * early is still seen.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        try {
            for (Object pool : pools(bean)) {
                Outcome outcome = decorate(pool);
                log.debug(
                        "BootUI request propagation on executor bean '{}' ({}): {}",
                        beanName,
                        pool.getClass().getName(),
                        outcome);
            }
        } catch (RuntimeException | LinkageError ex) {
            // Request propagation is a diagnostic aid; it never stands between the application and its executor.
            log.debug("BootUI left executor bean '{}' undecorated", beanName, ex);
        }
        return bean;
    }

    /** The pools {@code bean} is or directly wraps, at most one level deep. */
    static List<Object> pools(Object bean) {
        List<Object> pools = new ArrayList<>();
        if (isPool(bean)) {
            pools.add(bean);
            return pools;
        }
        if (!(bean instanceof Executor)) {
            return pools;
        }
        for (Class<?> type = bean.getClass(); type != null && !framework(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Executor.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object value = read(field, bean);
                if (isPool(value) && !pools.contains(value)) {
                    pools.add(value);
                }
            }
        }
        return pools;
    }

    /** Decorates one pool, never replacing a decorator it already has. */
    static Outcome decorate(Object pool) {
        Field field = decoratorField(pool.getClass());
        if (field == null) {
            return Outcome.SKIPPED;
        }
        Object current;
        try {
            field.setAccessible(true);
            current = field.get(pool);
        } catch (RuntimeException | IllegalAccessException ex) {
            return Outcome.SKIPPED;
        }
        if (current instanceof BootUiTaskDecorator || current instanceof Composed) {
            return Outcome.ALREADY;
        }
        TaskDecorator decorator =
                current instanceof TaskDecorator application ? new Composed(application) : new BootUiTaskDecorator();
        if (pool instanceof ThreadPoolTaskExecutor executor) {
            executor.setTaskDecorator(decorator);
        } else if (pool instanceof ThreadPoolTaskScheduler scheduler) {
            scheduler.setTaskDecorator(decorator);
        } else if (pool instanceof SimpleAsyncTaskExecutor simple) {
            simple.setTaskDecorator(decorator);
        } else {
            return Outcome.SKIPPED;
        }
        return current == null ? Outcome.DECORATED : Outcome.COMPOSED;
    }

    /** Spring's pools that take a decorator, {@link SimpleAsyncTaskExecutor} with virtual threads included. */
    private static boolean isPool(Object candidate) {
        return candidate instanceof ThreadPoolTaskExecutor
                || candidate instanceof ThreadPoolTaskScheduler
                || candidate instanceof SimpleAsyncTaskExecutor;
    }

    /**
     * The JDK's, Spring's, and BootUI's own executors wrap nothing of the application's worth looking into, except Spring
     * Security's, such as {@code DelegatingSecurityContextAsyncTaskExecutor}, which wraps the application's pool.
     */
    private static boolean framework(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("org.springframework.security.")) {
            return false;
        }
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("org.springframework.")
                || name.startsWith("io.github.jdubois.bootui.");
    }

    private static Object read(Field field, Object bean) {
        try {
            field.setAccessible(true);
            return field.get(bean);
        } catch (RuntimeException | IllegalAccessException ex) {
            return null;
        }
    }

    private static Field decoratorField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField("taskDecorator");
                if (TaskDecorator.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Declared on a superclass, or not at all.
            }
        }
        return null;
    }

    /**
     * The application's decorator with BootUI's inside it: the application's sees and wraps every task first, as it
     * did, and BootUI's carries the request's correlation to the task itself.
     */
    static final class Composed implements TaskDecorator {

        private final TaskDecorator application;

        Composed(TaskDecorator application) {
            this.application = application;
        }

        @Override
        public Runnable decorate(Runnable runnable) {
            return application.decorate(SpringTaskPropagation.propagate(runnable));
        }

        TaskDecorator application() {
            return application;
        }
    }
}
