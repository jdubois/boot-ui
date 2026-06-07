package io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures.bad;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class BadMvcController {

    @GetMapping("/mvc")
    @ResponseBody
    public String mvc() {
        return "mvc";
    }
}
