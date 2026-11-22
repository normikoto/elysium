package dev.biribiri.elysium.api.web;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * Mastodon ignores a trailing slash on API paths, and some clients send one: Mastodon.py, which Brutaldon uses, asks for
 * {@code /api/v1/instance/}. Spring matches paths exactly, so without this those requests miss both their controller and
 * the security rules that let them through without a token, and are refused with a 401.
 *
 * <p>The request is rewritten rather than redirected, since clients don't all follow a redirect for a {@code POST}. It runs
 * ahead of Spring Security's filters, so that the rules see the path the controllers do.
 */
@Configuration(proxyBeanMethods = false)
class TrailingSlashConfig {

    @Bean
    FilterRegistrationBean<UrlHandlerFilter> trailingSlashFilter() {
        var filter = UrlHandlerFilter.trailingSlashHandler("/api/**").wrapRequest().build();
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 20);
        return registration;
    }
}
