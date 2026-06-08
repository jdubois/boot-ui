package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.HttpSessionActionRequest;
import io.github.jdubois.bootui.core.dto.HttpSessionActionResult;
import io.github.jdubois.bootui.core.dto.HttpSessionAttributeDto;
import io.github.jdubois.bootui.core.dto.HttpSessionDto;
import io.github.jdubois.bootui.core.dto.HttpSessionsReport;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HttpSessionsService {

    private static final Logger log = LoggerFactory.getLogger(HttpSessionsService.class);

    private static final int MAX_DISPLAY_VALUE_LENGTH = 2048;

    private final Supplier<ManagerResolution> managerResolver;

    private final BootUiProperties properties;

    private final BootUiExposure exposure;

    HttpSessionsService(ApplicationContext applicationContext, BootUiProperties properties) {
        this(() -> resolveTomcatManagers(applicationContext), properties, applicationContext.getEnvironment());
    }

    HttpSessionsService(Manager manager, BootUiProperties properties) {
        this(() -> ManagerResolution.available(List.of(manager)), properties);
    }

    HttpSessionsService(Supplier<ManagerResolution> managerResolver, BootUiProperties properties) {
        this(managerResolver, properties, null);
    }

    HttpSessionsService(
            Supplier<ManagerResolution> managerResolver, BootUiProperties properties, Environment environment) {
        this.managerResolver = managerResolver;
        this.properties = properties;
        this.exposure = new BootUiExposure(environment, properties);
    }

    HttpSessionsReport sessions(String currentSessionId) {
        int limit = maxSessions();
        boolean actionEnabled = !properties.isPanelReadOnly(BootUiPanels.HTTP_SESSIONS);
        BootUiProperties.ValueExposure valueExposure = valueExposure();
        ManagerResolution resolution = managerResolver.get();
        if (!resolution.available()) {
            return HttpSessionsReport.unavailable(
                    resolution.unavailableReason(), limit, actionEnabled, valueExposure.name());
        }

        List<Session> sessions = findSessions(resolution.managers());
        List<HttpSessionDto> dtos = sessions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(this::lastAccessedTime).reversed())
                .limit(limit)
                .map(session -> toDto(session, currentSessionId, valueExposure))
                .filter(Objects::nonNull)
                .toList();
        return new HttpSessionsReport(
                true,
                null,
                sessions.size(),
                dtos.size(),
                limit,
                sessions.size() > limit,
                actionEnabled,
                valueExposure.name(),
                dtos);
    }

    ResponseEntity<HttpSessionActionResult> clear(String sessionKey, HttpSessionActionRequest request) {
        if (properties.isPanelReadOnly(BootUiPanels.HTTP_SESSIONS)) {
            return result(
                    HttpStatus.FORBIDDEN,
                    "read_only",
                    properties.panelReadOnlyReason(BootUiPanels.HTTP_SESSIONS),
                    sessionKey,
                    0);
        }
        if (!confirmed(request)) {
            return result(
                    HttpStatus.BAD_REQUEST,
                    "confirmation_required",
                    "Clearing an HTTP session requires explicit confirmation.",
                    sessionKey,
                    0);
        }
        ManagerResolution resolution = managerResolver.get();
        if (!resolution.available()) {
            return result(HttpStatus.CONFLICT, "unavailable", resolution.unavailableReason(), sessionKey, 0);
        }
        Session session = findByKey(resolution.managers(), sessionKey);
        if (session == null) {
            return result(HttpStatus.NOT_FOUND, "not_found", "HTTP session was not found.", sessionKey, 0);
        }
        HttpSession facade = facade(session);
        if (facade == null) {
            return result(HttpStatus.CONFLICT, "expired", "HTTP session is no longer valid.", sessionKey, 0);
        }
        List<String> attributeNames = attributeNames(facade);
        int removed = 0;
        for (String name : attributeNames) {
            try {
                facade.removeAttribute(name);
                removed++;
            } catch (RuntimeException ex) {
                log.warn(
                        "BootUI failed to remove HTTP session attribute '{}' from session key {}",
                        name,
                        forLog(sessionKey),
                        ex);
                return result(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed",
                        "Failed to clear HTTP session attribute '" + name + "' ("
                                + ex.getClass().getSimpleName() + ").",
                        sessionKey,
                        removed);
            }
        }
        log.warn("BootUI cleared {} attributes from HTTP session key {}", removed, forLog(sessionKey));
        return result(
                HttpStatus.OK,
                "cleared",
                "Cleared " + removed + " HTTP session attribute" + (removed == 1 ? "." : "s."),
                sessionKey,
                removed);
    }

    ResponseEntity<HttpSessionActionResult> invalidate(String sessionKey, HttpSessionActionRequest request) {
        if (properties.isPanelReadOnly(BootUiPanels.HTTP_SESSIONS)) {
            return result(
                    HttpStatus.FORBIDDEN,
                    "read_only",
                    properties.panelReadOnlyReason(BootUiPanels.HTTP_SESSIONS),
                    sessionKey,
                    0);
        }
        if (!confirmed(request)) {
            return result(
                    HttpStatus.BAD_REQUEST,
                    "confirmation_required",
                    "Destroying an HTTP session requires explicit confirmation.",
                    sessionKey,
                    0);
        }
        ManagerResolution resolution = managerResolver.get();
        if (!resolution.available()) {
            return result(HttpStatus.CONFLICT, "unavailable", resolution.unavailableReason(), sessionKey, 0);
        }
        Session session = findByKey(resolution.managers(), sessionKey);
        if (session == null) {
            return result(HttpStatus.NOT_FOUND, "not_found", "HTTP session was not found.", sessionKey, 0);
        }
        try {
            session.expire();
        } catch (RuntimeException ex) {
            log.warn("BootUI failed to destroy HTTP session key {}", forLog(sessionKey), ex);
            return result(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed",
                    "Failed to destroy HTTP session (" + ex.getClass().getSimpleName() + ").",
                    sessionKey,
                    0);
        }
        log.warn("BootUI destroyed HTTP session key {}", forLog(sessionKey));
        return result(HttpStatus.OK, "destroyed", "Destroyed HTTP session.", sessionKey, 0);
    }

    private HttpSessionDto toDto(
            Session session, String currentSessionId, BootUiProperties.ValueExposure valueExposure) {
        String rawId = session.getId();
        HttpSession facade = facade(session);
        if (facade == null) {
            return null;
        }
        List<String> names = attributeNames(facade);
        List<HttpSessionAttributeDto> attributes = names.stream()
                .map(name -> attribute(facade, name, valueExposure))
                .filter(Objects::nonNull)
                .toList();
        return new HttpSessionDto(
                sessionKey(rawId),
                displaySessionId(rawId, valueExposure),
                valueExposure != BootUiProperties.ValueExposure.FULL,
                rawId != null && rawId.equals(currentSessionId),
                instant(session.getCreationTime()),
                instant(session.getLastAccessedTime()),
                idleSeconds(session.getLastAccessedTime()),
                session.getMaxInactiveInterval(),
                attributes.size(),
                attributes);
    }

    private HttpSessionAttributeDto attribute(
            HttpSession session, String name, BootUiProperties.ValueExposure valueExposure) {
        Object value;
        try {
            value = session.getAttribute(name);
        } catch (RuntimeException ex) {
            log.debug("Could not read HTTP session attribute '{}'", name, ex);
            return new HttpSessionAttributeDto(name, null, null, false, false);
        }
        String type = value == null ? null : value.getClass().getName();
        DisplayValue display = displayAttributeValue(value, valueExposure);
        return new HttpSessionAttributeDto(name, type, display.value(), display.masked(), display.truncated());
    }

    private DisplayValue displayAttributeValue(Object value, BootUiProperties.ValueExposure valueExposure) {
        if (value == null || valueExposure == BootUiProperties.ValueExposure.METADATA_ONLY) {
            return new DisplayValue(null, false, false);
        }
        if (valueExposure != BootUiProperties.ValueExposure.FULL) {
            return new DisplayValue(SecretMasker.MASKED_VALUE, true, false);
        }
        String text;
        try {
            text = String.valueOf(value);
        } catch (RuntimeException ex) {
            log.debug("Could not render HTTP session attribute value", ex);
            return new DisplayValue(null, false, false);
        }
        if (text.length() <= MAX_DISPLAY_VALUE_LENGTH) {
            return new DisplayValue(text, false, false);
        }
        return new DisplayValue(text.substring(0, MAX_DISPLAY_VALUE_LENGTH) + "...", false, true);
    }

    private HttpSession facade(Session session) {
        try {
            return session.getSession();
        } catch (IllegalStateException ex) {
            return null;
        } catch (RuntimeException ex) {
            log.debug("Could not access HTTP session facade", ex);
            return null;
        }
    }

    private List<String> attributeNames(HttpSession session) {
        try {
            Enumeration<String> names = session.getAttributeNames();
            List<String> result = Collections.list(names);
            result.sort(String.CASE_INSENSITIVE_ORDER);
            return result;
        } catch (IllegalStateException ex) {
            return List.of();
        } catch (RuntimeException ex) {
            log.debug("Could not enumerate HTTP session attributes", ex);
            return List.of();
        }
    }

    private List<Session> findSessions(List<Manager> managers) {
        List<Session> sessions = new ArrayList<>();
        for (Manager manager : managers) {
            try {
                Collections.addAll(sessions, manager.findSessions());
            } catch (RuntimeException ex) {
                log.warn("BootUI could not inspect Tomcat HTTP sessions", ex);
            }
        }
        return sessions;
    }

    private Session findByKey(List<Manager> managers, String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        for (Session session : findSessions(managers)) {
            if (sessionKey.equals(sessionKey(session.getId()))) {
                return session;
            }
        }
        return null;
    }

    private long lastAccessedTime(Session session) {
        try {
            return Math.max(0, session.getLastAccessedTime());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private Instant instant(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }

    private Long idleSeconds(long lastAccessedTime) {
        if (lastAccessedTime <= 0) {
            return null;
        }
        return Math.max(
                0,
                Duration.between(Instant.ofEpochMilli(lastAccessedTime), Instant.now())
                        .toSeconds());
    }

    private String displaySessionId(String id, BootUiProperties.ValueExposure valueExposure) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (valueExposure == BootUiProperties.ValueExposure.FULL) {
            return id;
        }
        return SecretMasker.MASKED_VALUE;
    }

    private BootUiProperties.ValueExposure valueExposure() {
        return exposure.valueExposure();
    }

    private int maxSessions() {
        return Math.max(1, properties.getHttpSessions().getMaxSessions());
    }

    private boolean confirmed(HttpSessionActionRequest request) {
        return request != null && Boolean.TRUE.equals(request.confirm());
    }

    private static String forLog(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private ResponseEntity<HttpSessionActionResult> result(
            HttpStatus status, String resultStatus, String message, String sessionKey, int affectedAttributes) {
        return ResponseEntity.status(status)
                .body(new HttpSessionActionResult(resultStatus, message, sessionKey, affectedAttributes));
    }

    static String sessionKey(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private static ManagerResolution resolveTomcatManagers(ApplicationContext applicationContext) {
        if (!(applicationContext instanceof WebServerApplicationContext webServerApplicationContext)) {
            return ManagerResolution.unavailable("HTTP Sessions require an embedded servlet web server");
        }
        WebServer webServer;
        try {
            webServer = webServerApplicationContext.getWebServer();
        } catch (RuntimeException ex) {
            log.warn("BootUI could not access the embedded web server for HTTP Sessions", ex);
            return ManagerResolution.unavailable("Embedded web server is not available");
        }
        if (!(webServer instanceof TomcatWebServer tomcatWebServer)) {
            return ManagerResolution.unavailable("HTTP Sessions require embedded Tomcat");
        }

        List<Manager> managers = new ArrayList<>();
        try {
            for (Container child : tomcatWebServer.getTomcat().getHost().findChildren()) {
                if (child instanceof Context context && context.getManager() != null) {
                    managers.add(context.getManager());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("BootUI could not access Tomcat session managers", ex);
            return ManagerResolution.unavailable("Tomcat session manager is not available");
        }
        if (managers.isEmpty()) {
            return ManagerResolution.unavailable("No Tomcat session manager is available");
        }
        return ManagerResolution.available(managers);
    }

    record ManagerResolution(List<Manager> managers, String unavailableReason) {

        static ManagerResolution available(List<Manager> managers) {
            return new ManagerResolution(List.copyOf(managers), null);
        }

        static ManagerResolution unavailable(String reason) {
            return new ManagerResolution(List.of(), reason);
        }

        boolean available() {
            return !managers.isEmpty();
        }
    }

    private record DisplayValue(String value, boolean masked, boolean truncated) {}
}
