package io.github.bootui.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableConfigurationProperties(BootUiSampleApplication.SampleSettings.class)
public class BootUiSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootUiSampleApplication.class, args);
    }

    @ConfigurationProperties(prefix = "sample")
    public static class SampleSettings {

        private String greeting = "Hello";

        private int retries = 3;

        public String getGreeting() {
            return greeting;
        }

        public void setGreeting(String greeting) {
            this.greeting = greeting;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }
    }

    @RestController
    @RequestMapping("/api/sample")
    public static class SampleController {

        private final SampleSettings settings;

        public SampleController(SampleSettings settings) {
            this.settings = settings;
        }

        @GetMapping("/hello")
        public String hello() {
            return settings.getGreeting() + ", BootUI! (retries=" + settings.getRetries() + ")";
        }
    }
}
