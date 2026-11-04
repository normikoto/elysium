package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Uses made-up signers with made-up headers, to show that nothing here depends on a signature format.
 */
class SignedFetcherTests {

    private static final URI URL = URI.create("https://remote.example/users/bob");
    private static final String ACCEPT = "application/activity+json";
    private static final SigningKey KEY = new SigningKey("https://example.test/actor#main-key", SignatureFixtures.privateKey());

    private final FederationHttpClient http = mock();
    private final ActorSigningKeys keys = mock();
    private final List<SigningKey> keysUsed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(keys.forInstance()).thenReturn(KEY);
    }

    @Test
    void sendsWhatASignerAddsAndSignsAGetWithoutABody() throws Exception {
        var seen = new ArrayList<String>();
        RequestSigner signer = (method, target, body, key) -> {
            seen.add(method + " " + target + " " + body.length);
            keysUsed.add(key);
            return headers("X-First", "1");
        };
        respond(200);

        var response = fetcher(signer).get(URL, ACCEPT);

        assertThat(response.status()).isEqualTo(200);
        assertThat(seen).containsExactly("GET " + URL + " 0");
        assertThat(keysUsed).containsExactly(KEY);
        assertThat(sentHeaders(1).getFirst().getFirst("X-First")).isEqualTo("1");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void triesTheNextSignerAfterARefusal(int status) throws Exception {
        when(http.get(eq(URL), eq(ACCEPT), any()))
                .thenReturn(new Response(status, null, new byte[0]))
                .thenReturn(new Response(200, null, new byte[0]));

        var response = fetcher(signer("X-First"), signer("X-Second")).get(URL, ACCEPT);

        assertThat(response.status()).isEqualTo(200);
        var sent = sentHeaders(2);
        assertThat(sent.get(0).containsHeader("X-First")).isTrue();
        assertThat(sent.get(1).containsHeader("X-Second")).isTrue();
        assertThat(sent.get(1).containsHeader("X-First")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 404, 410, 429, 500})
    void stopsAtAnAnswerThatIsNotARefusal(int status) throws Exception {
        respond(status);

        var response = fetcher(signer("X-First"), signer("X-Second")).get(URL, ACCEPT);

        assertThat(response.status()).isEqualTo(status);
        sentHeaders(1);
    }

    @Test
    void doesNotRetryWithASingleSigner() throws Exception {
        respond(401);

        var response = fetcher(signer("X-First")).get(URL, ACCEPT);

        assertThat(response.status()).isEqualTo(401);
        sentHeaders(1);
    }

    @Test
    void returnsTheLastRefusalIfEverySignerIsRefused() throws Exception {
        when(http.get(eq(URL), eq(ACCEPT), any()))
                .thenReturn(new Response(401, null, new byte[0]))
                .thenReturn(new Response(403, null, new byte[0]));

        var response = fetcher(signer("X-First"), signer("X-Second")).get(URL, ACCEPT);

        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void neverSendsARequestItCouldNotSign() {
        RequestSigner signer = (method, target, body, key) -> {
            throw new IllegalArgumentException("No valid host");
        };

        assertThatThrownBy(() -> fetcher(signer).get(URL, ACCEPT))
                .isInstanceOfSatisfying(KeyFetchException.class, e -> assertThat(e.isTemporary()).isFalse());
        verifyNoInteractions(http);
    }

    @Test
    void needsASigner() {
        assertThatThrownBy(() -> new SignedFetcher(http, List.of(), keys)).isInstanceOf(IllegalArgumentException.class);
    }

    private SignedFetcher fetcher(RequestSigner... signers) {
        return new SignedFetcher(http, List.of(signers), keys);
    }

    private void respond(int status) throws Exception {
        when(http.get(eq(URL), eq(ACCEPT), any())).thenReturn(new Response(status, null, new byte[0]));
    }

    private List<HttpHeaders> sentHeaders(int requests) throws Exception {
        var captor = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(http, times(requests)).get(eq(URL), eq(ACCEPT), captor.capture());
        return captor.getAllValues();
    }

    private static RequestSigner signer(String header) {
        return (method, target, body, key) -> headers(header, "1");
    }

    private static HttpHeaders headers(String name, String value) {
        var headers = new HttpHeaders();
        headers.set(name, value);
        return headers;
    }
}
