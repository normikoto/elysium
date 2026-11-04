package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AuthRateLimitsTests {

    private static final String ADDRESS = "203.0.113.1";

    private final AtomicLong now = new AtomicLong();
    private final AuthRateLimits limits = limits(true, "https://example.com");

    @Test
    void limitsFailuresForAUsernameFromAnAddress() {
        assertLoginsAllowed(5, "alice", ADDRESS);

        assertThat(limits.beginLogin(login("alice", ADDRESS))).contains(Duration.ofMinutes(15));
        assertThat(limits.beginLogin(login("alice", "203.0.113.2"))).isEmpty();
    }

    @Test
    void ignoresCaseInUsernames() {
        assertLoginsAllowed(3, "alice", ADDRESS);
        assertLoginsAllowed(2, "ALICE", ADDRESS);

        assertThat(limits.beginLogin(login("Alice", ADDRESS))).isPresent();
    }

    @Test
    void trimsUsernamesAsSpringSecurityDoes() {
        assertLoginsAllowed(2, " alice", ADDRESS);
        assertLoginsAllowed(2, "alice\t", ADDRESS);
        assertLoginsAllowed(1, "alice", ADDRESS);

        assertThat(limits.beginLogin(login(" alice ", ADDRESS))).isPresent();
    }

    @Test
    void limitsFailuresFromAnAddressAcrossUsernames() {
        for (var i = 0; i < 20; i++) {
            assertThat(limits.beginLogin(login("user" + i, ADDRESS))).isEmpty();
        }

        assertThat(limits.beginLogin(login("another", ADDRESS))).isPresent();
    }

    @Test
    void limitsFailuresForAUsernameFromAnyAddress() {
        for (var i = 0; i < 50; i++) {
            assertThat(limits.beginLogin(login("alice", "203.0.113." + i))).isEmpty();
        }

        assertThat(limits.beginLogin(login("alice", "198.51.100.1"))).contains(Duration.ofHours(1));
    }

    @Test
    void anAttemptTurnedAwayByOneLimitDoesntUseTheOthers() {
        assertLoginsAllowed(5, "alice", ADDRESS);
        for (var i = 0; i < 30; i++) {
            assertThat(limits.beginLogin(login("alice", ADDRESS))).isPresent();
        }

        // The address has used 5 of its 20 attempts, despite the 30 that were turned away.
        for (var i = 0; i < 15; i++) {
            assertThat(limits.beginLogin(login("user" + i, ADDRESS))).isEmpty();
        }
        assertThat(limits.beginLogin(login("another", ADDRESS))).isPresent();
    }

    @Test
    void usernamesThatCantExistOnlyCountAgainstTheAddress() {
        for (var i = 0; i < 20; i++) {
            assertThat(limits.beginLogin(login("not-valid", ADDRESS))).isEmpty();
        }

        assertThat(limits.beginLogin(login("not-valid", ADDRESS))).isPresent();
        assertThat(limits.beginLogin(login("not-valid", "203.0.113.2"))).isEmpty();
    }

    @Test
    void aMissingUsernameOnlyCountsAgainstTheAddress() {
        for (var i = 0; i < 20; i++) {
            assertThat(limits.beginLogin(login(null, ADDRESS))).isEmpty();
        }

        assertThat(limits.beginLogin(login(null, ADDRESS))).isPresent();
    }

    @Test
    void allowsLoginsAgainAfterTheWindow() {
        assertLoginsAllowed(5, "alice", ADDRESS);

        now.addAndGet(Duration.ofMinutes(15).toNanos());

        assertThat(limits.beginLogin(login("alice", ADDRESS))).isEmpty();
    }

    @Test
    void aSuccessfulLoginClearsFailuresForThatUsernameAndAddress() {
        assertLoginsAllowed(4, "alice", ADDRESS);
        var success = login("alice", ADDRESS);
        assertThat(limits.beginLogin(success)).isEmpty();

        limits.loginSucceeded(success);

        assertLoginsAllowed(5, "alice", ADDRESS);
        assertThat(limits.beginLogin(login("alice", ADDRESS))).isPresent();
    }

    @Test
    void aSuccessfulLoginDoesntClearFailuresFromTheAddress() {
        for (var i = 0; i < 19; i++) {
            assertThat(limits.beginLogin(login("user" + i, ADDRESS))).isEmpty();
        }
        var success = login("mine", ADDRESS);
        assertThat(limits.beginLogin(success)).isEmpty();

        limits.loginSucceeded(success);

        // Only the successful attempt's own slot came back.
        assertThat(limits.beginLogin(login("victim", ADDRESS))).isEmpty();
        assertThat(limits.beginLogin(login("victim", ADDRESS))).isPresent();
    }

    @Test
    void aLoginThatWasntCountedGivesBackItsSlots() {
        for (var i = 0; i < 100; i++) {
            var attempt = login("alice", ADDRESS);
            assertThat(limits.beginLogin(attempt)).isEmpty();
            limits.loginNotCounted(attempt);
        }
    }

    @Test
    void onlyTheFirstWayOfFinishingALoginHasAnyEffect() {
        assertLoginsAllowed(1, "alice", ADDRESS);
        var attempt = login("alice", ADDRESS);
        assertThat(limits.beginLogin(attempt)).isEmpty();

        limits.loginNotCounted(attempt);
        limits.loginNotCounted(attempt);
        limits.loginSucceeded(attempt);

        assertLoginsAllowed(4, "alice", ADDRESS);
        assertThat(limits.beginLogin(login("alice", ADDRESS))).isPresent();
    }

    @Test
    void aFailedLoginStaysCountedEvenIfSomethingGoesWrongAfterwards() {
        var attempt = login("alice", ADDRESS);
        assertThat(limits.beginLogin(attempt)).isEmpty();

        limits.loginFailed(attempt);
        limits.loginNotCounted(attempt);

        assertLoginsAllowed(4, "alice", ADDRESS);
        assertThat(limits.beginLogin(login("alice", ADDRESS))).isPresent();
    }

    @Test
    void aLoginTurnedAwayHasNothingToGiveBack() {
        assertLoginsAllowed(5, "alice", ADDRESS);
        var blocked = login("alice", ADDRESS);
        assertThat(limits.beginLogin(blocked)).isPresent();

        limits.loginNotCounted(blocked);

        assertThat(limits.beginLogin(login("alice", ADDRESS))).isPresent();
    }

    @Test
    void countsAnIpv6NetworkAsOneAddress() {
        for (var i = 0; i < 20; i++) {
            assertThat(limits.beginLogin(login("not-valid", "2001:db8:1:2::" + Integer.toHexString(i + 1)))).isEmpty();
        }

        assertThat(limits.beginLogin(login("not-valid", "2001:db8:1:2:ffff::1"))).isPresent();
        assertThat(limits.beginLogin(login("not-valid", "2001:db8:1:3::1"))).isEmpty();
    }

    @Test
    void countsAnAddressThatCantBeParsedAsItIs() {
        for (var i = 0; i < 20; i++) {
            assertThat(limits.beginLogin(login("not-valid", "unknown"))).isEmpty();
        }

        assertThat(limits.beginLogin(login("not-valid", "unknown"))).isPresent();
    }

    @Test
    void limitsSignUpsFromAnAddress() {
        for (var i = 0; i < 5; i++) {
            assertThat(limits.beginSignUp(login(null, ADDRESS))).isEmpty();
        }

        assertThat(limits.beginSignUp(login(null, ADDRESS))).contains(Duration.ofHours(1));
        assertThat(limits.beginSignUp(login(null, "203.0.113.2"))).isEmpty();
    }

    @Test
    void aSignUpThatWasntCountedGivesBackItsSlot() {
        for (var i = 0; i < 20; i++) {
            var attempt = login(null, ADDRESS);
            assertThat(limits.beginSignUp(attempt)).isEmpty();
            limits.signUpNotCounted(attempt);
            limits.signUpNotCounted(attempt);
        }
    }

    @Test
    void signUpsAndLoginsAreCountedSeparately() {
        for (var i = 0; i < 5; i++) {
            limits.beginSignUp(login(null, ADDRESS));
        }

        assertThat(limits.beginLogin(login("alice", ADDRESS))).isEmpty();
    }

    @Test
    void doesNothingWhenDisabled() {
        var disabled = limits(false, "https://example.com");

        for (var i = 0; i < 100; i++) {
            assertThat(disabled.beginLogin(login("alice", ADDRESS))).isEmpty();
            assertThat(disabled.beginSignUp(login(null, ADDRESS))).isEmpty();
        }
        disabled.loginSucceeded(login("alice", ADDRESS));
        disabled.loginFailed(login("alice", ADDRESS));
        disabled.loginNotCounted(login("alice", ADDRESS));
        disabled.signUpNotCounted(login(null, ADDRESS));
    }

    @Test
    void warnsOnceAboutLocalAddressesWithAPublicBaseUrl(CapturedOutput output) {
        limits.beginLogin(login("alice", "127.0.0.1"));
        limits.beginLogin(login("alice", "172.18.0.1"));
        limits.beginSignUp(login(null, "192.168.0.1"));

        assertThat(output.getAll().split("forward-headers-strategy", -1)).hasSize(2);
        assertThat(output).contains("127.0.0.1");
    }

    @Test
    void warnsAboutEveryRangeTomcatTrustsAsAProxy(CapturedOutput output) {
        var addresses = new String[]{
                "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.0.1", "100.64.0.1", "100.127.255.255",
                "::1", "fe80::1", "fc00::1", "fd00::1"
        };

        for (var address : addresses) {
            limits(true, "https://example.com").beginLogin(login("alice", address));
        }

        assertThat(output.getAll().split("forward-headers-strategy", -1)).hasSize(addresses.length + 1);
    }

    @Test
    void doesntWarnAboutPublicAddresses(CapturedOutput output) {
        for (var address : new String[]{ADDRESS, "2001:db8::1", "100.63.255.255", "100.128.0.1", "172.32.0.1"}) {
            limits.beginLogin(login("alice", address));
        }

        assertThat(output).doesNotContain("forward-headers-strategy");
    }

    @Test
    void doesntWarnWhenTheBaseUrlIsLocal(CapturedOutput output) {
        for (var baseUrl : new String[]{"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"}) {
            limits(true, baseUrl).beginLogin(login("alice", "127.0.0.1"));
        }

        assertThat(output).doesNotContain("forward-headers-strategy");
    }

    @Test
    void doesntWarnWhenDisabled(CapturedOutput output) {
        limits(false, "https://example.com").beginLogin(login("alice", "127.0.0.1"));

        assertThat(output).doesNotContain("forward-headers-strategy");
    }

    private void assertLoginsAllowed(int count, String username, String address) {
        for (var i = 0; i < count; i++) {
            assertThat(limits.beginLogin(login(username, address))).isEmpty();
        }
    }

    private AuthRateLimits limits(boolean enabled, String baseUrl) {
        return new AuthRateLimits(
                new RateLimitProperties(enabled),
                new ClientAddresses(new InstanceProperties(URI.create(baseUrl))),
                now::get);
    }

    private static MockHttpServletRequest login(String username, String address) {
        var request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr(address);
        if (username != null) {
            request.setParameter("username", username);
        }
        return request;
    }
}
