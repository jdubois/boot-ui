package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.DevToolsActionResult;
import io.github.jdubois.bootui.core.BootUiDtos.DevToolsStatus;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

public class DefaultDevToolsBridge implements DevToolsBridge {

    private static final Logger log = LoggerFactory.getLogger(DefaultDevToolsBridge.class);

    private static final String RESTARTER_CLASS = "org.springframework.boot.devtools.restart.Restarter";

    private static final String LIVE_RELOAD_SERVER_CLASS =
            "org.springframework.boot.devtools.livereload.LiveReloadServer";

    private static final String OPTIONAL_LIVE_RELOAD_SERVER_CLASS =
            "org.springframework.boot.devtools.autoconfigure.OptionalLiveReloadServer";

    private final ApplicationContext applicationContext;

    private final ClassLoader classLoader;

    private final AtomicBoolean restartPending = new AtomicBoolean(false);
    private final ScheduledExecutorService restartExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "bootui-devtools-restart"));

    public DefaultDevToolsBridge(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.classLoader = applicationContext.getClassLoader() == null
                ? ClassUtils.getDefaultClassLoader()
                : applicationContext.getClassLoader();
    }

    @Override
    public DevToolsStatus status() {
        Availability restart = restartAvailability();
        LiveReloadHandle liveReload = liveReloadHandle();
        return new DevToolsStatus(
                restart.available(),
                restart.reason(),
                restartPending.get(),
                liveReload.available(),
                liveReload.port(),
                liveReload.reason());
    }

    @Override
    public DevToolsActionResult triggerLiveReload() {
        LiveReloadHandle liveReload = liveReloadHandle();
        if (!liveReload.available()) {
            return new DevToolsActionResult("livereload", "unavailable", liveReload.reason());
        }
        try {
            Method triggerReload = liveReload.target().getClass().getMethod("triggerReload");
            triggerReload.invoke(liveReload.target());
            return new DevToolsActionResult(
                    "livereload", "triggered", "LiveReload notification sent to connected browsers.");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not trigger Spring Boot DevTools LiveReload", unwrap(ex));
        }
    }

    @Override
    public DevToolsActionResult scheduleRestart() {
        Availability restart = restartAvailability();
        if (!restart.available()) {
            return new DevToolsActionResult("restart", "unavailable", restart.reason());
        }
        if (!restartPending.compareAndSet(false, true)) {
            return new DevToolsActionResult("restart", "already_pending", "A DevTools restart is already pending.");
        }

        Object restarter = restarter();
        restartExecutor.schedule(() -> restartAfterResponse(restarter), 250, TimeUnit.MILLISECONDS);
        return new DevToolsActionResult(
                "restart",
                "scheduled",
                "Restart scheduled. BootUI will reconnect when the application is available again.");
    }

    @PreDestroy
    public void stop() {
        restartExecutor.shutdownNow();
    }

    private void restartAfterResponse(Object restarter) {
        try {
            Method restart = restarter.getClass().getMethod("restart");
            restart.invoke(restarter);
        } catch (ReflectiveOperationException ex) {
            restartPending.set(false);
            log.warn("Spring Boot DevTools restart failed", unwrap(ex));
        } catch (Exception ex) {
            restartPending.set(false);
            log.warn("Spring Boot DevTools restart failed", ex);
        }
    }

    private Availability restartAvailability() {
        if (!ClassUtils.isPresent(RESTARTER_CLASS, classLoader)) {
            return Availability.unavailable("spring-boot-devtools is not on the classpath.");
        }
        try {
            restarter();
            return Availability.ready();
        } catch (IllegalStateException ex) {
            return Availability.unavailable(ex.getMessage());
        }
    }

    private Object restarter() {
        try {
            Class<?> restarterClass = ClassUtils.forName(RESTARTER_CLASS, classLoader);
            Method getInstance = restarterClass.getMethod("getInstance");
            return getInstance.invoke(null);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException();
            throw new IllegalStateException(
                    cause.getMessage() == null
                            ? "Spring Boot DevTools Restarter is not initialized."
                            : cause.getMessage(),
                    cause);
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IllegalStateException("Spring Boot DevTools Restarter is not available.", ex);
        }
    }

    private LiveReloadHandle liveReloadHandle() {
        if (!ClassUtils.isPresent(OPTIONAL_LIVE_RELOAD_SERVER_CLASS, classLoader)
                && !ClassUtils.isPresent(LIVE_RELOAD_SERVER_CLASS, classLoader)) {
            return LiveReloadHandle.unavailable("Spring Boot DevTools LiveReload is not on the classpath.");
        }
        LiveReloadHandle optional = beanHandle(OPTIONAL_LIVE_RELOAD_SERVER_CLASS);
        if (optional.available()) {
            return optional;
        }
        LiveReloadHandle server = beanHandle(LIVE_RELOAD_SERVER_CLASS);
        if (server.available()) {
            return server;
        }
        return LiveReloadHandle.unavailable("Spring Boot DevTools LiveReload server is not available.");
    }

    private LiveReloadHandle beanHandle(String className) {
        if (!ClassUtils.isPresent(className, classLoader)) {
            return LiveReloadHandle.unavailable(null);
        }
        try {
            Class<?> type = ClassUtils.forName(className, classLoader);
            Map<String, ?> beans = applicationContext.getBeansOfType(type, false, false);
            if (beans.isEmpty()) {
                return LiveReloadHandle.unavailable(null);
            }
            Object bean = beans.values().iterator().next();
            return new LiveReloadHandle(bean, liveReloadPort(bean), null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return LiveReloadHandle.unavailable("Spring Boot DevTools LiveReload server is not available.");
        }
    }

    private Integer liveReloadPort(Object bean) {
        Object target = liveReloadServer(bean);
        if (target == null) {
            return null;
        }
        try {
            Method getPort = target.getClass().getMethod("getPort");
            return (Integer) getPort.invoke(target);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private Object liveReloadServer(Object bean) {
        if (bean.getClass().getName().equals(LIVE_RELOAD_SERVER_CLASS)) {
            return bean;
        }
        try {
            Field server = bean.getClass().getDeclaredField("server");
            server.setAccessible(true);
            return server.get(bean);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private Throwable unwrap(ReflectiveOperationException ex) {
        return ex instanceof InvocationTargetException invocation && invocation.getTargetException() != null
                ? invocation.getTargetException()
                : ex;
    }

    private record Availability(boolean available, String reason) {

        static Availability ready() {
            return new Availability(true, null);
        }

        static Availability unavailable(String reason) {
            return new Availability(false, reason);
        }
    }

    private record LiveReloadHandle(Object target, Integer port, String reason) {

        static LiveReloadHandle unavailable(String reason) {
            return new LiveReloadHandle(null, null, reason);
        }

        boolean available() {
            return target != null;
        }
    }
}
