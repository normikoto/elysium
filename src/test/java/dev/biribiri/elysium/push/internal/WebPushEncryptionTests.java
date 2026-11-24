package dev.biribiri.elysium.push.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static dev.biribiri.elysium.push.internal.WebPushDecryption.base64Url;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushEncryptionTests {

    // RFC 8291, appendix A
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String SERVER_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String SERVER_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String CLIENT_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String CLIENT_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6"
            + "e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulc"
            + "y4a-fN";

    @Test
    void matchesTheExampleInRfc8291() throws Exception {
        var server = WebPushDecryption.keyPair(SERVER_PRIVATE, SERVER_PUBLIC);
        var client = P256.decodePublic(base64Url(CLIENT_PUBLIC));

        var encrypted = WebPushEncryption.aes128gcm(PLAINTEXT.getBytes(StandardCharsets.UTF_8), client,
                base64Url(AUTH_SECRET), server, base64Url(SALT));

        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted.body())).isEqualTo(BODY);
    }

    @Test
    void theClientCanReadEitherEncoding() throws Exception {
        var client = WebPushDecryption.keyPair(CLIENT_PRIVATE, CLIENT_PUBLIC);
        var secret = base64Url(AUTH_SECRET);
        var message = "{\"title\":\"Ünïcödé 🍉\"}".getBytes(StandardCharsets.UTF_8);

        var standard = WebPushEncryption.aes128gcm(message, (ECPublicKey) client.getPublic(), secret);
        assertThat(WebPushDecryption.aes128gcm(standard.body(), client, secret)).isEqualTo(message);

        var legacy = WebPushEncryption.aesgcm(message, (ECPublicKey) client.getPublic(), secret);
        assertThat(WebPushDecryption.aesgcm(legacy.body(), legacy.salt(), legacy.serverKey(), client, secret))
                .isEqualTo(message);
    }

    @Test
    void usesANewKeyAndSaltForEachMessage() throws Exception {
        var client = P256.decodePublic(base64Url(CLIENT_PUBLIC));
        var secret = base64Url(AUTH_SECRET);

        var first = WebPushEncryption.aesgcm(new byte[]{1}, client, secret);
        var second = WebPushEncryption.aesgcm(new byte[]{1}, client, secret);

        assertThat(first.salt()).isNotEqualTo(second.salt());
        assertThat(first.serverKey()).isNotEqualTo(second.serverKey());
    }

    @Test
    void theLongestMessageFitsInOneRecordThatPushServicesMustAccept() {
        var client = P256.decodePublic(base64Url(CLIENT_PUBLIC));
        var secret = base64Url(AUTH_SECRET);

        var body = WebPushEncryption.aes128gcm(new byte[WebPushEncryption.MAX_PLAINTEXT], client, secret).body();

        assertThat(body).hasSize(4096);
        assertThatThrownBy(() -> WebPushEncryption.aes128gcm(new byte[WebPushEncryption.MAX_PLAINTEXT + 1], client, secret))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesKeysThatArentPointsOnTheCurve() {
        var key = base64Url(CLIENT_PUBLIC);
        key[64] ^= 1;

        assertThatThrownBy(() -> P256.decodePublic(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> P256.decodePublic(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }
}
