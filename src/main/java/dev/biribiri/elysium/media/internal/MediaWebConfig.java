package dev.biribiri.elysium.media.internal;

import dev.biribiri.elysium.media.Images;
import dev.biribiri.elysium.media.MediaProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serves the media directory. A file's name is new whenever its content is, so caches can keep it forever.
 */
@Configuration(proxyBeanMethods = false)
class MediaWebConfig implements WebMvcConfigurer {

    private final MediaProperties properties;

    MediaWebConfig(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // A location without a trailing slash is treated as a file, which only happens if the directory doesn't exist yet.
        var location = properties.directory().toUri().toString();
        registry.addResourceHandler(Images.PATH + "*")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
