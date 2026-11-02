package dev.biribiri.elysium.auth.internal;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/", "/.well-known/webfinger", "/users/*", "/actuator/health", "/actuator/health/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Applies the @CrossOrigin settings on controllers, including to preflight requests.
                .cors(withDefaults())
                .authorizeHttpRequests(requests -> requests
                        // Lets Spring's error handling keep its original status instead of turning it into a 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
