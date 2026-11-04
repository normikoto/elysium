package dev.biribiri.elysium.actor.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The instance actor's keypair. It's created the first time it's needed, because SQL migrations can't generate an RSA
 * key. The key never changes once it exists, so it's only read once.
 */
@Component
public class InstanceActorKeys {

    private final JdbcClient jdbc;
    private volatile RsaKeys keys;

    InstanceActorKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the keypair. This includes the private key, so it shouldn't be logged.
     */
    public RsaKeys get() {
        var loaded = keys;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (keys == null) {
                keys = load();
            }
            return keys;
        }
    }

    private RsaKeys load() {
        return read().orElseGet(() -> {
            var generated = RsaKeys.generate();
            jdbc.sql("""
                            INSERT INTO instance_actor (public_key_pem, private_key_pem)
                            VALUES (:publicKeyPem, :privateKeyPem)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("publicKeyPem", generated.publicKeyPem())
                    .param("privateKeyPem", generated.privateKeyPem())
                    .update();
            // Not the generated keys, because another instance of this server may have inserted its own first.
            return read().orElseThrow();
        });
    }

    private Optional<RsaKeys> read() {
        return jdbc.sql("SELECT public_key_pem, private_key_pem FROM instance_actor")
                .query((rs, rowNum) -> new RsaKeys(rs.getString("public_key_pem"), rs.getString("private_key_pem")))
                .optional();
    }
}
