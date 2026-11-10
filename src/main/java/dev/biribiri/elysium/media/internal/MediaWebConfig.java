package dev.biribiri.elysium.media.internal;

import dev.biribiri.elysium.media.Images;
import dev.biribiri.elysium.media.MediaProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the media directory. A file's name is new whenever its content is, so caches can keep it forever.
 *
 * <p>Video and audio are served as they were uploaded, so a file is only served under a name made for it, as the type its
 * extension says, and a browser is told neither to guess another type nor to run anything in it. That way, a file that
 * a browser could read as a page still can't act as one on this server's origin.
 */
@Configuration(proxyBeanMethods = false)
class MediaWebConfig implements WebMvcConfigurer {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; media-src 'self'; img-src 'self'; sandbox";

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
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(false)
                .addResolver(new StoredFileResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                return true;
            }
        }).addPathPatterns(Images.PATH + "*");
    }

    /**
     * Leaves out temporary files, which are in the same directory while they're being written.
     */
    private static final class StoredFileResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            return FileNames.isValid(resourcePath) ? super.getResource(resourcePath, location) : null;
        }
    }
}
