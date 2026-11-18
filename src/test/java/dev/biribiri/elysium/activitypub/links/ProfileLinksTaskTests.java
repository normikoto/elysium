package dev.biribiri.elysium.activitypub.links;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileLinksTaskTests {

    private static final URI PAGE = URI.create("https://site.test/about/");
    private static final URI PROFILE = URI.create("https://social.test/users/alice");

    @Test
    void findsALinkBackMarkedMe() {
        assertThat(linksBack("<a rel=\"me\" href=\"https://social.test/users/alice\">")).isTrue();
        assertThat(linksBack("<a rel=\"nofollow ME\" href=\"https://SOCIAL.test/users/alice/\">")).isTrue();
        assertThat(linksBack("<link rel=\"me\" href=\"https://social.test/users/alice\">")).isTrue();
    }

    @Test
    void needsRelMeAndTheProfilesUrl() {
        assertThat(linksBack("<a href=\"https://social.test/users/alice\">")).isFalse();
        assertThat(linksBack("<a rel=\"meh\" href=\"https://social.test/users/alice\">")).isFalse();
        assertThat(linksBack("<a rel=\"me\" href=\"https://social.test/users/alicex\">")).isFalse();
        assertThat(linksBack("<a rel=\"me\" href=\"http://social.test/users/alice\">")).isFalse();
        assertThat(linksBack("<a rel=\"me\" href=\"https://evil.test/users/alice\">")).isFalse();
        assertThat(linksBack("<a rel=\"me\" href=\"/users/alice\">")).isFalse();
    }

    @Test
    void resolvesRelativeLinksAgainstThePage() {
        var page = URI.create("https://social.test/somewhere/");
        var html = "<a rel=\"me\" href=\"/users/alice\">".getBytes(StandardCharsets.UTF_8);

        assertThat(ProfileLinksTask.hasLinkTo(page, html, ProfileLinksTask.localBacklink(PROFILE))).isTrue();
    }

    @Test
    void acceptsRemoteBacklinksOnlyToTheActorOrItsProfileOnTheSameOrigin() {
        var profile = URI.create("https://social.test/@alice?account=Alice");
        var accepts = ProfileLinksTask.remoteBacklink(PROFILE, profile);

        assertThat(accepts.test(PROFILE)).isTrue();
        assertThat(accepts.test(URI.create("https://SOCIAL.test:443/@alice/?account=Alice"))).isTrue();
        assertThat(accepts.test(URI.create("https://social.test/@Alice?account=Alice"))).isFalse();
        assertThat(accepts.test(URI.create("https://social.test/@alice?account=alice"))).isFalse();
        assertThat(accepts.test(URI.create("https://social.test:8443/@alice?account=Alice"))).isFalse();

        var unrelated = URI.create("https://unrelated.test/@alice");
        assertThat(ProfileLinksTask.remoteBacklink(PROFILE, unrelated).test(unrelated)).isFalse();
        assertThat(ProfileLinksTask.remoteBacklink(PROFILE, null).test(PROFILE)).isTrue();
    }

    private static boolean linksBack(String html) {
        return ProfileLinksTask.hasLinkTo(PAGE, html.getBytes(StandardCharsets.UTF_8), ProfileLinksTask.localBacklink(PROFILE));
    }
}
