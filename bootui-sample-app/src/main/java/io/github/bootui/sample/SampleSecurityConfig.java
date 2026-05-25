package io.github.bootui.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Sample security configuration that demonstrates BootUI working alongside Spring Security.
 *
 * <p>BootUI paths are permitted without authentication because BootUI already enforces
 * its own localhost-only access control via {@code LocalhostOnlyFilter}.</p>
 */
@Configuration
@EnableWebSecurity
public class SampleSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/bootui/**").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(org.springframework.security.config.Customizer.withDefaults())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/bootui/**")
            );
        return http.build();
    }
}
