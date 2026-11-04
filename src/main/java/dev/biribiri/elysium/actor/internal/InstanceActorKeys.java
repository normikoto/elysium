package dev.biribiri.elysium.actor.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * The instance actor's keypair. It's created the first time it's needed, because SQL migrations can't generate an RSA
 * key. The key never changes once it exists, so it's only read once.
 */
@Component
public class InstanceActorKeys {

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;
    private volatile RsaKeys keys;

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
     * @return the keypair
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
        var existing = ownTransaction.execute(status -> read());
        if (existing.isPresent()) {
            return existing.get();
        }
        // Generating keys is slow, so it happens before the transaction takes a database connection.
        var generated = RsaKeys.generate();
        return ownTransaction.execute(status -> {
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
