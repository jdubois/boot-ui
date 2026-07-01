package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.BeanSummary;
import java.util.List;

/**
 * Framework-neutral seam behind the Beans panel: it reports the host application's managed beans,
 * already mapped to one {@link BeanSummary} per bean, already classified, and with BootUI's own beans
 * filtered out.
 *
 * <p>The Spring Boot adapter implements this over Actuator's {@code BeansEndpoint}; the Quarkus adapter
 * implements it over the Arc/CDI container. Two concerns deliberately stay in the adapter (not the
 * engine):</p>
 *
 * <ul>
 *   <li><strong>Self-data filtering</strong> — dropping BootUI's own beans. The Spring filter inspects
 *       the live {@code Class<?>} and the actuator-supplied defining {@code resource}; doing it where
 *       those values exist is provably byte-identical to the original controller.</li>
 *   <li><strong>Classification</strong> ({@code BOOTUI}/{@code FRAMEWORK}/{@code PLATFORM}/
 *       {@code APPLICATION}/{@code OTHER}) — it is framework-specific: {@code FRAMEWORK} means
 *       {@code org.springframework.} on Spring but {@code io.quarkus.}/{@code io.vertx.}/{@code org.jboss.}
 *       and friends on Quarkus. Performing it in the engine would either mis-bucket each framework's own
 *       beans or force a framework-prefix list into the neutral engine. The classification
 *       <em>vocabulary</em> stays shared so the UI filter dropdown is identical on both platforms.</li>
 * </ul>
 *
 * <p>The engine {@code BeansService} therefore owns only the framework-neutral concerns (sorting,
 * classification/free-text filtering and paging) on top of the rows this provider returns.</p>
 */
public interface BeanProvider {

    /**
     * Whether a bean-inventory backend is currently available. {@code false} means the backend type is on
     * the classpath but no usable instance exists (for example Actuator present but the beans endpoint
     * bean is absent); the engine then serves an empty report.
     */
    boolean available();

    /**
     * The mapped, classified, self-filtered, <em>unsorted</em> and <em>unpaged</em> beans: one entry per
     * managed bean, with BootUI's own beans already removed. The engine applies sorting, classification /
     * free-text filtering and paging on top of this. Returns an empty list when {@link #available()} is
     * {@code false}.
     */
    List<BeanSummary> beans();
}
