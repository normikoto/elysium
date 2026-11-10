package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ProfileProperties;
import dev.biribiri.elysium.content.AttachmentProperties;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Spring's limits on uploads, which follow the largest file any upload can be, so that operators only set the limits for
 * each kind. Each kind is checked against its own limit once it's received. Spring Boot's own settings are left alone,
 * since this replaces them.
 */
@Configuration(proxyBeanMethods = false)
class MultipartConfig {

    /**
     * For the rest of a form, besides its files.
     */
    private static final DataSize FORM = DataSize.ofMegabytes(4);

    @Bean
    MultipartConfigElement multipartConfigElement(ProfileProperties profiles, AttachmentProperties attachments) {
        var largestFile = Math.max(profiles.maxImageSize().toBytes(), attachments.largest().toBytes());
        // A profile can have two images at once, and an attachment a thumbnail.
        var largestRequest = Math.max(2 * profiles.maxImageSize().toBytes(),
                attachments.largest().toBytes() + attachments.maxImageSize().toBytes()) + FORM.toBytes();
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(largestFile));
        factory.setMaxRequestSize(DataSize.ofBytes(largestRequest));
        // Written to disk as they arrive, rather than held in memory.
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        return factory.createMultipartConfig();
    }
}
