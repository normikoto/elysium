package dev.biribiri.elysium.activitypub.signature;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * A key and signatures made with OpenSSL rather than this project's own code, so that tests can't pass just because
 * signing and verifying share a mistake.
 *
 * <p><strong>This private key is only for tests, and isn't used anywhere else.</strong>
 *
 * <p>These were made with:
 *
 * <pre>
 * openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out key.pem
 * openssl pkey -in key.pem -pubout -out pub.pem
 * printf '%s' '{"type":"Create"}' | openssl dgst -sha256 -binary | base64 -w0
 * printf '%s' "$base" | openssl dgst -sha256 -sign key.pem | base64 -w0
 * </pre>
 */
final class SignatureFixtures {

    static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCq32fBR4BKOLL/
            hYZFgYqi76g6kOy+2x0SHahTgTWlS0gtSXwkuvchksuG7TfiiodNFVH13zGGAkMs
            tFJvJjmCM9KLaOIpmTmc+P2X7rG3++y8H6R4adMFlqdC4t8Q0M07h84SxER9Elo7
            ZWu8stypEqldMTEgoffA/7Hj9K/2cg4eElCB4cwTPWAsLCVN/NAdhji48BwSvP7+
            VnEFeLxTjK+Dn2ceqno8HrAjrNOD76ddpIjSKgnqNWKQvVp1lG03tRqkON9AJcGh
            Q9rQtg2QEC9VEqasHOptISMwG+/c+7jDUx9VVKOVtHXLdRi00CUmd0cWxBRQvq1F
            0BD2784nAgMBAAECggEACMSsC1QC9kwQi2IrsLdkCNZpeI/lRinyRPK2p4pS2WeS
            jZhewXfCpLtmrEbR/lMDfGYirY5FeU32an3aWQ/KFa9ZF4RLTr0pIq/4O2qpPfq0
            fDbXdsJfbML9VEZWEgPU9EAUyAvAQ/bEIJYkwCshfZ+Sh1C156nID+RPGZZ9zRSZ
            wlGw3tpLpw1OtpBa7qVp3E3sdAEZMiB+DF7pdIUbkAfSRfhi6Ur4HqQG5Xtbpo/g
            NNuCrtNv8E8kIkc2a8RvQLlH4Ge1IFrrjCtd5n1OpgelC//wVUVTCNwPoFJNHRV0
            c+vGTgMFVqhClEalnb4nOTbxByQ91/fnphjGkfVHYQKBgQDWCi+qq9ZYdIB3R8ra
            rafUBITuDlJ4EDM3/Fw90MCL2bibxlu75UsXmYw9xl5Pq7p0XYhAZkPL7gFGjW2j
            T/pduN+vX3WVExCm2kdm7dIbvhdM+lpjcHQYzCMaUgv7MyW0cuiL/zxqztOoK1QV
            QWilmVWcVK+A9n40hGtlI7mRsQKBgQDMXtTuamUV4wXaXMz3k5fbnWYFLA7lTk3H
            jQ5iv+ebfl3V8ZYeWIQLgSSjHspusEeP92DAhGbUuh+FypLg3RFrSYHd7topg8hN
            7L/aliSjY2uE06318+bpXXbhVKqt/g9y0L3WHKxn2Fc7WnWrf+frY45Z/F6k2ED1
            I7YSTmO7VwKBgQDPf4SCYqAjlJ3OBS1lHiCra84qsnvEpX/h8KbDzpI7gdB0Q7I+
            z6Uitdq9MIfRmNvjX6FtPTbQhmV6LjVxs2E2ZrmDXHmzEY59EvNRl5CvEHOqTrI9
            4JDMUWpeY9f1qLoO268RuViq+uM6DvcuvABlOedC7N5Pmabz5sPugtmtoQKBgE1Y
            epe8toMhB18MDhqIxkk8eZbthLmt9+vU66Pd4ybLHXJR2zGi6F5+QsJlbdGUb5EB
            26EgutY5lqKI9yuZHvWOvVKkCfb6Q/2R404z0qvvfYqonkbeRv78+3fuFiaBZ5FZ
            hO8arPkkPGdfJfeBEeHZLM/Lnnoeyf68xDSLbF1DAoGBANO4ygW7zqZs7NJziOOL
            WKf7v1AcYm0cT9yJIgQUv+ZNtS78GZa54LMFMR+w5XNWDzYQ0oGMEiIpoDJxJUSd
            KCyBQUNNcPOsSir0dlo7BM9Hu2x2m+FWStHkH71ziLLypFJ3WeD8ruyA/VPWoy3x
            46KUD9Qb3Gyj+tO1Pj3eVTJM
            -----END PRIVATE KEY-----
            """;

    static final String PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqt9nwUeASjiy/4WGRYGK
            ou+oOpDsvtsdEh2oU4E1pUtILUl8JLr3IZLLhu034oqHTRVR9d8xhgJDLLRSbyY5
            gjPSi2jiKZk5nPj9l+6xt/vsvB+keGnTBZanQuLfENDNO4fOEsREfRJaO2VrvLLc
            qRKpXTExIKH3wP+x4/Sv9nIOHhJQgeHMEz1gLCwlTfzQHYY4uPAcErz+/lZxBXi8
            U4yvg59nHqp6PB6wI6zTg++nXaSI0ioJ6jVikL1adZRtN7UapDjfQCXBoUPa0LYN
            kBAvVRKmrBzqbSEjMBvv3Pu4w1MfVVSjlbR1y3UYtNAlJndHFsQUUL6tRdAQ9u/O
            JwIDAQAB
            -----END PUBLIC KEY-----
            """;

