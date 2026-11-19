package dev.biribiri.elysium.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

/**
 * Serves the pages' stylesheet under a name with a hash of its content, which the templates' {@code @{...}} links are
 * rewritten to. Browsers can then keep it for good, and still get the new one as soon as a release changes it.
 */
@Configuration(proxyBeanMethods = false)
class StylesheetServing implements WebMvcConfigurer {

    static final String PATH = "/css/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(PATH + "*")
                .addResourceLocations("classpath:/static" + PATH)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
    }

    /**
     * What rewrites the links, which Spring Boot only adds itself when its own resource chain is turned on.
     */
    @Bean
    ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
        return new ResourceUrlEncodingFilter();
    }
}
