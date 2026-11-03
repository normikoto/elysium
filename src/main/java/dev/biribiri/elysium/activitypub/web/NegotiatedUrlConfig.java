package dev.biribiri.elysium.activitypub.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Settings for actor and status URLs, which serve an ActivityPub document or a web page depending on the {@code Accept}
 * header. These apply to the URL rather than to either handler:
 *
 * <ul>
 * <li>A cache must not mix up the two responses.
 * <li>A preflight request doesn't say which of the two it's for, so {@code @CrossOrigin} on a handler isn't enough:
 * Spring picks one of the handlers arbitrarily, and might pick one without it.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class NegotiatedUrlConfig implements WebMvcConfigurer {

    private static final String[] NEGOTIATED_URLS = {"/users/*", "/users/*/statuses/*"};

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (var url : NEGOTIATED_URLS) {
            registry.addMapping(url).allowedMethods("GET", "HEAD");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT);
                return true;
            }
        }).addPathPatterns(NEGOTIATED_URLS);
    }
}
