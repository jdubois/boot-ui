package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the BootUI single-page application root and convenience aliases.
 *
 * <p>The compiled Vue assets ship inside {@code bootui-ui} at
 * {@code META-INF/resources/bootui/} which Spring Boot serves automatically.</p>
 */
@Controller
public class BootUiIndexController {

    private final BootUiProperties properties;

    @Autowired
    public BootUiIndexController(BootUiProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/bootui")
    public void root(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Include the servlet context path so the redirect keeps working when the host app
        // sets server.servlet.context-path (e.g. /api/bootui/ instead of /bootui/). See #332.
        response.sendRedirect(request.getContextPath() + properties.getPath() + "/");
    }

    @GetMapping("/bootui/")
    public String spaIndex() {
        return "forward:/bootui/index.html";
    }
}
