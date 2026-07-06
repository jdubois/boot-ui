package io.github.jdubois.bootui.sample.config;

import io.github.jdubois.bootui.sample.catalog.SampleSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient} aimed at the companion BootUI Quarkus sample app (bootui-quarkus-sample-app),
 * so the "cross-service" sample action can demonstrate a real HTTP call from this Spring Boot app into
 * the Quarkus app's secured, SQL-backed endpoint. Credentials mirror the Quarkus sample's embedded
 * admin/admin account (see its application.properties); this is a local-only demo, never do this in
 * production.
 */
@Configuration(proxyBeanMethods = false)
class QuarkusClientConfiguration {

    @Bean
    RestClient quarkusRestClient(RestClient.Builder builder, SampleSettings settings) {
        return builder.baseUrl(settings.getQuarkusBaseUrl())
                .requestInterceptor(new BasicAuthenticationInterceptor("admin", "admin"))
                .build();
    }
}
