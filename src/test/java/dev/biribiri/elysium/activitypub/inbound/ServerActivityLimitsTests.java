package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.inbound.ServerActivityLimits.Admission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ServerActivityLimitsTests {

    private static final int MIB = 1024 * 1024;

    private final AtomicLong now = new AtomicLong();

    @Test
    void limitsActivitiesPerMinute() {
        var limits = limits(2, 10, Set.of());

        assertThat(admitted(limits, "social.example.com", 100)).isTrue();
        assertThat(admitted(limits, "social.example.com", 100)).isTrue();
        assertThat(admitted(limits, "social.example.com", 100)).isFalse();

        advance(Duration.ofMinutes(1));
        assertThat(admitted(limits, "social.example.com", 100)).isTrue();
    }

    @Test
    void limitsDataPerHour() {
        var limits = limits(100, 2, Set.of());

        assertThat(admitted(limits, "example.com", MIB)).isTrue();
        assertThat(admitted(limits, "example.com", MIB)).isTrue();
        assertThat(admitted(limits, "example.com", 1)).isFalse();

        advance(Duration.ofMinutes(59));
        assertThat(limits.tryAdmit(actor("example.com"), 1))
                .isEqualTo(new Admission.Refused(Duration.ofMinutes(1)));

        advance(Duration.ofMinutes(1));
        assertThat(admitted(limits, "example.com", MIB)).isTrue();
    }

    @Test
    void suggestsWaitingOnlyForTheLimitThatWasReached() {
        var limits = limits(1, 10, Set.of());
        limits.tryAdmit(actor("example.com"), 100);
        advance(Duration.ofSeconds(20));

        assertThat(limits.tryAdmit(actor("example.com"), 100))
                .isEqualTo(new Admission.Refused(Duration.ofSeconds(40)));
    }

    @Test
    void anActivityTurnedAwayForItsSizeDoesNotCount() {
        var limits = limits(2, 2, Set.of());

        assertThat(admitted(limits, "example.com", MIB)).isTrue();
        assertThat(admitted(limits, "example.com", MIB + 1)).isFalse();
        // This is the second of two activities, which wouldn't fit if the refused one had counted.
        assertThat(admitted(limits, "example.com", 1)).isTrue();
    }

    @Test
    void releasingGivesBackWhatAnActivityCountedFor() {
        var limits = limits(1, 1, Set.of());
        var admission = (Admission.Admitted) limits.tryAdmit(actor("example.com"), MIB);

        admission.release().run();

        assertThat(admitted(limits, "example.com", MIB)).isTrue();
    }

    @Test
    void sharesALimitAcrossSubdomains() {
        var limits = limits(1, 10, Set.of());

        assertThat(admitted(limits, "a.example.com", 100)).isTrue();
        assertThat(admitted(limits, "b.example.com", 100)).isFalse();
        assertThat(admitted(limits, "example.org", 100)).isTrue();
    }

    @Test
    void doesNotLimitExemptDomainsOrTheirSubdomains() {
        var limits = limits(1, 10, Set.of(" Mastodon.Social. "));

        for (var i = 0; i < 5; i++) {
            assertThat(admitted(limits, "mastodon.social", MIB)).isTrue();
            assertThat(admitted(limits, "files.MASTODON.social", MIB)).isTrue();
        }
        assertThat(admitted(limits, "notmastodon.social", 100)).isTrue();
        assertThat(admitted(limits, "notmastodon.social", 100)).isFalse();
    }

    @Test
    void readsAnInternationalExemptDomainInTheFormHostsArrive() {
        var limits = limits(1, 10, Set.of("bücher.example"));

        assertThat(admitted(limits, "social.xn--bcher-kva.example", 100)).isTrue();
        assertThat(admitted(limits, "social.xn--bcher-kva.example", 100)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "a.b.example.com, example.com",
            "example.com, example.com",
            "Social.Example.COM, example.com",
            "x.y.z.co.uk, z.co.uk",
            "a.github.io, a.github.io",
            "b.a.github.io, a.github.io",
            "a.b.xn--fiqs8s, b.xn--fiqs8s",
            "social.unknown-tld, social.unknown-tld",
            "localhost, localhost",
            "1.2.3.4, 1.2.3.4",
            "[2001:db8::1], ipv6:20010db800000000",
            "[2001:0db8:0:0::2], ipv6:20010db800000000",
    })
    void countsEachRegistrableDomainAsOneServer(String host, String key) {
        assertThat(ServerActivityLimits.serverKey(actor(host))).isEqualTo(key);
    }

    @Test
    void countsAHostThatURIDoesNotReadAsItsAuthority() {
        var limits = limits(1, 10, Set.of());
        var signer = URI.create("https://my_host.example/users/bob");

        assertThat(signer.getHost()).isNull();
        assertThat(limits.tryAdmit(signer, 100)).isInstanceOf(Admission.Admitted.class);
        assertThat(limits.tryAdmit(signer, 100)).isInstanceOf(Admission.Refused.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://mastodon.social", "*.mastodon.social", "mastodon.social:443", "1.2.3.4", "social"})
    void refusesAnExemptDomainThatIsNotADomain(String domain) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboxLimitProperties(1, DataSize.ofMegabytes(1), Set.of(domain)))
                .withMessageContaining("exempt-domains");
    }

    @ParameterizedTest
    @ValueSource(strings = {"co.uk", "github.io", "social"})
    void refusesAnExemptDomainThatAnyoneCanRegisterUnder(String domain) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboxLimitProperties(1, DataSize.ofMegabytes(1), Set.of(domain)));
    }

    @Test
    void refusesADataLimitBelowTheLargestActivity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboxLimitProperties(1, DataSize.ofBytes(MIB - 1), Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InboxLimitProperties(0, DataSize.ofMegabytes(1), Set.of()));
    }

    @Test
    void usesTheDefaultsForSettingsThatAreEmpty() {
        var source = new MapConfigurationPropertySource(Map.of(
                "elysium.federation.inbox-limits.data-per-hour", "",
                "elysium.federation.inbox-limits.exempt-domains", ""));

        var properties = new Binder(source)
                .bindOrCreate("elysium.federation.inbox-limits", InboxLimitProperties.class);

        assertThat(properties.dataPerHour()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(properties.exemptDomains()).isEmpty();
        assertThat(properties.activitiesPerMinute()).isEqualTo(120);
    }

    private boolean admitted(ServerActivityLimits limits, String host, int bytes) {
        return limits.tryAdmit(actor(host), bytes) instanceof Admission.Admitted;
    }

    private ServerActivityLimits limits(int activitiesPerMinute, int mibPerHour, Set<String> exempt) {
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
