package dev.biribiri.elysium.actor.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * The instance actor's keypairs. They're created the first time they're needed, because SQL migrations can't generate
 * keys. They never change once they exist, so they're only read once.
 */
@Component
public class InstanceActorKeys {

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private volatile Keys keys;

    InstanceActorKeys(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        // Separate from any transaction the caller is in. If that one rolled back, this server would keep using a key
        // that was never stored. It could also have an older view of the database, which wouldn't show a key that another
        // instance of this server has just inserted.
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * @param rsa     signs HTTP requests
     * @param ed25519 signs integrity proofs
     */
    public record Keys(RsaKeys rsa, Ed25519Keys ed25519) {
    }

    public Keys get() {
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

    private Keys load() {
        var existing = ownTransaction.execute(status -> read());
        if (existing.isPresent()) {
            return existing.get();
        }
        // Generating keys is slow, so it happens before the transaction takes a database connection.
        var rsa = RsaKeys.generate();
        var ed25519 = Ed25519Keys.generate();
        return ownTransaction.execute(status -> {
            jdbc.sql("""
                            INSERT INTO instance_actor (public_key_pem, private_key_pem,
                                                        ed25519_public_key_pem, ed25519_private_key_pem)
                            VALUES (:publicKeyPem, :privateKeyPem, :ed25519PublicKeyPem, :ed25519PrivateKeyPem)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("publicKeyPem", rsa.publicKeyPem())
                    .param("privateKeyPem", rsa.privateKeyPem())
                    .param("ed25519PublicKeyPem", ed25519.publicKeyPem())
                    .param("ed25519PrivateKeyPem", ed25519.privateKeyPem())
                    .update();
            // Not the generated keys, because another instance of this server may have inserted its own first.
            return read().orElseThrow();
        });
    }

    private Optional<Keys> read() {
        return jdbc.sql("""
                        SELECT public_key_pem, private_key_pem, ed25519_public_key_pem, ed25519_private_key_pem
                        FROM instance_actor
                        """)
                .query((row, n) -> new Keys(
                        new RsaKeys(row.getString("public_key_pem"), row.getString("private_key_pem")),
                        new Ed25519Keys(row.getString("ed25519_public_key_pem"), row.getString("ed25519_private_key_pem"))))
                .optional();
    }
}