    static final String BODY = "{\"type\":\"Create\"}";
    static final String BODY_DIGEST = "JeE18werLvQnEoHViKDam+ZK1D8E27TBC2kIISI7pIY=";
    static final String DATE = "Tue, 03 Nov 2026 12:00:00 GMT";
    static final long CREATED = 1793707200;

    /**
     * Signs {@code (request-target): post /users/alice/inbox}, {@code host: example.test}, {@link #DATE}, and
     * {@code digest: SHA-256=} followed by {@link #BODY_DIGEST}.
     */
    static final String POST_SIGNATURE =
            "I/znNmyvcZmlz7kSW+YEDzB/Tso0opVdbd16c0Ow14UhxWjEY3JCwdwSqdoK6OdhC8NsIEszbZia7yH5LSdrFHR+aegZEWKtIiB6Y0ZfT515D5jJOl0LCHjctaKOcwli/1ikvZSApf04nGA4I0tlYQx6sJOAxK8jZoIMnXGwIQifXAbQ6LOPXOTxxH3Jz2/xM84U6YrcI1boz0DO79moafHENtQB50of0FUqu0dQ70/hWLLSCWf0xFinPbMNCJQZtFUxm3N/g8grdh7KtMJJz+sXqHyIVCSGYTF88SpDjdMjgnmpFUWEg7X94twcz9Iq21PDjfBEL3OeeE5clbRQIQ==";

    /**
     * Signs {@code (request-target): get /users/alice?page=1}, {@code host: example.test}, and {@link #DATE}.
     */
    static final String GET_SIGNATURE =
            "W6Sl3ImfQwtwTeK7q5KskV5UKZKNDe2vHWpUa3Y77Ymht9n56WOxf7Euwa+q8OcRtxpt7b9Cn8tteSVDfk9qgjytVYjgng5nu0PvgTrC4ix+kyYL/898CZeDxWIzsjU9JIm1/FZ+/0QaDTDxXvzgIpqzg2bOD4iOfgAm8Itb83bvQoZAZ7VVdOir27GfTx1PQspexIDzn7lggWtD732+J3CQZv0T1hnN6n+K7xqUf//r7+UWrEYkIf9ztmZmMwUQmHfFZfJpts5AUT8YOIMJ25X4XCPr1jyCXrAp58vLCmakihuJfp+OZpzrMEjvrHy8EyV6yUuciSf+KhiHETDZQA==";

    /**
     * Signs {@code (request-target): get /users/alice}, {@code host: example.test}, and
     * {@code (created): 1793707200}.
     */
    static final String CREATED_SIGNATURE =
            "K7AkJjmrg+0sPNZBWwMUq2AORYdCny++6TJpC7+jQlRrUApPInzUMIoD2sjFj01XUf9y0pkTPSOc4oByPtXFU7ETZYLcwKvOFE/FXQfpeRdU+U7ohwlqCdtezwPJKySrFK3BCW/KO03AHsdki/7ywxvfBuGva675tDbVZBbZskUcHlylacRTXA1AFTsGhjJuvr3ORWZTmFHl2JqVJry4kbnbsrzmLYolecZim0fA/lkBFB/P2hYg1NrfilyMQ551ShEJ+Dl39XllG+DIJNmbvgQMGILYedMA5bvsQupGkMfUGgNRGIQwUpzXXb42fFYsng4/eJCZcVg8eU/kf9ixVA==";

