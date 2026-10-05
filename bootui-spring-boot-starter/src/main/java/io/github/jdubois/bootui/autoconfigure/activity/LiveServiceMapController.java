package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Live Flow service map endpoint, served under the Live Activity panel because Live Flow is a mode
 * of that panel rather than a panel of its own.
 *
 * <p>Registered by both the servlet and the reactive autoconfiguration: it injects only stack-neutral beans
 * and returns a stable core DTO, so Spring MVC and Spring WebFlux serve an identical contract from one class.
 * Living under {@code /activity} also means the existing panel enable/read-only policy and the shared
 * localhost/Host guard already cover it with no extra registration.</p>
 *
 * <p>Rendering this map performs no network call, probe, DNS lookup, connection attempt, or scan. It only
 * re-reads bounded buffers other panels already populate.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/activity")
public class LiveServiceMapController {

    private final LiveServiceMapService service;

    public LiveServiceMapController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<RestClientTraceRecorder> restClientTrace,
            ObjectProvider<ConnectionPoolService> connectionPools,
            ObjectProvider<SqlTraceRecorder> sqlTrace,
            ObjectProvider<KafkaActivityRecorder> kafka,
            ObjectProvider<RabbitActivityRecorder> rabbit,
            ObjectProvider<CacheActivityRecorder> cacheActivity,
            BootUiProperties properties,
            BootUiExposure exposure,
            ApplicationContext applicationContext) {
        this.service = new LiveServiceMapService(
                httpExchanges,
                restClientTrace,
                connectionPools,
                sqlTrace,
                kafka,
                rabbit,
                cacheActivity,
                properties,
                exposure,
                className -> beanPresent(applicationContext, className));
    }

    @GetMapping("/service-map")
    public ServiceMapReport serviceMap() {
        return service.serviceMap();
    }

    /**
     * Mirrors the dedicated messaging controllers' template-bean requirement without statically linking
     * their optional API types from this always-loaded controller.
     */
    private static boolean beanPresent(ApplicationContext applicationContext, String className) {
        try {
            Class<?> type = ClassUtils.forName(className, LiveServiceMapController.class.getClassLoader());
            return applicationContext.getBeanNamesForType(type, true, false).length > 0;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }
}
