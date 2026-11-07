package dev.biribiri.elysium.activitypub.links;

import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Checks whether the page each field links to links back to the profile with {@code rel="me"}, which is how someone shows
 * that a site is theirs.
 *
 * <p>Each link gets one try, and one that can't be fetched just isn't verified, as on Mastodon. Retrying would keep a
 * failed job in front of admins for a problem that's the linked site's, and saving the profile again tries again. The
 * fetches go through the federation client, which only fetches HTTPS from public addresses, so a profile can't be used
 * to reach anything on this server's network.
 */
@Configuration(proxyBeanMethods = false)
class ProfileLinksTask {

    /**
     * Sites commonly redirect to {@code www} or to a trailing slash. The client doesn't follow redirects itself, so that
     * each one is checked as a fetch of its own.
     */
    private static final int MAX_REDIRECTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ProfileLinksTask.class);

    private final ActorService actors;
    private final ProfileService profiles;
    private final FederationHttpClient http;

    ProfileLinksTask(ActorService actors, ProfileService profiles, FederationHttpClient http) {
        this.actors = actors;
        this.profiles = profiles;
        this.http = http;
    }

    @Bean
    Task<ProfileLinksJob> checkProfileLinks() {
        return Jobs.task(ProfileLinks.TASK, this::run);
    }

    private CompletionHandler<ProfileLinksJob> run(ProfileLinksJob job) {
        var actor = actors.findLocalById(job.actorId());
        if (actor.isEmpty()) {
            return Jobs.done();
        }
        for (var link : profiles.linksToVerify(job.actorId())) {
            if (linksBack(link.uri(), actor.get().uri())) {
                profiles.markVerified(job.actorId(), link, Instant.now());
            }
        }
        return Jobs.done();
    }

    private boolean linksBack(URI page, URI profile) {
        var uri = page;
        try {
            for (var redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                var response = http.get(uri, "text/html", HttpHeaders.EMPTY);
                if (response.status() >= 300 && response.status() < 400) {
                    var location = location(uri, response);
                    if (location.isEmpty()) {
                        return false;
                    }
                    uri = location.get();
                    continue;
                }
                return response.status() == 200 && hasLinkTo(uri, response.body(), profile);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Couldn't check {} for a link back to {}", page, profile, e);
        }
        return false;
    }

    private static Optional<URI> location(URI from, FederationHttpClient.Response response) {
        try {
            return Optional.ofNullable(response.location()).map(from::resolve);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Relative links are resolved against the page, so a page on the same server as the profile can link back with a path.
     */
    static boolean hasLinkTo(URI page, byte[] html, URI profile) {
        var document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), page.toString());
        return document.select("a[rel][href], link[rel][href]").stream()
                .filter(element -> Arrays.stream(element.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"))
                        .anyMatch("me"::equals))
                .map(element -> element.absUrl("href"))
                .anyMatch(href -> sameUrl(href, profile));
    }

    /**
     * A trailing slash and the case of the host don't change which page a link is to.
     */
    private static boolean sameUrl(String href, URI profile) {
        try {
            var link = URI.create(href);
            return profile.getScheme().equalsIgnoreCase(link.getScheme())
                    && profile.getHost().equalsIgnoreCase(link.getHost())
                    && profile.getPort() == link.getPort()
                    && stripSlash(profile.getPath()).equals(stripSlash(link.getPath()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripSlash(String path) {
        var p = path == null ? "" : path;
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
