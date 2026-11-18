package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ProfileProperties;
import dev.biribiri.elysium.content.AttachmentProperties;
import dev.biribiri.elysium.content.CustomEmojiProperties;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring's limits on uploads, which follow the largest file any upload can be, so that operators only set the limits for
 * each kind. Each request is first held to its own endpoint's limit by {@link UploadLengthFilter}, and each file is
 * checked against its kind's limit once it's received. Spring Boot's own settings are left alone, since this replaces
 * them.
 */
@Configuration(proxyBeanMethods = false)
class MultipartConfig {

    @Bean
    UploadLimits uploadLimits(ProfileProperties profiles, AttachmentProperties attachments, CustomEmojiProperties emojis) {
        return new UploadLimits(profiles, attachments, emojis);
    }

    @Bean
    MultipartConfigElement multipartConfigElement(UploadLimits limits) {
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(limits.largestFile()));
        factory.setMaxRequestSize(DataSize.ofBytes(limits.largestRequest()));
        // Written to disk as they arrive, rather than held in memory.
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        return factory.createMultipartConfig();
    }

    /**
     * Ahead of Spring Security's filters, because {@code CsrfFilter} reads the {@code _csrf} parameter, and Tomcat
     * parses a whole multipart body to answer that.
     */
    @Bean
    FilterRegistrationBean<UploadLengthFilter> uploadLengthFilter(UploadLimits limits, JsonMapper json) {
        var registration = new FilterRegistrationBean<>(new UploadLengthFilter(limits, json));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10);
        return registration;
    }
}
