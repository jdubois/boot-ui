package io.github.jdubois.bootui.sample.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sample")
public class SampleSettings {

    private String greeting = "Hello";

    private int retries = 3;

    private String quarkusBaseUrl = "http://localhost:8081";

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

    public String getQuarkusBaseUrl() {
        return quarkusBaseUrl;
    }

    public void setQuarkusBaseUrl(String quarkusBaseUrl) {
        this.quarkusBaseUrl = quarkusBaseUrl;
    }
}
