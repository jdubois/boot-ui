package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/http-probe")
public class HttpProbeController {

    private final HttpProbeService probeService;

    public HttpProbeController(HttpProbeService probeService) {
        this.probeService = probeService;
    }

    @PostMapping
    public HttpProbeResponse probe(@RequestBody HttpProbeRequest request) {
        return probeService.probe(request);
    }
}
