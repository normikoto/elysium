package dev.biribiri.elysium.activitypub.inbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ServerActivityLimitsTests {

    private static final int MIB = 1024 * 1024;

    private final AtomicLong now = new AtomicLong();

    @Test
    void limitsActivitiesPerMinute() {
        var limits = limits(2, 10, List.of());

        assertThat(limits.tryAccept(actor("social.example.com"), 100)).isTrue();
        assertThat(limits.tryAccept(actor("social.example.com"), 100)).isTrue();
        assertThat(limits.tryAccept(actor("social.example.com"), 100)).isFalse();

        advance(Duration.ofMinutes(1));
        assertThat(limits.tryAccept(actor("social.example.com"), 100)).isTrue();
    }

    @Test
    void limitsDataPerHour() {
        var limits = limits(100, 2, List.of());

        assertThat(limits.tryAccept(actor("example.com"), MIB)).isTrue();
        assertThat(limits.tryAccept(actor("example.com"), MIB)).isTrue();
        assertThat(limits.tryAccept(actor("example.com"), 1)).isFalse();

        advance(Duration.ofMinutes(59));
        assertThat(limits.tryAccept(actor("example.com"), 1)).isFalse();
        assertThat(limits.timeUntilReset(actor("example.com"))).isEqualTo(Duration.ofMinutes(1));

        advance(Duration.ofMinutes(1));
        assertThat(limits.tryAccept(actor("example.com"), MIB)).isTrue();
    }

    @Test
    void anActivityTurnedAwayForItsSizeDoesNotCount() {
        var limits = limits(2, 2, List.of());

        assertThat(limits.tryAccept(actor("example.com"), MIB)).isTrue();
        assertThat(limits.tryAccept(actor("example.com"), MIB + 1)).isFalse();
        // This is the second of two activities, which wouldn't fit if the refused one had counted.
        assertThat(limits.tryAccept(actor("example.com"), 1)).isTrue();
    }

    @Test
    void sharesALimitAcrossSubdomains() {
        var limits = limits(1, 10, List.of());

        assertThat(limits.tryAccept(actor("a.example.com"), 100)).isTrue();
        assertThat(limits.tryAccept(actor("b.example.com"), 100)).isFalse();
        assertThat(limits.tryAccept(actor("example.org"), 100)).isTrue();
    }

    @Test
    void doesNotLimitExemptDomainsOrTheirSubdomains() {
        var limits = limits(1, 10, List.of(" Mastodon.Social. "));

        for (var i = 0; i < 5; i++) {
            assertThat(limits.tryAccept(actor("mastodon.social"), MIB)).isTrue();
            assertThat(limits.tryAccept(actor("files.mastodon.social"), MIB)).isTrue();
        }
        assertThat(limits.tryAccept(actor("notmastodon.social"), 100)).isTrue();
        assertThat(limits.tryAccept(actor("notmastodon.social"), 100)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "a.b.example.com, example.com",
            "example.com, example.com",
            "x.y.z.co.uk, z.co.uk",
            "a.github.io, a.github.io",
            "b.a.github.io, a.github.io",
            "a.b.xn--fiqs8s, b.xn--fiqs8s",
            "social.unknown-tld, social.unknown-tld",
            "localhost, localhost",
            "1.2.3.4, 1.2.3.4",
            "[2001:db8::1], [2001:db8::1]",
    })
    void countsEachRegistrableDomainAsOneServer(String host, String key) {
        assertThat(ServerActivityLimits.serverKey(host)).isEqualTo(key);
    }

    @Test
    void ignoresTheCaseOfAHost() {
        var limits = limits(1, 10, List.of());

        assertThat(limits.tryAccept(actor("Social.Example.COM"), 100)).isTrue();
        assertThat(limits.tryAccept(actor("social.example.com"), 100)).isFalse();
    }

    @Test
    void refusesADataLimitBelowTheLargestActivity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboxLimitProperties(1, DataSize.ofBytes(MIB - 1), List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboxLimitProperties(0, DataSize.ofMegabytes(1), List.of()));
    }

    private ServerActivityLimits limits(int activitiesPerMinute, int mibPerHour, List<String> exempt) {
        return new ServerActivityLimits(
                new InboxLimitProperties(activitiesPerMinute, DataSize.ofMegabytes(mibPerHour), exempt), now::get);
    }

    private static URI actor(String host) {
        return URI.create("https://" + host + "/users/bob");
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