    /**
     * The SHA-512 of {@link #BODY}, made with {@code openssl dgst -sha512 -binary}.
     */
    static final String BODY_SHA512 =
            "2Zff58xsFuebfSQtprwv7ZmmVBQR8ep5Pjta8geJjAeRVjpp/hbJaMWfWo9jglZ5krZKPk4c3ig1De/c5z0+4w==";

    static final String RFC_9421_POST_INPUT = "sig1=(\"@method\" \"@target-uri\" \"content-digest\");created=1793707200;"
            + "keyid=\"https://remote.example/users/bob#main-key\";alg=\"rsa-v1_5-sha256\"";

    /**
     * An RFC 9421 signature of {@link #RFC_9421_POST_INPUT}, which covers {@code @method: POST},
     * {@code @target-uri: https://example.test/users/alice/inbox}, and {@code content-digest: sha-256=:} followed by
     * {@link #BODY_DIGEST} and {@code :}.
     */
    static final String RFC_9421_POST_SIGNATURE =
            "dU3IGNPAf7d7VG4obA+z3EPNpjwsxjiMWKLsaCPkX+rftFM+9nZ/oUAJ3Q949du30R8e8uIMjB5oj9CAYML3ACCOcC/LE+gb5dtcxIZU2a+EOwACz8XY0e7c6GGfktXDWbCib5xD74bhb/ihwczkiUmngOYTEi0dreRLsIAq7n73UJR/PcZrl4xriRQwjY/SD7h2zhv6c5slw2PopgPmLXmDc4eHHbZM8iffaPqNMUlhlsp7z7IEdWzBseMTEVCGvAUToFkTr+sh5mELYqOrVUTvmgxGCg3mn4aD5NLNgVONWY8nE4V18IEgth7E23ttiUuAZyuiVHcDSBWPRoIbUQ==";

    static final String RFC_9421_GET_INPUT = "sig1=(\"@method\" \"@authority\" \"@path\" \"@query\");created=1793707200;"
            + "keyid=\"https://remote.example/users/bob#main-key\"";

    /**
     * An RFC 9421 signature of {@link #RFC_9421_GET_INPUT}, which covers {@code @method: GET},
     * {@code @authority: example.test}, {@code @path: /users/alice}, and {@code @query: ?page=1}.
     */
    static final String RFC_9421_GET_SIGNATURE =
            "DHPt95ECF4AfDJW6dEzkuo/LETStFEEzQWWjqCvkdqVUo41kfZ0QuaeRIyOFyqJ10i7yR9GqhOl1U5vUalyd+NDiAd9LzYpO0bg4navnAHZzy01MMKjv+cHAsUpaEW9p4s2SfV3vkdiiV9AIQpMpUXqWK4iettk3k3wrNR730vNDX5KncVXVQkDIwcMtl+yKl2uAaadCVhqliQGt3LFLMWBeU3IXty36PVyknGeQG/O3ocUKbJqqyY6ZxUaxGFOlOR4EuOE4s04n3LnJMlUcz3wLD7arwRiZU2037H4S42o9qq8XzIT8p8+SpcGsU40gmon3fV8O+uM3LF5yMMi2xA==";

    /**
     * For tests that aren't about the budget. Application code has no way to skip it, because each caller has to choose one.
     */
    static final KeyFetchBudget UNLIMITED = () -> Optional.of(new KeyFetchBudget.Reservation() {
        @Override
        public void finished() {
        }

        @Override
        public void cancelled() {
        }
    });

    private SignatureFixtures() {
    }

    static PublicKey publicKey() {
        return PublicKeyPem.parse(PUBLIC_KEY_PEM);
    }

    static PrivateKey privateKey() {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(PRIVATE_KEY_PEM)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return a new key's public half in PEM form
     */
    static String newPublicKeyPem(int bits) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            var encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
            return "-----BEGIN PUBLIC KEY-----\n"
                    + encoder.encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] der(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
    }
}
