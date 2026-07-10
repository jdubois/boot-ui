package io.github.jdubois.bootui.engine.restapi.newrules.responsecontracts.spring;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/widgets")
public class SpringResponseContractController {

    @RequestMapping(method = RequestMethod.HEAD)
    public String head() {
        return "body-that-must-not-be-sent";
    }

    @RequestMapping(path = "/headers-only", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headersOnly() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/update")
    public String updateWidget() {
        return "updated";
    }

    @GetMapping("/post-process")
    public String postProcess() {
        return "processed";
    }

    @GetMapping("/put-aside")
    public String putAside() {
        return "aside";
    }

    @GetMapping("/patch-version")
    public String patchVersion() {
        return "versioned";
    }
}
