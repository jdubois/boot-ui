package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a run's {@link StructureSnapshot} from the framework-neutral bean and mapping providers every adapter already
 * has ({@code docs/PLAN-v2.md} §5.7): Spring's bean graph and handler mappings, Quarkus's ArC beans with their injection
 * edges and JAX-RS resources. Framework, platform, and BootUI beans are left out. When the beans cannot be read, the
 * snapshot says why, so change impact reports them unavailable rather than empty.
 */
public final class StructureSnapshots {

    private static final Set<String> LEFT_OUT = Set.of("FRAMEWORK", "PLATFORM", "BOOTUI");

    private StructureSnapshots() {}

    /** The structure the providers describe now, either of which may be {@code null}. */
    public static StructureSnapshot read(String runId, BeanProvider beans, MappingProvider mappings) {
        List<StructureSnapshot.RouteHandler> routes = new ArrayList<>();
        if (mappings != null && mappings.available()) {
            for (MappingDto mapping : mappings.mappings()) {
                if (mapping.pattern() == null || mapping.pattern().isBlank()) {
                    continue;
                }
                String label = RouteLabel.of(
                                mapping.method(),
                                mapping.pattern(),
                                mapping.pattern(),
                                null,
                                RouteTemplateResolver.empty())
                        .id();
                routes.add(new StructureSnapshot.RouteHandler(
                        label, handlerClass(mapping.handler()), handlerMethod(mapping.handler())));
            }
        }
        if (beans == null || !beans.available()) {
            return new StructureSnapshot(
                    runId,
                    routes,
                    List.of(),
                    "The application's beans could not be read, so the code that depends on a symbol is unknown.");
        }
        List<StructureSnapshot.Bean> application = new ArrayList<>();
        for (BeanSummary bean : beans.beans()) {
            if (bean.name() == null || LEFT_OUT.contains(String.valueOf(bean.classification()))) {
                continue;
            }
            application.add(
                    new StructureSnapshot.Bean(bean.name(), bean.type(), repository(bean), bean.dependencies()));
        }
        return new StructureSnapshot(runId, routes, application, null);
    }

    /** {@code com.example.ProductController#list()} as {@code com.example.ProductController}. */
    static String handlerClass(String handler) {
        if (handler == null || handler.isBlank()) {
            return null;
        }
        int hash = handler.indexOf('#');
        String type = hash < 0 ? handler : handler.substring(0, hash);
        int space = type.lastIndexOf(' ');
        return space < 0 ? type.strip() : type.substring(space + 1).strip();
    }

    /**
     * {@code com.example.ProductController#list(Pageable)}, as Spring describes a handler method, or Quarkus's
     * {@code com.example.ProductResource#list}, as {@code list}; {@code null} when the handler names no method.
     */
    static String handlerMethod(String handler) {
        if (handler == null) {
            return null;
        }
        int hash = handler.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String method = handler.substring(hash + 1);
        int parenthesis = method.indexOf('(');
        method = (parenthesis < 0 ? method : method.substring(0, parenthesis)).strip();
        return method.isEmpty() ? null : method;
    }

    private static boolean repository(BeanSummary bean) {
        String name = bean.name().toLowerCase(Locale.ROOT);
        String type = bean.type() == null ? "" : bean.type().toLowerCase(Locale.ROOT);
        return name.endsWith("repository") || type.endsWith("repository");
    }
}
