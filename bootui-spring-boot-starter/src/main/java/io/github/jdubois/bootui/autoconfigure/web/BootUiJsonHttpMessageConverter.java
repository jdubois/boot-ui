package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes BootUI API DTOs independently from a host application's Jackson customizations.
 *
 * <p>Application-owned mappers are often configured for persistence formats such as Redis and may
 * enable polymorphic type metadata. Reusing such a mapper for BootUI's browser contract changes
 * ordinary JSON arrays into implementation-tagged wrappers. This converter is selected only for
 * BootUI API requests and deliberately leaves raw strings, downloads, resources, and streams to
 * Spring MVC's existing converters.</p>
 */
public final class BootUiJsonHttpMessageConverter extends JacksonJsonHttpMessageConverter {

    private final String apiPath;

    public BootUiJsonHttpMessageConverter(BootUiProperties properties) {
        super(JsonMapper.builder().findAndAddModules().build());
        this.apiPath = properties.getApiPath();
    }

    @Override
    public boolean canRead(ResolvableType type, MediaType mediaType) {
        return false;
    }

    @Override
    public boolean canWrite(ResolvableType type, Class<?> valueClass, MediaType mediaType) {
        Class<?> candidate = valueClass != null ? valueClass : type.resolve();
        return isBootUiApiRequest() && isJsonBody(candidate) && super.canWrite(type, valueClass, mediaType);
    }

    private boolean isBootUiApiRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }

    private static boolean isJsonBody(Class<?> valueClass) {
        return valueClass != null
                && valueClass != String.class
                && valueClass != byte[].class
                && !Resource.class.isAssignableFrom(valueClass);
    }
}
