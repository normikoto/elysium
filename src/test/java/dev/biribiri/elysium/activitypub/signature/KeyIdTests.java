package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyIdTests {

    private static final InstanceProperties INSTANCE = new InstanceProperties(URI.create("https://example.test"));

    @Test
    void findsTheDocumentOfARemoteKey() {
        var keyId = KeyId.parse("https://Remote.example:443/users/bob?a=1#main-key", INSTANCE);

        assertThat(keyId.local()).isFalse();
        assertThat(keyId.authority()).isEqualTo("remote.example");
        assertThat(keyId.document()).hasToString("https://Remote.example:443/users/bob?a=1");
        assertThat(keyId.hasFragment()).isTrue();
    }

    @Test
    void keepsANonDefaultPortInTheAuthorityButNotTheHost() {
        var keyId = KeyId.parse("https://REMOTE.example:8443/k", INSTANCE);

        assertThat(keyId.authority()).isEqualTo("remote.example:8443");
        assertThat(keyId.host()).isEqualTo("remote.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.test/users/alice#main-key",
            "https://EXAMPLE.test/users/alice#main-key",
            "https://example.test:443/users/alice#main-key",
            "HTTPS://example.test/users/alice#main-key",
            "http://example.test/users/alice#main-key"
    })
    void recognizesEveryFormOfThisServersName(String value) {
        var keyId = KeyId.parse(value, INSTANCE);

        assertThat(keyId.local()).isTrue();
        assertThat(keyId.document()).hasToString("https://example.test/users/alice");
    }

    @Test
    void doesNotLetAPathChangeTheHostOfALocalKey() {
        var keyId = KeyId.parse("https://example.test//other.example/users/alice", INSTANCE);

        assertThat(keyId.document().getHost()).isEqualTo("example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "main-key",
            "/users/bob#main-key",
            "acct:bob@remote.example",
            "ftp://remote.example/key",
            "file:///etc/passwd",
            "http://remote.example/users/bob#main-key",
            "https://user:pass@remote.example/key",
            "https://remote.example./key",
            "https://under_score.example/key",
            "https://bad host/key"
    })
    void rejectsIdsItWontLookUp(String value) {
        assertThatThrownBy(() -> KeyId.parse(value, INSTANCE)).isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void rejectsVeryLongIds() {
        var value = "https://remote.example/" + "a".repeat(2048);

        assertThatThrownBy(() -> KeyId.parse(value, INSTANCE)).isInstanceOf(SignatureVerificationException.class);
    }
}
