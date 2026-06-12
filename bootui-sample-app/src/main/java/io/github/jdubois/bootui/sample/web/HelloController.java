package io.github.jdubois.bootui.sample.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello, world";
    }

    @GetMapping("/secure")
    public String secure() {
        return "Secure Hello, world";
    }
}
