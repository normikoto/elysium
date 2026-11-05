package dev.biribiri.elysium.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainNameTests {

    @ParameterizedTest
    @CsvSource({
            "example.com, example.com",
            "EXAMPLE.Com, example.com",
            "example.com., example.com",
            "' example.com ', example.com",
            "sub.example.com, sub.example.com",
            "münchen.example, xn--mnchen-3ya.example",
            "MÜNCHEN.example, xn--mnchen-3ya.example",
            "mu\u0308nchen.example, xn--mnchen-3ya.example",
            "xn--mnchen-3ya.example, xn--mnchen-3ya.example",
            "xn--fa-hia.example, xn--fa-hia.example"
    })
    void readsDomainsToBlock(String value, String expected) {
        assertThat(DomainName.parse(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", " ", "*.example.com", "example.com:8443", "https://example.com", "example.com/path", "user@example.com",
            "exa mple.com", "a..example.com", ".example.com", "-a.example.com", "a_b.example.com", "com", "localhost",
            "192.0.2.1", "[2001:db8::1]", "2001:db8::1", "0x7f.0x1", "example.123"
    })
    void refusesWhatCannotBeBlocked(String value) {
        assertThatThrownBy(() -> DomainName.parse(value)).isInstanceOf(InvalidDomainBlockException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"faß.example", "σς.example", "a\u200Db.example", "ｅxample.com"})
    void refusesNamesThatConvertingWouldTurnIntoAnotherDomain(String value) {
        assertThatThrownBy(() -> DomainName.parse(value)).isInstanceOf(InvalidDomainBlockException.class);
    }

    @Test
    void refusesDomainsThatAreTooLong() {
        var label = "a".repeat(63);
        assertThatThrownBy(() -> DomainName.parse(String.join(".", label, label, label, label, "example")))
                .isInstanceOf(InvalidDomainBlockException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "evil.example, evil.example",
            "EVIL.example, evil.example",
            "evil.example., evil.example",
            "evil.example:8443, evil.example",
            "evil.example.:8443, evil.example"
    })
    void readsHostsFromOtherServers(String host, String expected) {
        assertThat(DomainName.normalizeHost(host)).contains(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "[2001:db8::1]", "[2001:db8::1]:8443", "192.0.2.1", "192.0.2.1:8443", "0x7f.0x1", "."})
    void doesNotApplyBlocksToIpAddressesOrHostsItCannotRead(String host) {
        assertThat(DomainName.normalizeHost(host)).isEmpty();
    }

    @Test
    void comparesADomainWithEachOneAboveIt() {
        assertThat(DomainName.suffixes("a.b.evil.example"))
                .containsExactlyInAnyOrder("a.b.evil.example", "b.evil.example", "evil.example");
    }

    @Test
    void doesNotCompareATopLevelDomain() {
        assertThat(DomainName.suffixes("localhost")).isEmpty();
    }

    @Test
    void aLookalikeHasNoSuffixInCommon() {
        assertThat(DomainName.suffixes("notevil.example")).doesNotContain("evil.example");
    }

    @Test
    void morePaddingCannotHideABlockedDomain() {
        var host = "a.".repeat(1000) + "evil.example";

        assertThat(DomainName.normalizeHost(host)).isPresent();
        assertThat(DomainName.suffixes(DomainName.normalizeHost(host).orElseThrow()))
                .hasSize(127)
                .contains("evil.example");
    }
}
