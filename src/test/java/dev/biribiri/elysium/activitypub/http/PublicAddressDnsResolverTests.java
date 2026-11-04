package dev.biribiri.elysium.activitypub.http;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicAddressDnsResolverTests {

    @ParameterizedTest
    @ValueSource(strings = {
            // Loopback and this host
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "::1", "::",
            // Private networks, including what cloud metadata services use
            "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.169.254", "100.64.0.1",
            "fc00::1", "fd00::1", "fe80::1",
            // Addresses reserved for other uses
            "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "240.0.0.1", "255.255.255.255", "224.0.0.1",
            "ff02::1", "2001:db8::1",
            // IPv6 forms that lead to IPv4 addresses
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::10.0.0.1", "::127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::"
    })
    void blocksAddressesThatArentPublic(String address) {
        assertThatThrownBy(() -> resolverReturning(address).resolve("host.example"))
                .isInstanceOf(BlockedTargetException.class);
    }

    /**
     * The cases above that look like this don't test it, because parsing normalizes them to IPv4 addresses. A DNS answer
     * stays an IPv6 address, and connecting to one reaches the IPv4 address inside.
     */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "192.168.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0"})
    void blocksDnsAnswersThatMapToPrivateIpv4Addresses(String ipv4) throws Exception {
        var answer = ipv4MappedAnswer(ipv4);

        assertThat(answer).isInstanceOf(Inet6Address.class);
        assertThatThrownBy(() -> resolverAnswering(answer).resolve("host.example"))
                .isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void connectsToTheIpv4FormOfADnsAnswerThatMapsToAPublicAddress() throws Exception {
        var resolved = resolverAnswering(ipv4MappedAnswer("8.8.8.8")).resolve("host.example");

        assertThat(resolved).hasSize(1);
        assertThat(resolved[0]).isInstanceOf(Inet4Address.class);
        assertThat(resolved[0].getHostAddress()).isEqualTo("8.8.8.8");
    }

    @Test
    void dropsMappedPrivateAnswersOfAHostWithBothKinds() throws Exception {
        var resolved = resolverAnswering(ipv4MappedAnswer("127.0.0.1"), InetAddress.getByName("8.8.8.8"))
                .resolve("host.example");

        assertThat(resolved).extracting(InetAddress::getHostAddress).containsExactly("8.8.8.8");
    }

    @Test
    void keepsPublicIpv6Answers() throws Exception {
        var resolved = resolverAnswering(InetAddress.getByName("2606:4700:4700::1111")).resolve("host.example");

        assertThat(resolved).hasSize(1);
        assertThat(resolved[0]).isInstanceOf(Inet6Address.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "93.184.216.34", "172.32.0.1", "2606:4700:4700::1111"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(resolverReturning(address).resolve("host.example")).hasSize(1);
    }

    @Test
    void onlyReturnsThePublicAddressesOfAHostWithBothKinds() throws Exception {
        var resolved = resolverReturning("127.0.0.1", "8.8.8.8", "10.0.0.1").resolve("host.example");

        assertThat(resolved).containsExactly(InetAddress.getByName("8.8.8.8"));
    }

    @Test
    void checksEveryLookupSoAHostCannotChangeItsAnswerAfterwards() throws Exception {
        var answers = new ArrayDeque<>(List.of("8.8.8.8", "127.0.0.1"));
        var resolver = new PublicAddressDnsResolver(
                new Fixed(() -> new String[]{answers.removeFirst()}), PublicAddressDnsResolver.PUBLIC_ADDRESSES);

        assertThat(resolver.resolve("host.example")).hasSize(1);
        assertThatThrownBy(() -> resolver.resolve("host.example")).isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void passesOnLookupFailuresWithoutCallingThemBlocked() {
        var resolver = new PublicAddressDnsResolver(new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                throw new UnknownHostException(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        }, PublicAddressDnsResolver.PUBLIC_ADDRESSES);

        assertThatThrownBy(() -> resolver.resolve("host.example"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(BlockedTargetException.class);
    }

    private static PublicAddressDnsResolver resolverReturning(String... addresses) {
        return new PublicAddressDnsResolver(new Fixed(() -> addresses), PublicAddressDnsResolver.PUBLIC_ADDRESSES);
    }

    private static PublicAddressDnsResolver resolverAnswering(InetAddress... answers) {
        return new PublicAddressDnsResolver(new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return answers;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        }, PublicAddressDnsResolver.PUBLIC_ADDRESSES);
    }

    /**
     * Builds what a DNS lookup returns for an AAAA record like {@code ::ffff:127.0.0.1}. Only this method of creating an
     * address leaves it as an IPv6 address.
     */
    private static InetAddress ipv4MappedAnswer(String ipv4) throws UnknownHostException {
        var bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        System.arraycopy(InetAddress.getByName(ipv4).getAddress(), 0, bytes, 12, 4);
        return Inet6Address.getByAddress(null, bytes, -1);
    }

    private record Fixed(Supplier<String[]> addresses) implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            var names = addresses.get();
            var resolved = new InetAddress[names.length];
            for (var i = 0; i < names.length; i++) {
                resolved[i] = InetAddress.getByName(names[i]);
            }
            return resolved;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
