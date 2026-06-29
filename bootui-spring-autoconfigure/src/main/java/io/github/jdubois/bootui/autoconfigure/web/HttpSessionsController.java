package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.HttpSessionActionRequest;
import io.github.jdubois.bootui.core.dto.HttpSessionActionResult;
import io.github.jdubois.bootui.core.dto.HttpSessionsReport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes bounded, Tomcat-only HTTP session inspection and confirmed session actions.
 */
@RestController
@ConditionalOnClass(name = {"org.springframework.boot.tomcat.TomcatWebServer", "org.apache.catalina.Manager"})
@RequestMapping("/bootui/api/http-sessions")
public class HttpSessionsController {

    private final HttpSessionsService service;

    @Autowired
    public HttpSessionsController(ApplicationContext applicationContext, BootUiProperties properties) {
        this(new HttpSessionsService(applicationContext, properties));
    }

    HttpSessionsController(HttpSessionsService service) {
        this.service = service;
    }

    @GetMapping
    public HttpSessionsReport sessions(HttpServletRequest request) {
        return service.sessions(currentSessionId(request));
    }

    @PostMapping("/{sessionKey}/clear")
    public ResponseEntity<HttpSessionActionResult> clear(
            @PathVariable String sessionKey, @RequestBody(required = false) HttpSessionActionRequest request) {
        return service.clear(sessionKey, request);
    }

    @PostMapping("/{sessionKey}/invalidate")
    public ResponseEntity<HttpSessionActionResult> invalidate(
            @PathVariable String sessionKey, @RequestBody(required = false) HttpSessionActionRequest request) {
        return service.invalidate(sessionKey, request);
    }

    private String currentSessionId(HttpServletRequest request) {
        HttpSession session = request == null ? null : request.getSession(false);
        return session == null ? null : session.getId();
    }
}
